package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.ejml.simple.SimpleMatrix;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The operator entry point of the accepted {@code synthetic-recovery-v1} protocol.
 *
 * <p>{@code score} scores one supplied synthetic dataset: explicit ilr observations and the
 * registered fixed observation covariance reach {@link WindowFilter} and {@link PredictiveScoring}
 * directly, without source preparation, so nothing reconstructs the covariance from the generated
 * shares. {@code control} generates the registered scenario and runs both known-parameter controls
 * at the generating truth, retaining every dataset and reporting component-level recovery verdicts
 * with their Monte Carlo uncertainty. Only when both controls demonstrate recovery does it repeat
 * each convention on the identical observations with {@link SyntheticSearch} selecting parameters
 * from the training observations alone.
 *
 * <p>Preflight, the execution cap, registration verification and reproduction are later slices of
 * the same workflow. Running either command is a software check; it is recovery evidence only when
 * its registered phase says so.
 */
public final class SyntheticRecovery {
  public static final int SUCCESS = 0;
  public static final int REJECTED = 2;

  /** A run stopped by a numerical failure, with its partial evidence preserved. */
  public static final int STOPPED = 3;

  /** The accepted protocol version. A document naming another protocol is refused. */
  static final String VERSION = "synthetic-recovery-v1";

  /** The registered master seed and draw count of the formal phase. */
  static final long FORMAL_SEED = 20260916;

  static final int FORMAL_DRAWS = 4000;

  /** The registered dataset indices of the formal and preflight phases. */
  static final int FORMAL_DATASETS = 10000;

  static final int PREFLIGHT_DATASETS = 4;

  /**
   * The registered component order. The residual component {@code OTHER} closes the composition.
   */
  static final List<String> ROSTER = List.of("S", "M", "SD", "V", "C", "KD", "L", "MP");

  static final String FORMAL = "formal";
  static final String TRAINING = "training";
  static final String SCORING = "scoring";

  /** The known-parameter stage this slice runs, and the stage it gates. */
  static final String KNOWN_STAGE = "known";

  static final String ESTIMATED_STAGE = "estimated";

  private static final String PREFLIGHT = "preflight";

  private static final String SOFTWARE_CHECK = "software_check";

  /** The software-check phase keeps test fixtures out of the registered scientific streams. */
  private static final List<String> PHASES = List.of(FORMAL, PREFLIGHT, SOFTWARE_CHECK);

  private static final String MIDPOINT = "midpoint";
  private static final String ILR_WINDOW = "ilr_window";

  /** Both conventions, in the order the controls run them. */
  private static final List<String> CONVENTIONS = List.of(MIDPOINT, ILR_WINDOW);

  private static final Map<String, WindowFilter.Convention> FILTER_CONVENTIONS =
      Map.of(
          MIDPOINT,
          WindowFilter.Convention.MIDPOINT,
          ILR_WINDOW,
          WindowFilter.Convention.FIELDWORK);

  private static final String NOT_RUN = "not_run";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SyntheticRecovery() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /** The operator entry point. It refuses an output that exists rather than replacing it. */
  public static int run(String... args) {
    if (args.length == 3 && args[0].equals("score")) return scoreOne(args[1], args[2], false);
    if (args.length == 3 && args[0].equals("tune")) return scoreOne(args[1], args[2], true);
    if (args.length == 3 && args[0].equals("control")) return control(args[1], args[2]);
    System.err.println(
        "Usage: score <dataset.json> <evidence.json> | tune <dataset.json> <evidence.json>"
            + " | control <plan.json> <dir>");
    return REJECTED;
  }

  /**
   * One supplied dataset, scored at the parameters its document names, or at the point the
   * training-only search selects when the document leaves them to it.
   */
  private static int scoreOne(String datasetFile, String evidenceFile, boolean tuning) {
    final Path output = Path.of(evidenceFile);
    final Dataset dataset;
    try {
      require(!Files.exists(output), "Output already exists: " + output);
      final Path file = Path.of(datasetFile);
      dataset = supplied(read(file), file, tuning);
    } catch (RuntimeException e) {
      rejected(output, e.getMessage());
      System.err.println(e.getMessage());
      return REJECTED;
    }
    if (!tuning)
      try {
        write(output, score(dataset));
        return SUCCESS;
      } catch (RuntimeException e) {
        rejected(output, e.getMessage());
        System.err.println(e.getMessage());
        return REJECTED;
      }

    // A numerical failure at the search or at the tuned fit stops the dataset with its earliest
    // failing operation preserved; the grid is never expanded and no seed is ever replaced.
    final SyntheticSearch.Selection selection = select(dataset);
    String operation = "search";
    try {
      if (selection.failed()) throw new IllegalArgumentException(stopping(selection));
      operation = "score";
      final ObjectNode evidence = score(dataset.at(selection.parameters()));
      evidence.put("stage", ESTIMATED_STAGE);
      evidence.set("search", search(selection));
      operation = "retain";
      write(output, evidence);
      return SUCCESS;
    } catch (RuntimeException e) {
      final ObjectNode stopped =
          failure(dataset.convention(), ESTIMATED_STAGE, dataset.datasetIndex(), operation, e);
      stopped.set("search", search(selection));
      if (!Files.exists(output))
        try {
          write(output, stopped);
        } catch (RuntimeException ignored) {
          // A preserved failure must never obscure the failure it records.
        }
      System.err.println(stopped.get("message").asString());
      return STOPPED;
    }
  }

  /** The training-only search of one dataset, under the convention the dataset names. */
  private static SyntheticSearch.Selection select(Dataset dataset) {
    return SyntheticSearch.select(
        PollObservations.explicit(dataset.period(), dataset.training()),
        FILTER_CONVENTIONS.get(dataset.convention()));
  }

