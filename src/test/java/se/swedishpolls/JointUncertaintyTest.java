package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class JointUncertaintyTest {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final List<LocalDate> ELECTIONS =
      List.of(LocalDate.of(2014, 9, 14), LocalDate.of(2018, 9, 9));
  private static final DailyStateSpace.Parameters POINT =
      new DailyStateSpace.Parameters(1e-4, 0.1, 1.5);

  /**
   * Few enough draws to keep the unit checks quick; the registered count is an integration cost.
   */
  private static final JointUncertainty.Rules RULES =
      new JointUncertainty.Rules(20260908, 400, List.of(0.5, 0.95), 4);

  private static CoverageValidation.Rules coverage() {
    return CoverageValidation.rules(PROTOCOL);
  }

  private static JointUncertainty.Estimated estimate(
      Roster.CoveragePeriod period, List<PollCsv.Poll> polls, JointUncertainty.Rules rules) {
    return JointUncertainty.estimate(period, polls, ELECTIONS, POINT, coverage(), rules);
  }

  @Test
  void readsTheRegisteredRulesAndRejectsInadmissibleOnes() {
    var rules = JointUncertainty.rules(PROTOCOL);
    assertEquals(20260908, rules.seed());
    assertEquals(10000, rules.draws());
    assertEquals(List.of(0.5, 0.95), rules.intervalLevels());
    assertEquals(8, rules.precisionRepeats());
    // The repeated-seed study starts at the registered seed and walks its successors.
    assertEquals(20260908, JointUncertainty.precisionSeeds(rules).getFirst());
    assertEquals(8, JointUncertainty.precisionSeeds(rules).size());
    assertEquals(20260915, JointUncertainty.precisionSeeds(rules).getLast());

    for (var inadmissible :
        List.<org.junit.jupiter.api.function.Executable>of(
            () -> new JointUncertainty.Rules(1, 1, List.of(0.95), 2),
            () -> new JointUncertainty.Rules(1, 10, List.of(0.95), 1),
            () -> new JointUncertainty.Rules(1, 10, List.of(), 2),
            () -> new JointUncertainty.Rules(1, 10, List.of(0.0), 2),
            () -> new JointUncertainty.Rules(1, 10, List.of(1.0), 2),
            () -> new JointUncertainty.Rules(1, 10, List.of(Double.NaN), 2),
            () -> new JointUncertainty.Rules(1, 10, List.of(0.95, 0.5), 2),
            () -> new JointUncertainty.Rules(1, 10, List.of(0.5, 0.5), 2)))
      assertThrows(IllegalArgumentException.class, inadmissible);
    assertThrows(
        IllegalArgumentException.class,
        () -> JointUncertainty.rules(Path.of("docs", "validation", "tuning.json")));
  }

  @Test
  void transformsEveryDrawBeforeAveragingSupport() {
    var period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    var polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    var estimated = estimate(period, polls, RULES);

    var days = estimated.segments().getFirst().days();
    assertEquals(148, days.size());
    for (var day : days) {
      assertEquals(9, day.components().size());
      // Each draw is a composition, so both the drawn mean and the state mean close to 100.
      assertEquals(
          100, day.components().stream().mapToDouble(JointUncertainty.Component::mean).sum(), 1e-9);
      assertEquals(
          100,
          day.components().stream().mapToDouble(JointUncertainty.Component::stateMean).sum(),
          1e-9);
    }
    // The transform is nonlinear, so averaging transformed draws is not transforming the mean.
    // Publishing the state mean would report a different composition than the draws support.
    var headline = days.getLast();
    assertTrue(
        headline.components().stream().anyMatch(summary -> summary.mean() != summary.stateMean()),
        headline::toString);
    // The state mean is exactly the day the daily history publishes, so the two agree on what the
    // fitted state is and differ only in how support is averaged.
    var history = EstimateHistory.estimate(period, polls, ELECTIONS, POINT, coverage());
    var published = history.segments().getFirst().days().getLast();
    assertEquals(headline.date(), published.date());
    for (var summary : headline.components())
      assertEquals(published.shares().get(summary.component()), summary.stateMean());
  }

  @Test
  void intervalsNestByLevelAndBracketTheDrawnMean() {
    var period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    var polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    var day = estimate(period, polls, RULES).segments().getFirst().days().getLast();

    for (var summary : day.components()) {
      assertEquals(List.of(0.5, 0.95), summary.intervals().stream().map(i -> i.level()).toList());
      var half = summary.intervals().getFirst();
      var wide = summary.intervals().getLast();
      assertTrue(wide.lower() < half.lower(), summary::toString);
      assertTrue(half.upper() < wide.upper(), summary::toString);
      assertTrue(wide.lower() < summary.mean() && summary.mean() < wide.upper(), summary::toString);
      // A marginal interval is read off one component's own draws and is never a summed endpoint.
      assertTrue(wide.lower() > 0, summary::toString);
    }
  }

  @Test
  void reproducesEveryDrawAtTheSameSeedAndMovesAtAnother() {
    var period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    var polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    var first = estimate(period, polls, RULES);
    var again = estimate(period, polls, RULES);
    var other = estimate(period, polls, RULES.withSeed(RULES.seed() + 1));

    var reproduced = JointUncertainty.reproduced(first.finalDraws(), again.finalDraws());
    assertTrue(reproduced.exact(), reproduced::toString);
    assertEquals(0, reproduced.maxAbsoluteDifference());
    assertEquals(RULES.draws() * 9, reproduced.comparedValues());
    // Every summary is a function of those draws, so the whole day comes back unchanged.
    assertEquals(first.segments().getFirst().days(), again.segments().getFirst().days());
    // A different seed draws a different sample; only its summaries are meant to agree closely.
    assertNotEquals(other.segments().getFirst().days(), first.segments().getFirst().days());
    assertThrows(
        IllegalArgumentException.class,
        () -> JointUncertainty.reproduced(first.finalDraws(), other.finalDraws()));
  }

  @Test
  void aDayDrawsFromItsOwnStreamSoItDoesNotDependOnTheRunAroundIt() {
    var period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    var polls =
        new ArrayList<>(
            CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 3, 2), "1"));
    polls.addAll(
        CoverageValidationTest.weekly(LocalDate.of(2015, 6, 1), LocalDate.of(2015, 8, 3), "1"));

    var estimated = estimate(period, polls, RULES);
    var shorter = estimate(period, polls.subList(0, 9), RULES);

    // The unsupported run splits the period into two separately fitted segments, and the retained
    // draws belong to the last day of the last one.
    assertEquals(2, estimated.segments().size());
    assertEquals(LocalDate.of(2015, 8, 3), estimated.finalDraws().date());
    assertEquals(LocalDate.of(2015, 8, 3), estimated.segments().getLast().to());
    assertEquals(RULES.draws(), estimated.finalDraws().count());
    assertEquals(9, estimated.finalDraws().components().size());
    for (int draw = 0; draw < estimated.finalDraws().count(); draw++) {
      double total = 0;
      for (int component = 0; component < 9; component++)
        total += estimated.finalDraws().shares().get(draw, component);
      assertEquals(100, total, 1e-9);
    }
    // The first run of support fits the same observations either way, so a run that goes on to
    // draw a second segment must return the first one unchanged. A shared draw stream would make
    // the same day depend on how many days the run around it computed.
    assertEquals(1, shorter.segments().size());
    assertEquals(estimated.segments().getFirst().days(), shorter.segments().getFirst().days());
  }

  @Test
  void repeatedSeedsMoveTheEndpointsOnlyByMonteCarloError() {
    var period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    var polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 4, 6), "1");
    var repeats = new ArrayList<JointUncertainty.Estimated>();
    for (long seed : JointUncertainty.precisionSeeds(RULES))
      repeats.add(estimate(period, polls, RULES.withSeed(seed)));

    var precision = JointUncertainty.precision(repeats);

    assertEquals(18, precision.size());
    for (var measured : precision) {
      assertEquals(4, measured.seeds());
      assertEquals(repeats.getFirst().segments().getFirst().days().size(), measured.comparedDays());
      // Four seeds over one component and level: the sample moves, the estimate underneath does
      // not, so nothing here may reach the scale of the estimate itself.
      assertTrue(measured.meanSpreadPoints() > 0, measured::toString);
      assertTrue(measured.lowerSpreadPoints() < 5, measured::toString);
      assertTrue(measured.upperSpreadPoints() < 5, measured::toString);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> JointUncertainty.precision(List.of(repeats.getFirst())));
  }

  @Test
  void recordsTheInputsBasisAndVersionsARerunNeeds() {
    var period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    var candidate =
        CoverageValidationTest.period(
            "candidate", LocalDate.of(2015, 1, 1), LocalDate.of(2015, 6, 1), true, false);
    var polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");
    var fewer =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 5, 25), "1");

    var run = estimate(period, polls, RULES).reproduction();

    assertEquals(RULES.seed(), run.seed());
    assertEquals(RULES.draws(), run.draws());
    assertEquals("eight", run.periodId());
    assertEquals(POINT, run.parameters());
    assertEquals(22, run.inputRows());
    assertEquals(64, run.basisSha256().length());
    assertEquals(64, run.inputRowsSha256().length());
    assertEquals(64, run.implementationSha256().length());
    assertEquals(Runtime.version().toString(), run.javaRuntimeVersion());
    assertNotNull(run.javaVmVersion());
    assertNotNull(run.osArch());
    assertNotNull(run.linearAlgebraVersion());
    // The same inputs digest the same way; a dropped poll and a different roster do not.
    assertEquals(
        run.inputRowsSha256(), estimate(period, polls, RULES).reproduction().inputRowsSha256());
    assertNotEquals(
        run.inputRowsSha256(), estimate(period, fewer, RULES).reproduction().inputRowsSha256());
    var other = estimate(candidate, polls, RULES).reproduction();
    assertNotEquals(run.basisSha256(), other.basisSha256());
    assertEquals(10, other.components().size());
    assertEquals(run.implementationSha256(), other.implementationSha256());
  }

  @Test
  void aNonPositiveDefiniteOrUnrepresentableStateStopsTheRunWithoutJitter() {
    var date = LocalDate.of(2015, 3, 2);
    var positiveDefinite = org.ejml.simple.SimpleMatrix.identity(3).scale(4);
    assertEquals(
        2.0, JointUncertainty.cholesky(ModelValues.copyOf(positiveDefinite), "eight", date)[0][0]);

    var singular = positiveDefinite.copy();
    singular.set(2, 2, 0);
    var asymmetric = positiveDefinite.copy();
    asymmetric.set(0, 1, 1);
    var nonfinite = positiveDefinite.copy();
    nonfinite.set(1, 1, Double.NaN);
    // A covariance that cannot be factorized blocks the run: there is no jitter and no clipping.
    for (var rejected : List.of(singular, asymmetric, nonfinite))
      assertThrows(
          IllegalArgumentException.class,
          () -> JointUncertainty.cholesky(ModelValues.copyOf(rejected), "eight", date));

    var batch =
        PollObservations.prepare(
            CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true),
            CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), date, "1"));
    var basis = PollObservations.transposedBasis(batch);
    var shares = new double[9];
    var unrepresentable = new double[8];
    unrepresentable[0] = Double.POSITIVE_INFINITY;
    assertThrows(
        IllegalArgumentException.class,
        () -> PollObservations.close(basis, unrepresentable, shares, "eight"));
  }
}
