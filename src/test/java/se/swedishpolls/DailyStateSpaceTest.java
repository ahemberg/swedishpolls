package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

class DailyStateSpaceTest {
  private static final LocalDate START = LocalDate.of(2018, 6, 1);
  private static final LocalDate ELECTION = LocalDate.of(2018, 9, 9);
  private static final List<LocalDate> ELECTIONS = List.of(LocalDate.of(2014, 9, 14), ELECTION);
  private static final DailyStateSpace.Parameters PARAMETERS =
      new DailyStateSpace.Parameters(0.003, 0.5, 1.5);

  /**
   * One eligible poll. The publication date is the collection end, so the method era follows it.
   */
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
    var period =
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
  void firstPollUpdatesIndependentDiffuseOpinionAndHousePriorsForBothRosters() {
    for (boolean fi : List.of(false, true)) {
      var batch = batch(fi, 30, row("Novus", 0, 0, "20"));
      int dimension = batch.basis().getNumRows();
      var value = new SimpleMatrix(dimension, 1);
      value.fill(2);
      var observation =
          new PollObservations.Observation(
              batch.observations().getFirst().poll(),
              START,
              ModelValues.copyOf(value),
              ModelValues.copyOf(SimpleMatrix.identity(dimension).scale(2)),
              0);
      var fit =
          DailyStateSpace.fit(
              replace(batch, observation),
              ELECTIONS,
              new DailyStateSpace.Parameters(0.01, Math.sqrt(2), 1));
      assertEquals(1, fit.days().size());
      var day = fit.days().getFirst();
      assertEquals(START, day.date());
      // Scalar conjugate normal reference: the centered single institute observes x + h, prior
      // variance 4 + 2.
      value.fill(1.5);
      assertMatrix(value, day.filteredMean(), 1e-14);
      assertMatrix(SimpleMatrix.identity(dimension).scale(1.5), day.filteredCovariance(), 1e-14);
      assertMatrix(day.filteredMean(), day.smoothedMean(), 0);
      assertMatrix(day.filteredCovariance(), day.smoothedCovariance(), 0);
      assertEquals(-2.2086593040445903 * dimension, fit.logLikelihood(), 1e-12);
      // A single active institute carries the whole ensemble weight, so its centered effect is
      // exactly zero.
      var cycle = fit.cycles().getFirst();
      assertEquals(List.of("Novus"), cycle.effects());
      assertEquals(List.of(1.0), cycle.weights());
      assertEquals(0, cycle.smoothedMean().normF());
      assertEquals(0, cycle.smoothedCovariance().normF(), 1e-14);
    }
  }

  @Test
  void dailyStatesAndCycleEffectsMatchDenseConditioningAcrossAnElectionBoundary() {
    for (boolean fi : List.of(false, true)) {
      // Deliberately out of order. Two overlapping Novus windows share midpoint day 2; Sifo crosses
      // the election.
      var batch =
          batch(
              fi,
              200,
              row("Sifo", 99, 101, "22")
                  + row("Novus", 1, 3, "20")
                  + row("Novus", 0, 4, "19")
                  + row("Sifo", 2, 2, "21")
                  + row("Novus", 100, 100, "18"));
      var fit = DailyStateSpace.fit(batch, ELECTIONS, PARAMETERS);
      assertSame(batch, fit.batch());
      assertEquals(PARAMETERS, fit.parameters());
      assertEquals(101, fit.days().size());
      assertEquals(2, fit.cycles().size());
      assertEquals(
          List.of(START, ELECTION),
          fit.cycles().stream().map(DailyStateSpace.Cycle::start).toList());
      assertEquals(
          List.of(ELECTION.minusDays(1), START.plusDays(100)),
          fit.cycles().stream().map(DailyStateSpace.Cycle::end).toList());
      for (int t = 0; t < fit.days().size(); t++) {
        var day = fit.days().get(t);
        assertEquals(START.plusDays(t), day.date());
        var filtered = dense(batch, ELECTIONS, day.date(), true, PARAMETERS);
        var smoothed = dense(batch, ELECTIONS, day.date(), false, PARAMETERS);
        assertMatrix(filtered.opinionMean(), day.filteredMean(), 1e-11);
        assertMatrix(filtered.opinionCovariance(), day.filteredCovariance(), 1e-11);
        assertMatrix(smoothed.opinionMean(), day.smoothedMean(), 1e-11);
        assertMatrix(smoothed.opinionCovariance(), day.smoothedCovariance(), 1e-11);
        assertEquals(smoothed.logLikelihood(), fit.logLikelihood(), 1e-10);
      }
      for (var cycle : fit.cycles()) {
        var filtered = dense(batch, ELECTIONS, cycle.end(), true, PARAMETERS);
        var smoothed = dense(batch, ELECTIONS, cycle.end(), false, PARAMETERS);
        assertEquals(List.of("Novus", "Sifo"), cycle.effects());
        assertEquals(List.of(0.5, 0.5), cycle.weights());
        assertMatrix(filtered.effectMean(), cycle.filteredMean(), 1e-11);
        assertMatrix(filtered.effectCovariance(), cycle.filteredCovariance(), 1e-11);
        assertMatrix(smoothed.effectMean(), cycle.smoothedMean(), 1e-11);
        assertMatrix(smoothed.effectCovariance(), cycle.smoothedCovariance(), 1e-11);
      }
      assertEquals(0, fit.days().getFirst().filteredMean().normF());
      assertTrue(fit.days().getFirst().smoothedMean().normF() > 0.01);
    }
  }