  /** What a search that stopped reports: the grid point it failed at and why. */
  private static String stopping(SyntheticSearch.Selection selection) {
    return "Failed training fit at grid point "
        + selection.failure().index()
        + ": "
        + selection.failure().message();
  }

  /**
   * Both known-parameter controls: generate the registered scenario of each convention, score every
   * dataset at the generating truth and reduce the retained evidence to the cells of the stage. A
   * numerical failure stops the run with its partial evidence preserved.
   */
  private static int control(String planFile, String directory) {
    final Path destination = Path.of(directory);
    final Path report = destination.resolve("control.json");
    final Plan plan;
    try {
      // A destination that exists could already hold evidence, so nothing is written into it.
      if (Files.exists(destination)) {
        System.err.println("Evidence destination already exists: " + destination);
        return REJECTED;
      }
      final Path file = Path.of(planFile);
      plan = plan(read(file), file);
    } catch (RuntimeException e) {
      rejected(report, e.getMessage());
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final List<Stage> known = new ArrayList<>();
    for (String convention : CONVENTIONS) {
      final Stage stage = stage(plan, convention, destination, KNOWN_STAGE);
      known.add(stage);
      if (stage.failure() != null) break;
    }

    // Both controls have to demonstrate recovery before either estimated stage runs; a failed,
    // inconclusive or incomplete control leaves both of them explicitly not run.
    final String control = SyntheticCoverage.combined(verdicts(known));
    final List<Stage> estimated = new ArrayList<>();
    if (control.equals(SyntheticCoverage.DEMONSTRATED))
      for (String convention : CONVENTIONS) {
        final Stage stage = stage(plan, convention, destination, ESTIMATED_STAGE);
        estimated.add(stage);
        if (stage.failure() != null) break;
      }

    write(report, report(plan, known, control, estimated));
    return Stream.concat(known.stream(), estimated.stream())
            .anyMatch(stage -> stage.failure() != null)
        ? STOPPED
        : SUCCESS;
  }

  /** One stage's verdict per convention, with a convention that never ran left incomplete. */
  private static List<String> verdicts(List<Stage> stages) {
    return CONVENTIONS.stream()
        .map(
            convention ->
                stages.stream()
                    .filter(stage -> stage.convention().equals(convention))
                    .map(Stage::verdict)
                    .findFirst()
                    .orElse(SyntheticCoverage.INCOMPLETE))
        .toList();
  }

  /** The registered plan of one control run. */
  private record Plan(String phase, long masterSeed, int datasets, int draws) {}

  private static Plan plan(JsonNode document, Path file) {
    require(
        VERSION.equals(required(document, "version").asString()),
        "Unregistered protocol version in " + file);
    final String phase = required(document, "phase").asString();
    require(PHASES.contains(phase), "Unregistered phase " + phase);
    final long masterSeed = required(document, "masterSeed").longValue();
    final int datasets = required(document, "datasets").intValue();
    final int draws = required(document, "draws").intValue();
    require(draws >= 2, "Coverage needs at least two draws");
    require(datasets >= 2, "A sample variance needs at least two datasets");
    if (phase.equals(FORMAL)) {
      require(masterSeed == FORMAL_SEED, "The formal phase uses master seed " + FORMAL_SEED);
      require(draws == FORMAL_DRAWS, "The formal phase uses " + FORMAL_DRAWS + " predictive draws");
      require(
          datasets == FORMAL_DATASETS,
          "The formal phase runs " + FORMAL_DATASETS + " datasets per convention");
    }
    if (phase.equals(PREFLIGHT))
      require(
          datasets <= PREFLIGHT_DATASETS,
          "Preflight runs at most " + PREFLIGHT_DATASETS + " datasets");
    return new Plan(phase, masterSeed, datasets, draws);
  }

  /** One method and stage: its cells and verdict, or the repetition that stopped it. */
  private record Stage(
      String convention,
      int completedDatasets,
      List<SyntheticCoverage.Cell> cells,
      String verdict,
      Map<String, Integer> endpointFrequencies,
      int endpointSelections,
      ObjectNode failure) {}

  /**
   * One convention and stage. Every dataset is generated, scored and retained before any of it is
   * reduced, and each dataset contributes exactly one fraction per cell. The estimated stage
   * regenerates the identical observations from the same streams and selects its parameters on the
   * training observations alone before scoring them.
   */
  private static Stage stage(Plan plan, String convention, Path destination, String stageName) {
    final boolean estimated = stageName.equals(ESTIMATED_STAGE);
    final List<String> components = components(prefix(plan.phase(), convention, 0));
    final int levels = SyntheticCoverage.LEVELS.size();
    final double[][][] fractions = new double[components.size()][levels][plan.datasets()];
    final long[][] covered = new long[components.size()][levels];
    final long[][] scored = new long[components.size()][levels];
    final Map<String, Integer> endpoints = new LinkedHashMap<>();
    int endpointSelections = 0;
    boolean drawsRetained = true;
    for (int index = 0; index < plan.datasets(); index++) {
      final Path file =
          destination.resolve(directory(stageName)).resolve(convention).resolve(index + ".json");
      final ObjectNode evidence;
      String operation = "generate";
      // Held outside the attempt, so a search that stopped the stage keeps its attempts in the
      // preserved failure rather than only in the exception that carried it out.
      SyntheticSearch.Selection selection = null;
      try {
        final Synthetic synthetic = generate(plan, convention, index);
        if (estimated) {
          operation = "search";
          selection = select(synthetic.dataset());
          if (selection.failed()) throw new IllegalArgumentException(stopping(selection));
        }
        operation = "score";
        evidence =
            score(
                selection == null
                    ? synthetic.dataset()
                    : synthetic.dataset().at(selection.parameters()));
        evidence.put("stage", stageName);
        if (selection != null) evidence.set("search", search(selection));
        evidence.set("generation", generation(synthetic));
        operation = "retain";
        write(file, evidence);
        if (selection != null) {
          for (String endpoint : selection.endpoints()) endpoints.merge(endpoint, 1, Integer::sum);
          if (!selection.endpoints().isEmpty()) endpointSelections++;
        }
      } catch (RuntimeException e) {
        // The repetition, its identity and the earliest failing operation are kept where its
        // evidence would have gone; the planned denominator is not reduced and the seed is never
        // replaced.
        final ObjectNode failure = failure(convention, stageName, index, operation, e);
        if (selection != null) failure.set("search", search(selection));
        if (!Files.exists(file))
          try {
            write(file, failure);
          } catch (RuntimeException ignored) {
            // A preserved failure must never obscure the failure it records.
          }
        return new Stage(
            convention,
            index,
            List.of(),
            SyntheticCoverage.INCOMPLETE,
            endpoints,
            endpointSelections,
            failure);
      }
      // One dataset's 25 scoring polls are correlated, so they reduce to one fraction per cell
      // before anything is averaged.
      final int polls = evidence.get("scoringPolls").intValue();
      final long[][] dataset = new long[components.size()][levels];
      for (JsonNode poll : evidence.get("polls")) {
        if (poll.get("drawsSha256").asString().length() != 64) drawsRetained = false;
        for (int component = 0; component < components.size(); component++) {
          final JsonNode summary = poll.get("components").get(component);
          for (int level = 0; level < levels; level++)
            if (summary.get("covered" + SyntheticCoverage.LEVELS.get(level)).booleanValue())
              dataset[component][level]++;
        }
      }
      for (int component = 0; component < components.size(); component++)
        for (int level = 0; level < levels; level++) {
          covered[component][level] += dataset[component][level];
          scored[component][level] += polls;
          fractions[component][level][index] = dataset[component][level] / (double) polls;
        }
    }

    final List<SyntheticCoverage.Cell> cells = new ArrayList<>();
    for (int component = 0; component < components.size(); component++)
      for (int level = 0; level < levels; level++)
        cells.add(
            SyntheticCoverage.cell(
                components.get(component),
                SyntheticCoverage.LEVELS.get(level),
                fractions[component][level],
                covered[component][level],
                scored[component][level],
                drawsRetained));
    return new Stage(
        convention,
        plan.datasets(),
        cells,
        SyntheticCoverage.stageVerdict(cells),
        endpoints,
        endpointSelections,
        null);
  }

  /** Each stage keeps its datasets apart, so neither can be mistaken for the other's evidence. */
  private static String directory(String stageName) {
    return stageName.equals(ESTIMATED_STAGE) ? "estimated" : "datasets";
  }

  /** One dataset's search: every attempt it made and the point it selected. */
  private static ObjectNode search(SyntheticSearch.Selection selection) {
    final ObjectNode search = JSON.createObjectNode();
    search.put("points", SyntheticSearch.POINTS);
    search.put("order", "ascending walkVariance, then houseScale, then covarianceMultiplier");
    search.put("tieBreak", "the first point of that order on an exact maximum");
    search.put("objective", "training marginal likelihood; scoring observations never take part");
    search.set("grid", JSON.valueToTree(SyntheticSearch.GRID));
    search.put("selectedIndex", selection.selectedIndex());
    // A stopped search selected nothing. Its point and likelihood are left null rather than
    // written as a nonfinite number, which no strict reader of the retained evidence would parse.
    if (selection.failed()) {
      search.putNull("selected");
      search.putNull("logLikelihood");
    } else {
      search.set("selected", JSON.valueToTree(selection.parameters()));
      search.put("logLikelihood", selection.logLikelihood());
    }
    search.set("endpoints", JSON.valueToTree(selection.endpoints()));
    final ArrayNode attempts = search.putArray("attempts");
    for (SyntheticSearch.Attempt attempt : selection.attempts()) {
      final ObjectNode retained = attempts.addObject();
      retained.put("index", attempt.index());
      retained.put("walkVariance", attempt.walkVariance());
      retained.put("houseScale", attempt.houseScale());
      retained.put("covarianceMultiplier", attempt.covarianceMultiplier());
      if (Double.isFinite(attempt.logLikelihood()))
        retained.put("logLikelihood", attempt.logLikelihood());
      else retained.putNull("logLikelihood");
      retained.put("status", attempt.status());
      retained.put("message", attempt.message());
    }
    return search;
  }

  /** The stage report: what was planned, what ran and what each cell may claim. */
  private static ObjectNode report(
      Plan plan, List<Stage> known, String control, List<Stage> estimated) {
    final ObjectNode report = JSON.createObjectNode();
    final boolean stopped =
        Stream.concat(known.stream(), estimated.stream())
            .anyMatch(stage -> stage.failure() != null);
    report.put("status", stopped ? "stopped" : "completed");
    report.put("version", VERSION);
    report.put("phase", plan.phase());
    report.put(
        "evidenceClass",
        plan.phase().equals(FORMAL) ? "formal" : plan.phase() + "; not recovery evidence");
    report.set("stages", JSON.valueToTree(List.of(KNOWN_STAGE, ESTIMATED_STAGE)));
    report.put("masterSeed", plan.masterSeed());
    report.put("plannedDatasets", plan.datasets());
    report.put("draws", plan.draws());
    report.set("parameters", JSON.valueToTree(SyntheticScenario.TRUTH));
    report.put(
        "parameterSource",
        "the registered generating truth; the known stage fits at it and the estimated stage"
            + " searches the frozen grid for it on the training observations alone");
    report.set("calendar", calendar());
    report.set("observationCovariance", matrix(SyntheticScenario.observationCovariance()));
    report.set("observationNoiseFactor", factor(SyntheticScenario.noiseFactor()));
    report.put(
        "observationCovarianceSource",
        "registered fixed matrix; never reconstructed from generated shares");
    final ObjectNode confidence = report.putObject("confidence");
    confidence.put("alpha", SyntheticCoverage.ALPHA);
    confidence.put("primaryCells", SyntheticCoverage.PRIMARY_CELLS);
    confidence.put("criticalValue", SyntheticCoverage.CRITICAL_VALUE);
    confidence.put(
        "adjustment",
        "two-sided Bonferroni across all 72 primary cells, kept whether or not the estimated"
            + " stages run");
    confidence.put("claim", "approximate large-sample simultaneous 95%, not a finite-sample bound");
    final ObjectNode bands = report.putObject("recoveryBands");
    for (int level : SyntheticCoverage.LEVELS) {
      final ArrayNode band = bands.putArray("level" + level);
      band.add(SyntheticCoverage.band(level).low());
      band.add(SyntheticCoverage.band(level).high());
    }

    final ArrayNode methods = report.putArray("methods");
    for (String convention : CONVENTIONS)
      methods.add(method(plan, known, convention, KNOWN_STAGE, null));

    report.put("knownStageVerdict", control);
    final boolean eligible = control.equals(SyntheticCoverage.DEMONSTRATED);
    report.put("estimatedStagesEligible", eligible);
    report.put(
        "estimatedStageGate",
        "both known-parameter controls must demonstrate recovery before either estimated stage"
            + " starts");
    final String blocked =
        "the known-parameter controls did not demonstrate recovery (" + control + ")";
    final ArrayNode stages = report.putArray("estimatedStages");
    for (String convention : CONVENTIONS)
      stages.add(method(plan, estimated, convention, ESTIMATED_STAGE, eligible ? null : blocked));

    final String tuned = eligible ? SyntheticCoverage.combined(verdicts(estimated)) : NOT_RUN;
    report.put("estimatedStageVerdict", tuned);
    final String overall =
        eligible
            ? SyntheticCoverage.combined(
                Stream.concat(verdicts(known).stream(), verdicts(estimated).stream()).toList())
            : control;
    report.put("recovery", overall);
    // A complete failed or inconclusive stage is a valid stopping result. Anything else leaves the
    // experiment incomplete, including demonstrated recovery, whose completion needs the
    // registration, preflight and reproduction slices this command does not yet carry.
    report.put(
        "experiment",
        overall.equals(SyntheticCoverage.FAILED) || overall.equals(SyntheticCoverage.INCONCLUSIVE)
            ? (eligible ? "stopped_after_estimated_stage" : "stopped_after_known_stage")
            : SyntheticCoverage.INCOMPLETE);
    if (overall.equals(SyntheticCoverage.DEMONSTRATED))
      report.put(
          "experimentReason",
          "every stage demonstrated recovery; completion additionally needs committed registration,"
              + " feasible preflight and reproduced predictive output");
    report.put(
        "conclusion",
        "software execution of the registered scenario; a completed statistical verdict here is"
            + " neither a model change nor permission to publish");
    return report;
  }

  /** One convention of one stage, or the reason it did not run. */
  private static ObjectNode method(
      Plan plan, List<Stage> stages, String convention, String stageName, String blocked) {
    final Stage stage =
        stages.stream()
            .filter(candidate -> candidate.convention().equals(convention))
            .findFirst()
            .orElse(null);
    final ObjectNode method = JSON.createObjectNode();
    method.put("convention", convention);
    method.put("stage", stageName);
    if (stage == null) {
      method.put("status", NOT_RUN);
      method.put("verdict", SyntheticCoverage.INCOMPLETE);
      method.put("reason", blocked == null ? "an earlier stage stopped the run" : blocked);
      return method;
    }
    method.put("status", stage.failure() == null ? "completed" : SyntheticCoverage.INCOMPLETE);
    method.put("verdict", stage.verdict());
    method.put("plannedDatasets", plan.datasets());
    method.put("completedDatasets", stage.completedDatasets());
    method.put("evidence", directory(stageName) + "/" + convention);
    if (stageName.equals(ESTIMATED_STAGE)) {
      method.put("gridPoints", SyntheticSearch.POINTS);
      method.put("endpointSelections", stage.endpointSelections());
      method.set("endpointFrequencies", JSON.valueToTree(stage.endpointFrequencies()));
      method.put(
          "endpointRule",
          "every numerically valid endpoint selection is included; this never waives a real-data"
              + " endpoint gate");
    }
    if (stage.failure() != null) method.set("failure", stage.failure());
    final ArrayNode cells = method.putArray("cells");
    for (SyntheticCoverage.Cell cell : stage.cells()) cells.add(JSON.valueToTree(cell));
    return method;
  }

  private static ObjectNode calendar() {
    final ObjectNode calendar = JSON.createObjectNode();
    calendar.put("periodStart", SyntheticScenario.PERIOD_START.toString());
    calendar.put("cutoff", SyntheticScenario.CUTOFF.toString());
    calendar.put("scoreThrough", SyntheticScenario.HORIZON.toString());
    calendar.put("weeks", SyntheticScenario.WEEKS);
    calendar.put("trainingPolls", SyntheticScenario.TRAINING_POLLS);
    calendar.put("scoringPolls", SyntheticScenario.SCORING_POLLS);
    calendar.set("institutes", JSON.valueToTree(SyntheticScenario.INSTITUTES));
    return calendar;
  }

  /** The repetition that stopped a stage, and the earliest operation that failed in it. */
  private static ObjectNode failure(
      String convention,
      String stageName,
      int datasetIndex,
      String operation,
      RuntimeException cause) {
    final ObjectNode failure = JSON.createObjectNode();
    failure.put("status", "numerical_failure");
    failure.put("version", VERSION);
    failure.put("convention", convention);
    failure.put("datasetIndex", datasetIndex);
    failure.put("stage", stageName);
    failure.put("operation", operation);
    failure.put("message", cause.getMessage() == null ? cause.toString() : cause.getMessage());
    failure.put(
        "denominator", "unchanged; the repetition is preserved and its seed is never replaced");
    return failure;
  }

  /** One generated dataset and the latent truth it came from. */
  private record Synthetic(Dataset dataset, SyntheticScenario.Generated generated) {}

  /** One dataset ready to score: the identity it carries and the rows it observes. */
  private record Dataset(
      String phase,
      String convention,
      int datasetIndex,
      long masterSeed,
      int draws,
      String prefix,
      Roster.CoveragePeriod period,
      LocalDate periodStart,
      LocalDate cutoff,
      LocalDate scoreThrough,
      DailyStateSpace.Parameters parameters,
      ModelValues covariance,
      List<PollObservations.Observation> training,
      List<PollObservations.Observation> scoring) {

    /** The same dataset at another parameter point: identical rows, covariance and streams. */
    Dataset at(DailyStateSpace.Parameters point) {
      return new Dataset(
          phase,
          convention,
          datasetIndex,
          masterSeed,
          draws,
          prefix,
          period,
          periodStart,
          cutoff,
          scoreThrough,
          point,
          covariance,
          training,
          scoring);
    }
  }

  /**
   * One dataset of the registered scenario, generated at the generating truth. The observations
   * reach the estimator as coordinates and a fixed covariance, never through source preparation.
   */
  private static Synthetic generate(Plan plan, String convention, int index) {
    final String prefix = prefix(plan.phase(), convention, index);
    final Roster.CoveragePeriod period = period(prefix);
    final ModelValues covariance = SyntheticScenario.observationCovariance();
    final SyntheticScenario.Generated generated =
        SyntheticScenario.generate(prefix, plan.masterSeed(), FILTER_CONVENTIONS.get(convention));
    final List<PollObservations.Observation> training = new ArrayList<>();
    final List<PollObservations.Observation> scoring = new ArrayList<>();
    for (int row = 0; row < generated.rows().size(); row++) {
      final SyntheticScenario.Row scheduled = generated.rows().get(row);
      final PollObservations.Observation observation =
          observation(
              poll(scheduled.rowNumber(), scheduled.institute(), scheduled.from(), scheduled.to()),
              period,
              column(generated.ilr().get(row)),
              covariance);
      (scheduled.training() ? training : scoring).add(observation);
    }
    require(
        training.size() == SyntheticScenario.TRAINING_POLLS,
        "The registered schedule has " + SyntheticScenario.TRAINING_POLLS + " training polls");
    require(
        scoring.size() == SyntheticScenario.SCORING_POLLS,
        "The registered schedule has " + SyntheticScenario.SCORING_POLLS + " scoring polls");
    return new Synthetic(
        new Dataset(
            plan.phase(),
            convention,
            index,
            plan.masterSeed(),
            plan.draws(),
            prefix,
            period,
            SyntheticScenario.PERIOD_START,
            SyntheticScenario.CUTOFF,
            SyntheticScenario.HORIZON,
            SyntheticScenario.TRUTH,
            covariance,
            training,
            scoring),
        generated);
  }

  /** The generating truth behind one dataset, retained beside the evidence it produced. */
  private static ObjectNode generation(Synthetic synthetic) {
    final SyntheticScenario.Generated generated = synthetic.generated();
    final ObjectNode generation = JSON.createObjectNode();
    final ObjectNode priors = generation.putObject("priors");
    priors.put("initialVariance", SyntheticScenario.INITIAL_VARIANCE);
    priors.put("walkVariance", SyntheticScenario.WALK_VARIANCE);
    priors.put("houseScale", SyntheticScenario.HOUSE_SCALE);
    priors.put("noiseMultiplier", SyntheticScenario.NOISE_MULTIPLIER);
    priors.put("institutePrior", "independent and uncentered; centering is an output transform");
    final ArrayNode streams = generation.putArray("streams");
    for (String stream : List.of("initial", "walk", "houses", "noise"))
      streams.add(synthetic.dataset().prefix() + "|" + stream);
    generation.set("referencePercentages", JSON.valueToTree(SyntheticScenario.REFERENCE));
    generation.put(
        "referenceUse", "covariance only; not the initial-state mean and never regenerated");
    final ObjectNode effects = generation.putObject("instituteEffects");
    for (int institute = 0; institute < SyntheticScenario.INSTITUTES.size(); institute++)
      effects.set(
          SyntheticScenario.INSTITUTES.get(institute),
          JSON.valueToTree(generated.instituteEffects().get(institute)));
    final ObjectNode latent = generation.putObject("latentStates");
    latent.put("dayZero", SyntheticScenario.PERIOD_START.toString());
    latent.put("days", generated.latent().size());
    latent.set("ilr", JSON.valueToTree(generated.latent()));
    final ArrayNode observations = generation.putArray("observations");
    for (int row = 0; row < generated.rows().size(); row++) {
      final SyntheticScenario.Row scheduled = generated.rows().get(row);
      final ObjectNode observation = observations.addObject();
      observation.put("rowNumber", scheduled.rowNumber());
      observation.put("institute", scheduled.institute());
      observation.put("membership", scheduled.training() ? TRAINING : SCORING);
      observation.put("fieldworkFrom", scheduled.from().toString());
      observation.put("fieldworkTo", scheduled.to().toString());
      observation.put(
          "midpoint",
          SyntheticScenario.PERIOD_START.plusDays(scheduled.midpointOffset()).toString());
      observation.set("ilr", JSON.valueToTree(generated.ilr().get(row)));
      observation.set("shares", JSON.valueToTree(generated.shares().get(row)));
    }
    return generation;
  }

  /** One supplied dataset document, read into the same shape a generated dataset takes. */
  private static Dataset supplied(JsonNode document, Path datasetFile, boolean tuning) {
    require(
        VERSION.equals(required(document, "version").asString()),
        "Unregistered protocol version in " + datasetFile);
    final String phase = required(document, "phase").asString();
    require(PHASES.contains(phase), "Unregistered phase " + phase);
    final String convention = required(document, "convention").asString();
    require(FILTER_CONVENTIONS.containsKey(convention), "Unregistered convention " + convention);
    final long masterSeed = required(document, "masterSeed").longValue();
    final int datasetIndex = required(document, "datasetIndex").intValue();
    final int draws = required(document, "draws").intValue();
    require(draws >= 2, "Coverage needs at least two draws");
    require(datasetIndex >= 0, "A dataset index is not negative");
    if (phase.equals(FORMAL)) {
      require(masterSeed == FORMAL_SEED, "The formal phase uses master seed " + FORMAL_SEED);
      require(draws == FORMAL_DRAWS, "The formal phase uses " + FORMAL_DRAWS + " predictive draws");
      require(
          datasetIndex < FORMAL_DATASETS, "Formal dataset indices run to " + (FORMAL_DATASETS - 1));
    }
    if (phase.equals(PREFLIGHT))
      require(
          datasetIndex < PREFLIGHT_DATASETS,
          "Preflight dataset indices run to " + (PREFLIGHT_DATASETS - 1));
    final LocalDate periodStart = date(document, "periodStart");
    final LocalDate cutoff = date(document, "cutoff");
    final LocalDate scoreThrough = date(document, "scoreThrough");
    require(periodStart.isBefore(cutoff), "Training starts before the cutoff");
    require(cutoff.isBefore(scoreThrough), "The scoring horizon follows the cutoff");
    // A tuned dataset carries no parameters: supplying them alongside a search would leave it
    // ambiguous which point the evidence was produced at.
    DailyStateSpace.Parameters parameters = null;
    if (tuning)
      require(
          document.get("parameters") == null,
          "A tuned dataset selects its own parameters on the training observations");
    else {
      final JsonNode point = required(document, "parameters");
      parameters =
          new DailyStateSpace.Parameters(
              required(point, "walkVariance").doubleValue(),
              required(point, "houseScale").doubleValue(),
              required(point, "covarianceMultiplier").doubleValue());
    }

    final String prefix = prefix(phase, convention, datasetIndex);
    final Roster.CoveragePeriod period =
        new Roster.CoveragePeriod(prefix, periodStart, scoreThrough, ROSTER, false, false, null);
    final int dimension = PollObservations.components(period).size() - 1;
    final ModelValues covariance = covariance(document, dimension);

    final List<PollObservations.Observation> training = new ArrayList<>();
    final List<PollObservations.Observation> scoring = new ArrayList<>();
    int previousRow = 0;
    for (JsonNode row : required(document, "observations")) {
      final int rowNumber = required(row, "rowNumber").intValue();
      final LocalDate from = date(row, "fieldworkFrom");
      final LocalDate to = date(row, "fieldworkTo");
      require(!to.isBefore(from), "Row " + rowNumber + " ends before it starts");
      final BigDecimal sampleSize = required(row, "sampleSize").decimalValue();
      require(sampleSize.signum() > 0, "Row " + rowNumber + " has a positive nominal sample size");
      final PollObservations.Observation observation =
          observation(
              poll(rowNumber, required(row, "institute").asString(), from, to, sampleSize),
              period,
              coordinates(row, rowNumber, dimension),
              covariance);
      require(rowNumber > previousRow, "Row identities are unique and ascending: " + rowNumber);
      previousRow = rowNumber;
      require(!from.isBefore(periodStart), "Row " + rowNumber + " starts before the period");
      final String membership = required(row, "membership").asString();
      // Membership follows the publication date and the fieldwork end, never the midpoint, so a
      // held-out window may overlap training dates without entering the training set.
      if (membership.equals(TRAINING)) {
        require(!to.isAfter(cutoff), "Training row " + rowNumber + " ends after the cutoff");
        training.add(observation);
      } else if (membership.equals(SCORING)) {
        require(to.isAfter(cutoff), "Scoring row " + rowNumber + " is published by the cutoff");
        require(!to.isAfter(scoreThrough), "Scoring row " + rowNumber + " ends after the horizon");
        scoring.add(observation);
      } else throw new IllegalArgumentException("Unregistered membership " + membership);
    }
    require(!training.isEmpty(), "A dataset has training observations");
    require(!scoring.isEmpty(), "A dataset has scoring observations");
    return new Dataset(
        phase,
        convention,
        datasetIndex,
        masterSeed,
        draws,
        prefix,
        period,
        periodStart,
        cutoff,
        scoreThrough,
        parameters,
        covariance,
        training,
        scoring);
  }

  /** One dataset of one convention: filter the training rows, then score every held-out row. */
  private static ObjectNode score(Dataset dataset) {
    final List<String> components = PollObservations.components(dataset.period());
    final PollObservations.Batch trainingBatch =
        PollObservations.explicit(dataset.period(), dataset.training());
    final PollObservations.Batch scoringBatch =
        PollObservations.explicit(dataset.period(), dataset.scoring());
    // The synthetic interval has no cycle reset, so every institute keeps one effect throughout.
    final WindowFilter.Scored filtered =
        WindowFilter.score(
            trainingBatch,
            dataset.scoring(),
            List.of(),
            dataset.parameters(),
            FILTER_CONVENTIONS.get(dataset.convention()));
    final Map<Integer, WindowFilter.Prediction> byRow = new LinkedHashMap<>();
    for (WindowFilter.Prediction prediction : filtered.predictions())
      byRow.put(prediction.poll().rowNumber(), prediction);

    final ObjectNode evidence = JSON.createObjectNode();
    evidence.put("status", "scored");
    evidence.put("version", VERSION);
    evidence.put("phase", dataset.phase());
    evidence.put("convention", dataset.convention());
    evidence.put("datasetIndex", dataset.datasetIndex());
    evidence.put("masterSeed", dataset.masterSeed());
    evidence.put("streamPrefix", dataset.prefix());
    evidence.put("periodStart", dataset.periodStart().toString());
    evidence.put("cutoff", dataset.cutoff().toString());
    evidence.put("scoreThrough", dataset.scoreThrough().toString());
    evidence.set("parameters", JSON.valueToTree(dataset.parameters()));
    evidence.set("components", JSON.valueToTree(components));
    evidence.put("dimension", components.size() - 1);
    evidence.put("draws", dataset.draws());
    evidence.set("observationCovariance", matrix(dataset.covariance()));
    evidence.put(
        "observationCovarianceSource",
        "registered fixed matrix; never reconstructed from generated shares");
    evidence.put("inputsSha256", inputsSha256(dataset));
    evidence.put(
        "inputsEncoding",
        "big-endian binary64 of the fixed covariance, then each row's identity, membership,"
            + " fieldwork dates and ilr coordinates in schedule order");
    evidence.put("drawEncoding", PredictiveScoring.DRAW_ENCODING);
    evidence.put("trainingPolls", dataset.training().size());
    evidence.put("scoringPolls", dataset.scoring().size());
    evidence.put("trainingLogLikelihood", filtered.logLikelihood());
    final ArrayNode polls = evidence.putArray("polls");
    for (PollObservations.Observation observation : dataset.scoring()) {
      final WindowFilter.Prediction prediction = byRow.get(observation.poll().rowNumber());
      require(
          prediction != null, "No prediction for scoring row " + observation.poll().rowNumber());
      polls.add(
          scored(
              scoringBatch,
              observation,
              prediction,
              dataset.prefix(),
              dataset.masterSeed(),
              dataset.draws(),
              components));
    }
    return evidence;
  }

  /** One scored poll: the predictive distribution it came from and the summaries it produced. */
  private static ObjectNode scored(
      PollObservations.Batch batch,
      PollObservations.Observation observation,
      WindowFilter.Prediction prediction,
      String prefix,
      long masterSeed,
      int draws,
      List<String> components) {
    final String stream = prefix + "|predictive|" + observation.poll().rowNumber();
    final PredictiveScoring.Summary summary =
        PredictiveScoring.score(
            batch,
            observation,
            prediction.mean(),
            prediction.covariance(),
            draws,
            masterSeed,
            stream,
            (ignoredStream, ignoredDraws) -> {});
    final ObjectNode row = JSON.createObjectNode();
    row.put("rowNumber", observation.poll().rowNumber());
    row.put("institute", observation.poll().institute());
    row.put("fieldworkFrom", observation.poll().collectionFrom().toString());
    row.put("fieldworkTo", observation.poll().collectionTo().toString());
    row.put("fieldworkDays", prediction.window().days());
    row.put("midpoint", observation.midpoint().toString());
    row.put("publicationDate", observation.poll().publicationDate().toString());
    row.put("sampleSize", observation.poll().sampleSize());
    row.put("predictedOn", prediction.predictedOn().toString());
    row.put("priorEffect", prediction.priorEffect());
    row.put("windowFrom", prediction.window().from().toString());
    row.put("windowTo", prediction.window().to().toString());
    row.put("stream", stream);
    row.put("seed", summary.seed());
    row.put("masterSeed", masterSeed);
    row.put("draws", summary.draws());
    row.put("dimension", summary.dimension());
    row.set("observedIlr", column(observation.ilr()));
    row.set("observedShares", JSON.valueToTree(PollObservations.shares(batch, observation.ilr())));
    row.set("predictiveMean", column(prediction.mean()));
    row.set("predictiveCovariance", matrix(prediction.covariance()));
    row.put("jointLogScore", summary.logScore());
    row.put("drawsSha256", summary.drawsSha256());
    final ArrayNode summaries = row.putArray("components");
    for (int index = 0; index < components.size(); index++) {
      final PredictiveScoring.Component component = summary.components().get(index);
      require(
          component.component().equals(components.get(index)),
          "Component order is fixed at " + components);
      summaries.add(JSON.valueToTree(component));
    }
    return row;
  }

  /**
   * The identity of one dataset's inputs. Both stages of a convention observe the same rows, the
   * same coordinates and the same fixed covariance, so their evidence carries the same hash and a
   * stage that silently regenerated different observations would be visible.
   */
  private static String inputsSha256(Dataset dataset) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    final ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES);
    final ModelValues covariance = dataset.covariance();
    for (int r = 0; r < covariance.getNumRows(); r++)
      for (int c = 0; c < covariance.getNumCols(); c++)
        digest.update(buffer.putDouble(0, covariance.get(r, c)).array());
    final Map<Integer, String> membership = new LinkedHashMap<>();
    for (PollObservations.Observation observation : dataset.training())
      membership.put(observation.poll().rowNumber(), TRAINING);
    for (PollObservations.Observation observation : dataset.scoring())
      membership.put(observation.poll().rowNumber(), SCORING);
    for (PollObservations.Observation observation :
        Stream.concat(dataset.training().stream(), dataset.scoring().stream())
            .sorted(Comparator.comparingInt(row -> row.poll().rowNumber()))
            .toList()) {
      digest.update(
          (observation.poll().rowNumber()
                  + "|"
                  + observation.poll().institute()
                  + "|"
                  + membership.get(observation.poll().rowNumber())
                  + "|"
                  + observation.poll().collectionFrom()
                  + "|"
                  + observation.poll().collectionTo()
                  + "|"
                  + observation.poll().publicationDate()
                  + "|")
              .getBytes(StandardCharsets.UTF_8));
      for (int i = 0; i < observation.ilr().getNumRows(); i++)
        digest.update(buffer.putDouble(0, observation.ilr().get(i, 0)).array());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String prefix(String phase, String convention, int datasetIndex) {
    return VERSION + "|" + phase + "|" + convention + "|" + datasetIndex;
  }

  private static Roster.CoveragePeriod period(String prefix) {
    return new Roster.CoveragePeriod(
        prefix,
        SyntheticScenario.PERIOD_START,
        SyntheticScenario.HORIZON,
        ROSTER,
        false,
        false,
        null);
  }

  private static List<String> components(String prefix) {
    return PollObservations.components(period(prefix));
  }

  /** The registered observation covariance, preserved exactly as the document supplies it. */
  private static ModelValues covariance(JsonNode document, int dimension) {
    final JsonNode rows = required(document, "observationCovariance");
    require(rows.size() == dimension, "The observation covariance is " + dimension + " square");
    final SimpleMatrix values = new SimpleMatrix(dimension, dimension);
    for (int r = 0; r < dimension; r++) {
      final JsonNode row = rows.get(r);
      require(row.size() == dimension, "The observation covariance is " + dimension + " square");
      for (int c = 0; c < dimension; c++) {
        final double value = row.get(c).doubleValue();
        require(Double.isFinite(value), "The observation covariance is finite");
        values.set(r, c, value);
      }
    }
    final ModelValues covariance = ModelValues.copyOf(values);
    require(
        covariance.symmetryError() <= 1e-12 * covariance.elementMaxAbs(),
        "The observation covariance is symmetric");
    // Factoring rejects a matrix no fit could run on, before any observation is read.
    WindowFilter.factor(square(covariance, dimension));
    return covariance;
  }

  private static double[][] square(ModelValues values, int dimension) {
    final double[][] matrix = new double[dimension][dimension];
    for (int r = 0; r < dimension; r++)
      for (int c = 0; c < dimension; c++) matrix[r][c] = values.get(r, c);
    return matrix;
  }

  private static ModelValues coordinates(JsonNode row, int rowNumber, int dimension) {
    final JsonNode coordinates = required(row, "ilr");
    require(
        coordinates.size() == dimension,
        "Row " + rowNumber + " has " + dimension + " ilr coordinates");
    final double[] values = new double[dimension];
    for (int i = 0; i < dimension; i++) {
      values[i] = coordinates.get(i).doubleValue();
      require(Double.isFinite(values[i]), "Row " + rowNumber + " has finite ilr coordinates");
    }
    return column(values);
  }

  /**
   * One poll of the synthetic calendar. Publication happens on the fieldwork end, and the nominal
   * sample size is carried for the record rather than used: the covariance is supplied.
   */
  private static PollCsv.Poll poll(
      int rowNumber, String institute, LocalDate from, LocalDate to, BigDecimal sampleSize) {
    return new PollCsv.Poll(
        rowNumber,
        Map.of("row", Integer.toString(rowNumber)),
        institute,
        institute,
        null,
        null,
        null,
        null,
        to,
        from,
        to,
        sampleSize,
        Map.of(),
        null,
        List.of());
  }

  private static PollCsv.Poll poll(int rowNumber, String institute, LocalDate from, LocalDate to) {
    return poll(rowNumber, institute, from, to, SyntheticScenario.SAMPLE_SIZE);
  }

  /**
   * One synthetic observation. Its ilr coordinates and its covariance are supplied, so neither the
   * shares nor the nominal sample size takes part in the numerical path. The midpoint follows the
   * estimator's own floor-of-half-elapsed-days convention.
   */
  private static PollObservations.Observation observation(
      PollCsv.Poll poll, Roster.CoveragePeriod period, ModelValues ilr, ModelValues covariance) {
    require(period.covers(poll), "Row " + poll.rowNumber() + " lies outside the synthetic period");
    final LocalDate from = poll.collectionFrom();
    final LocalDate to = poll.collectionTo();
    return new PollObservations.Observation(
        poll, from.plusDays(ChronoUnit.DAYS.between(from, to) / 2), ilr, covariance, 0);
  }

  private static ModelValues column(double[] values) {
    final SimpleMatrix matrix = new SimpleMatrix(values.length, 1);
    for (int r = 0; r < values.length; r++) matrix.set(r, 0, values[r]);
    return ModelValues.owned(matrix);
  }

  private static ArrayNode column(ModelValues values) {
    final ArrayNode array = JSON.createArrayNode();
    for (int r = 0; r < values.getNumRows(); r++) array.add(values.get(r, 0));
    return array;
  }

  private static ArrayNode matrix(ModelValues values) {
    final ArrayNode array = JSON.createArrayNode();
    for (int r = 0; r < values.getNumRows(); r++) {
      final ArrayNode row = array.addArray();
      for (int c = 0; c < values.getNumCols(); c++) row.add(values.get(r, c));
    }
    return array;
  }

  private static ArrayNode factor(double[][] values) {
    final ArrayNode array = JSON.createArrayNode();
    for (double[] row : values) {
      final ArrayNode line = array.addArray();
      for (double value : row) line.add(value);
    }
    return array;
  }

  private static LocalDate date(JsonNode parent, String field) {
    return LocalDate.parse(required(parent, field).asString());
  }

  private static JsonNode required(JsonNode parent, String field) {
    final JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull())
      throw new IllegalArgumentException("Incomplete synthetic dataset: missing " + field);
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static JsonNode read(Path file) {
    try {
      return JSON.readTree(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid JSON in " + file, e);
    }
  }

  private static void write(Path file, JsonNode value) {
    try {
      final Path parent = file.toAbsolutePath().normalize().getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.write(
          file,
          (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator())
              .getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void rejected(Path output, String reason) {
    if (Files.exists(output)) return;
    final ObjectNode result = JSON.createObjectNode();
    result.put("status", "rejected");
    result.put("version", VERSION);
    result.putArray("reasons").add(reason == null ? "Synthetic scoring failed" : reason);
    result.put("scoringEvidence", NOT_RUN);
    try {
      write(output, result);
    } catch (RuntimeException ignored) {
      // A rejection must never overwrite or obscure the original failure.
    }
  }
}
