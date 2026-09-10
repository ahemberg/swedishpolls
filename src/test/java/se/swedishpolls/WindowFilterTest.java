package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

class WindowFilterTest {
  private static final LocalDate START = LocalDate.of(2018, 6, 1);
  private static final LocalDate ELECTION = LocalDate.of(2018, 9, 9);
  private static final List<LocalDate> ELECTIONS = List.of(LocalDate.of(2014, 9, 14), ELECTION);
  private static final DailyStateSpace.Parameters PARAMETERS =
      new DailyStateSpace.Parameters(0.003, 0.5, 1.5);

  private static String row(String institute, int from, int to, String m) {
    return "2018-06,"
        + institute
        + ","
        + m
        + ",5,8,5,25,8,5,12,1,10,1000,"
        + START.plusDays(to)
        + ","
        + institute
        + ","
        + START.plusDays(from)
        + ","
        + START.plusDays(to)
        + ",FALSE\n";
  }

  private static PollObservations.Batch batch(boolean fi, int days, String rows) {
    final se.swedishpolls.Roster.CoveragePeriod period =
        new Roster.CoveragePeriod(
            "test",
            START,
            START.plusDays(days),
            fi
                ? Stream.concat(PollCsv.PARTIES.stream(), Stream.of("FI")).toList()
                : PollCsv.PARTIES,
            fi,
            false,
            "https://example.invalid");
    return PollObservations.prepare(period, PollCsv.parse(PollCsvTest.csv(rows)));
  }

  @Test
  void midpointWindowsReproduceTheFittedEstimatorLikelihoodOnBothRosters() {
    for (boolean fi : List.of(false, true)) {
      // Overlapping Novus windows, a Sifo window crossing the election, and one single-day poll.
      final se.swedishpolls.PollObservations.Batch batch =
          batch(
              fi,
              200,
              row("Sifo", 99, 101, "22")
                  + row("Novus", 1, 3, "20")
                  + row("Novus", 0, 4, "19")
                  + row("Sifo", 2, 2, "21")
                  + row("Novus", 100, 100, "18"));
      assertEquals(
          DailyStateSpace.logLikelihood(batch, ELECTIONS, PARAMETERS),
          WindowFilter.logLikelihood(
              batch, ELECTIONS, PARAMETERS, WindowFilter.Convention.MIDPOINT),
          1e-9);
    }
  }

  @Test
  void fieldworkWindowsAndTheirPredictionsMatchTheDenseJointSystem() {
    for (se.swedishpolls.WindowFilter.Convention convention : WindowFilter.Convention.values())
      for (boolean fi : List.of(false, true)) {
        final se.swedishpolls.PollObservations.Batch training =
            batch(
                fi,
                200,
                row("Sifo", 99, 101, "22")
                    + row("Novus", 1, 3, "20")
                    + row("Novus", 0, 4, "19")
                    + row("Sifo", 2, 2, "21"));
        // One held-out poll closes before the last training window, one after it, and one comes
        // from an institute with no effect in this cycle.
        final java.util.List<se.swedishpolls.PollObservations.Observation> heldOut =
            batch(
                    fi,
                    200,
                    row("Novus", 40, 44, "26")
                        + row("Sifo", 120, 124, "17")
                        + row("Skop", 130, 133, "23"))
                .observations();
        final se.swedishpolls.WindowFilter.Scored scored =
            WindowFilter.score(training, heldOut, ELECTIONS, PARAMETERS, convention);
        final se.swedishpolls.WindowFilterTest.Dense dense = dense(training, heldOut, convention);
        assertEquals(dense.logLikelihood(), scored.logLikelihood(), 1e-9);
        assertEquals(heldOut.size(), scored.predictions().size());
        for (int i = 0; i < heldOut.size(); i++) {
          final se.swedishpolls.WindowFilter.Prediction prediction = scored.predictions().get(i);
          assertEquals(heldOut.get(i).poll().rowNumber(), prediction.poll().rowNumber());
          assertEquals(convention.window(heldOut.get(i)), prediction.window());
          assertMatrix(dense.means().get(i), prediction.mean(), 1e-9);
          assertMatrix(dense.covariances().get(i), prediction.covariance(), 1e-9);
        }
        // Skop has no training effect in the cycle, so its effect comes from the prior.
        assertEquals(
            List.of(false, false, true),
            scored.predictions().stream().map(WindowFilter.Prediction::priorEffect).toList());
      }
  }