  @Test
  void newCyclesResetHouseEffectsWhileTheOpinionStateCarriesAcrossTheElection() {
    // The two institutes swap their deviations at the election, leaving the ensemble reference
    // unchanged.
    var rows = new StringBuilder();
    for (int week = 0; week < 20; week++)
      rows.append(row("Sifo", week * 7, week * 7 + 1, week * 7 < 100 ? "24" : "16"))
          .append(row("Novus", week * 7 + 2, week * 7 + 3, week * 7 + 2 < 100 ? "16" : "24"));
    var fit = DailyStateSpace.fit(batch(false, 200, rows.toString()), ELECTIONS, PARAMETERS);
    assertEquals(2, fit.cycles().size());
    assertEquals(List.of("Novus", "Sifo"), fit.cycles().getFirst().effects());
    assertEquals(fit.cycles().getFirst().effects(), fit.cycles().get(1).effects());
    var before = fit.cycles().getFirst().smoothedMean();
    var after = fit.cycles().get(1).smoothedMean();
    // Row 0 is the Novus effect and row 8 the Sifo effect in the first ilr coordinate, which
    // contrasts M with L.
    assertTrue(
        before.get(0) < -0.1 && before.get(8) > 0.1,
        () -> "Effects before: " + before.get(0) + ", " + before.get(8));
    assertTrue(
        after.get(0) > 0.1 && after.get(8) < -0.1,
        () -> "Effects after: " + after.get(0) + ", " + after.get(8));
    // The opinion state carries across the reset: the centered level does not jump on the election
    // day.
    var day = (int) ChronoUnit.DAYS.between(START, ELECTION);
    assertTrue(
        fit.days()
                .get(day)
                .smoothedMean()
                .minus(fit.days().get(day - 1).smoothedMean())
                .elementMaxAbs()
            < 0.01,
        () ->
            "Level jump: "
                + fit.days()
                    .get(day)
                    .smoothedMean()
                    .minus(fit.days().get(day - 1).smoothedMean())
                    .elementMaxAbs());
  }

  @Test
  void sparseInstitutesShrinkTowardZeroWhileRepeatedOnesKeepTheirDeviation() {
    var ensemble = new StringBuilder();
    var novus = new StringBuilder();
    for (int week = 0; week < 12; week++) {
      ensemble
          .append(row("Sifo", week * 7, week * 7 + 1, "20"))
          .append(row("Skop", week * 7 + 4, week * 7 + 5, "20"));
      novus.append(row("Novus", week * 7 + 2, week * 7 + 3, "26"));
    }
    // A house scale near the per-poll observation noise makes the shrinkage of a single poll
    // visible.
    var parameters = new DailyStateSpace.Parameters(0.003, 0.05, 1.5);
    var many =
        DailyStateSpace.fit(batch(false, 100, ensemble + novus.toString()), ELECTIONS, parameters);
    var once =
        DailyStateSpace.fit(
            batch(false, 100, ensemble + row("Novus", 2, 3, "26")), ELECTIONS, parameters);
    int index = many.cycles().getFirst().effects().indexOf("Novus");
    assertEquals(index, once.cycles().getFirst().effects().indexOf("Novus"));
    double repeatedEffect = many.cycles().getFirst().smoothedMean().get(index * 8);
    double singleEffect = once.cycles().getFirst().smoothedMean().get(index * 8);
    assertTrue(repeatedEffect > 0.05, () -> "Repeated Novus effect: " + repeatedEffect);
    assertTrue(
        singleEffect > 0 && singleEffect < repeatedEffect / 2,
        () -> "Single Novus effect: " + singleEffect + " against " + repeatedEffect);
  }

