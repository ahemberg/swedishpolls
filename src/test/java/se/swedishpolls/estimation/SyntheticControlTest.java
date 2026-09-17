package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The operator boundary of the known-parameter control stage. The schedule, the generating streams,
 * the fixed covariance and the coverage arithmetic are specified here independently of the
 * workflow, on a software-check run small enough to execute in a test.
 */
class SyntheticControlTest {
  private static final long SEED = 4711;
  private static final int DATASETS = 3;
  private static final int DRAWS = 64;
  private static final int DIMENSION = 8;
  private static final int COMPONENTS = 9;
  private static final int DAYS = 215;
  private static final int SCORING_POLLS = 25;
  private static final LocalDate START = LocalDate.of(2016, 1, 1);
  private static final double CRITICAL_VALUE = 3.391763140587952;
  private static final List<String> CONVENTIONS = List.of("midpoint", "ilr_window");
  private static final List<String> NAMES =
      List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER");
  private static final double[] REFERENCE = {30, 25, 15, 8, 6, 4, 4, 6, 2};
  private static final int[] LENGTHS = {1, 10, 21};

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path temp;

  private static Path evidence;

  @BeforeAll
  static void runTheControlStage() {
    evidence = temp.resolve("control-run");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "control", write(temp.resolve("plan.json"), plan()).toString(), evidence.toString()));
  }

  @Test
  void generatesTheRegisteredCalendarScheduleAndPublicationTimeMembership() {
    final JsonNode calendar = report().get("calendar");
    assertEquals("2016-01-01", calendar.get("periodStart").asString());
    assertEquals("2016-06-28", calendar.get("cutoff").asString());
    assertEquals("2016-08-02", calendar.get("scoreThrough").asString());
    assertEquals(115, calendar.get("trainingPolls").intValue());
    assertEquals(25, calendar.get("scoringPolls").intValue());

    final JsonNode rows = dataset("midpoint", 0).get("generation").get("observations");
    assertEquals(140, rows.size());
    final List<Integer> scoringEnds = new ArrayList<>();
    int training = 0;
    int overlapping = 0;
    for (int week = 0; week < 28; week++)
      for (int institute = 0; institute < 5; institute++) {
        final JsonNode row = rows.get(5 * week + institute);
        assertEquals(5 * week + institute + 1, row.get("rowNumber").intValue());
        assertEquals("I" + institute, row.get("institute").asString());
        final int end = 20 + 7 * week;
        final int length = LENGTHS[(institute + week) % 3];
        assertEquals(day(end), row.get("fieldworkTo").asString());
        assertEquals(day(end - length + 1), row.get("fieldworkFrom").asString());
        // The estimator's own floor-of-half-elapsed-days midpoint, over an untruncated window.
        assertEquals(day(end - length + 1 + (length - 1) / 2), row.get("midpoint").asString());
        if (row.get("membership").asString().equals("training")) {
          assertTrue(end <= 179, "A training row is published by the cutoff");
          training++;
        } else {
          scoringEnds.add(end);
          if (end - length + 1 <= 179) overlapping++;
        }
      }
    assertEquals(115, training);
    assertEquals(
        List.of(
            181, 181, 181, 181, 181, 188, 188, 188, 188, 188, 195, 195, 195, 195, 195, 202, 202,
            202, 202, 202, 209, 209, 209, 209, 209),
        scoringEnds);
    // Membership follows publication, so held-out fieldwork may reach back into training dates.
    assertTrue(overlapping > 0, "A held-out window overlaps training dates");
  }

  @Test
  void reproducesBothConventionsFromTheRegisteredGeneratingStreams() {
    for (String convention : CONVENTIONS) {
      final JsonNode generation = dataset(convention, 1).get("generation");
      final String prefix = "synthetic-recovery-v1|software_check|" + convention + "|1";
      assertEquals(
          List.of(prefix + "|initial", prefix + "|walk", prefix + "|houses", prefix + "|noise"),
          generation.get("streams").valueStream().map(JsonNode::asString).toList());

      // The initial state has variance 4, the first day carries no innovation, every later day
      // adds one of variance 0.0001, and the five institute effects have scale 0.05.
      final double[][] latent = new double[DAYS][DIMENSION];
      final RandomGenerator initial = generator(prefix + "|initial");
      for (int i = 0; i < DIMENSION; i++) latent[0][i] = 2 * initial.nextGaussian();
      final RandomGenerator walk = generator(prefix + "|walk");
      for (int day = 1; day < DAYS; day++)
        for (int i = 0; i < DIMENSION; i++)
          latent[day][i] = latent[day - 1][i] + 0.01 * walk.nextGaussian();
      final RandomGenerator houses = generator(prefix + "|houses");
      final double[][] effects = new double[5][DIMENSION];
      for (int institute = 0; institute < 5; institute++)
        for (int i = 0; i < DIMENSION; i++) effects[institute][i] = 0.05 * houses.nextGaussian();

      assertEquals(DAYS, generation.get("latentStates").get("days").intValue());
      for (int day = 0; day < DAYS; day++)
        for (int i = 0; i < DIMENSION; i++)
          assertEquals(
              latent[day][i],
              generation.get("latentStates").get("ilr").get(day).get(i).doubleValue(),
              1e-12);
      for (int institute = 0; institute < 5; institute++)
        for (int i = 0; i < DIMENSION; i++)
          assertEquals(
              effects[institute][i],
              generation.get("instituteEffects").get("I" + institute).get(i).doubleValue(),
              1e-12);

      // Observation noise is the lower Cholesky factor of 1.5 R against a standard normal vector,
      // added in ilr coordinates and inverse-transformed without rounding or zero replacement.
      final double[][] factor = cholesky(scaled(referenceCovariance(), 1.5));
      final RandomGenerator noise = generator(prefix + "|noise");
      final JsonNode rows = generation.get("observations");
      for (int index = 0; index < rows.size(); index++) {
        final JsonNode row = rows.get(index);
        final double[] normal = new double[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) normal[i] = noise.nextGaussian();
        final int end = 20 + 7 * (index / 5);
        final int length = LENGTHS[((index % 5) + index / 5) % 3];
        final double[] mean = new double[DIMENSION];
        if (convention.equals("midpoint")) {
          final int midpoint = end - length + 1 + (length - 1) / 2;
          System.arraycopy(latent[midpoint], 0, mean, 0, DIMENSION);
        } else {
          // The window mean averages the daily ilr states, never the daily shares.
          for (int day = end - length + 1; day <= end; day++)
            for (int i = 0; i < DIMENSION; i++) mean[i] += latent[day][i] / length;
        }
        final double[] expected = new double[DIMENSION];
        for (int r = 0; r < DIMENSION; r++) {
          expected[r] = mean[r] + effects[index % 5][r];
          for (int c = 0; c <= r; c++) expected[r] += factor[r][c] * normal[c];
        }
        for (int i = 0; i < DIMENSION; i++)
          assertEquals(expected[i], row.get("ilr").get(i).doubleValue(), 1e-12);
        final double[] shares = shares(expected);
        for (int c = 0; c < COMPONENTS; c++)
          assertEquals(shares[c], row.get("shares").get(c).doubleValue(), 1e-12);
      }
    }

    // The two conventions are separate collections, drawn from streams of their own.
    assertNotEquals(
        dataset("midpoint", 1).get("generation").get("observations").get(0).get("ilr").toString(),
        dataset("ilr_window", 1)
            .get("generation")
            .get("observations")
            .get(0)
            .get("ilr")
            .toString());
  }

  @Test
  void keepsTheFixedCovarianceAndItsNoiseFactorInsteadOfRebuildingThemFromGeneratedShares() {
    final SimpleMatrix reference = referenceCovariance();
    assertMatrix(reference, report().get("observationCovariance"), 1e-15);
    assertMatrix(reference, dataset("ilr_window", 0).get("observationCovariance"), 1e-15);
    assertEquals(
        "registered fixed matrix; never reconstructed from generated shares",
        report().get("observationCovarianceSource").asString());

    // The retained factor is the lower Cholesky factor of 1.5 R.
    final SimpleMatrix factor = matrix(report().get("observationNoiseFactor"));
    assertMatrix(scaled(reference, 1.5), factor.mult(factor.transpose()), 1e-15);
    for (int r = 0; r < DIMENSION; r++)
      for (int c = r + 1; c < DIMENSION; c++) assertEquals(0, factor.get(r, c));

    // What a round trip through the generated shares would rebuild is a different matrix.
    final JsonNode row = dataset("ilr_window", 0).get("generation").get("observations").get(0);
    final double[] proportions = new double[COMPONENTS];
    for (int c = 0; c < COMPONENTS; c++)
      proportions[c] = row.get("shares").get(c).doubleValue() / 100;
    assertTrue(
        error(covarianceAt(proportions), reference) > 1e-6,
        "The generated shares have to imply a different covariance");
  }

  @Test
  void reducesEachDatasetToOneFractionPerCellAndReportsItsMonteCarloUncertainty() {
    for (String convention : CONVENTIONS) {
      final JsonNode method = method(convention);
      assertEquals("completed", method.get("status").asString());
      assertEquals(DATASETS, method.get("completedDatasets").intValue());
      assertEquals(18, method.get("cells").size());

      int cell = 0;
      for (String component : NAMES)
        for (int level : List.of(95, 50)) {
          final JsonNode reported = method.get("cells").get(cell++);
          assertEquals(component, reported.get("component").asString());
          assertEquals(level, reported.get("level").intValue());

          // One fraction per dataset, read back from the retained evidence of that dataset.
          final double[] fractions = new double[DATASETS];
          long covered = 0;
          for (int index = 0; index < DATASETS; index++) {
            final JsonNode polls = dataset(convention, index).get("polls");
            assertEquals(SCORING_POLLS, polls.size());
            int inside = 0;
            for (JsonNode poll : polls)
              if (poll.get("components")
                  .get(NAMES.indexOf(component))
                  .get("covered" + level)
                  .booleanValue()) inside++;
            fractions[index] = inside / (double) SCORING_POLLS;
            covered += inside;
          }
          double total = 0;
          for (double fraction : fractions) total += fraction;
          final double coverage = total / DATASETS;
          double squares = 0;
          for (double fraction : fractions)
            squares += (fraction - coverage) * (fraction - coverage);
          final double standardError = Math.sqrt(squares / (DATASETS - 1) / DATASETS);
          assertEquals(coverage, reported.get("coverage").doubleValue(), 1e-12);
          assertEquals(standardError, reported.get("standardError").doubleValue(), 1e-12);
          assertEquals(
              CRITICAL_VALUE * standardError, reported.get("halfWidth").doubleValue(), 1e-12);
          assertEquals(
              Math.max(0, coverage - CRITICAL_VALUE * standardError),
              reported.get("lower").doubleValue(),
              1e-12);
          assertEquals(
              Math.min(1, coverage + CRITICAL_VALUE * standardError),
              reported.get("upper").doubleValue(),
              1e-12);
          assertEquals(covered, reported.get("coveredPolls").longValue());
          assertEquals(DATASETS * SCORING_POLLS, reported.get("scoredPolls").longValue());
        }
    }

    final JsonNode confidence = report().get("confidence");
    assertEquals(72, confidence.get("primaryCells").intValue());
    assertEquals(CRITICAL_VALUE, confidence.get("criticalValue").doubleValue());
    assertEquals(0.05, confidence.get("alpha").doubleValue());
    assertEquals(
        List.of(0.93, 0.97),
        report()
            .get("recoveryBands")
            .get("level95")
            .valueStream()
            .map(JsonNode::doubleValue)
            .toList());
    assertEquals(
        List.of(0.47, 0.53),
        report()
            .get("recoveryBands")
            .get("level50")
            .valueStream()
            .map(JsonNode::doubleValue)
            .toList());
  }

  @Test
  void leavesBothEstimatedStagesNotRunUntilTheControlsDemonstrateRecovery() {
    final JsonNode report = report();
    assertEquals("completed", report.get("status").asString());
    assertEquals(
        List.of("known", "estimated"),
        report.get("stages").valueStream().map(JsonNode::asString).toList());
    assertEquals("software_check; not recovery evidence", report.get("evidenceClass").asString());
    assertEquals(0.0001, report.get("parameters").get("walkVariance").doubleValue());
    assertEquals(0.05, report.get("parameters").get("houseScale").doubleValue());
    assertEquals(1.5, report.get("parameters").get("covarianceMultiplier").doubleValue());

    // Three datasets cannot separate any cell from its band, so neither control demonstrates
    // recovery and both estimated stages stay explicitly not run.
    final String known = report.get("knownStageVerdict").asString();
    assertNotEquals("demonstrated", known);
    assertFalse(report.get("estimatedStagesEligible").booleanValue());
    assertEquals(2, report.get("estimatedStages").size());
    for (JsonNode stage : report.get("estimatedStages")) {
      assertEquals("estimated", stage.get("stage").asString());
      assertEquals("not_run", stage.get("status").asString());
      assertNull(stage.get("cells"), "A stage that did not run has no cells");
      assertTrue(
          stage.get("reason").asString().contains(known),
          () -> "Unexpected reason: " + stage.get("reason").asString());
    }
    assertEquals("not_run", report.get("estimatedStageVerdict").asString());
    assertEquals(known, report.get("recovery").asString());

    // A stage the gate blocked runs no search and writes no evidence at all.
    assertFalse(Files.exists(evidence.resolve("estimated")), "No estimated dataset was scored");
    // The divisor stays at 72 whether or not the estimated stages run.
    assertEquals(72, report.get("confidence").get("primaryCells").intValue());
  }

  @Test
  void refusesAnOccupiedDestinationAndAPlanThatWouldShrinkTheFormalRun() {
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "control", write(temp.resolve("again.json"), plan()).toString(), evidence.toString()));
    assertEquals("completed", report().get("status").asString());

    final ObjectNode shrunk = plan();
    shrunk.put("phase", "formal");
    shrunk.put("masterSeed", 20260916);
    shrunk.put("draws", 4000);
    assertRejected("shrunk", shrunk, "10000 datasets per convention");

    final ObjectNode reseeded = plan();
    reseeded.put("phase", "formal");
    reseeded.put("datasets", 10000);
    reseeded.put("draws", 4000);
    assertRejected("reseeded", reseeded, "master seed 20260916");

    final ObjectNode single = plan();
    single.put("datasets", 1);
    assertRejected("single", single, "at least two datasets");

    final ObjectNode other = plan();
    other.put("version", "synthetic-recovery-v2");
    assertRejected("other", other, "Unregistered protocol version");
  }

  private void assertRejected(String name, ObjectNode plan, String reason) {
    final Path destination = temp.resolve(name);
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "control",
            write(temp.resolve(name + "-plan.json"), plan).toString(),
            destination.toString()));
    final JsonNode rejection = read(destination.resolve("control.json"));
    assertEquals("rejected", rejection.get("status").asString());
    assertFalse(Files.exists(destination.resolve("datasets")), "No dataset was generated");
    assertTrue(
        rejection.get("reasons").get(0).asString().contains(reason),
        () -> "Unexpected reason: " + rejection.get("reasons").get(0).asString());
  }

  private static ObjectNode plan() {
    final ObjectNode plan = JSON.createObjectNode();
    plan.put("version", "synthetic-recovery-v1");
    plan.put("phase", "software_check");
    plan.put("masterSeed", SEED);
    plan.put("datasets", DATASETS);
    plan.put("draws", DRAWS);
    return plan;
  }

  private static JsonNode report() {
    return read(evidence.resolve("control.json"));
  }

  private static JsonNode method(String convention) {
    for (JsonNode method : report().get("methods"))
      if (method.get("convention").asString().equals(convention)) return method;
    throw new IllegalStateException("No method " + convention);
  }

  private static JsonNode dataset(String convention, int index) {
    return read(evidence.resolve("datasets").resolve(convention).resolve(index + ".json"));
  }

  private static String day(int offset) {
    return START.plusDays(offset).toString();
  }

  /** The registered generator and seed derivation of one named stream. */
  private static RandomGenerator generator(String stream) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((stream + "|" + SEED).getBytes(StandardCharsets.UTF_8));
      long seed = 0;
      for (int index = 0; index < Long.BYTES; index++) seed = (seed << 8) | (digest[index] & 0xFF);
      return RandomGeneratorFactory.of("Random").create(seed);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** {@code H diag(1/p) H' / 1000} at the registered reference percentages. */
  private static SimpleMatrix referenceCovariance() {
    final double[] proportions = new double[COMPONENTS];
    for (int c = 0; c < COMPONENTS; c++) proportions[c] = REFERENCE[c] / 100;
    return covarianceAt(proportions);
  }

  private static SimpleMatrix covarianceAt(double[] proportions) {
    final SimpleMatrix basis = helmert();
    final SimpleMatrix covariance = new SimpleMatrix(DIMENSION, DIMENSION);
    for (int r = 0; r < DIMENSION; r++)
      for (int c = 0; c < DIMENSION; c++) {
        double value = 0;
        for (int j = 0; j < COMPONENTS; j++)
          value += basis.get(r, j) * basis.get(c, j) / (1000 * proportions[j]);
        covariance.set(r, c, value);
      }
    return covariance;
  }

  private static SimpleMatrix scaled(SimpleMatrix matrix, double factor) {
    return matrix.scale(factor);
  }

  private static double[][] cholesky(SimpleMatrix covariance) {
    final double[][] lower = new double[DIMENSION][DIMENSION];
    for (int r = 0; r < DIMENSION; r++)
      for (int c = 0; c <= r; c++) {
        double value = covariance.get(r, c);
        for (int i = 0; i < c; i++) value -= lower[r][i] * lower[c][i];
        lower[r][c] = r == c ? Math.sqrt(value) : value / lower[c][c];
      }
    return lower;
  }

  /** The closure of {@code exp(H' z)} in percent. */
  private static double[] shares(double[] ilr) {
    final SimpleMatrix basis = helmert();
    final double[] shares = new double[COMPONENTS];
    double max = Double.NEGATIVE_INFINITY;
    for (int c = 0; c < COMPONENTS; c++) {
      double log = 0;
      for (int r = 0; r < DIMENSION; r++) log += basis.get(r, c) * ilr[r];
      shares[c] = log;
      if (log > max) max = log;
    }
    double total = 0;
    for (int c = 0; c < COMPONENTS; c++) {
      shares[c] = Math.exp(shares[c] - max);
      total += shares[c];
    }
    for (int c = 0; c < COMPONENTS; c++) shares[c] = 100 * shares[c] / total;
    return shares;
  }

  private static SimpleMatrix helmert() {
    final SimpleMatrix basis = new SimpleMatrix(DIMENSION, COMPONENTS);
    for (int r = 0; r < DIMENSION; r++) {
      final double scale = Math.sqrt((r + 1.0) * (r + 2.0));
      for (int c = 0; c <= r; c++) basis.set(r, c, 1 / scale);
      basis.set(r, r + 1, -(r + 1) / scale);
    }
    return basis;
  }

  private static SimpleMatrix matrix(JsonNode values) {
    final SimpleMatrix matrix = new SimpleMatrix(values.size(), values.get(0).size());
    for (int r = 0; r < values.size(); r++)
      for (int c = 0; c < values.get(r).size(); c++)
        matrix.set(r, c, values.get(r).get(c).doubleValue());
    return matrix;
  }

  private static void assertMatrix(SimpleMatrix expected, JsonNode actual, double tolerance) {
    assertMatrix(expected, matrix(actual), tolerance);
  }

  private static void assertMatrix(SimpleMatrix expected, SimpleMatrix actual, double tolerance) {
    assertTrue(
        error(expected, actual) <= tolerance, () -> "Maximum error: " + error(expected, actual));
  }

  private static double error(SimpleMatrix expected, SimpleMatrix actual) {
    return expected.minus(actual).elementMaxAbs();
  }

  private static Path write(Path file, JsonNode document) {
    try {
      Files.writeString(file, JSON.writeValueAsString(document), StandardCharsets.UTF_8);
      return file;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode read(Path file) {
    try {
      return JSON.readTree(Files.readAllBytes(file));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