  @Test
  void everyPredictionConditionsOnTheWholeTrainingSetWhateverOrderTheWindowsClose() {
    final se.swedishpolls.PollObservations.Batch training =
        batch(false, 200, row("Sifo", 0, 2, "22") + row("Novus", 60, 62, "18"));
    final java.util.List<se.swedishpolls.PollObservations.Observation> early =
        batch(false, 200, row("Sifo", 10, 12, "20")).observations();
    final se.swedishpolls.WindowFilter.Scored scored =
        WindowFilter.score(
            training, early, ELECTIONS, PARAMETERS, WindowFilter.Convention.FIELDWORK);
    // The held-out window closes on day 12, long before the training window on day 62, and the
    // prediction is still resolved at the training end.
    assertEquals(START.plusDays(62), scored.predictions().getFirst().predictedOn());
    final se.swedishpolls.PollObservations.Batch withoutLate =
        batch(false, 200, row("Sifo", 0, 2, "22"));
    final se.swedishpolls.WindowFilter.Scored ignoringLate =
        WindowFilter.score(
            withoutLate, early, ELECTIONS, PARAMETERS, WindowFilter.Convention.FIELDWORK);
    assertTrue(
        scored
                .predictions()
                .getFirst()
                .mean()
                .minus(ignoringLate.predictions().getFirst().mean())
                .elementMaxAbs()
            > 1e-6,
        "The later training poll has to move the earlier held-out prediction");
    assertMatrix(
        dense(training, early, WindowFilter.Convention.FIELDWORK).means().getFirst(),
        scored.predictions().getFirst().mean(),
        1e-9);
  }

  @Test
  void aFieldworkWindowSpreadsOneObservationOverItsDaysRatherThanItsMidpoint() {
    final se.swedishpolls.PollObservations.Batch training =
        batch(false, 300, row("Sifo", 0, 0, "30") + row("Novus", 200, 240, "18"));
    final double midpoint =
        WindowFilter.logLikelihood(
            training, ELECTIONS, PARAMETERS, WindowFilter.Convention.MIDPOINT);
    final double window =
        WindowFilter.logLikelihood(
            training, ELECTIONS, PARAMETERS, WindowFilter.Convention.FIELDWORK);
    // A 41-day window averages away part of the walk the midpoint convention has to absorb.
    assertNotEquals(midpoint, window);
    assertTrue(Double.isFinite(window));
  }