  @Test
  void methodErasSplitOneInstituteAndShareTheContinuationWithoutExtraEnsembleWeight() {
    // Demoskop's era changes with its publication date; Inizio continues the same era under a new
    // name.
    var start = LocalDate.of(2019, 6, 1);
    var period =
        new Roster.CoveragePeriod(
            "era",
            start,
            start.plusDays(400),
            PollCsv.PARTIES,
            false,
            false,
            "https://example.invalid");
    var rows = new StringBuilder();
    for (int week = 0; week < 6; week++) {
      rows.append(era("Demoskop", start.plusDays(week * 7L), "18"));
      rows.append(era("Demoskop", start.plusDays(200 + week * 7), "24"));
      rows.append(era("Inizio", start.plusDays(210 + week * 7), "24"));
      rows.append(era("Novus", start.plusDays(week * 7 + 3), "21"));
      rows.append(era("Novus", start.plusDays(203 + week * 7), "21"));
    }
    var fit =
        DailyStateSpace.fit(
            PollObservations.prepare(period, PollCsv.parse(PollCsvTest.csv(rows.toString()))),
            List.of(),
            PARAMETERS);
    var cycle = fit.cycles().getFirst();
    assertEquals(
        List.of("Novus", "demoskop_before_2019_11", "inizio_continuation"), cycle.effects());
    // Three institutes: Demoskop splits its 1/3 over two eras, and Inizio adds its own 1/3 to the
    // continuation.
    assertEquals(List.of(1.0 / 3, 1.0 / 6, 1.0 / 3 + 1.0 / 6), cycle.weights());
    assertEquals(1, cycle.weights().stream().mapToDouble(Double::doubleValue).sum(), 1e-15);
    assertEquals(3 * 8, cycle.smoothedMean().getNumRows());
    // Demoskop's earlier era keeps its own deviation while its continuation pools with Inizio above
    // the ensemble.
    assertTrue(
        cycle.smoothedMean().get(8) < -0.05 && cycle.smoothedMean().get(16) > 0.05,
        () -> "Era effects: " + cycle.smoothedMean().get(8) + ", " + cycle.smoothedMean().get(16));
  }

  private static String era(String institute, LocalDate date, String m) {
    return "2019-06,"
        + institute
        + ","
        + m
        + ",5,8,5,25,8,5,12,1,10,1000,"
        + date.plusDays(1)
        + ","
        + institute
        + ","
        + date
        + ","
        + date.plusDays(1)
        + ",FALSE\n";
  }

  @Test
  void centeringLeavesTheWeightedEnsembleEffectAtZeroWithASingularPositiveSemidefiniteCovariance() {
    var rows = new StringBuilder();
    for (int week = 0; week < 6; week++)
      rows.append(row("Sifo", week * 7, week * 7 + 1, "24"))
          .append(row("Novus", week * 7 + 2, week * 7 + 3, "18"))
          .append(row("Skop", week * 7 + 4, week * 7 + 5, "20"));
    var cycle =
        DailyStateSpace.fit(batch(false, 100, rows.toString()), ELECTIONS, PARAMETERS)
            .cycles()
            .getFirst();
    var weights = new SimpleMatrix(8, cycle.smoothedMean().getNumRows());
    for (int effect = 0; effect < cycle.weights().size(); effect++)
      for (int i = 0; i < 8; i++) weights.set(i, effect * 8 + i, cycle.weights().get(effect));
    assertTrue(weights.mult(cycle.smoothedMean().copy()).elementMaxAbs() < 1e-12);
    assertTrue(
        weights.mult(cycle.smoothedCovariance().copy()).mult(weights.transpose()).elementMaxAbs()
            < 1e-12);
    var eigenvalues = cycle.smoothedCovariance().eig().getEigenvalues();
    double largest =
        eigenvalues.stream().mapToDouble(org.ejml.data.Complex_F64::getReal).max().orElseThrow();
    assertTrue(
        eigenvalues.stream()
            .allMatch(value -> value.getReal() > -1e-12 * largest && value.getImaginary() == 0));
    assertEquals(
        8,
        eigenvalues.stream().filter(value -> Math.abs(value.getReal()) < 1e-12 * largest).count());
  }

