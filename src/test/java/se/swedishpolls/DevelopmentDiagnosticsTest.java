package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.testsupport.PollCsvFixtures;

class DevelopmentDiagnosticsTest {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final LocalDate START = LocalDate.of(2018, 1, 1);
  private static final List<LocalDate> ELECTIONS =
      List.of(LocalDate.of(2014, 9, 14), LocalDate.of(2018, 9, 9));
  private static final DailyStateSpace.Parameters PARAMETERS =
      new DailyStateSpace.Parameters(0.0003, 0.05, 1.5);
  private static final DevelopmentDiagnostics.Rules RULES =
      new DevelopmentDiagnostics.Rules(
          20260908,
          35,
          400,
          List.of(1, 3, 6),
          List.of(1, 2),
          List.of(1, 8, 15),
          List.of(0.9, 0.98),
          List.of(0.4, 0.6));

  /** One eligible poll, with its publication date given separately from its fieldwork. */
  private static String row(String institute, int from, int to, int published, String m) {
    return "2018-01,"
        + institute
        + ","
        + m
        + ",5,8,5,25,8,5,12,1,10,1000,"
        + START.plusDays(published)
        + ","
        + institute
        + ","
        + START.plusDays(from)
        + ","
        + START.plusDays(to)
        + ",FALSE\n";
  }

  private static Roster.CoveragePeriod period() {
    return new Roster.CoveragePeriod(
        "test",
        START,
        START.plusDays(400),
        PollCsv.PARTIES,
        false,
        true,
        "https://example.invalid");
  }

  private static PollObservations.Batch batch(String rows) {
    return PollObservations.prepare(period(), PollCsv.parse(PollCsvFixtures.csv(rows)));
  }

