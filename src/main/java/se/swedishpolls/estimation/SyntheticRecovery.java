package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>This slice scores one supplied synthetic dataset: explicit ilr observations and the registered
 * fixed observation covariance reach {@link WindowFilter} and {@link PredictiveScoring} directly,
 * without source preparation, so nothing reconstructs the covariance from the generated shares. It
 * retains the predictive distribution, stream identity, interval summaries and draw hash of every
 * scored poll. Generation, parameter search, coverage aggregation and reporting are later slices of
 * the same workflow; running this command is a software check, not recovery evidence.
 */
public final class SyntheticRecovery {
  public static final int SUCCESS = 0;
  public static final int REJECTED = 2;

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

  private static final String PREFLIGHT = "preflight";

  /** The software-check phase keeps test fixtures out of the registered scientific streams. */
  private static final List<String> PHASES = List.of(FORMAL, PREFLIGHT, "software_check");

  private static final Map<String, WindowFilter.Convention> CONVENTIONS =
      Map.of(
          "midpoint",
          WindowFilter.Convention.MIDPOINT,
          "ilr_window",
          WindowFilter.Convention.FIELDWORK);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SyntheticRecovery() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /** The operator entry point. It refuses an output that exists rather than replacing it. */
  public static int run(String... args) {
    if (args.length != 3 || !args[0].equals("score")) {
      System.err.println("Usage: score <dataset.json> <evidence.json>");
      return REJECTED;
    }
    final Path output = Path.of(args[2]);
    try {
      score(Path.of(args[1]), output);
      return SUCCESS;
    } catch (RuntimeException e) {
      rejected(output, e.getMessage());
      System.err.println(e.getMessage());
      return REJECTED;
    }
  }

