package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PredictiveComparisonTest {
  @Test
  void usesPairedTemporalStandardErrorAndStrictBaselineImprovement() {
    // Candidate-reference differences: four -1s followed by four +1s.
    // Bartlett lag-3 long-run variance = 1 + 2*(.75*.625 + .5*.25 + .25*-.125) = 2.125.
    final double[] candidate = {9, 9, 9, 9, 11, 11, 11, 11};
    final double[] baseline = {8, 8, 8, 8, 8, 8, 8, 8};
    final double[] reference = {10, 10, 10, 10, 10, 10, 10, 10};
    final se.swedishpolls.PredictiveComparison.Result result =
        PredictiveComparison.evaluate(candidate, baseline, reference);
    assertEquals(0.5153882032022076, result.pairedStandardError(), 1e-14);
    assertEquals(0, result.referenceDifference());
    assertTrue(result.passes());
    assertFalse(PredictiveComparison.evaluate(candidate, candidate, reference).passes());
    assertFalse(PredictiveComparison.evaluate(baseline, new double[8], reference).passes());
    assertThrows(
        IllegalArgumentException.class,
        () -> PredictiveComparison.evaluate(new double[7], new double[7], new double[7]));
    assertThrows(
        IllegalArgumentException.class,
        () -> PredictiveComparison.evaluate(candidate, new double[9], reference));
    candidate[0] = Double.NaN;
    assertThrows(
        IllegalArgumentException.class,
        () -> PredictiveComparison.evaluate(candidate, baseline, reference));
  }
}
