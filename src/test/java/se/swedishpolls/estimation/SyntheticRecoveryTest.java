package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.ObjectNode;

class SyntheticRecoveryTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final LocalDate START = LocalDate.of(2016, 1, 1);
  private static final LocalDate CUTOFF = START.plusDays(40);
  private static final LocalDate THROUGH = START.plusDays(60);
  private static final int DIMENSION = 8;
  private static final int COMPONENTS = 9;
  private static final double WALK = 0.0001;
  private static final double HOUSE = 0.05;
  private static final double MULTIPLIER = 1.5;
  private static final long SEED = 4711;

  /** A window, an institute and an ilr observation, all fixed by the test rather than generated. */
  private record Row(int rowNumber, String institute, int from, int to, boolean training) {
    private double[] ilr() {
      final double[] coordinates = new double[DIMENSION];
      for (int i = 0; i < DIMENSION; i++) coordinates[i] = 0.3 * Math.sin(rowNumber + 0.5 * i);
      return coordinates;
    }
  }

  // One single-day and two multi-day training polls, then a held-out window that overlaps training
  // dates and a held-out poll from an institute with no training observation of its own.
  private static final List<Row> ROWS =
      List.of(
          new Row(1, "I0", 10, 10, true),
          new Row(2, "I1", 12, 21, true),
          new Row(3, "I0", 30, 39, true),
          new Row(4, "I2", 35, 45, false),
          new Row(5, "I1", 50, 50, false));

  @TempDir Path temp;

  @Test
  void bothConventionsPredictTheDenseGaussianConditionalOfTheSuppliedObservations() {
    for (String convention : List.of("midpoint", "ilr_window")) {
      final Path evidenceFile = temp.resolve(convention + "-dense.json");
      assertEquals(
          SyntheticRecovery.SUCCESS,
          SyntheticRecovery.run(
              "score",
              write(temp.resolve(convention + "-dense-input.json"), dataset(convention)).toString(),
              evidenceFile.toString()));
      final JsonNode evidence = read(evidenceFile);
      final Dense dense = dense(convention, fixedCovariance());
      assertEquals(
          dense.logLikelihood(), evidence.get("trainingLogLikelihood").doubleValue(), 1e-9);
      assertEquals(2, evidence.get("polls").size());
      for (int i = 0; i < 2; i++) {
        final JsonNode poll = evidence.get("polls").get(i);
        assertEquals(scoring().get(i).rowNumber(), poll.get("rowNumber").intValue());
        assertMatrix(dense.means().get(i), poll.get("predictiveMean"), 1e-9);
        assertMatrix(dense.covariances().get(i), poll.get("predictiveCovariance"), 1e-9);
      }
      // The institute with no training observation of its own predicts from the effect prior.
      assertTrue(evidence.get("polls").get(0).get("priorEffect").booleanValue());
      assertFalse(evidence.get("polls").get(1).get("priorEffect").booleanValue());
      assertEquals(
          List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER"),
          evidence.get("components").valueStream().map(JsonNode::asString).toList());
    }
  }

  @Test
  void keepsTheRegisteredCovarianceInsteadOfReconstructingItFromTheObservedShares() {
    final Path evidenceFile = temp.resolve("fixed.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "score",
            write(temp.resolve("fixed-input.json"), dataset("midpoint")).toString(),
            evidenceFile.toString()));
    final JsonNode evidence = read(evidenceFile);
    assertMatrix(fixedCovariance(), evidence.get("observationCovariance"), 0);

    // The same path run on the covariance a share round trip would rebuild predicts something
    // measurably different, so a reconstruction could not pass the dense comparison above.
    final Dense reconstructed = dense("midpoint", reconstructedCovariance());
    final JsonNode poll = evidence.get("polls").get(0);
    assertTrue(
        error(reconstructed.covariances().get(0), poll.get("predictiveCovariance")) > 1e-3,
        "Reconstructing the covariance from the observed shares has to change the prediction");
    assertMatrix(
        dense("midpoint", fixedCovariance()).covariances().get(0),
        poll.get("predictiveCovariance"),
        1e-9);
    assertEquals(
        "registered fixed matrix; never reconstructed from generated shares",
        evidence.get("observationCovarianceSource").asString());
  }

  @Test
  void retainsStreamsSeedsIntervalSummariesAndDrawHashesOfEveryScoredPoll() {
    final Path input = write(temp.resolve("summaries-input.json"), dataset("ilr_window"));
    final Path evidenceFile = temp.resolve("summaries.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run("score", input.toString(), evidenceFile.toString()));
    final JsonNode evidence = read(evidenceFile);
    assertEquals(
        "synthetic-recovery-v1|software_check|ilr_window|7",
        evidence.get("streamPrefix").asString());
    assertEquals(3, evidence.get("trainingPolls").intValue());

    for (JsonNode poll : evidence.get("polls")) {
      final String stream =
          "synthetic-recovery-v1|software_check|ilr_window|7|predictive|"
              + poll.get("rowNumber").intValue();
      assertEquals(stream, poll.get("stream").asString());
      assertEquals(streamSeed(stream, SEED), poll.get("seed").longValue());
      assertEquals(DIMENSION, poll.get("dimension").intValue());
      assertEquals(600, poll.get("draws").intValue());
      assertEquals(COMPONENTS, poll.get("components").size());

      final double[][] drawn = redraw(poll);
      assertEquals(hash(drawn), poll.get("drawsSha256").asString());
      for (int component = 0; component < COMPONENTS; component++) {
        final JsonNode summary = poll.get("components").get(component);
        final double[] column = drawn[component].clone();
        final double mean = Arrays.stream(column).average().orElseThrow();
        double variance = 0;
        for (double value : column)
          variance += (value - mean) * (value - mean) / (column.length - 1);
        Arrays.sort(column);
        final double observed = summary.get("observed").doubleValue();
        assertEquals(mean, summary.get("mean").doubleValue(), 1e-12);
        assertEquals(variance, summary.get("variance").doubleValue(), 1e-12);
        assertEquals(quantile(column, 0.025), summary.get("lower95").doubleValue(), 1e-12);
        assertEquals(quantile(column, 0.975), summary.get("upper95").doubleValue(), 1e-12);
        assertEquals(quantile(column, 0.25), summary.get("lower50").doubleValue(), 1e-12);
        assertEquals(quantile(column, 0.75), summary.get("upper50").doubleValue(), 1e-12);
        // An endpoint counts as covered, so coverage is an inclusive comparison.
        assertEquals(
            observed >= quantile(column, 0.025) && observed <= quantile(column, 0.975),
            summary.get("covered95").booleanValue());
        assertEquals(
            observed >= quantile(column, 0.25) && observed <= quantile(column, 0.75),
            summary.get("covered50").booleanValue());
      }
    }

    final Path repeated = temp.resolve("summaries-again.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run("score", input.toString(), repeated.toString()));
    assertEquals(text(evidenceFile), text(repeated));
  }

  @Test
  void scoresTheFormalConfigurationAndRefusesAnythingElse() {
    final ObjectNode formal = dataset("midpoint");
    formal.put("phase", "formal");
    formal.put("masterSeed", SyntheticRecovery.FORMAL_SEED);
    formal.put("draws", SyntheticRecovery.FORMAL_DRAWS);
    final Path evidenceFile = temp.resolve("formal.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "score",
            write(temp.resolve("formal-input.json"), formal).toString(),
            evidenceFile.toString()));
    final JsonNode evidence = read(evidenceFile);
    assertEquals(
        "synthetic-recovery-v1|formal|midpoint|7", evidence.get("streamPrefix").asString());
    assertEquals(
        SyntheticRecovery.FORMAL_DRAWS, evidence.get("polls").get(0).get("draws").intValue());

    final ObjectNode fewDraws = dataset("midpoint");
    fewDraws.put("phase", "formal");
    fewDraws.put("masterSeed", SyntheticRecovery.FORMAL_SEED);
    assertRejected("few-draws", fewDraws, "4000 predictive draws");

    final ObjectNode otherSeed = dataset("midpoint");
    otherSeed.put("phase", "formal");
    otherSeed.put("draws", SyntheticRecovery.FORMAL_DRAWS);
    assertRejected("other-seed", otherSeed, "master seed 20260916");
  }

  @Test
  void predictiveStreamsAndSeedsDoNotDependOnTheParametersTheDatasetIsScoredAt() {
    final ObjectNode tuned = dataset("midpoint");
    ((ObjectNode) tuned.get("parameters")).put("walkVariance", 0.001);
    ((ObjectNode) tuned.get("parameters")).put("covarianceMultiplier", 3);
    final Path known = temp.resolve("known.json");
    final Path estimated = temp.resolve("estimated.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "score",
            write(temp.resolve("known-input.json"), dataset("midpoint")).toString(),
            known.toString()));
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "score",
            write(temp.resolve("estimated-input.json"), tuned).toString(),
            estimated.toString()));
    // The two stages read the same underlying normal draws, so the predictive distributions may
    // differ while the streams and seeds do not.
    for (int i = 0; i < 2; i++) {
      final JsonNode first = read(known).get("polls").get(i);
      final JsonNode second = read(estimated).get("polls").get(i);
      assertEquals(first.get("stream").asString(), second.get("stream").asString());
      assertEquals(first.get("seed").longValue(), second.get("seed").longValue());
      assertTrue(
          error(matrix(first.get("predictiveCovariance")), second.get("predictiveCovariance"))
              > 1e-6,
          "Another parameter point has to change the predictive covariance");
    }
  }

  @Test
  void refusesLeakedTrainingRowsInadmissibleCovarianceAndAnExistingDestination() {
    final ObjectNode leaked = dataset("midpoint");
    ((ObjectNode) leaked.get("observations").get(3)).put("membership", "training");
    assertRejected("leaked", leaked, "ends after the cutoff");

    final ObjectNode duplicated = dataset("midpoint");
    ((ObjectNode) duplicated.get("observations").get(2))
        .put("rowNumber", duplicated.get("observations").get(1).get("rowNumber").intValue());
    assertRejected("duplicated", duplicated, "unique and ascending");

    final ObjectNode asymmetric = dataset("midpoint");
    ((ArrayNode) asymmetric.get("observationCovariance").get(0)).set(1, DoubleNode.valueOf(1));
    assertRejected("asymmetric", asymmetric, "symmetric");

    // Off-diagonal correlation beyond the variances leaves a covariance no fit could factor.
    final ObjectNode indefinite = dataset("midpoint");
    for (int r = 0; r < DIMENSION; r++)
      for (int c = 0; c < DIMENSION; c++)
        if (r != c)
          ((ArrayNode) indefinite.get("observationCovariance").get(r))
              .set(c, DoubleNode.valueOf(0.05));
    assertRejected("indefinite", indefinite, "positive definite");

    final Path taken = temp.resolve("taken.json");
    write(taken, JSON.createObjectNode().put("status", "earlier"));
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "score",
            write(temp.resolve("taken-input.json"), dataset("midpoint")).toString(),
            taken.toString()));
    assertEquals("earlier", read(taken).get("status").asString());
  }

  private void assertRejected(String name, ObjectNode dataset, String reason) {
    final Path evidenceFile = temp.resolve(name + ".json");
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "score",
            write(temp.resolve(name + "-input.json"), dataset).toString(),
            evidenceFile.toString()));
    final JsonNode rejection = read(evidenceFile);
    assertEquals("rejected", rejection.get("status").asString());
    assertEquals("not_run", rejection.get("scoringEvidence").asString());
    assertTrue(
        rejection.get("reasons").get(0).asString().contains(reason),
        () -> "Unexpected reason: " + rejection.get("reasons").get(0).asString());
  }

  /** The dataset document an operator supplies: explicit observations and one fixed covariance. */
  private static ObjectNode dataset(String convention) {
    final ObjectNode document = JSON.createObjectNode();
    document.put("version", "synthetic-recovery-v1");
    document.put("phase", "software_check");
    document.put("convention", convention);
    document.put("masterSeed", SEED);
    document.put("datasetIndex", 7);
    document.put("draws", 600);
    document.put("periodStart", START.toString());
    document.put("cutoff", CUTOFF.toString());
    document.put("scoreThrough", THROUGH.toString());
    final ObjectNode parameters = document.putObject("parameters");
    parameters.put("walkVariance", WALK);
    parameters.put("houseScale", HOUSE);
    parameters.put("covarianceMultiplier", MULTIPLIER);
    final ArrayNode covariance = document.putArray("observationCovariance");
    final SimpleMatrix fixed = fixedCovariance();
    for (int r = 0; r < DIMENSION; r++) {
      final ArrayNode row = covariance.addArray();
      for (int c = 0; c < DIMENSION; c++) row.add(fixed.get(r, c));
    }
    final ArrayNode observations = document.putArray("observations");
    for (Row row : ROWS) {
      final ObjectNode observation = observations.addObject();
      observation.put("rowNumber", row.rowNumber());
      observation.put("institute", row.institute());
      observation.put("membership", row.training() ? "training" : "scoring");
      observation.put("fieldworkFrom", START.plusDays(row.from()).toString());
      observation.put("fieldworkTo", START.plusDays(row.to()).toString());
      observation.put("sampleSize", 1000);
      final ArrayNode ilr = observation.putArray("ilr");
      for (double coordinate : row.ilr()) ilr.add(coordinate);
    }
    return document;
  }

  /** A fixed observation covariance that no composition of these observations would produce. */
  private static SimpleMatrix fixedCovariance() {
    final SimpleMatrix covariance = new SimpleMatrix(DIMENSION, DIMENSION);
    for (int r = 0; r < DIMENSION; r++)
      for (int c = 0; c < DIMENSION; c++) covariance.set(r, c, r == c ? 0.03 : 0.01);
    return covariance;
  }

  /** What a round trip through source preparation would rebuild from the observed shares. */
  private static SimpleMatrix reconstructedCovariance() {
    final SimpleMatrix basis = helmert();
    final double[] proportions = new double[COMPONENTS];
    final double[] shares = shares(scoring().getFirst().ilr());
    for (int c = 0; c < COMPONENTS; c++) proportions[c] = shares[c] / 100;
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

  private record Dense(
      double logLikelihood, List<SimpleMatrix> means, List<SimpleMatrix> covariances) {}

  /**
   * Independent batch Gaussian conditioning over the supplied observations: the random walk's own
   * {@code min(s,t)} structure, one shared effect per institute and the given observation
   * covariance, inverted in one system with no sequential filter.
   */
  private static Dense dense(String convention, SimpleMatrix observationCovariance) {
    final List<Row> training = ROWS.stream().filter(Row::training).toList();
    final int size = training.size() * DIMENSION;
    final SimpleMatrix joint = new SimpleMatrix(size, size);
    final SimpleMatrix values = new SimpleMatrix(size, 1);
    for (int i = 0; i < training.size(); i++) {
      final SimpleMatrix ilr = new SimpleMatrix(DIMENSION, 1);
      for (int k = 0; k < DIMENSION; k++) ilr.set(k, 0, training.get(i).ilr()[k]);
      values.insertIntoThis(i * DIMENSION, 0, ilr);
      for (int j = 0; j < training.size(); j++)
        joint.insertIntoThis(
            i * DIMENSION,
            j * DIMENSION,
            block(training.get(i), training.get(j), i == j, convention, observationCovariance));
    }
    final SimpleMatrix inverse = joint.invert();
    final List<SimpleMatrix> means = new ArrayList<>();
    final List<SimpleMatrix> covariances = new ArrayList<>();
    for (Row row : scoring()) {
      final SimpleMatrix cross = new SimpleMatrix(DIMENSION, size);
      for (int j = 0; j < training.size(); j++)
        cross.insertIntoThis(
            0,
            j * DIMENSION,
            block(row, training.get(j), false, convention, observationCovariance));
      final SimpleMatrix own = block(row, row, true, convention, observationCovariance);
      means.add(cross.mult(inverse).mult(values));
      covariances.add(own.minus(cross.mult(inverse).mult(cross.transpose())));
    }
    return new Dense(
        -0.5
            * (size * Math.log(2 * Math.PI)
                + Math.log(joint.determinant())
                + values.dot(inverse.mult(values))),
        means,
        covariances);
  }

  /**
   * The prior covariance of two window averages, a shared institute effect and one poll's noise.
   */
  private static SimpleMatrix block(
      Row first, Row second, boolean same, String convention, SimpleMatrix observationCovariance) {
    final int[] left = window(first, convention);
    final int[] right = window(second, convention);
    double covariance = 0;
    for (int t = left[0]; t <= left[1]; t++)
      for (int s = right[0]; s <= right[1]; s++) covariance += 4 + WALK * Math.min(t, s);
    final int leftDays = left[1] - left[0] + 1;
    final int rightDays = right[1] - right[0] + 1;
    SimpleMatrix block =
        SimpleMatrix.identity(DIMENSION).scale(covariance / (leftDays * (double) rightDays));
    if (first.institute().equals(second.institute()))
      block = block.plus(SimpleMatrix.identity(DIMENSION).scale(HOUSE * HOUSE));
    return same ? block.plus(observationCovariance.scale(MULTIPLIER)) : block;
  }

  /** Day offsets of one observation's window: its own days, or the midpoint day alone. */
  private static int[] window(Row row, String convention) {
    if (convention.equals("ilr_window")) return new int[] {row.from(), row.to()};
    final int midpoint = row.from() + (row.to() - row.from()) / 2;
    return new int[] {midpoint, midpoint};
  }

  private static List<Row> scoring() {
    return ROWS.stream().filter(row -> !row.training()).toList();
  }

  /** Redraws one poll's predictive array from the retained prediction, seed and encoding. */
  private static double[][] redraw(JsonNode poll) {
    final int draws = poll.get("draws").intValue();
    final double[] mean = new double[DIMENSION];
    for (int i = 0; i < DIMENSION; i++) mean[i] = poll.get("predictiveMean").get(i).doubleValue();
    final double[][] covariance = new double[DIMENSION][DIMENSION];
    for (int r = 0; r < DIMENSION; r++)
      for (int c = 0; c < DIMENSION; c++)
        covariance[r][c] = poll.get("predictiveCovariance").get(r).get(c).doubleValue();
    final double[][] factor = cholesky(covariance);
    final RandomGenerator random =
        RandomGeneratorFactory.of("Random").create(poll.get("seed").longValue());
    final double[][] drawn = new double[COMPONENTS][draws];
    for (int draw = 0; draw < draws; draw++) {
      final double[] normal = new double[DIMENSION];
      for (int i = 0; i < DIMENSION; i++) normal[i] = random.nextGaussian();
      final double[] state = new double[DIMENSION];
      for (int i = 0; i < DIMENSION; i++) {
        state[i] = mean[i];
        for (int j = 0; j <= i; j++) state[i] += factor[i][j] * normal[j];
      }
      final double[] shares = shares(state);
      for (int component = 0; component < COMPONENTS; component++)
        drawn[component][draw] = shares[component];
    }
    return drawn;
  }

  /** The registered draw encoding: big-endian binary64, draw-major and then component order. */
  private static String hash(double[][] drawn) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      final byte[] encoded = new byte[COMPONENTS * Double.BYTES];
      final ByteBuffer buffer = ByteBuffer.wrap(encoded);
      for (int draw = 0; draw < drawn[0].length; draw++) {
        buffer.clear();
        for (double[] component : drawn) buffer.putDouble(component[draw]);
        digest.update(encoded);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The documented seed derivation: SHA-256 of the stream and master seed, first eight bytes. */
  private static long streamSeed(String stream, long seed) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((stream + "|" + seed).getBytes(StandardCharsets.UTF_8));
      long value = 0;
      for (int index = 0; index < Long.BYTES; index++)
        value = (value << 8) | (digest[index] & 0xFF);
      return value;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** The closure of {@code exp(H' z)} in percent, shifted by its maximum before exponentiating. */
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

  private static double[][] cholesky(double[][] covariance) {
    final double[][] lower = new double[DIMENSION][DIMENSION];
    for (int r = 0; r < DIMENSION; r++)
      for (int c = 0; c <= r; c++) {
        double value = covariance[r][c];
        for (int i = 0; i < c; i++) value -= lower[r][i] * lower[c][i];
        lower[r][c] = r == c ? Math.sqrt(value) : value / lower[c][c];
      }
    return lower;
  }

  private static double quantile(double[] sorted, double probability) {
    final double position = (sorted.length - 1) * probability;
    final int low = (int) Math.floor(position);
    final int high = Math.min(low + 1, sorted.length - 1);
    return sorted[low] + (position - low) * (sorted[high] - sorted[low]);
  }

  private static void assertMatrix(SimpleMatrix expected, JsonNode actual, double tolerance) {
    assertTrue(
        error(expected, actual) <= tolerance, () -> "Maximum error: " + error(expected, actual));
  }

  private static SimpleMatrix matrix(JsonNode values) {
    final SimpleMatrix matrix = new SimpleMatrix(values.size(), values.get(0).size());
    for (int r = 0; r < values.size(); r++)
      for (int c = 0; c < values.get(r).size(); c++)
        matrix.set(r, c, values.get(r).get(c).doubleValue());
    return matrix;
  }

  private static double error(SimpleMatrix expected, JsonNode actual) {
    double error = 0;
    for (int r = 0; r < expected.getNumRows(); r++)
      for (int c = 0; c < expected.getNumCols(); c++) {
        final JsonNode row = actual.get(r);
        final double value =
            expected.getNumCols() == 1 ? row.doubleValue() : row.get(c).doubleValue();
        error = Math.max(error, Math.abs(expected.get(r, c) - value));
      }
    return error;
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

  private static String text(Path file) {
    try {
      return Files.readString(file);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