  @Test
  void cyclesWithoutEligiblePollsHoldNoHouseEffects() {
    // The period opens well before the election, so its first cycle sees no poll at all.
    var period =
        new Roster.CoveragePeriod(
            "gap",
            START.minusYears(2),
            START.plusDays(30),
            PollCsv.PARTIES,
            false,
            false,
            "https://example.invalid");
    var batch =
        PollObservations.prepare(period, PollCsv.parse(PollCsvTest.csv(row("Novus", 0, 1, "20"))));
    var fit = DailyStateSpace.fit(batch, List.of(START.minusYears(1)), PARAMETERS);
    assertEquals(2, fit.cycles().size());
    assertEquals(List.of(), fit.cycles().getFirst().effects());
    assertEquals(0, fit.cycles().getFirst().smoothedMean().getNumRows());
    assertEquals(List.of("Novus"), fit.cycles().get(1).effects());
    assertEquals(0, fit.days().getFirst().filteredMean().normF());
    assertMatrix(
        SimpleMatrix.identity(8).scale(4), fit.days().getFirst().filteredCovariance(), 1e-14);
  }

  @Test
  void separateCoveragePeriodsRestartTheDiffusePriorAndCarryNoHouseEffects() {
    var rows = new StringBuilder();
    for (int week = 0; week < 6; week++)
      rows.append(row("Sifo", week * 7, week * 7 + 1, "24"))
          .append(row("Novus", week * 7 + 2, week * 7 + 3, "18"));
    var whole = batch(false, 100, rows.toString());
    var later =
        new Roster.CoveragePeriod(
            "later",
            START.plusDays(22),
            START.plusDays(100),
            PollCsv.PARTIES,
            false,
            false,
            "https://example.invalid");
    var tail = PollObservations.prepare(later, PollCsv.parse(PollCsvTest.csv(rows.toString())));
    var fit = DailyStateSpace.fit(tail, ELECTIONS, PARAMETERS);
    assertEquals(START.plusDays(22), fit.days().getFirst().date());
    assertEquals(0, fit.days().getFirst().filteredMean().normF());
    // Nothing carries over: the opinion prior is 4 I and the two equally weighted house priors add
    // w^2 s^2.
    assertMatrix(
        SimpleMatrix.identity(8).scale(4 + 2 * 0.25 * 0.25),
        fit.days().getFirst().filteredCovariance(),
        1e-14);
    assertEquals(1, fit.cycles().size());
    assertEquals(START.plusDays(22), fit.cycles().getFirst().start());
    assertEquals(List.of("Novus", "Sifo"), fit.cycles().getFirst().effects());
    assertTrue(tail.observations().size() < whole.observations().size());
    assertMatrix(
        dense(tail, ELECTIONS, fit.days().getLast().date(), false, PARAMETERS).opinionMean(),
        fit.days().getLast().smoothedMean(),
        1e-11);
  }

