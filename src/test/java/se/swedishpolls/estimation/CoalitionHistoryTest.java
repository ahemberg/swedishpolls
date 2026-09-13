package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoalitionHistoryTest {
  @Test
  void varyingSumsInterpolateQuantilesAndOnlyMissingConstituentsAreUnavailable() {
    final JointUncertainty.Draws draws =
        new JointUncertainty.Draws(
            "fixture",
            LocalDate.of(2020, 1, 1),
            List.of("S", "M", "OTHER"),
            1,
            ModelValues.copyOf(
                new org.ejml.simple.SimpleMatrix(
                    new double[][] {{10, 20, 70}, {20, 30, 50}, {30, 10, 60}})));
    final List<CoalitionHistory.Summary> subsets = CoalitionHistory.aggregate(draws);
    assertEquals(new CoalitionHistory.Summary(40, 30.5, 49.5), subsets.get(2));
    assertEquals("party_unavailable", subsets.get(3).availability());
    assertNull(subsets.get(3).mean());
    assertNull(subsets.get(3).lower());
    assertNull(subsets.get(3).upper());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void dailySingletonsAndRetainedFinalDrawsUseThePartyEnsemble(boolean individualFi) {
    final se.swedishpolls.source.Roster.CoveragePeriod period =
        CoverageValidationTest.period(
            "fixture", LocalDate.of(2015, 1, 1), null, individualFi, true);
    final List<se.swedishpolls.source.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 1, 19), "1");
    final CoverageValidation.Rules coverage =
        CoverageValidation.rules(java.nio.file.Path.of("docs/validation/protocol.json"));
    final DailyStateSpace.Parameters parameters = new DailyStateSpace.Parameters(1e-4, 0.1, 1.5);
    final JointUncertainty.Rules rules =
        new JointUncertainty.Rules(20260908, 100, List.of(0.95), 2);
    final EstimateHistory.Fitted fitted =
        EstimateHistory.fitted(period, polls, List.of(), parameters, coverage);
    final CoalitionHistory.Estimated result =
        CoalitionHistory.estimate(fitted, period, coverage, rules);
    final EstimateHistory.Estimated expected =
        EstimateHistory.estimate(fitted, period, coverage, rules);
    assertEquals(expected, result.history());
    for (int day = 0; day < result.days().size(); day++) {
      for (int party = 0; party < CoalitionHistory.ROSTER.size(); party++) {
        final EstimateHistory.Estimate estimate =
            expected
                .segments()
                .getFirst()
                .days()
                .get(day)
                .components()
                .get(CoalitionHistory.ROSTER.get(party));
        final CoalitionHistory.Summary singleton =
            result.days().get(day).subsets().get((1 << party) - 1);
        assertEquals(estimate.mean(), singleton.mean());
        assertEquals(estimate.intervals().getFirst().lower(), singleton.lower());
        assertEquals(estimate.intervals().getFirst().upper(), singleton.upper());
      }
    }
    final ComparableRemainder.Estimated remainder =
        ComparableRemainder.estimate(fitted, period, List.of(), coverage, rules);
    for (int index = 0; index < result.days().size(); index++) {
      final List<CoalitionHistory.Summary> subsets = result.days().get(index).subsets();
      final double outside = remainder.segments().getFirst().days().get(index).mean();
      assertEquals(
          100,
          subsets.getLast().mean() + outside,
          1e-10,
          "FI enters the comparable remainder exactly once under either roster");
      for (int mask = 1; mask < 256; mask++) {
        double sum = 0;
        for (int bit = 0; bit < 8; bit++) {
          if ((mask & (1 << bit)) != 0) sum += subsets.get((1 << bit) - 1).mean();
        }
        assertEquals(sum, subsets.get(mask - 1).mean(), 1e-10);
      }
    }
    assertTrue(
        JointUncertainty.reproduced(
                JointUncertainty.finalDay(fitted, period, parameters, rules).draws(),
                result.finalDraws())
            .exact());
  }

  @Test
  void aFixedJointSumHasNoBandDespiteWideMarginalBands() {
    final JointUncertainty.Draws draws =
        new JointUncertainty.Draws(
            "fixture",
            LocalDate.of(2020, 1, 1),
            List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER"),
            1,
            ModelValues.copyOf(
                new org.ejml.simple.SimpleMatrix(
                    new double[][] {
                      {10, 40, 5, 5, 5, 5, 5, 5, 20},
                      {40, 10, 5, 5, 5, 5, 5, 5, 20}
                    })));
    final List<CoalitionHistory.Summary> subsets = CoalitionHistory.aggregate(draws);
    assertEquals(255, subsets.size());
    assertEquals(new CoalitionHistory.Summary(50, 50, 50), subsets.get(2));
    assertEquals(10.75, subsets.getFirst().lower(), 1e-12);
    assertEquals(39.25, subsets.getFirst().upper(), 1e-12);
    for (final CoalitionHistory.Summary subset : subsets) {
      assertTrue(Double.isFinite(subset.mean()));
      assertTrue(subset.lower() >= 0 && subset.upper() <= 100);
      assertTrue(subset.lower() <= subset.upper());
    }
  }
}