  @Test
  void rejectsInadmissibleParametersAndEmptyTraining() {
    final se.swedishpolls.PollObservations.Batch training =
        batch(false, 100, row("Sifo", 0, 2, "22"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WindowFilter.logLikelihood(
                training,
                ELECTIONS,
                new DailyStateSpace.Parameters(-1, 0.5, 1),
                WindowFilter.Convention.MIDPOINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WindowFilter.logLikelihood(
                training,
                ELECTIONS,
                new DailyStateSpace.Parameters(0.001, 0, 1),
                WindowFilter.Convention.MIDPOINT));
    // A poll whose window lies outside the period composes nothing, so the batch has no
    // observation to fit.
    final se.swedishpolls.PollObservations.Batch empty = batch(false, 5, row("Sifo", 50, 52, "22"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WindowFilter.logLikelihood(
                empty, ELECTIONS, PARAMETERS, WindowFilter.Convention.MIDPOINT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            WindowFilter.score(
                training,
                training.observations(),
                List.of(ELECTION, ELECTION),
                PARAMETERS,
                WindowFilter.Convention.MIDPOINT));
  }

  private record Dense(
      double logLikelihood, List<SimpleMatrix> means, List<SimpleMatrix> covariances) {}

  /**
   * Independent batch Gaussian conditioning over the window-average observations. Builds the full
   * observation joint covariance from the random walk's own {@code min(s,t)} structure and inverts
   * it, with no sequential filter.
   */
  private static Dense dense(
      PollObservations.Batch training,
      List<PollObservations.Observation> heldOut,
      WindowFilter.Convention convention) {
    final int dimension = training.basis().getNumRows();
    final java.util.List<se.swedishpolls.PollObservations.Observation> trained =
        training.observations();
    final int size = trained.size() * dimension;
    final org.ejml.simple.SimpleMatrix joint = new SimpleMatrix(size, size);
    final org.ejml.simple.SimpleMatrix values = new SimpleMatrix(size, 1);
    for (int i = 0; i < trained.size(); i++) {
      values.insertIntoThis(i * dimension, 0, trained.get(i).ilr().copy());
      for (int j = 0; j < trained.size(); j++)
        joint.insertIntoThis(
            i * dimension,
            j * dimension,
            block(trained.get(i), trained.get(j), i == j, convention, dimension));
    }
    final org.ejml.simple.SimpleMatrix inverse = joint.invert();
    final java.util.ArrayList<org.ejml.simple.SimpleMatrix> means = new ArrayList<SimpleMatrix>();
    final java.util.ArrayList<org.ejml.simple.SimpleMatrix> covariances =
        new ArrayList<SimpleMatrix>();
    for (se.swedishpolls.PollObservations.Observation observation : heldOut) {
      final org.ejml.simple.SimpleMatrix cross = new SimpleMatrix(dimension, size);
      for (int j = 0; j < trained.size(); j++)
        cross.insertIntoThis(
            0, j * dimension, block(observation, trained.get(j), false, convention, dimension));
      final org.ejml.simple.SimpleMatrix own =
          block(observation, observation, true, convention, dimension);
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

  /** The prior covariance of two window averages, plus a shared effect and one poll's own noise. */
  private static SimpleMatrix block(
      PollObservations.Observation first,
      PollObservations.Observation second,
      boolean same,
      WindowFilter.Convention convention,
      int dimension) {
    final se.swedishpolls.WindowFilter.Window left = convention.window(first);
    final se.swedishpolls.WindowFilter.Window right = convention.window(second);
    double covariance = 0;
    for (java.time.LocalDate t = left.from(); !t.isAfter(left.to()); t = t.plusDays(1))
      for (java.time.LocalDate s = right.from(); !s.isAfter(right.to()); s = s.plusDays(1))
        covariance +=
            4
                + PARAMETERS.walkVariance()
                    * Math.min(
                        ChronoUnit.DAYS.between(START, t), ChronoUnit.DAYS.between(START, s));
    org.ejml.simple.SimpleMatrix block =
        SimpleMatrix.identity(dimension).scale(covariance / (left.days() * (double) right.days()));
    if (identity(first, convention).equals(identity(second, convention)))
      block =
          block.plus(
              SimpleMatrix.identity(dimension)
                  .scale(PARAMETERS.houseScale() * PARAMETERS.houseScale()));
    return same ? block.plus(first.covariance().scale(PARAMETERS.covarianceMultiplier())) : block;
  }

  /** House effects reset by cycle, so an identity is the effect name inside its own cycle. */
  private static String identity(
      PollObservations.Observation observation, WindowFilter.Convention convention) {
    final java.time.LocalDate closes = convention.window(observation).to();
    return (closes.isBefore(ELECTION) ? "before:" : "after:")
        + DailyStateSpace.effectIdentity(observation.poll());
  }

  private static void assertMatrix(SimpleMatrix expected, ModelValues actual, double tolerance) {
    assertEquals(expected.getNumRows(), actual.getNumRows());
    assertEquals(expected.getNumCols(), actual.getNumCols());
    assertTrue(
        expected.minus(actual.copy()).elementMaxAbs() <= tolerance,
        () -> "Maximum error: " + expected.minus(actual.copy()).elementMaxAbs());
  }
}