  @Test
  void theLikelihoodOnlyPathReturnsExactlyTheRetainedFitsLikelihoodAndRejectsTheSameInputs() {
    for (boolean fi : List.of(false, true)) {
      var batch =
          batch(
              fi,
              200,
              row("Sifo", 99, 101, "22")
                  + row("Novus", 1, 3, "20")
                  + row("Novus", 0, 4, "19")
                  + row("Sifo", 2, 2, "21")
                  + row("Novus", 100, 100, "18"));
      for (var parameters : List.of(PARAMETERS, new DailyStateSpace.Parameters(0, 0.05, 1)))
        assertEquals(
            DailyStateSpace.fit(batch, ELECTIONS, parameters).logLikelihood(),
            DailyStateSpace.logLikelihood(batch, ELECTIONS, parameters),
            0);
    }
    var batch = batch(false, 30, row("Novus", 0, 0, "20"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DailyStateSpace.logLikelihood(
                batch, ELECTIONS, new DailyStateSpace.Parameters(0.003, 0, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> DailyStateSpace.logLikelihood(batch(false, 30, ""), ELECTIONS, PARAMETERS));
    assertThrows(
        IllegalArgumentException.class,
        () -> DailyStateSpace.logLikelihood(batch, List.of(ELECTION, ELECTION), PARAMETERS));
  }

  @Test
  void rejectsInvalidParametersElectionOrderEmptyBatchesAndInvalidNumericsWithoutRepair() {
    var batch = batch(false, 30, row("Novus", 0, 0, "20"));
    for (double value : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DailyStateSpace.fit(batch, ELECTIONS, new DailyStateSpace.Parameters(value, 0.5, 1)));
    for (double value : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DailyStateSpace.fit(
                  batch, ELECTIONS, new DailyStateSpace.Parameters(0.003, value, 1)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DailyStateSpace.fit(
                  batch, ELECTIONS, new DailyStateSpace.Parameters(0.003, 0.5, value)));
    }
    for (var elections :
        List.of(
            List.of(ELECTION, ELECTION),
            List.of(ELECTION, ELECTION.minusDays(1)),
            java.util.Collections.<LocalDate>singletonList(null)))
      assertThrows(
          IllegalArgumentException.class, () -> DailyStateSpace.fit(batch, elections, PARAMETERS));
    assertThrows(
        IllegalArgumentException.class,
        () -> DailyStateSpace.fit(batch(false, 30, ""), ELECTIONS, PARAMETERS));
    var observation = batch.observations().getFirst();
    int dimension = observation.ilr().getNumRows();
    var asymmetric = SimpleMatrix.identity(dimension);
    asymmetric.set(0, 1, 0.1);
    var indefinite = SimpleMatrix.identity(dimension);
    indefinite.set(0, 1, 2);
    indefinite.set(1, 0, 2);
    var nonfinite = SimpleMatrix.identity(dimension);
    nonfinite.set(0, 0, Double.NaN);
    for (var covariance :
        List.of(
            asymmetric,
            indefinite,
            nonfinite,
            new SimpleMatrix(dimension, dimension),
            SimpleMatrix.identity(dimension - 1))) {
      var invalid =
          replace(
              batch,
              new PollObservations.Observation(
                  observation.poll(), START, observation.ilr(), ModelValues.copyOf(covariance), 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> DailyStateSpace.fit(invalid, ELECTIONS, PARAMETERS));
    }
    var nonfiniteMean = observation.ilr().copy();
    nonfiniteMean.set(0, Double.POSITIVE_INFINITY);
    for (var mean : List.of(nonfiniteMean, new SimpleMatrix(dimension, 2))) {
      var invalid =
          replace(
              batch,
              new PollObservations.Observation(
                  observation.poll(),
                  START,
                  ModelValues.copyOf(mean),
                  observation.covariance(),
                  0));
      assertThrows(
          IllegalArgumentException.class,
          () -> DailyStateSpace.fit(invalid, ELECTIONS, PARAMETERS));
    }
    for (var date : List.of(START.minusDays(1), START.plusDays(31))) {
      var invalid =
          replace(
              batch,
              new PollObservations.Observation(
                  observation.poll(), date, observation.ilr(), observation.covariance(), 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> DailyStateSpace.fit(invalid, ELECTIONS, PARAMETERS));
    }
    // Finite parameter values can still overflow during propagation. Never return a nonfinite fit.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DailyStateSpace.fit(
                batch(false, 30, row("Novus", 4, 6, "20")),
                ELECTIONS,
                new DailyStateSpace.Parameters(Double.MAX_VALUE, 0.5, 1)));
  }

  @Test
  void zeroWalkIsAStaticFitAndReorderingInputsDoesNotChangeResultsOrMutateTheBatch() {
    var batch = batch(true, 30, row("Novus", 0, 0, "20") + row("Sifo", 2, 4, "18"));
    var original = batch.observations().getFirst().ilr().copy();
    var covariance = batch.observations().getFirst().covariance().copy();
    var parameters = new DailyStateSpace.Parameters(0, 0.5, 2);
    var fit = DailyStateSpace.fit(batch, ELECTIONS, parameters);
    var reversed =
        new PollObservations.Batch(
            batch.period(),
            batch.components(),
            batch.basis(),
            batch.observations().reversed(),
            batch.exclusions());
    var rerun = DailyStateSpace.fit(reversed, ELECTIONS, parameters);
    var reference = dense(batch, ELECTIONS, START, false, parameters);
    for (int i = 0; i < fit.days().size(); i++) {
      var day = fit.days().get(i);
      assertMatrix(reference.opinionMean(), day.smoothedMean(), 1e-11);
      assertMatrix(reference.opinionCovariance(), day.smoothedCovariance(), 1e-11);
      assertMatrix(day.filteredMean(), rerun.days().get(i).filteredMean(), 0);
      assertMatrix(day.smoothedCovariance(), rerun.days().get(i).smoothedCovariance(), 0);
    }
    assertEquals(fit.logLikelihood(), rerun.logLikelihood());
    assertMatrix(original, batch.observations().getFirst().ilr(), 0);
    assertMatrix(covariance, batch.observations().getFirst().covariance(), 0);
  }

  private static PollObservations.Batch replace(
      PollObservations.Batch batch, PollObservations.Observation observation) {
    return new PollObservations.Batch(
        batch.period(),
        batch.components(),
        batch.basis(),
        List.of(observation),
        batch.exclusions());
  }

  private static void assertMatrix(SimpleMatrix expected, SimpleMatrix actual, double tolerance) {
    assertEquals(expected.getNumRows(), actual.getNumRows());
    assertEquals(expected.getNumCols(), actual.getNumCols());
    assertTrue(
        expected.minus(actual).elementMaxAbs() <= tolerance,
        () -> "Maximum error: " + expected.minus(actual).elementMaxAbs());
  }

  private static void assertMatrix(SimpleMatrix expected, ModelValues actual, double tolerance) {
    assertMatrix(expected, actual.copy(), tolerance);
  }

  private static void assertMatrix(ModelValues expected, ModelValues actual, double tolerance) {
    assertMatrix(expected.copy(), actual.copy(), tolerance);
  }

  private record Reference(
      SimpleMatrix opinionMean,
      SimpleMatrix opinionCovariance,
      SimpleMatrix effectMean,
      SimpleMatrix effectCovariance,
      double logLikelihood) {}

  /**
   * Independent batch Gaussian conditioning over the opinion path and every cycle house effect.
   * Inverts the full observation joint covariance, with no sequential filter or backward recursion.
   */
  private static Reference dense(
      PollObservations.Batch batch,
      List<LocalDate> elections,
      LocalDate date,
      boolean filtered,
      DailyStateSpace.Parameters parameters) {
    var start = batch.period().effectiveFrom();
    int dimension = batch.basis().getNumRows();
    var used =
        batch.observations().stream()
            .filter(o -> !filtered || !o.midpoint().isAfter(date))
            .toList();
    var identities = new ArrayList<String>();
    for (var observation : batch.observations()) {
      var identity = identity(observation, start, elections);
      if (!identities.contains(identity)) identities.add(identity);
    }
    var weights = weights(batch, elections, cycle(date, start, elections));
    int latent = dimension * (1 + identities.size());
    var prior = new SimpleMatrix(latent, latent);
    for (int i = 0; i < dimension; i++)
      prior.set(i, i, 4 + parameters.walkVariance() * ChronoUnit.DAYS.between(start, date));
    for (int i = dimension; i < latent; i++)
      prior.set(i, i, parameters.houseScale() * parameters.houseScale());
    int size = used.size() * dimension;
    var joint = new SimpleMatrix(size, size);
    var cross = new SimpleMatrix(latent, size);
    var values = new SimpleMatrix(size, 1);
    for (int i = 0; i < used.size(); i++) {
      var observation = used.get(i);
      long t = ChronoUnit.DAYS.between(start, observation.midpoint());
      int effect = identities.indexOf(identity(observation, start, elections));
      values.insertIntoThis(i * dimension, 0, observation.ilr().copy());
      cross.insertIntoThis(
          0,
          i * dimension,
          SimpleMatrix.identity(dimension)
              .scale(
                  4
                      + parameters.walkVariance()
                          * Math.min(ChronoUnit.DAYS.between(start, date), t)));
      cross.insertIntoThis(
          dimension * (1 + effect),
          i * dimension,
          SimpleMatrix.identity(dimension)
              .scale(parameters.houseScale() * parameters.houseScale()));
      for (int j = 0; j < used.size(); j++) {
        var other = used.get(j);
        long s = ChronoUnit.DAYS.between(start, other.midpoint());
        var block =
            SimpleMatrix.identity(dimension).scale(4 + parameters.walkVariance() * Math.min(t, s));
        if (identity(other, start, elections).equals(identity(observation, start, elections)))
          block =
              block.plus(
                  SimpleMatrix.identity(dimension)
                      .scale(parameters.houseScale() * parameters.houseScale()));
        if (i == j)
          block = block.plus(observation.covariance().scale(parameters.covarianceMultiplier()));
        joint.insertIntoThis(i * dimension, j * dimension, block);
      }
    }
    var mean = new SimpleMatrix(latent, 1);
    var covariance = prior;
    double logLikelihood = 0;
    if (size > 0) {
      var inverse = joint.invert();
      mean = cross.mult(inverse).mult(values);
      covariance = prior.minus(cross.mult(inverse).mult(cross.transpose()));
      logLikelihood =
          -0.5
              * (size * Math.log(2 * Math.PI)
                  + Math.log(joint.determinant())
                  + values.dot(inverse.mult(values)));
    }
    // The centering transform maps the latent vector onto the centered opinion and centered cycle
    // effects.
    var active = weights.keySet().stream().toList();
    var transform = new SimpleMatrix(dimension * (1 + active.size()), latent);
    for (int i = 0; i < dimension; i++) transform.set(i, i, 1);
    for (int e = 0; e < active.size(); e++) {
      int column = dimension * (1 + identities.indexOf(active.get(e)));
      for (int i = 0; i < dimension; i++) {
        transform.set(i, column + i, weights.get(active.get(e)));
        for (int f = 0; f < active.size(); f++)
          transform.set(
              dimension * (1 + f) + i, column + i, (e == f ? 1 : 0) - weights.get(active.get(e)));
      }
    }
    var centeredMean = transform.mult(mean);
    var centeredCovariance = transform.mult(covariance).mult(transform.transpose());
    int effects = dimension * active.size();
    return new Reference(
        centeredMean.extractMatrix(0, dimension, 0, 1),
        centeredCovariance.extractMatrix(0, dimension, 0, dimension),
        centeredMean.extractMatrix(dimension, dimension + effects, 0, 1),
        centeredCovariance.extractMatrix(
            dimension, dimension + effects, dimension, dimension + effects),
        logLikelihood);
  }

  private static int cycle(LocalDate date, LocalDate start, List<LocalDate> elections) {
    return (int)
        elections.stream()
            .filter(election -> election.isAfter(start) && !election.isAfter(date))
            .count();
  }

  private static String identity(
      PollObservations.Observation observation, LocalDate start, List<LocalDate> elections) {
    return cycle(observation.midpoint(), start, elections)
        + ":"
        + DailyStateSpace.effectIdentity(observation.poll());
  }

  /**
   * Equal weight per active institute, split over the method eras it used in the cycle. This
   * repeats the production weighting rather than deriving it, so the fixtures below assert the
   * expected weights directly.
   */
  private static Map<String, Double> weights(
      PollObservations.Batch batch, List<LocalDate> elections, int cycle) {
    var start = batch.period().effectiveFrom();
    var eras = new TreeMap<String, TreeSet<String>>();
    for (var observation : batch.observations())
      if (cycle(observation.midpoint(), start, elections) == cycle)
        eras.computeIfAbsent(observation.poll().institute(), institute -> new TreeSet<>())
            .add(identity(observation, start, elections));
    var weights = new TreeMap<String, Double>();
    for (var institute : eras.values())
      for (var era : institute)
        weights.merge(era, 1.0 / (eras.size() * institute.size()), Double::sum);
    return new LinkedHashMap<>(weights);
  }
}