  @Test
  void registersTheProtocolRulesAndRejectsInadmissibleOnes() {
    final se.swedishpolls.DevelopmentDiagnostics.Rules rules =
        DevelopmentDiagnostics.rules(PROTOCOL);
    assertEquals(35, rules.scoreHorizonDays());
    assertEquals(4000, rules.scoreDraws());
    assertEquals(List.of(1, 3, 6), rules.pairedStandardErrorLags());
    assertEquals(List.of(1, 8, 15), rules.fieldworkBands());
    assertEquals(List.of(0.9, 0.98), rules.coverage95());
    assertEquals(List.of(0.4, 0.6), rules.coverage50());
    // The frozen gate compares at lag three, so the registered lags have to contain it.
    assertTrue(rules.pairedStandardErrorLags().contains(3));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DevelopmentDiagnostics.Rules(
                1,
                35,
                1,
                List.of(3),
                List.of(1),
                List.of(1),
                List.of(0.9, 0.98),
                List.of(0.4, 0.6)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DevelopmentDiagnostics.Rules(
                1,
                35,
                10,
                List.of(3, 3),
                List.of(1),
                List.of(1),
                List.of(0.9, 0.98),
                List.of(0.4, 0.6)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DevelopmentDiagnostics.Rules(
                1,
                35,
                10,
                List.of(3),
                List.of(1),
                List.of(2),
                List.of(0.9, 0.98),
                List.of(0.4, 0.6)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DevelopmentDiagnostics.Rules(
                1,
                35,
                10,
                List.of(3),
                List.of(1),
                List.of(1),
                List.of(0.98, 0.9),
                List.of(0.4, 0.6)));
  }

  @Test
  void scoresEveryEligiblePollPublishedInsideTheHorizonAndNoOther() {
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        PollCsv.parse(
            PollCsvFixtures.csv(
                row("Novus", 0, 2, 3, "20")
                    + row("Sifo", 40, 42, 43, "21")
                    + row("Novus", 60, 62, 80, "22")
                    + row("Sifo", 80, 82, 100, "23")
                    + row("Skop", 41, 43, 44, "24").replace(",1000,", ",NA,")));
    final se.swedishpolls.DevelopmentTuning.Fold fold =
        new DevelopmentTuning.Fold(START.plusDays(45), START.plusDays(80));
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> heldOut =
        DevelopmentDiagnostics.heldOut(polls, fold, RULES);
    // Published on day 80, inside the horizon; the day 43 publication trained and the day 100 one
    // is beyond it. The missing sample size is ineligible and never scored.
    assertEquals(List.of(3), heldOut.stream().map(PollCsv.Poll::rowNumber).toList());
    assertTrue(DevelopmentTuning.training(polls, fold).stream().noneMatch(heldOut::contains));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DevelopmentDiagnostics.heldOut(
                polls, new DevelopmentTuning.Fold(START, START.plusDays(34)), RULES));
  }

  @Test
  void thePairedStandardErrorAtTheFrozenLagIsTheGatesOwn() {
    final double[] differences = new double[] {-1, -1, -1, -1, 1, 1, 1, 1};
    final double[] candidate = new double[] {9, 9, 9, 9, 11, 11, 11, 11};
    final double[] baseline = new double[8];
    final double[] reference = new double[] {10, 10, 10, 10, 10, 10, 10, 10};
    assertEquals(
        PredictiveComparison.evaluate(candidate, baseline, reference).pairedStandardError(),
        DevelopmentDiagnostics.pairedStandardError(differences, 3),
        1e-14);
    // The other registered lags weight the same autocovariances differently, so a longer
    // dependence shows up as a different standard error rather than being selected away.
    assertTrue(
        DevelopmentDiagnostics.pairedStandardError(differences, 1)
            < DevelopmentDiagnostics.pairedStandardError(differences, 3));
    assertNotEquals(
        DevelopmentDiagnostics.pairedStandardError(differences, 3),
        DevelopmentDiagnostics.pairedStandardError(differences, 6));
    assertTrue(DevelopmentDiagnostics.pairedStandardError(new double[] {1, 1, 1, 1}, 3) >= 0);
  }

  @Test
  void theBaselineAveragesItsWindowAndFallsBackToTheFiveMostRecentWhenItIsThin() {
    final se.swedishpolls.PollObservations.Batch wide =
        batch(
            row("Novus", 0, 2, 3, "20")
                + row("Sifo", 30, 32, 33, "22")
                + row("Novus", 60, 62, 63, "24")
                + row("Sifo", 90, 92, 93, "26"));
    final se.swedishpolls.RecencyBaseline.Fit fit = RecencyBaseline.fit(wide, START.plusDays(93));
    assertEquals(4, fit.used().size());
    // Weights fall by half every 30 days, so the newest poll carries most of the average and M
    // lands above the midpoint of 20 and 26.
    assertTrue(fit.weights().getLast() > 8 * fit.weights().getFirst());
    final double m = fit.composition().get(0);
    assertTrue(m > 24 && m < 26, () -> "Weighted M: " + m);
    assertTrue(fit.effectiveSampleSize() > 0 && fit.effectiveSampleSize() < 4000);
    // Only one poll falls inside the 120-day window, so the fallback reaches back for five.
    final se.swedishpolls.PollObservations.Batch thin =
        batch(
            row("Novus", 0, 2, 3, "20")
                + row("Sifo", 10, 12, 13, "22")
                + row("Novus", 20, 22, 23, "24")
                + row("Sifo", 300, 302, 303, "26"));
    final se.swedishpolls.RecencyBaseline.Fit fallback =
        RecencyBaseline.fit(thin, START.plusDays(303));
    assertEquals(4, fallback.used().size());
    final se.swedishpolls.ModelValues predictive =
        RecencyBaseline.predictiveCovariance(thin, fallback, thin.observations().getLast());
    // A held-out poll adds its own sampling variance, so the prediction is wider than the average.
    assertTrue(
        predictive.copy().minus(fallback.covariance().copy()).elementMaxAbs() > 0,
        "The held-out poll's own noise has to widen the baseline prediction");
    assertThrows(
        IllegalArgumentException.class, () -> RecencyBaseline.fit(wide, START.minusDays(1)));
  }

  @Test
  void aFoldScoresAllThreeCandidatesOnTheSameHeldOutPollsAndRecordsWhatItRead() {
    final java.lang.StringBuilder rows = new StringBuilder();
    for (int week = 0; week < 20; week++) {
      rows.append(row("Novus", week * 7, week * 7 + 2, week * 7 + 3, "20"));
      rows.append(row("Sifo", week * 7 + 3, week * 7 + 5, week * 7 + 6, "22"));
    }
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        PollCsv.parse(PollCsvFixtures.csv(rows.toString()));
    final se.swedishpolls.DevelopmentTuning.Fold fold =
        new DevelopmentTuning.Fold(START.plusDays(100), START.plusDays(135));
    final se.swedishpolls.DevelopmentTuning.Grid grid =
        new DevelopmentTuning.Grid(List.of(0.0003), List.of(0.05, 0.1), List.of(1.0, 1.5));
    final se.swedishpolls.DevelopmentDiagnostics.Folded folded =
        DevelopmentDiagnostics.fold(period(), polls, ELECTIONS, fold, grid, PARAMETERS, RULES);
    assertEquals("test", folded.fold().periodId());
    assertEquals(PARAMETERS, folded.fold().candidateParameters());
    assertTrue(
        grid.houseScales().contains(folded.fold().referenceParameters().houseScale()),
        () -> "Reference house scale: " + folded.fold().referenceParameters());
    assertEquals(folded.fold().trainingPolls(), folded.fold().trainingObservations());
    assertTrue(folded.fold().scoredPolls() > 0);
    assertEquals(0, folded.fold().excludedHeldOutPolls());
    assertEquals(64, folded.fold().trainingRowsSha256().length());
    assertNotEquals(folded.fold().trainingRowsSha256(), folded.fold().scoredRowsSha256());
    // The three candidates score the same polls, so their means are comparable at all.
    assertEquals(
        List.of(
            DevelopmentDiagnostics.CANDIDATE,
            DevelopmentDiagnostics.REFERENCE,
            DevelopmentDiagnostics.BASELINE),
        List.copyOf(folded.fold().meanLogScore().keySet()));
    for (java.util.List<se.swedishpolls.DevelopmentDiagnostics.Scored> scored :
        folded.scored().values()) assertEquals(folded.fold().scoredPolls(), scored.size());
    for (java.lang.Double score : folded.fold().meanLogScore().values())
      assertTrue(Double.isFinite(score));
    // Three different predictive distributions of the same polls, so three different scores.
    assertEquals(3, folded.fold().meanLogScore().values().stream().distinct().count());
    // A fold whose horizon contains no publication scores nothing rather than an empty mean.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DevelopmentDiagnostics.fold(
                period(),
                polls,
                ELECTIONS,
                new DevelopmentTuning.Fold(START.plusDays(300), START.plusDays(335)),
                grid,
                PARAMETERS,
                RULES));
  }

  @Test
  void coverageAndMisfitAreCountedPerPartyInstituteAndFieldworkBand() {
    final java.lang.StringBuilder rows = new StringBuilder();
    for (int week = 0; week < 24; week++) {
      rows.append(row("Novus", week * 7, week * 7, week * 7 + 1, "20"));
      rows.append(row("Sifo", week * 7 + 1, week * 7 + 8, week * 7 + 9, "22"));
      rows.append(row("Skop", week * 7 + 2, week * 7 + 18, week * 7 + 19, "21"));
    }
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        PollCsv.parse(PollCsvFixtures.csv(rows.toString()));
    final se.swedishpolls.DevelopmentDiagnostics.Folded folded =
        DevelopmentDiagnostics.fold(
            period(),
            polls,
            ELECTIONS,
            new DevelopmentTuning.Fold(START.plusDays(120), START.plusDays(155)),
            new DevelopmentTuning.Grid(List.of(0.0003), List.of(0.05), List.of(1.5)),
            PARAMETERS,
            RULES);
    final java.util.List<java.lang.String> components = batch(rows.toString()).components();
    final java.util.List<se.swedishpolls.DevelopmentDiagnostics.Misfit> misfit =
        DevelopmentDiagnostics.misfit("test", components, folded.scored(), RULES);
    final java.util.List<se.swedishpolls.DevelopmentDiagnostics.Misfit> pooled =
        misfit.stream().filter(row -> row.scope().equals("all")).toList();
    assertEquals(3, pooled.size());
    for (se.swedishpolls.DevelopmentDiagnostics.Misfit row : pooled) {
      assertEquals(row.polls() * components.size(), row.cases());
      assertTrue(row.coverage95() >= row.coverage50());
      assertTrue(row.coverage95() >= 0 && row.coverage95() <= 1);
    }
    final java.util.List<java.lang.String> parties =
        misfit.stream()
            .filter(row -> row.scope().equals("party"))
            .map(DevelopmentDiagnostics.Misfit::name)
            .toList();
    assertEquals(components, parties);
    final java.util.List<java.lang.String> institutes =
        misfit.stream()
            .filter(row -> row.scope().equals("institute"))
            .map(DevelopmentDiagnostics.Misfit::name)
            .toList();
    assertEquals(List.of("Novus", "Sifo", "Skop"), institutes);
    // One-day, nine-day and nineteen-day fieldwork windows land in the three registered bands.
    final java.util.List<java.lang.String> bands =
        misfit.stream()
            .filter(row -> row.scope().equals("fieldwork_days"))
            .map(DevelopmentDiagnostics.Misfit::name)
            .toList();
    assertEquals(List.of("1-7", "8-14", "15+"), bands);
    for (se.swedishpolls.DevelopmentDiagnostics.Misfit row : misfit)
      assertTrue(
          Double.isFinite(row.meanStandardizedResidual())
              && row.rootMeanSquareStandardizedResidual() >= 0);
  }

  @Test
  void pollCountCenteringMovesTheReferenceWithoutMovingTheFit() {
    // Novus polls three times as often as Sifo and sits above it, so weighting by poll count
    // moves the level the ensemble is centered on.
    final java.lang.StringBuilder rows = new StringBuilder();
    for (int week = 0; week < 30; week++) {
      rows.append(row("Novus", week * 7, week * 7 + 1, week * 7 + 2, "26"));
      rows.append(row("Novus", week * 7 + 2, week * 7 + 3, week * 7 + 4, "26"));
      rows.append(row("Novus", week * 7 + 4, week * 7 + 5, week * 7 + 6, "26"));
      if (week % 3 == 0) rows.append(row("Sifo", week * 7 + 6, week * 7 + 6, week * 7 + 7, "16"));
    }
    final se.swedishpolls.PollObservations.Batch batch = batch(rows.toString());
    assertEquals(
        DailyStateSpace.fit(batch, ELECTIONS, PARAMETERS).logLikelihood(),
        DailyStateSpace.fit(batch, ELECTIONS, PARAMETERS, DailyStateSpace.Centering.POLL_COUNT)
            .logLikelihood(),
        0,
        "Centering changes the reference, never the fit");
    final se.swedishpolls.DailyStateSpace.Fit equal =
        DailyStateSpace.fit(batch, ELECTIONS, PARAMETERS);
    final se.swedishpolls.DailyStateSpace.Fit counted =
        DailyStateSpace.fit(batch, ELECTIONS, PARAMETERS, DailyStateSpace.Centering.POLL_COUNT);
    assertEquals(List.of(0.5, 0.5), equal.cycles().getFirst().weights());
    // Ninety Novus polls against ten from Sifo.
    assertEquals(0.9, counted.cycles().getFirst().weights().getFirst(), 1e-12);
    assertEquals(0.1, counted.cycles().getFirst().weights().getLast(), 1e-12);
    final java.util.Map<java.lang.String, java.lang.Double> equalShares =
        PollObservations.shares(batch, equal.days().getLast().smoothedMean());
    final java.util.Map<java.lang.String, java.lang.Double> countedShares =
        PollObservations.shares(batch, counted.days().getLast().smoothedMean());
    assertTrue(
        countedShares.get("M") > equalShares.get("M") + 0.5,
        () -> "Poll-count M " + countedShares.get("M") + " against equal " + equalShares.get("M"));
  }
}