  /** One dataset of one convention: filter the training rows, then score every held-out row. */
  private static void score(Path datasetFile, Path output) {
    require(!Files.exists(output), "Output already exists: " + output);
    final JsonNode document = read(datasetFile);
    require(
        VERSION.equals(required(document, "version").asString()),
        "Unregistered protocol version in " + datasetFile);
    final String phase = required(document, "phase").asString();
    require(PHASES.contains(phase), "Unregistered phase " + phase);
    final String convention = required(document, "convention").asString();
    require(CONVENTIONS.containsKey(convention), "Unregistered convention " + convention);
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
    final JsonNode point = required(document, "parameters");
    final DailyStateSpace.Parameters parameters =
        new DailyStateSpace.Parameters(
            required(point, "walkVariance").doubleValue(),
            required(point, "houseScale").doubleValue(),
            required(point, "covarianceMultiplier").doubleValue());

    final String prefix = VERSION + "|" + phase + "|" + convention + "|" + datasetIndex;
    final Roster.CoveragePeriod period =
        new Roster.CoveragePeriod(prefix, periodStart, scoreThrough, ROSTER, false, false, null);
    final List<String> components = PollObservations.components(period);
    final int dimension = components.size() - 1;
    final ModelValues covariance = covariance(document, dimension);

    final List<PollObservations.Observation> training = new ArrayList<>();
    final List<PollObservations.Observation> scoring = new ArrayList<>();
    int previousRow = 0;
    for (JsonNode row : required(document, "observations")) {
      final PollObservations.Observation observation =
          observation(row, period, dimension, covariance);
      final int rowNumber = observation.poll().rowNumber();
      require(rowNumber > previousRow, "Row identities are unique and ascending: " + rowNumber);
      previousRow = rowNumber;
      final LocalDate from = observation.poll().collectionFrom();
      final LocalDate to = observation.poll().collectionTo();
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

    final PollObservations.Batch trainingBatch = PollObservations.explicit(period, training);
    final PollObservations.Batch scoringBatch = PollObservations.explicit(period, scoring);
    // The synthetic interval has no cycle reset, so every institute keeps one effect throughout.
    final WindowFilter.Scored filtered =
        WindowFilter.score(
            trainingBatch, scoring, List.of(), parameters, CONVENTIONS.get(convention));
    final Map<Integer, WindowFilter.Prediction> byRow = new LinkedHashMap<>();
    for (WindowFilter.Prediction prediction : filtered.predictions())
      byRow.put(prediction.poll().rowNumber(), prediction);

    final ObjectNode evidence = JSON.createObjectNode();
    evidence.put("status", "scored");
    evidence.put("version", VERSION);
    evidence.put("phase", phase);
    evidence.put("convention", convention);
    evidence.put("datasetIndex", datasetIndex);
    evidence.put("masterSeed", masterSeed);
    evidence.put("streamPrefix", prefix);
    evidence.put("periodStart", periodStart.toString());
    evidence.put("cutoff", cutoff.toString());
    evidence.put("scoreThrough", scoreThrough.toString());
    evidence.set("parameters", JSON.valueToTree(parameters));
    evidence.set("components", JSON.valueToTree(components));
    evidence.put("dimension", dimension);
    evidence.put("draws", draws);
    evidence.set("observationCovariance", matrix(covariance));
    evidence.put(
        "observationCovarianceSource",
        "registered fixed matrix; never reconstructed from generated shares");
    evidence.put("drawEncoding", PredictiveScoring.DRAW_ENCODING);
    evidence.put("trainingPolls", training.size());
    evidence.put("scoringPolls", scoring.size());
    evidence.put("trainingLogLikelihood", filtered.logLikelihood());
    final ArrayNode polls = evidence.putArray("polls");
    for (PollObservations.Observation observation : scoring) {
      final WindowFilter.Prediction prediction = byRow.get(observation.poll().rowNumber());
      require(
          prediction != null, "No prediction for scoring row " + observation.poll().rowNumber());
      polls.add(
          scored(scoringBatch, observation, prediction, prefix, masterSeed, draws, components));
    }
    write(output, evidence);
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

  /**
   * One synthetic observation. Its ilr coordinates and its covariance are supplied, so neither the
   * shares nor the nominal sample size takes part in the numerical path.
   */
  private static PollObservations.Observation observation(
      JsonNode row, Roster.CoveragePeriod period, int dimension, ModelValues covariance) {
    final int rowNumber = required(row, "rowNumber").intValue();
    final String institute = required(row, "institute").asString();
    final LocalDate from = date(row, "fieldworkFrom");
    final LocalDate to = date(row, "fieldworkTo");
    require(!to.isBefore(from), "Row " + rowNumber + " ends before it starts");
    final BigDecimal sampleSize = required(row, "sampleSize").decimalValue();
    require(sampleSize.signum() > 0, "Row " + rowNumber + " has a positive nominal sample size");
    final JsonNode coordinates = required(row, "ilr");
    require(
        coordinates.size() == dimension,
        "Row " + rowNumber + " has " + dimension + " ilr coordinates");
    final SimpleMatrix ilr = new SimpleMatrix(dimension, 1);
    for (int i = 0; i < dimension; i++) {
      final double value = coordinates.get(i).doubleValue();
      require(Double.isFinite(value), "Row " + rowNumber + " has finite ilr coordinates");
      ilr.set(i, 0, value);
    }
    // Publication happens on the fieldwork end, and the midpoint follows the estimator's own
    // floor-of-half-elapsed-days convention.
    final PollCsv.Poll poll =
        new PollCsv.Poll(
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
    require(period.covers(poll), "Row " + rowNumber + " lies outside the synthetic period");
    return new PollObservations.Observation(
        poll,
        from.plusDays(ChronoUnit.DAYS.between(from, to) / 2),
        ModelValues.copyOf(ilr),
        covariance,
        0);
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
    result.put("scoringEvidence", "not_run");
    try {
      write(output, result);
    } catch (RuntimeException ignored) {
      // A rejection must never overwrite or obscure the original failure.
    }
  }
}
