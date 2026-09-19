package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The operator boundary of the estimated-parameter stage: the training-only search that selects a
 * point before a dataset is scored. The registered grid, its order, its tie break and its failure
 * behaviour are specified here independently of the workflow.
 */
class SyntheticSearchTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final LocalDate START = LocalDate.of(2016, 1, 1);
  private static final LocalDate CUTOFF = START.plusDays(40);
  private static final LocalDate THROUGH = START.plusDays(60);
  private static final int DIMENSION = 8;
  private static final long SEED = 4711;

  /** The registered axes, written out here rather than read from the code under test. */
  private static final List<Double> WALKS =
      List.of(0.000003, 0.00001, 0.00003, 0.0001, 0.0003, 0.001);

  private static final List<Double> HOUSES = List.of(0.01, 0.02, 0.05, 0.1, 0.2);
  private static final List<Double> MULTIPLIERS = List.of(0.5, 0.75, 1.0, 1.5, 2.0, 3.0);

  private record Row(int rowNumber, String institute, int from, int to, boolean training) {
    private double[] ilr() {
      final double[] coordinates = new double[DIMENSION];
      for (int i = 0; i < DIMENSION; i++) coordinates[i] = 0.3 * Math.sin(rowNumber + 0.5 * i);
      return coordinates;
    }
  }

  private static final List<Row> ROWS =
      List.of(
          new Row(1, "I0", 10, 10, true),
          new Row(2, "I1", 12, 21, true),
          new Row(3, "I0", 30, 39, true),
          new Row(4, "I2", 35, 45, false),
          new Row(5, "I1", 50, 50, false));

  @TempDir Path temp;

  @Test
  void evaluatesEveryRegisteredGridPointInAscendingOrderAndKeepsTheFirstMaximum() {
    final JsonNode search = tune("order", dataset("midpoint")).get("search");
    assertEquals(180, search.get("points").intValue());
    assertEquals(WALKS, doubles(search.get("grid").get("walkVariances")));
    assertEquals(HOUSES, doubles(search.get("grid").get("houseScales")));
    assertEquals(MULTIPLIERS, doubles(search.get("grid").get("covarianceMultipliers")));

    final JsonNode attempts = search.get("attempts");
    assertEquals(180, attempts.size());
    int index = 0;
    for (double walk : WALKS)
      for (double house : HOUSES)
        for (double multiplier : MULTIPLIERS) {
          final JsonNode attempt = attempts.get(index);
          assertEquals(index, attempt.get("index").intValue());
          assertEquals(walk, attempt.get("walkVariance").doubleValue());
          assertEquals(house, attempt.get("houseScale").doubleValue());
          assertEquals(multiplier, attempt.get("covarianceMultiplier").doubleValue());
          assertEquals("evaluated", attempt.get("status").asString());
          assertTrue(
              Double.isFinite(attempt.get("logLikelihood").doubleValue()),
              "Every retained likelihood is finite when the search completes");
          index++;
        }

    // The first index attaining the maximum, which is what an exact tie has to resolve to.
    int expected = 0;
    for (int i = 1; i < attempts.size(); i++)
      if (attempts.get(i).get("logLikelihood").doubleValue()
          > attempts.get(expected).get("logLikelihood").doubleValue()) expected = i;
    assertEquals(expected, search.get("selectedIndex").intValue());
    final JsonNode best = attempts.get(expected);
    assertEquals(
        best.get("logLikelihood").doubleValue(), search.get("logLikelihood").doubleValue());
    for (String axis : List.of("walkVariance", "houseScale", "covarianceMultiplier"))
      assertEquals(best.get(axis).doubleValue(), search.get("selected").get(axis).doubleValue());
  }

  @Test
  void keepsTheFirstPointOfTheRegisteredOrderWhenPointsTieExactly() {
    // The only training observation sits on day zero, which carries no innovation, so the walk
    // variance cannot reach the training likelihood and all six of its values tie exactly.
    final JsonNode search =
        tune(
                "tie",
                dataset(
                    "midpoint",
                    List.of(new Row(1, "I0", 0, 0, true), new Row(2, "I1", 45, 45, false))))
            .get("search");

    final JsonNode attempts = search.get("attempts");
    double best = Double.NEGATIVE_INFINITY;
    for (JsonNode attempt : attempts)
      best = Math.max(best, attempt.get("logLikelihood").doubleValue());
    final List<Integer> tied = new ArrayList<>();
    for (int i = 0; i < attempts.size(); i++)
      if (attempts.get(i).get("logLikelihood").doubleValue() == best) tied.add(i);

    assertEquals(
        WALKS.size(),
        tied.size(),
        () -> "One point per walk variance has to tie exactly, not " + tied.size());
    assertEquals(tied.get(0), search.get("selectedIndex").intValue());
    assertNotEquals(
        tied.get(tied.size() - 1),
        search.get("selectedIndex").intValue(),
        "Selecting the last tied point would be the wrong tie break");
    assertEquals(WALKS.get(0), search.get("selected").get("walkVariance").doubleValue());
  }

  @Test
  void selectsFromTheTrainingObservationsAloneSoAHeldOutRowCannotMoveThePoint() {
    final ObjectNode moved = dataset("midpoint");
    for (JsonNode row : moved.get("observations"))
      if (row.get("membership").asString().equals("scoring"))
        for (int i = 0; i < DIMENSION; i++)
          ((ArrayNode) row.get("ilr")).set(i, DoubleNode.valueOf(4.0 + i));

    final JsonNode original = tune("training-only", dataset("midpoint"));
    final JsonNode shifted = tune("training-only-moved", moved);
    assertEquals(
        original.get("search").get("attempts").toString(),
        shifted.get("search").get("attempts").toString());
    assertEquals(
        original.get("search").get("selected").toString(),
        shifted.get("search").get("selected").toString());

    // The two runs really do score different held-out observations, so the agreement above is
    // about the selection rather than about identical inputs.
    assertNotEquals(
        original.get("polls").get(0).get("observedIlr").toString(),
        shifted.get("polls").get(0).get("observedIlr").toString());
  }

  @Test
  void keepsAValidEndpointSelectionAndNamesTheAxesItSitsOn() {
    final JsonNode search = tune("endpoints", dataset("ilr_window")).get("search");
    final List<String> expected = new ArrayList<>();
    endpoints(expected, "walkVariance", WALKS, search.get("selected"));
    endpoints(expected, "houseScale", HOUSES, search.get("selected"));
    endpoints(expected, "covarianceMultiplier", MULTIPLIERS, search.get("selected"));
    assertEquals(expected, search.get("endpoints").valueStream().map(JsonNode::asString).toList());
    // A selection on a grid end stays in the evidence with its scored output beside it.
    assertEquals(2, read(temp.resolve("endpoints.json")).get("polls").size());
  }

  private static void endpoints(
      List<String> endpoints, String axis, List<Double> values, JsonNode selected) {
    final int index = values.indexOf(selected.get(axis).doubleValue());
    assertTrue(index >= 0, () -> "The selected " + axis + " is a registered grid value");
    if (index == 0) endpoints.add(axis + ":lower");
    if (index == values.size() - 1) endpoints.add(axis + ":upper");
  }

  @Test
  void stopsOnAGridPointThatDoesNotFitAndPreservesEveryAttemptItMade() {
    // A training observation far outside any state the prior supports drives the likelihood
    // beyond what a double can carry.
    final ObjectNode overflowing = dataset("midpoint");
    for (int i = 0; i < DIMENSION; i++)
      ((ArrayNode) overflowing.get("observations").get(0).get("ilr"))
          .set(i, DoubleNode.valueOf(1e200));

    final Path evidenceFile = temp.resolve("stopped.json");
    assertEquals(
        SyntheticRecovery.STOPPED,
        SyntheticRecovery.run(
            "tune",
            write(temp.resolve("stopped-input.json"), overflowing).toString(),
            evidenceFile.toString()));
    final JsonNode stopped = read(evidenceFile);
    assertEquals("numerical_failure", stopped.get("status").asString());
    assertEquals("estimated", stopped.get("stage").asString());
    assertEquals("search", stopped.get("operation").asString());
    assertEquals(
        "unchanged; the repetition is preserved and its seed is never replaced",
        stopped.get("denominator").asString());
    assertNull(stopped.get("polls"), "A stopped search produces no scoring evidence");

    // The failing point is retained rather than skipped, and the search stops there.
    final JsonNode attempts = stopped.get("search").get("attempts");
    assertEquals(-1, stopped.get("search").get("selectedIndex").intValue());
    assertTrue(attempts.size() <= 180, "The grid is never expanded");
    final JsonNode last = attempts.get(attempts.size() - 1);
    assertEquals("failed", last.get("status").asString());
    assertEquals(attempts.size() - 1, last.get("index").intValue());
    assertTrue(
        stopped.get("message").asString().contains("grid point " + last.get("index").intValue()),
        () -> "Unexpected message: " + stopped.get("message").asString());
    for (int i = 0; i < attempts.size() - 1; i++)
      assertEquals("evaluated", attempts.get(i).get("status").asString());
  }

  @Test
  void scoresTheTunedDatasetOnTheSameInputsAndPredictiveStreamsAsTheKnownStage() {
    final ObjectNode known = dataset("midpoint");
    final ObjectNode point = known.putObject("parameters");
    point.put("walkVariance", 0.0001);
    point.put("houseScale", 0.05);
    point.put("covarianceMultiplier", 1.5);
    final Path scored = temp.resolve("known.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "score", write(temp.resolve("known-input.json"), known).toString(), scored.toString()));
    final JsonNode control = read(scored);
    final JsonNode tuned = tune("tuned", dataset("midpoint"));

    // The same rows, the same fixed covariance and the same per-row predictive streams; only the
    // parameter point, and therefore the predictive distribution, differs.
    assertEquals(control.get("inputsSha256").asString(), tuned.get("inputsSha256").asString());
    assertEquals(
        control.get("observationCovariance").toString(),
        tuned.get("observationCovariance").toString());
    assertEquals(control.get("polls").size(), tuned.get("polls").size());
    for (int i = 0; i < control.get("polls").size(); i++) {
      final JsonNode first = control.get("polls").get(i);
      final JsonNode second = tuned.get("polls").get(i);
      assertEquals(first.get("rowNumber").intValue(), second.get("rowNumber").intValue());
      assertEquals(first.get("stream").asString(), second.get("stream").asString());
      assertEquals(first.get("seed").longValue(), second.get("seed").longValue());
      assertEquals(first.get("observedIlr").toString(), second.get("observedIlr").toString());
    }
    assertNotEquals(control.get("parameters").toString(), tuned.get("parameters").toString());
    assertNull(control.get("search"), "A dataset scored at a named point runs no search");
    assertEquals("estimated", tuned.get("stage").asString());
  }

  @Test
  void refusesASuppliedParameterPointBesideASearch() {
    final ObjectNode supplied = dataset("midpoint");
    final ObjectNode point = supplied.putObject("parameters");
    point.put("walkVariance", 0.0001);
    point.put("houseScale", 0.05);
    point.put("covarianceMultiplier", 1.5);
    final Path evidenceFile = temp.resolve("supplied.json");
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "tune",
            write(temp.resolve("supplied-input.json"), supplied).toString(),
            evidenceFile.toString()));
    final JsonNode rejection = read(evidenceFile);
    assertEquals("rejected", rejection.get("status").asString());
    assertTrue(
        rejection.get("reasons").get(0).asString().contains("selects its own parameters"),
        () -> "Unexpected reason: " + rejection.get("reasons").get(0).asString());
  }

  private JsonNode tune(String name, ObjectNode document) {
    final Path evidenceFile = temp.resolve(name + ".json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "tune",
            write(temp.resolve(name + "-input.json"), document).toString(),
            evidenceFile.toString()));
    return read(evidenceFile);
  }

  private static ObjectNode dataset(String convention) {
    return dataset(convention, ROWS);
  }

  /** A tuned dataset document: the same shape a scored one takes, without a parameter point. */
  private static ObjectNode dataset(String convention, List<Row> rows) {
    final ObjectNode document = JSON.createObjectNode();
    document.put("version", "synthetic-recovery-v2");
    document.put("phase", "software_check");
    document.put("convention", convention);
    document.put("masterSeed", SEED);
    document.put("datasetIndex", 7);
    document.put("draws", 200);
    document.put("periodStart", START.toString());
    document.put("cutoff", CUTOFF.toString());
    document.put("scoreThrough", THROUGH.toString());
    final ArrayNode covariance = document.putArray("observationCovariance");
    final SimpleMatrix fixed = fixedCovariance();
    for (int r = 0; r < DIMENSION; r++) {
      final ArrayNode row = covariance.addArray();
      for (int c = 0; c < DIMENSION; c++) row.add(fixed.get(r, c));
    }
    final ArrayNode observations = document.putArray("observations");
    for (Row row : rows) {
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

  private static SimpleMatrix fixedCovariance() {
    final SimpleMatrix covariance = new SimpleMatrix(DIMENSION, DIMENSION);
    for (int r = 0; r < DIMENSION; r++)
      for (int c = 0; c < DIMENSION; c++) covariance.set(r, c, r == c ? 0.03 : 0.01);
    return covariance;
  }

  private static List<Double> doubles(JsonNode values) {
    return values.valueStream().map(JsonNode::doubleValue).toList();
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
