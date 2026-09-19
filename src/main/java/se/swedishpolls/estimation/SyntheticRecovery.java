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
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.ejml.simple.SimpleMatrix;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The operator entry point of the accepted {@code synthetic-recovery-v2} protocol.
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

  /** A completed operation whose outcome did not confirm what it checked. */
  public static final int BLOCKED = 1;

  public static final int REJECTED = 2;

  /** A run stopped by a numerical failure, with its partial evidence preserved. */
  public static final int STOPPED = 3;

  /** A run the 24-hour watchdog stopped, with its completed records preserved. */
  public static final int INTERRUPTED = 4;

  /** A preflight whose projection does not fit the registered host. */
  public static final int INFEASIBLE = 5;

  /** A run a stage budget deferred, with every completed stage reported as it stands. */
  public static final int DEFERRED = 6;

  /** The accepted protocol version. A document naming another protocol is refused. */
  static final String VERSION = "synthetic-recovery-v2";

  /**
   * The token every stream name carries. A stream name is a generating input: changing it changes
   * every draw in the experiment, so the v2 amendment leaves it at the revision that registered it.
   */
  static final String STREAM_VERSION = "synthetic-recovery-v1";

  /** The registered master seed and draw count of the formal phase. */
  static final long FORMAL_SEED = 20260916;

  static final int FORMAL_DRAWS = 4000;

  /** The registered dataset indices of the formal phase. */
  static final int FORMAL_DATASETS = 10000;

  /**
   * The registered sustained-preflight window: the smallest sub-window a rate may be read from, the
   * measured window itself, and the most warm-up the host is given to reach thermal steady state.
   */
  static final int PREFLIGHT_SUB_WINDOW_DATASETS = SyntheticBudget.SUB_WINDOW_DATASETS;

  static final int PREFLIGHT_WINDOW_DATASETS = 2 * PREFLIGHT_SUB_WINDOW_DATASETS;

  static final int PREFLIGHT_WARM_UP_LIMIT = 3 * PREFLIGHT_SUB_WINDOW_DATASETS;

  /** Steady state: two consecutive warm-up sub-windows whose rate differs by at most this. */
  static final double STEADY_STATE_TOLERANCE = 0.05;

  static final String STEADY_STATE_CRITERION =
      "two consecutive warm-up sub-windows of the registered size whose wall-clock time per"
          + " dataset differs by at most 5%, with every worker busy throughout";

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

  static final String PREFLIGHT = "preflight";

  static final String SOFTWARE_CHECK = "software_check";

  /** The software-check phase keeps test fixtures out of the registered scientific streams. */
  private static final List<String> PHASES = List.of(FORMAL, PREFLIGHT, SOFTWARE_CHECK);

  private static final String MIDPOINT = "midpoint";
  private static final String ILR_WINDOW = "ilr_window";

  /** Both conventions, in the order the controls run them. */
  static final List<String> CONVENTIONS = List.of(MIDPOINT, ILR_WINDOW);

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
    final String command = args.length == 0 ? "" : args[0];
    if (args.length == 3 && command.equals("score")) return scoreOne(args[1], args[2], false);
    if (args.length == 3 && command.equals("tune")) return scoreOne(args[1], args[2], true);
    if ((args.length == 3 || args.length == 4) && command.equals("control"))
      return control(args[1], args[2], args.length == 4 ? args[3] : "1");
    if (args.length == 3 && command.equals("register")) return register(args[1], args[2]);
    if (args.length == 3 && command.equals("preflight")) return preflight(args[1], args[2]);
    if (args.length == 4 && command.equals("commit")) return commit(args[1], args[2], args[3]);
    if ((args.length == 3 || args.length == 4) && command.equals("experiment"))
      return experiment(args[1], args[2], args.length == 4 ? args[3] : null);
    if (args.length == 4 && command.equals("reproduce"))
      return reproduce(args[1], args[2], args[3]);
    if (args.length == 4 && command.equals("summarize"))
      return summarize(args[1], args[2], args[3]);
    if (args.length == 3 && command.equals("report")) return infeasibilityReport(args[1], args[2]);
    if (args.length == 5 && command.equals("report"))
      return finalReport(args[1], args[2], args[3], args[4]);
    System.err.println(
        "Usage: score <dataset.json> <evidence.json> | tune <dataset.json> <evidence.json>"
            + " | control <plan.json> <dir> [workers]"
            + " | register <plan.json> <registration.json>"
            + " | preflight <registration.json> <dir>"
            + " | commit <registration.json> <preflight.json> <execution.json>"
            + " | experiment <execution.json> <dir> [resumption.json]"
            + " | reproduce <execution.json> <dir> <reproduction.json>"
            + " | summarize <execution.json> <dir> <results.json>"
            + " | report <execution.json> <dir> <reproduction.json> <report.json>"
            + " | report <preflight.json> <report.json>");
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
  private static int control(String planFile, String directory, String workerCount) {
    final Path destination = Path.of(directory);
    final Path report = destination.resolve("control.json");
    final Plan plan;
    final int workers;
    try {
      // A destination that exists could already hold evidence, so nothing is written into it.
      if (Files.exists(destination)) {
        System.err.println("Evidence destination already exists: " + destination);
        return REJECTED;
      }
      final Path file = Path.of(planFile);
      plan = plan(read(file), file);
      workers = Integer.parseInt(workerCount);
      require(
          workers >= 1 && workers <= Runtime.getRuntime().availableProcessors(),
          "A run uses between one worker and one per available processor");
    } catch (RuntimeException e) {
      rejected(report, e.getMessage());
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final Run run = execute(plan, destination, () -> false, workers, UNBUDGETED);
    write(report, run.report());
    return run.exitCode();
  }

  /** One completed or stopped pass over both stages, and the report it reduced to. */
  private record Run(ObjectNode report, List<Stage> known, List<Stage> estimated, int exitCode) {}

  /**
   * The stage-budget gate. Each stage is projected against the budget remaining when its turn
   * comes; a stage that does not fit is never started. Timing may inform what runs, and coverage
   * may never: nothing in this seam reads a cell, a fraction or a verdict.
   */
  private interface Budgeting {
    /** The projection of one stage, or null when no budget bounds this run. */
    SyntheticBudget.StageBudget check(String convention, String stageName, List<Stage> known);
  }

  /** A software check runs under no clock and no budget. */
  private static final Budgeting UNBUDGETED = (convention, stageName, known) -> null;

  /**
   * Both known-parameter controls and, when they demonstrate recovery, both estimated stages, in
   * the fixed order {@code midpoint} known, {@code ilr_window} known, {@code midpoint} estimated,
   * {@code ilr_window} estimated. Conventions and stages stay sequential: the
   * controls-before-estimation gate depends on it, and a gate running concurrently with the thing
   * it gates is not a gate. Workers parallelise datasets within one stage only.
   */
  private static Run execute(
      Plan plan,
      Path destination,
      SyntheticReproduction.Deadline deadline,
      int workers,
      Budgeting budgeting) {
    final List<Stage> known = new ArrayList<>();
    for (String convention : CONVENTIONS) {
      final Stage stage =
          budgeted(plan, convention, destination, KNOWN_STAGE, deadline, workers, budgeting, known);
      known.add(stage);
      if (stage.stopped()) break;
    }

    // Both controls have to demonstrate recovery before either estimated stage runs; a failed,
    // inconclusive or incomplete control leaves both of them explicitly not run.
    final String control = SyntheticCoverage.combined(verdicts(known));
    final List<Stage> estimated = new ArrayList<>();
    if (control.equals(SyntheticCoverage.DEMONSTRATED))
      for (String convention : CONVENTIONS) {
        final Stage stage =
            budgeted(
                plan,
                convention,
                destination,
                ESTIMATED_STAGE,
                deadline,
                workers,
                budgeting,
                known);
        estimated.add(stage);
        if (stage.stopped()) break;
      }

    final List<Stage> all = Stream.concat(known.stream(), estimated.stream()).toList();
    final int exitCode =
        all.stream().anyMatch(stage -> stage.interruption() != null)
            ? INTERRUPTED
            : all.stream().anyMatch(stage -> stage.failure() != null)
                ? STOPPED
                : all.stream().anyMatch(stage -> stage.deferral() != null) ? DEFERRED : SUCCESS;
    return new Run(report(plan, known, control, estimated), known, estimated, exitCode);
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
    return new Plan(phase, masterSeed, datasets, draws);
  }

  /** One method and stage: its cells and verdict, or what stopped or deferred it. */
  private record Stage(
      String convention,
      int completedDatasets,
      List<SyntheticCoverage.Cell> cells,
      String verdict,
      Map<String, Integer> endpointFrequencies,
      int endpointSelections,
      long elapsedNanos,
      ObjectNode failure,
      ObjectNode interruption,
      ObjectNode deferral) {

    boolean stopped() {
      return failure != null || interruption != null || deferral != null;
    }
  }

  /**
   * One stage, gated by the budget remaining when it starts. A stage that does not project within
   * that budget is not started at all: it is recorded as deferred, unmeasured, with {@code N}, the
   * grid, the schedule and the confidence rule untouched.
   */
  private static Stage budgeted(
      Plan plan,
      String convention,
      Path destination,
      String stageName,
      SyntheticReproduction.Deadline deadline,
      int workers,
      Budgeting budgeting,
      List<Stage> known) {
    // Past the cap there is no budget left to defer against: that is the watchdog's business, and
    // the stage reports the interruption rather than a deferral.
    final SyntheticBudget.StageBudget budget =
        deadline.expired() ? null : budgeting.check(convention, stageName, known);
    if (budget != null && !budget.fits()) {
      final ObjectNode deferral = deferral(budget);
      write(destination.resolve("deferral.json"), deferral);
      return new Stage(
          convention,
          0,
          List.of(),
          SyntheticCoverage.INCOMPLETE,
          Map.of(),
          0,
          0,
          null,
          null,
          deferral);
    }
    return stage(plan, convention, destination, stageName, deadline, workers);
  }

  /** The stage a budget deferred, the budget it had and the projection that exceeded it. */
  private static ObjectNode deferral(SyntheticBudget.StageBudget budget) {
    final ObjectNode deferral = JSON.createObjectNode();
    deferral.put("status", "stage_budget_deferred");
    deferral.put("version", VERSION);
    deferral.put("convention", budget.convention());
    deferral.put("stage", budget.stage());
    deferral.put("deferredAt", Instant.now().toString());
    deferral.put("perDatasetNanos", budget.perDatasetNanos());
    deferral.put("projectedNanos", budget.projectedNanos());
    deferral.put("remainingNanos", budget.remainingNanos());
    deferral.put("projectionMargin", SyntheticBudget.MARGIN);
    deferral.put("projectionSource", budget.source());
    deferral.put(
        "reason",
        "the stage does not project within the budget remaining when its turn came; it is"
            + " unmeasured rather than shortened");
    deferral.put(
        "settings",
        "unchanged; N stays at the registered repetition count and the grid, schedule and"
            + " confidence rule are untouched");
    deferral.put(
        "inputs",
        "timing only; no coverage figure, per-dataset fraction, cell verdict or endpoint frequency"
            + " reached this decision");
    return deferral;
  }

  /** One dataset's retained outcome, as the worker that ran it produced it. */
  private record Retained(
      int datasetIndex, ObjectNode evidence, ObjectNode failure, List<String> endpoints) {}

  /**
   * One convention and stage. Every dataset is generated, scored and retained before any of it is
   * reduced, and each dataset contributes exactly one fraction per cell. The estimated stage
   * regenerates the identical observations from the same streams and selects its parameters on the
   * training observations alone before scoring them.
   *
   * <p>Workers take datasets in batches of the registered worker count. Every dataset derives its
   * streams from its own name and reads no other dataset's state, so which worker takes which
   * dataset cannot reach any result; the reduction walks the batch in dataset order regardless of
   * the order the workers finished it.
   */
  private static Stage stage(
      Plan plan,
      String convention,
      Path destination,
      String stageName,
      SyntheticReproduction.Deadline deadline,
      int workers) {
    final boolean estimated = stageName.equals(ESTIMATED_STAGE);
    final List<String> components = components(prefix(plan.phase(), convention, 0));
    final int levels = SyntheticCoverage.LEVELS.size();
    final double[][][] fractions = new double[components.size()][levels][plan.datasets()];
    final long[][] covered = new long[components.size()][levels];
    final long[][] scored = new long[components.size()][levels];
    final Map<String, Integer> endpoints = new LinkedHashMap<>();
    int endpointSelections = 0;
    boolean drawsRetained = true;
    final long startNanos = System.nanoTime();
    int index = 0;
    try (final ExecutorService pool = Executors.newFixedThreadPool(workers)) {
      while (index < plan.datasets()) {
        // The watchdog stops scheduling at the cap and lets the batch in flight finish. Everything
        // completed so far stays where it is, and the reason it stopped is preserved beside it.
        if (deadline.expired()) {
          final ObjectNode interruption =
              interruption(convention, stageName, index, deadline.overrunMillis());
          write(destination.resolve("interruption.json"), interruption);
          return new Stage(
              convention,
              index,
              List.of(),
              SyntheticCoverage.INCOMPLETE,
              endpoints,
              endpointSelections,
              System.nanoTime() - startNanos,
              null,
              interruption,
              null);
        }
        final int batch = Math.min(workers, plan.datasets() - index);
        final List<Future<Retained>> scheduled = new ArrayList<>();
        for (int offset = 0; offset < batch; offset++) {
          final int datasetIndex = index + offset;
          scheduled.add(
              pool.submit(
                  () -> retain(plan, convention, destination, stageName, datasetIndex, estimated)));
        }
        for (int offset = 0; offset < batch; offset++) {
          final Retained retained = awaited(scheduled.get(offset));
          if (retained.failure() != null)
            // The repetition, its identity and the earliest failing operation are kept where its
            // evidence would have gone; the planned denominator is not reduced and the seed is
            // never replaced. Repetitions that shared its batch keep their own retained evidence.
            return new Stage(
                convention,
                retained.datasetIndex(),
                List.of(),
                SyntheticCoverage.INCOMPLETE,
                endpoints,
                endpointSelections,
                System.nanoTime() - startNanos,
                retained.failure(),
                null,
                null);
          for (String endpoint : retained.endpoints()) endpoints.merge(endpoint, 1, Integer::sum);
          if (!retained.endpoints().isEmpty()) endpointSelections++;
          // One dataset's 25 scoring polls are correlated, so they reduce to one fraction per cell
          // before anything is averaged.
          final ObjectNode evidence = retained.evidence();
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
              fractions[component][level][retained.datasetIndex()] =
                  dataset[component][level] / (double) polls;
            }
        }
        index += batch;
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
        System.nanoTime() - startNanos,
        null,
        null,
        null);
  }

  /**
   * One dataset of one stage, generated, scored and retained by whichever worker took it. Nothing
   * here reads shared state, so the evidence it writes is the same at one worker and at four.
   */
  private static Retained retain(
      Plan plan,
      String convention,
      Path destination,
      String stageName,
      int index,
      boolean estimated) {
    final Path file =
        destination.resolve(directory(stageName)).resolve(convention).resolve(index + ".json");
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
      final ObjectNode evidence =
          score(
              selection == null
                  ? synthetic.dataset()
                  : synthetic.dataset().at(selection.parameters()));
      evidence.put("stage", stageName);
      if (selection != null) evidence.set("search", search(selection));
      evidence.set("generation", generation(synthetic));
      operation = "retain";
      write(file, evidence);
      return new Retained(
          index, evidence, null, selection == null ? List.of() : selection.endpoints());
    } catch (RuntimeException e) {
      final ObjectNode failure = failure(convention, stageName, index, operation, e);
      if (selection != null) failure.set("search", search(selection));
      if (!Files.exists(file))
        try {
          write(file, failure);
        } catch (RuntimeException ignored) {
          // A preserved failure must never obscure the failure it records.
        }
      return new Retained(index, null, failure, List.of());
    }
  }

  /** One worker's dataset, waited for. A worker that died takes the stage down with it. */
  private static Retained awaited(Future<Retained> scheduled) {
    try {
      return scheduled.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("A worker was interrupted before its dataset completed", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(
          "A worker failed outside the repetition it ran", e.getCause());
    }
  }

  /** The repetition the watchdog stopped scheduling at, and how far past the cap it ran. */
  private static ObjectNode interruption(
      String convention, String stageName, int completed, long overrunMillis) {
    final ObjectNode interruption = JSON.createObjectNode();
    interruption.put("status", "interrupted");
    interruption.put("version", VERSION);
    interruption.put("convention", convention);
    interruption.put("stage", stageName);
    interruption.put("datasetIndex", completed);
    interruption.put("completedRepetitions", completed);
    interruption.put("overrunMillis", overrunMillis);
    interruption.put("reason", "the 24-hour wall-clock cap was reached");
    interruption.put("stoppedAt", Instant.now().toString());
    interruption.put(
        "drain",
        "scheduling stopped at the cap and the repetitions in flight were allowed to finish; a"
            + " partially written dataset is not evidence");
    interruption.put(
        "preservation", "every completed record is retained; nothing is adapted or rerun");
    interruption.put(
        "coverage",
        "none; a stage the watchdog stopped short reports no coverage and N stays at the"
            + " registered repetition count");
    interruption.put(
        "resumption",
        "resuming needs a separately recorded owner decision and preserves this partial output");
    return interruption;
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
    final List<Stage> all = Stream.concat(known.stream(), estimated.stream()).toList();
    final boolean interrupted = all.stream().anyMatch(stage -> stage.interruption() != null);
    final boolean stopped = all.stream().anyMatch(stage -> stage.failure() != null);
    final boolean deferred = all.stream().anyMatch(stage -> stage.deferral() != null);
    report.put(
        "status",
        interrupted
            ? "interrupted"
            : stopped ? "stopped" : deferred ? "stage_budget_deferred" : "completed");
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
    report.put(
        "interimStopping",
        "none; repetitions are never adapted and no coverage is inspected before a stage"
            + " completes");
    final ObjectNode bands = report.putObject("recoveryBands");
    for (int level : SyntheticCoverage.LEVELS) {
      final ArrayNode band = bands.putArray("level" + level);
      band.add(SyntheticCoverage.band(level).low());
      band.add(SyntheticCoverage.band(level).high());
    }

    final ArrayNode methods = report.putArray("methods");
    for (String convention : CONVENTIONS)
      methods.add(method(plan, known, convention, KNOWN_STAGE, null));

    // Both control verdicts stand at the top, each as a complete result in its own right. A
    // control that ran all its repetitions and reported all 18 of its cells is a result whether or
    // not the stages depending on it ever start, and the combined verdict below is a gate rather
    // than a substitute for either.
    final ArrayNode controls = report.putArray("controlVerdicts");
    for (String convention : CONVENTIONS) controls.add(controlVerdict(plan, known, convention));
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
    report.put("experiment", experiment(overall, eligible, interrupted, stopped, deferred));
    report.put("experimentReason", experimentReason(overall, interrupted, stopped, deferred));
    report.put(
        "conclusion",
        "software execution of the registered scenario; a completed statistical verdict here is"
            + " neither a model change nor permission to publish");
    return report;
  }

  /**
   * What the run itself amounts to. A complete failed or inconclusive stage is a valid stopping
   * result; an interrupted or numerically stopped run is incomplete, and demonstrated recovery
   * stays incomplete here because completion also needs reproduced output and unchanged protected
   * evidence, which the reproduction and report commands establish.
   */
  private static String experiment(
      String overall, boolean eligible, boolean interrupted, boolean stopped, boolean deferred) {
    if (interrupted) return "incomplete_interrupted";
    if (stopped) return "incomplete_numerical_failure";
    if (deferred) return "incomplete_stage_budget_deferred";
    if (overall.equals(SyntheticCoverage.FAILED) || overall.equals(SyntheticCoverage.INCONCLUSIVE))
      return eligible ? "stopped_after_estimated_stage" : "stopped_after_known_stage";
    return SyntheticCoverage.INCOMPLETE;
  }

  private static String experimentReason(
      String overall, boolean interrupted, boolean stopped, boolean deferred) {
    if (interrupted)
      return "the 24-hour cap stopped scheduling; completed records are preserved and resuming"
          + " needs a separately recorded owner decision";
    if (stopped)
      return "a numerical failure stopped the run at a repetition the evidence preserves";
    if (deferred)
      return "a stage did not project within the budget remaining when its turn came and was"
          + " deferred unmeasured; every completed stage is a result in its own right";
    if (overall.equals(SyntheticCoverage.DEMONSTRATED))
      return "every stage demonstrated recovery; completion additionally needs reproduced"
          + " predictive output and unchanged protected real-data evidence";
    return "the stage verdicts are a completed result of the registered run";
  }

  /** One control, as the report states it at the top: what it ran and what it established. */
  private static ObjectNode controlVerdict(Plan plan, List<Stage> known, String convention) {
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("convention", convention);
    retained.put("stage", KNOWN_STAGE);
    final Stage stage =
        known.stream()
            .filter(candidate -> candidate.convention().equals(convention))
            .findFirst()
            .orElse(null);
    if (stage == null) {
      retained.put("status", NOT_RUN);
      retained.put("verdict", SyntheticCoverage.INCOMPLETE);
      retained.put("reason", "an earlier stage stopped the run");
      return retained;
    }
    retained.put(
        "status",
        stage.deferral() != null
            ? NOT_RUN
            : stage.stopped() ? SyntheticCoverage.INCOMPLETE : "completed");
    retained.put("verdict", stage.verdict());
    retained.put("plannedDatasets", plan.datasets());
    retained.put("completedDatasets", stage.completedDatasets());
    retained.put("cells", stage.cells().size());
    if (stage.deferral() != null) retained.put("reason", stage.deferral().get("reason").asString());
    return retained;
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
    method.put(
        "status",
        stage.deferral() != null
            ? NOT_RUN
            : stage.stopped() ? SyntheticCoverage.INCOMPLETE : "completed");
    method.put("verdict", stage.verdict());
    method.put("plannedDatasets", plan.datasets());
    method.put("completedDatasets", stage.completedDatasets());
    method.put("elapsedNanos", stage.elapsedNanos());
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
    if (stage.interruption() != null) method.set("interruption", stage.interruption());
    if (stage.deferral() != null) {
      method.set("deferral", stage.deferral());
      method.put("reason", stage.deferral().get("reason").asString());
    }
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
    // Preflight indices run from zero upward, as many as steady state and the measured window
    // require. They are bounded by the window the registration fixed, not by a constant here.
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
    evidence.set("coverageFractions", fractions(polls, components));
    evidence.put(
        "coverageFractionRule",
        "one fraction per component and interval level over this dataset's scoring polls; the"
            + " polls are correlated and are never averaged as independent repetitions");
    return evidence;
  }

  /** What one dataset contributes to each cell: the fraction of its scoring polls covered. */
  private static ObjectNode fractions(ArrayNode polls, List<String> components) {
    final ObjectNode fractions = JSON.createObjectNode();
    for (int component = 0; component < components.size(); component++) {
      final ObjectNode levels = fractions.putObject(components.get(component));
      for (int level : SyntheticCoverage.LEVELS) {
        int covered = 0;
        for (JsonNode poll : polls)
          if (poll.get("components").get(component).get("covered" + level).booleanValue())
            covered++;
        levels.put("level" + level, covered / (double) polls.size());
      }
    }
    return fractions;
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
    return STREAM_VERSION + "|" + phase + "|" + convention + "|" + datasetIndex;
  }

  static Roster.CoveragePeriod period(String prefix) {
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

  /** The registered component order, which every dataset of the scenario observes. */
  static List<String> components() {
    return components(STREAM_VERSION);
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
  static PollCsv.Poll poll(
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
  static PollObservations.Observation observation(
      PollCsv.Poll poll, Roster.CoveragePeriod period, ModelValues ilr, ModelValues covariance) {
    require(period.covers(poll), "Row " + poll.rowNumber() + " lies outside the synthetic period");
    final LocalDate from = poll.collectionFrom();
    final LocalDate to = poll.collectionTo();
    return new PollObservations.Observation(
        poll, from.plusDays(ChronoUnit.DAYS.between(from, to) / 2), ilr, covariance, 0);
  }

  static ModelValues column(double[] values) {
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

  /** Validates a registration plan and freezes it, with its own checksum beside it. */
  private static int register(String planFile, String registrationFile) {
    final Path output = Path.of(registrationFile);
    try {
      SyntheticRegistration.register(Path.of(planFile), output);
      return SUCCESS;
    } catch (RuntimeException e) {
      // A refusal leaves nothing behind: a rejection document at a registered path would block
      // the corrected operation the operator is about to run.
      System.err.println(e.getMessage());
      return REJECTED;
    }
  }

  /**
   * The sustained-throughput preflight: warm the host to thermal steady state at the registered
   * worker count, then time a measured window in which every worker stays busy, and project from
   * the slowest sub-window of that window.
   *
   * <p>v1 timed one dataset at a time on a cold machine, which samples the best conditions an
   * H-series laptop part will ever see. Sustained all-core clocks on that part are materially
   * lower, so a projection from the cold burst understates the run.
   *
   * <p>Each dataset measures generation, the known-parameter prediction path, all 180 training-grid
   * evaluations, estimated-parameter prediction, the actual evidence writing and hashing and full
   * reproduction. The estimated path runs here to be costed, never to be inspected: no coverage
   * from these datasets enters a formal result.
   */
  private static int preflight(String registrationFile, String directory) {
    final Instant started = Instant.now();
    final long startNanos = System.nanoTime();
    final Path destination = Path.of(directory);
    final SyntheticRegistration.Checked checked;
    try {
      if (Files.exists(destination)) {
        System.err.println("Preflight destination already exists: " + destination);
        return REJECTED;
      }
      checked = SyntheticRegistration.check(Path.of(registrationFile), true);
      SyntheticRegistration.verifyDestination(checked, destination, "preflightLocation");
      SyntheticRegistration.claimWorkers(destination, checked);
    } catch (RuntimeException e) {
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final int workers = checked.workers();
    final int subWindow = checked.limit("preflightSubWindowDatasets");
    final int windowDatasets = checked.limit("preflightWindowDatasets");
    final int warmUpLimit = checked.limit("preflightWarmUpLimit");
    final Path record = destination.resolve("preflight.json");
    final Plan plan = new Plan(PREFLIGHT, checked.masterSeed(), windowDatasets, checked.draws());
    final double tolerance = SyntheticRegistration.tolerance(checked, "interval");
    final List<SyntheticBudget.SubWindow> windows = new ArrayList<>();
    final ArrayNode measurements = JSON.createArrayNode();
    final ArrayNode steadyState = JSON.createArrayNode();
    final ArrayNode measuredWindows = JSON.createArrayNode();
    boolean reachedSteadyState = true;
    try (final ExecutorService pool = Executors.newFixedThreadPool(workers)) {
      for (String convention : CONVENTIONS) {
        final Timed warmUp =
            timed(
                pool,
                plan,
                convention,
                destination,
                tolerance,
                workers,
                0,
                warmUpLimit,
                subWindow,
                startNanos,
                measurements,
                "warm_up",
                true);
        if (warmUp.failure() != null)
          return stoppedPreflight(
              checked,
              started,
              startNanos,
              measurements,
              steadyState,
              measuredWindows,
              record,
              destination,
              warmUp.failure());
        steadyState.add(steadyState(convention, warmUp, subWindow));
        reachedSteadyState &= warmUp.steady();
        final Timed window =
            timed(
                pool,
                plan,
                convention,
                destination,
                tolerance,
                workers,
                warmUp.completed(),
                windowDatasets,
                subWindow,
                startNanos,
                measurements,
                "timed",
                false);
        if (window.failure() != null)
          return stoppedPreflight(
              checked,
              started,
              startNanos,
              measurements,
              steadyState,
              measuredWindows,
              record,
              destination,
              window.failure());
        windows.addAll(window.subWindows());
        measuredWindows.add(measuredWindow(convention, window));
      }
    }

    final ObjectNode result =
        preflightRecord(
            checked,
            started,
            startNanos,
            measurements,
            steadyState,
            measuredWindows,
            reachedSteadyState);
    final SyntheticBudget.Budget budget =
        SyntheticBudget.project(
            windows,
            checked.datasets(),
            System.nanoTime() - startNanos,
            usableSpace(Path.of(required(checked.plan(), "outputLocation").asString())));
    result.put("status", "measured");
    result.set("budget", budget(budget));
    result.put("feasibility", budget.feasible() ? "feasible" : "infeasible");
    result.set("reasons", JSON.valueToTree(budget.reasons()));
    result.put(
        "settings",
        "unchanged; an infeasible projection stops the run rather than lowering repetitions,"
            + " shortening windows, omitting grid points or changing precision");
    result.put(
        "budgeting",
        "feasibility is the storage rule and the first stage alone; a later stage the elapsed run"
            + " no longer affords is deferred when its turn comes, not refused here");
    write(record, result);
    return budget.feasible() ? SUCCESS : INFEASIBLE;
  }

  /** A preflight a numerical failure stopped, with the operation that failed preserved. */
  private static int stoppedPreflight(
      SyntheticRegistration.Checked checked,
      Instant started,
      long startNanos,
      ArrayNode measurements,
      ArrayNode steadyState,
      ArrayNode measuredWindows,
      Path record,
      Path destination,
      ObjectNode failure) {
    write(destination.resolve("preflight-failure.json"), failure);
    final ObjectNode stopped =
        preflightRecord(
            checked, started, startNanos, measurements, steadyState, measuredWindows, false);
    stopped.put("status", "stopped");
    stopped.put("feasibility", "not_measured");
    stopped.set("failure", failure);
    write(record, stopped);
    System.err.println(failure.get("message").asString());
    return STOPPED;
  }

  /** One warm-up or measured stretch of preflight: its sub-windows and what stopped it. */
  private record Timed(
      String convention,
      int completed,
      long elapsedNanos,
      List<SyntheticBudget.SubWindow> subWindows,
      boolean steady,
      ObjectNode failure) {}

  /**
   * Runs consecutive sub-windows of {@code subWindow} datasets with every worker busy, timing each
   * sub-window's wall clock. Warm-up stops as soon as two consecutive sub-windows agree to within
   * the steady-state tolerance, or at the registered warm-up limit; the measured window runs its
   * registered length.
   */
  private static Timed timed(
      ExecutorService pool,
      Plan plan,
      String convention,
      Path destination,
      double tolerance,
      int workers,
      int firstIndex,
      int datasets,
      int subWindow,
      long capStartNanos,
      ArrayNode measurements,
      String role,
      boolean warmingUp) {
    final List<SyntheticBudget.SubWindow> windows = new ArrayList<>();
    final long stretchStart = System.nanoTime();
    int completed = 0;
    boolean steady = !warmingUp;
    while (completed < datasets) {
      final List<SyntheticBudget.Measured> measured = new ArrayList<>();
      final long windowStart = System.nanoTime();
      while (measured.size() < subWindow && completed < datasets) {
        final int batch =
            Math.min(workers, Math.min(subWindow - measured.size(), datasets - completed));
        final List<Future<SyntheticBudget.Measured>> scheduled = new ArrayList<>();
        for (int offset = 0; offset < batch; offset++) {
          final int index = firstIndex + completed + offset;
          scheduled.add(
              pool.submit(() -> measure(plan, convention, index, destination, tolerance)));
        }
        for (int offset = 0; offset < batch; offset++) {
          final int index = firstIndex + completed + offset;
          try {
            // The cap starts at preflight launch, so preflight is the first work it bounds.
            require(
                System.nanoTime() - capStartNanos < SyntheticBudget.CAP_NANOS,
                "The 24-hour wall-clock cap was reached during preflight");
            final SyntheticBudget.Measured dataset = scheduled.get(offset).get();
            measured.add(dataset);
            measurements.add(measurement(convention, index, dataset, role));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Timed(
                convention,
                completed,
                0,
                windows,
                steady,
                failure(
                    convention,
                    PREFLIGHT,
                    index,
                    "measure",
                    new IllegalStateException("A worker was interrupted", e)));
          } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            return new Timed(
                convention,
                completed,
                0,
                windows,
                steady,
                failure(
                    convention,
                    PREFLIGHT,
                    index,
                    "measure",
                    cause instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException(cause)));
          } catch (RuntimeException e) {
            return new Timed(
                convention,
                completed,
                0,
                windows,
                steady,
                failure(convention, PREFLIGHT, index, "measure", e));
          }
        }
        completed += batch;
      }
      final SyntheticBudget.SubWindow window =
          new SyntheticBudget.SubWindow(convention, System.nanoTime() - windowStart, measured);
      windows.add(window);
      // Thermal steady state: the host stops getting slower between consecutive sub-windows.
      if (warmingUp && windows.size() >= 2) {
        final double previous = windows.get(windows.size() - 2).perDatasetNanos();
        final double current = window.perDatasetNanos();
        if (previous > 0 && Math.abs(current - previous) / previous <= STEADY_STATE_TOLERANCE) {
          steady = true;
          break;
        }
      }
    }
    return new Timed(
        convention, completed, System.nanoTime() - stretchStart, windows, steady, null);
  }

  /** What established thermal steady state before a convention's window was measured. */
  private static ObjectNode steadyState(String convention, Timed warmUp, int subWindow) {
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("convention", convention);
    retained.put("criterion", STEADY_STATE_CRITERION);
    retained.put("tolerance", STEADY_STATE_TOLERANCE);
    retained.put("subWindowDatasets", subWindow);
    retained.put("warmUpDatasets", warmUp.completed());
    retained.put("reached", warmUp.steady());
    if (!warmUp.steady())
      retained.put(
          "note",
          "the registered warm-up limit was reached before the criterion; the window was measured"
              + " anyway and its slowest sub-window still carries the charge");
    final ArrayNode rates = retained.putArray("subWindows");
    for (SyntheticBudget.SubWindow window : warmUp.subWindows()) {
      final ObjectNode entry = rates.addObject();
      entry.put("datasets", window.datasets());
      entry.put("elapsedNanos", window.elapsedNanos());
      entry.put("perDatasetNanos", window.perDatasetNanos());
    }
    return retained;
  }

  /** The measured window of one convention: its sub-windows and the rates they sustained. */
  private static ObjectNode measuredWindow(String convention, Timed window) {
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("convention", convention);
    retained.put("datasets", window.completed());
    retained.put("elapsedNanos", window.elapsedNanos());
    retained.put(
        "charge",
        "the largest elapsed time per dataset among the consecutive sub-windows; the window mean"
            + " is not the charge");
    final ArrayNode rates = retained.putArray("subWindows");
    for (SyntheticBudget.SubWindow sub : window.subWindows()) {
      final ObjectNode entry = rates.addObject();
      entry.put("datasets", sub.datasets());
      entry.put("elapsedNanos", sub.elapsedNanos());
      entry.put("perDatasetNanos", sub.perDatasetNanos());
      entry.put("knownStageNanos", sub.stageNanos(KNOWN_STAGE));
      entry.put("estimatedStageNanos", sub.stageNanos(ESTIMATED_STAGE));
    }
    return retained;
  }

  /** One preflight dataset, measured through both stages in the intended evidence format. */
  private static SyntheticBudget.Measured measure(
      Plan plan, String convention, int index, Path destination, double tolerance) {
    final long generationStart = System.nanoTime();
    final Synthetic synthetic = generate(plan, convention, index);
    final long generationNanos = System.nanoTime() - generationStart;

    final long knownStart = System.nanoTime();
    final ObjectNode known = score(synthetic.dataset());
    final long knownPredictionNanos = System.nanoTime() - knownStart;
    known.put("stage", KNOWN_STAGE);
    known.set("generation", generation(synthetic));
    final Path knownFile = preflightFile(destination, KNOWN_STAGE, convention, index);
    final long knownWrite = System.nanoTime();
    write(knownFile, known);
    final long knownEvidenceNanos = System.nanoTime() - knownWrite;
    final long knownReproduction = System.nanoTime();
    SyntheticReproduction.dataset(
        knownFile, known, tolerance, coverageCounter(), new ArrayList<>());
    final long knownReproductionNanos = System.nanoTime() - knownReproduction;

    final long searchStart = System.nanoTime();
    final SyntheticSearch.Selection selection = select(synthetic.dataset());
    final long likelihoodNanos = System.nanoTime() - searchStart;
    if (selection.failed()) throw new IllegalArgumentException(stopping(selection));
    final long tunedStart = System.nanoTime();
    final ObjectNode tuned = score(synthetic.dataset().at(selection.parameters()));
    final long tunedPredictionNanos = System.nanoTime() - tunedStart;
    tuned.put("stage", ESTIMATED_STAGE);
    tuned.set("search", search(selection));
    tuned.set("generation", generation(synthetic));
    final Path tunedFile = preflightFile(destination, ESTIMATED_STAGE, convention, index);
    final long tunedWrite = System.nanoTime();
    write(tunedFile, tuned);
    final long estimatedEvidenceNanos = System.nanoTime() - tunedWrite;
    final long tunedReproduction = System.nanoTime();
    SyntheticReproduction.dataset(
        tunedFile, tuned, tolerance, coverageCounter(), new ArrayList<>());
    final long estimatedReproductionNanos = System.nanoTime() - tunedReproduction;

    return new SyntheticBudget.Measured(
        convention,
        generationNanos,
        knownPredictionNanos,
        knownEvidenceNanos,
        knownReproductionNanos,
        likelihoodNanos,
        tunedPredictionNanos,
        estimatedEvidenceNanos,
        estimatedReproductionNanos,
        size(knownFile),
        size(tunedFile));
  }

  /** The counter a preflight reproduction fills; its coverage is measured, never inspected. */
  private static long[][] coverageCounter() {
    return new long[components().size()][SyntheticCoverage.LEVELS.size()];
  }

  private static Path preflightFile(
      Path destination, String stageName, String convention, int index) {
    return destination
        .resolve(PREFLIGHT)
        .resolve(directory(stageName))
        .resolve(convention)
        .resolve(index + ".json");
  }

  private static ObjectNode measurement(
      String convention, int index, SyntheticBudget.Measured measured, String role) {
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("convention", convention);
    retained.put("datasetIndex", index);
    retained.put("role", role);
    retained.put("generationNanos", measured.generationNanos());
    retained.put("knownPredictionNanos", measured.knownPredictionNanos());
    retained.put("knownEvidenceNanos", measured.knownEvidenceNanos());
    retained.put("knownReproductionNanos", measured.knownReproductionNanos());
    retained.put("likelihoodNanos", measured.likelihoodNanos());
    retained.put("gridPoints", SyntheticSearch.POINTS);
    retained.put("tunedPredictionNanos", measured.tunedPredictionNanos());
    retained.put("estimatedEvidenceNanos", measured.estimatedEvidenceNanos());
    retained.put("estimatedReproductionNanos", measured.estimatedReproductionNanos());
    retained.put("knownBytes", measured.knownBytes());
    retained.put("estimatedBytes", measured.estimatedBytes());
    return retained;
  }

  private static ObjectNode preflightRecord(
      SyntheticRegistration.Checked checked,
      Instant started,
      long startNanos,
      ArrayNode measurements,
      ArrayNode steadyState,
      ArrayNode measuredWindows,
      boolean reachedSteadyState) {
    final ObjectNode record = JSON.createObjectNode();
    record.put("version", VERSION);
    record.put("phase", checked.phase());
    record.put("registrationSha256", checked.sha256());
    record.put("streamPhase", PREFLIGHT);
    record.put("deadlineStartedAt", started.toString());
    record.put("capNanos", SyntheticBudget.CAP_NANOS);
    record.put("elapsedNanos", System.nanoTime() - startNanos);
    record.put("hostname", SyntheticRegistration.hostname());
    record.put("workers", checked.workers());
    record.put("subWindowDatasets", checked.limit("preflightSubWindowDatasets"));
    record.put("windowDatasets", checked.limit("preflightWindowDatasets"));
    record.put("warmUpLimit", checked.limit("preflightWarmUpLimit"));
    record.put("steadyStateCriterion", STEADY_STATE_CRITERION);
    record.put("steadyStateReached", reachedSteadyState);
    record.set("steadyState", steadyState);
    record.set("windows", measuredWindows);
    record.put(
        "measurement",
        "sustained throughput at the registered worker count after thermal steady state; not a"
            + " cold single-worker burst");
    record.put(
        "coverage",
        "not inspected; preflight datasets never enter the formal collection or any summary");
    record.set("measurements", measurements);
    return record;
  }

  private static ObjectNode budget(SyntheticBudget.Budget budget) {
    final ObjectNode retained = JSON.createObjectNode();
    final ArrayNode stages = retained.putArray("stages");
    for (SyntheticBudget.Stage stage : budget.stages()) {
      final ObjectNode entry = stages.addObject();
      entry.put("convention", stage.convention());
      entry.put("stage", stage.stage());
      entry.put("sustainedNanos", stage.sustainedNanos());
      entry.put("largestBytes", stage.largestBytes());
    }
    retained.put(
        "charging",
        "generation is charged to the known stage; each stage carries its own evidence work and"
            + " reproduction");
    retained.put("datasets", budget.datasets());
    retained.put("perRepetitionNanos", budget.perRepetitionNanos());
    retained.put("projectionMargin", SyntheticBudget.MARGIN);
    retained.put("projectedNanos", budget.projectedNanos());
    retained.put("preflightNanos", budget.preflightNanos());
    retained.put("totalNanos", budget.totalNanos());
    retained.put("capNanos", budget.capNanos());
    retained.put("withinCap", budget.withinCap());
    final ObjectNode first = retained.putObject("firstStage");
    first.put("convention", budget.firstStage().convention());
    first.put("stage", budget.firstStage().stage());
    first.put("perDatasetNanos", budget.firstStage().perDatasetNanos());
    first.put("projectedNanos", budget.firstStage().projectedNanos());
    first.put("remainingNanos", budget.firstStage().remainingNanos());
    first.put("fits", budget.firstStage().fits());
    first.put("source", budget.firstStage().source());
    retained.put("projectedBytes", budget.projectedBytes());
    retained.put("requiredBytes", budget.requiredBytes());
    retained.put("usableBytes", budget.usableBytes());
    retained.put("sufficientDisk", budget.sufficientDisk());
    return retained;
  }

  /** The final execution identity, which no formal dataset is generated without. */
  private static int commit(String registrationFile, String preflightFile, String executionFile) {
    final Path output = Path.of(executionFile);
    try {
      SyntheticRegistration.commit(Path.of(registrationFile), Path.of(preflightFile), output);
      return SUCCESS;
    } catch (RuntimeException e) {
      System.err.println(e.getMessage());
      return REJECTED;
    }
  }

  /**
   * The registered run: one host, the registered workers and a watchdog whose clock started at
   * preflight launch. It writes a fresh registered destination and never resumes an interrupted run
   * without a separately recorded owner decision.
   */
  private static int experiment(String executionFile, String directory, String resumptionFile) {
    final Path destination = Path.of(directory);
    final Path report = destination.resolve("report.json");
    final SyntheticRegistration.Execution execution;
    final ObjectNode resumed;
    try {
      if (Files.exists(destination)) {
        System.err.println("Evidence destination already exists: " + destination);
        return REJECTED;
      }
      execution = SyntheticRegistration.execution(Path.of(executionFile));
      resumed = resumption(execution, resumptionFile, destination);
      SyntheticRegistration.verifyProtectedEvidence(execution.checked().plan());
      SyntheticRegistration.claimWorkers(destination, execution.checked());
    } catch (RuntimeException e) {
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final SyntheticRegistration.Checked checked = execution.checked();
    final Map<String, String> before = protectedDigests(checked.plan());
    final Plan plan =
        new Plan(checked.phase(), checked.masterSeed(), checked.datasets(), checked.draws());
    final Instant startedAt = Instant.now();
    final long startNanos = System.nanoTime();
    final Run run =
        execute(plan, destination, execution, checked.workers(), budgeting(execution, plan));
    final ObjectNode result = run.report();
    result.put("startedAt", startedAt.toString());
    result.put("completedAt", Instant.now().toString());
    result.put("elapsedNanos", System.nanoTime() - startNanos);
    result.put("exitCode", run.exitCode());
    result.set("identities", identities(execution));
    result.put("evidenceLocation", destination.toString());
    result.set("protectedEvidence", protectedEvidence(checked.plan(), before));
    if (resumed != null) result.set("resumedFrom", resumed);
    write(report, result);
    return run.exitCode();
  }

  /**
   * The stage-budget gate of a registered run. A known stage is charged what the sustained
   * preflight window measured it at; an estimated stage is re-projected from its own control's
   * throughput over the repetitions that control actually completed, scaled by the cost ratio the
   * preflight window measured between the two stages.
   *
   * <p>Ten thousand completed repetitions measure the host far better than a preflight window can,
   * and by the time an estimated stage is due that measurement exists. Only timing reaches this
   * decision; no coverage figure, fraction, verdict or endpoint frequency does.
   */
  private static Budgeting budgeting(SyntheticRegistration.Execution execution, Plan plan) {
    final Map<String, Long> sustained = execution.sustainedStageNanos();
    return (convention, stageName, known) -> {
      final long knownNanos = sustained.getOrDefault(convention + "/" + KNOWN_STAGE, 0L);
      if (stageName.equals(KNOWN_STAGE))
        return SyntheticBudget.stageBudget(
            convention,
            stageName,
            knownNanos,
            plan.datasets(),
            execution.remainingNanos(),
            "the sustained preflight window");
      final Stage control =
          known.stream()
              .filter(stage -> stage.convention().equals(convention))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("No completed control for " + convention));
      return SyntheticBudget.stageBudget(
          convention,
          stageName,
          SyntheticBudget.reprojected(
              knownNanos,
              sustained.getOrDefault(convention + "/" + ESTIMATED_STAGE, 0L),
              control.elapsedNanos(),
              control.completedDatasets()),
          plan.datasets(),
          execution.remainingNanos(),
          "the control's throughput over "
              + control.completedDatasets()
              + " completed repetitions, at the preflight stage-cost ratio");
    };
  }

  /**
   * An interrupted run is never continued on its own. A resumption needs an owner decision recorded
   * outside this workflow, and it writes a destination of its own so the original partial output
   * stays exactly as the watchdog left it.
   */
  private static ObjectNode resumption(
      SyntheticRegistration.Execution execution, String resumptionFile, Path destination) {
    if (resumptionFile == null) {
      SyntheticRegistration.verifyDestination(execution.checked(), destination, "outputLocation");
      return null;
    }
    final Path file = Path.of(resumptionFile);
    final JsonNode decision = SyntheticRegistration.read(file);
    require(
        VERSION.equals(required(decision, "version").asString()),
        "Unregistered protocol version in " + file);
    require(
        required(decision, "executionSha256").asString().equals(execution.sha256()),
        "The owner decision names another execution identity");
    for (String field : List.of("decidedOn", "decidedBy", "reason"))
      require(
          !required(decision, field).asString().isBlank(), "An owner decision records " + field);
    final JsonNode interrupted = required(decision, "interruptedRun");
    final Path original = Path.of(required(interrupted, "path").asString());
    require(
        !original.toAbsolutePath().normalize().equals(destination.toAbsolutePath().normalize()),
        "A resumed run writes a destination of its own");
    require(
        SyntheticRegistration.treeDigest(original)
            .equals(required(interrupted, "sha256").asString()),
        "The interrupted run's partial output is not the one the owner decided on");
    require(
        Files.isRegularFile(original.resolve("interruption.json")),
        "A resumed run continues an interrupted run, not a completed one");
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("path", original.toString());
    retained.put("sha256", required(interrupted, "sha256").asString());
    retained.put("decidedOn", required(decision, "decidedOn").asString());
    retained.put("decidedBy", required(decision, "decidedBy").asString());
    retained.put("reason", required(decision, "reason").asString());
    retained.put("decisionSha256", SyntheticRegistration.digest(file));
    retained.put("preservation", "the original partial output is retained unchanged");
    return retained;
  }

  /** Regenerates every completed predictive array and recomputes what was read from it. */
  private static int reproduce(String executionFile, String directory, String output) {
    final Path destination = Path.of(directory);
    final Path record = Path.of(output);
    final SyntheticRegistration.Execution execution;
    final JsonNode report;
    try {
      require(!Files.exists(record), "Output already exists: " + record);
      execution = SyntheticRegistration.execution(Path.of(executionFile));
      report = SyntheticRegistration.read(destination.resolve("report.json"));
      require(
          required(required(report, "identities"), "executionSha256")
              .asString()
              .equals(execution.sha256()),
          "The run was not generated under this execution identity");
    } catch (RuntimeException e) {
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final SyntheticReproduction.Outcome outcome =
        SyntheticReproduction.reproduce(
            destination,
            report,
            SyntheticRegistration.tolerance(execution.checked(), "interval"),
            SyntheticRegistration.tolerance(execution.checked(), "coverage"),
            execution);
    final ObjectNode retained = SyntheticReproduction.record(outcome);
    retained.set("identities", identities(execution));
    retained.put("evidenceLocation", destination.toString());
    write(record, retained);
    return outcome.interrupted() ? INTERRUPTED : outcome.reproduced() ? SUCCESS : BLOCKED;
  }

  /** The reviewable report of one run: every stage, the reproduction and the protected evidence. */
  private static int finalReport(
      String executionFile, String directory, String reproductionFile, String output) {
    final Path record = Path.of(output);
    final SyntheticRegistration.Execution execution;
    final JsonNode run;
    final JsonNode reproduction;
    try {
      require(!Files.exists(record), "Output already exists: " + record);
      execution = SyntheticRegistration.execution(Path.of(executionFile));
      run = SyntheticRegistration.read(Path.of(directory).resolve("report.json"));
      reproduction = SyntheticRegistration.read(Path.of(reproductionFile));
      require(
          required(required(reproduction, "identities"), "executionSha256")
              .asString()
              .equals(execution.sha256()),
          "The reproduction was not run under this execution identity");
    } catch (RuntimeException e) {
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final ObjectNode report = JSON.createObjectNode();
    final String experiment = required(run, "experiment").asString();
    final String reproduced = required(reproduction, "outcome").asString();
    final boolean unchanged = protectedUnchanged(run);
    final boolean confirmed = reproduced.equals("reproduced") && unchanged;
    final boolean complete =
        required(run, "status").asString().equals("completed")
            && required(run, "recovery").asString().equals(SyntheticCoverage.DEMONSTRATED)
            && confirmed;
    report.put("version", VERSION);
    report.put("phase", execution.checked().phase());
    report.put(
        "outcome",
        complete
            ? "complete_recovery_demonstrated"
            : experiment.equals("stopped_after_known_stage")
                    || experiment.equals("stopped_after_estimated_stage")
                ? "complete_" + required(run, "recovery").asString()
                : experiment);
    report.put("recovery", required(run, "recovery").asString());
    report.set("controlVerdicts", required(run, "controlVerdicts"));
    report.put("knownStageVerdict", required(run, "knownStageVerdict").asString());
    report.put("estimatedStageVerdict", required(run, "estimatedStageVerdict").asString());
    report.put("evidenceLocation", required(run, "evidenceLocation").asString());
    report.set("identities", required(run, "identities"));
    report.set("stages", stageAccounting(run));
    final ObjectNode reproducing = report.putObject("reproduction");
    reproducing.put("outcome", reproduced);
    reproducing.put("datasets", required(reproduction, "reproducedDatasets").intValue());
    reproducing.put("arrays", required(reproduction, "reproducedArrays").intValue());
    reproducing.set("findings", required(reproduction, "findings"));
    reproducing.put("path", reproductionFile);
    report.set("protectedEvidence", required(run, "protectedEvidence"));
    report.put("protectedEvidenceUnchanged", unchanged);
    report.put(
        "completion",
        complete
            ? "every planned stage completed, its predictive output reproduced and the protected"
                + " real-data evidence and shipped freeze are unchanged"
            : "incomplete or stopped; see the outcome and the stage accounting");
    report.put(
        "authority",
        "a recovery verdict here supports this synthetic scenario only; it is neither a model"
            + " change nor permission to publish");
    write(record, report);
    // A completed failed or inconclusive verdict is a result, not an error. Evidence that did not
    // reproduce, or protected evidence that moved, is: the report says so and so does the exit
    // code.
    return confirmed ? SUCCESS : BLOCKED;
  }

  /**
   * The committed record of one run: all 72 cells with their status, coverage, standard error and
   * interval, and one rollup digest per stage and convention.
   *
   * <p>Each rollup digest is taken over a full per-file manifest written beside the evidence on the
   * volume, so the committed digest reaches every retained file without the repository carrying any
   * of them.
   */
  private static int summarize(String executionFile, String directory, String output) {
    final Path destination = Path.of(directory);
    final Path record = Path.of(output);
    final SyntheticRegistration.Execution execution;
    final JsonNode run;
    try {
      require(!Files.exists(record), "Output already exists: " + record);
      execution = SyntheticRegistration.execution(Path.of(executionFile));
      run = SyntheticRegistration.read(destination.resolve("report.json"));
      require(
          required(required(run, "identities"), "executionSha256")
              .asString()
              .equals(execution.sha256()),
          "The run was not generated under this execution identity");
    } catch (RuntimeException e) {
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final ObjectNode results = JSON.createObjectNode();
    results.put("version", VERSION);
    results.put("phase", execution.checked().phase());
    results.put("recovery", required(run, "recovery").asString());
    results.set("controlVerdicts", required(run, "controlVerdicts"));
    results.put("knownStageVerdict", required(run, "knownStageVerdict").asString());
    results.put("estimatedStageVerdict", required(run, "estimatedStageVerdict").asString());
    results.put("experiment", required(run, "experiment").asString());
    results.put("evidenceLocation", required(run, "evidenceLocation").asString());
    results.put("primaryCells", SyntheticCoverage.PRIMARY_CELLS);
    results.put("criticalValue", SyntheticCoverage.CRITICAL_VALUE);
    results.put(
        "cellAccounting",
        "all "
            + SyntheticCoverage.PRIMARY_CELLS
            + " primary cells are listed; a stage that did not"
            + " run reports its cells as not measured and never as coverage over a subset");
    results.set("identities", required(run, "identities"));
    final ArrayNode cells = results.putArray("cells");
    final ArrayNode rollups = results.putArray("rollups");
    for (String field : List.of("methods", "estimatedStages"))
      for (JsonNode method : required(run, field)) {
        final String convention = required(method, "convention").asString();
        final String stageName = required(method, "stage").asString();
        final String status = required(method, "status").asString();
        for (String component : components())
          for (int level : SyntheticCoverage.LEVELS) {
            final JsonNode published = cell(method, component, level);
            final ObjectNode entry = cells.addObject();
            entry.put("convention", convention);
            entry.put("stage", stageName);
            entry.put("component", component);
            entry.put("level", level);
            if (published == null) {
              entry.put("status", status);
              entry.put("verdict", SyntheticCoverage.INCOMPLETE);
              entry.put("measured", false);
              continue;
            }
            entry.put("status", status);
            entry.put("measured", true);
            entry.put("verdict", required(published, "verdict").asString());
            entry.put("coverage", required(published, "coverage").doubleValue());
            entry.put("standardError", required(published, "standardError").doubleValue());
            entry.put("lower", required(published, "lower").doubleValue());
            entry.put("upper", required(published, "upper").doubleValue());
          }
        rollups.add(rollup(destination, convention, stageName, method));
      }
    require(
        cells.size() == SyntheticCoverage.PRIMARY_CELLS,
        "A committed record accounts for every primary cell");
    write(record, results);
    return SUCCESS;
  }

  /** One published cell of one stage, or null when the stage published none. */
  private static JsonNode cell(JsonNode method, String component, int level) {
    final JsonNode published = method.get("cells");
    if (published == null) return null;
    for (JsonNode candidate : published)
      if (candidate.get("component").asString().equals(component)
          && candidate.get("level").intValue() == level) return candidate;
    return null;
  }

  /**
   * One stage's rollup: a full per-file manifest written beside the evidence, and the digest of
   * that manifest, which is what the repository commits.
   */
  private static ObjectNode rollup(
      Path destination, String convention, String stageName, JsonNode method) {
    final ObjectNode retained = JSON.createObjectNode();
    retained.put("convention", convention);
    retained.put("stage", stageName);
    final JsonNode evidence = method.get("evidence");
    if (evidence == null) {
      retained.put("status", NOT_RUN);
      retained.put("files", 0);
      retained.put(
          "manifest", "none; the stage retained no evidence and has nothing to digest over");
      return retained;
    }
    final Path stagePath = destination.resolve(evidence.asString());
    final Path manifest =
        destination.resolve("manifests").resolve(stageName + "-" + convention + ".txt");
    final List<String> lines = new ArrayList<>();
    try (final Stream<Path> files = Files.walk(stagePath)) {
      for (Path file :
          files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList())
        lines.add(
            destination.relativize(file)
                + "|"
                + size(file)
                + "|"
                + SyntheticRegistration.digest(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    final byte[] bytes =
        String.join(System.lineSeparator(), lines)
            .concat(System.lineSeparator())
            .getBytes(StandardCharsets.UTF_8);
    SyntheticRegistration.writeNew(manifest, bytes);
    retained.put("status", required(method, "status").asString());
    retained.put("files", lines.size());
    retained.put("manifest", destination.relativize(manifest).toString());
    retained.put("manifestSha256", SyntheticRegistration.sha256(bytes));
    retained.put(
        "encoding", "one line per retained file: path relative to the evidence root, size, sha256");
    return retained;
  }

  /** The report of a run that never started, because its preflight did not fit the host. */
  private static int infeasibilityReport(String preflightFile, String output) {
    final Path record = Path.of(output);
    final JsonNode preflight;
    try {
      require(!Files.exists(record), "Output already exists: " + record);
      preflight = SyntheticRegistration.read(Path.of(preflightFile));
      require(
          VERSION.equals(required(preflight, "version").asString()),
          "Unregistered protocol version in " + preflightFile);
      require(
          !required(preflight, "feasibility").asString().equals("feasible"),
          "A feasible preflight is reported with its run");
    } catch (RuntimeException e) {
      System.err.println(e.getMessage());
      return REJECTED;
    }

    final ObjectNode report = JSON.createObjectNode();
    report.put("version", VERSION);
    report.put("phase", required(preflight, "phase").asString());
    report.put("outcome", "stopped_at_preflight_" + required(preflight, "feasibility").asString());
    report.put("recovery", NOT_RUN);
    report.set("reasons", required(preflight, "reasons"));
    report.set("budget", preflight.get("budget"));
    report.put("registrationSha256", required(preflight, "registrationSha256").asString());
    report.put("preflightPath", preflightFile);
    final ArrayNode stages = report.putArray("stages");
    for (String stageName : List.of(KNOWN_STAGE, ESTIMATED_STAGE))
      for (String convention : CONVENTIONS) {
        final ObjectNode stage = stages.addObject();
        stage.put("convention", convention);
        stage.put("stage", stageName);
        stage.put("status", NOT_RUN);
        stage.put("verdict", SyntheticCoverage.INCOMPLETE);
        stage.put("reason", "the registered host cannot carry the run within its resource limit");
      }
    report.put(
        "completion",
        "no formal dataset was generated; the infeasibility is retained and the scientific"
            + " settings are unchanged");
    write(record, report);
    return SUCCESS;
  }

  /** Every planned stage of a run, whether it completed, stopped or never started. */
  private static ArrayNode stageAccounting(JsonNode run) {
    final ArrayNode stages = JSON.createArrayNode();
    for (String field : List.of("methods", "estimatedStages"))
      for (JsonNode method : required(run, field)) {
        final ObjectNode stage = stages.addObject();
        for (String name : List.of("convention", "stage", "status", "verdict"))
          stage.put(name, required(method, name).asString());
        if (method.get("reason") != null) stage.put("reason", method.get("reason").asString());
        if (method.get("completedDatasets") != null)
          stage.put("completedDatasets", method.get("completedDatasets").intValue());
        if (method.get("plannedDatasets") != null)
          stage.put("plannedDatasets", method.get("plannedDatasets").intValue());
        if (method.get("failure") != null) stage.set("failure", method.get("failure"));
        if (method.get("interruption") != null)
          stage.set("interruption", method.get("interruption"));
      }
    return stages;
  }

  private static boolean protectedUnchanged(JsonNode run) {
    for (JsonNode location : required(run, "protectedEvidence"))
      if (!required(location, "unchanged").booleanValue()) return false;
    return true;
  }

  /** The protected real-data evidence and shipped freeze, before and after the run. */
  private static Map<String, String> protectedDigests(JsonNode plan) {
    final Map<String, String> digests = new LinkedHashMap<>();
    for (JsonNode location : required(plan, "protectedLocations")) {
      final Path path = Path.of(required(location, "path").asString());
      digests.put(path.toString(), SyntheticRegistration.treeDigest(path));
    }
    return digests;
  }

  private static ArrayNode protectedEvidence(JsonNode plan, Map<String, String> before) {
    final ArrayNode retained = JSON.createArrayNode();
    for (JsonNode location : required(plan, "protectedLocations")) {
      final Path path = Path.of(required(location, "path").asString());
      final String registered = required(location, "sha256").asString();
      final String after = SyntheticRegistration.treeDigest(path);
      final ObjectNode entry = retained.addObject();
      entry.put("path", path.toString());
      entry.put("registeredSha256", registered);
      entry.put("beforeSha256", before.get(path.toString()));
      entry.put("afterSha256", after);
      entry.put("unchanged", after.equals(registered) && after.equals(before.get(path.toString())));
    }
    return retained;
  }

  /** The identities every artifact of one run carries, so nothing can be read out of context. */
  private static ObjectNode identities(SyntheticRegistration.Execution execution) {
    final ObjectNode identities = JSON.createObjectNode();
    identities.put("protocol", VERSION);
    identities.put("executionSha256", execution.sha256());
    for (String field :
        List.of(
            "phase",
            "registrationPath",
            "registrationSha256",
            "preflightPath",
            "preflightSha256",
            "implementationCommit",
            "hostname",
            "architecture",
            "javaRuntime",
            "vm",
            "deadlineStartedAt",
            "deadlineAt")) identities.put(field, required(execution.identity(), field).asString());
    identities.put("workers", required(execution.identity(), "workers").intValue());
    identities.put("remainingMillis", execution.remainingMillis());
    identities.set("stageProjections", required(execution.identity(), "stageProjections"));
    identities.put("stageBudgeting", required(execution.identity(), "stageBudgeting").asString());
    identities.set("commands", required(execution.checked().plan(), "commands"));
    identities.set(
        "numericalTolerances", required(execution.checked().plan(), "numericalTolerances"));
    return identities;
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The free space of the filesystem the formal evidence will land on. The registered destination
   * does not exist yet, so the nearest existing ancestor stands for its filestore; a destination on
   * another volume than preflight is measured where it will be written.
   */
  private static long usableSpace(Path directory) {
    Path ancestor = directory.toAbsolutePath().normalize();
    while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
    if (ancestor == null) throw new IllegalArgumentException("No filesystem holds " + directory);
    final Path existing = ancestor;
    try {
      return Files.getFileStore(existing).getUsableSpace();
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
