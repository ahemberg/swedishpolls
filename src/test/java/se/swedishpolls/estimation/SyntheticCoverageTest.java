package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticCoverageTest {
  private static final int DATASETS = 400;

  @Test
  void reducesDatasetFractionsWithTheRegisteredVarianceStandardErrorAndCriticalValue() {
    final double[] fractions = new double[DATASETS];
    for (int dataset = 0; dataset < DATASETS; dataset++)
      fractions[dataset] = dataset % 4 == 0 ? 0.88 : 0.96;
    final SyntheticCoverage.Cell cell = SyntheticCoverage.cell("S", 95, fractions, 940, 1000, true);

    // Independently: the mean of the fractions, their sample variance with denominator N-1, the
    // standard error of that mean, and an interval of 3.391763140587952 standard errors.
    double total = 0;
    for (double fraction : fractions) total += fraction;
    final double mean = total / DATASETS;
    double squares = 0;
    for (double fraction : fractions) squares += (fraction - mean) * (fraction - mean);
    final double variance = squares / (DATASETS - 1);
    final double standardError = Math.sqrt(variance / DATASETS);
    assertEquals(mean, cell.coverage(), 1e-12);
    assertEquals(variance, cell.variance(), 1e-12);
    assertEquals(standardError, cell.standardError(), 1e-12);
    assertEquals(3.391763140587952 * standardError, cell.halfWidth(), 1e-12);
    assertEquals(mean - cell.halfWidth(), cell.lower(), 1e-12);
    assertEquals(mean + cell.halfWidth(), cell.upper(), 1e-12);
    assertEquals(DATASETS, cell.datasets());

    // The 25 polls of a dataset are correlated, so the reported precision is not the per-poll
    // binomial precision of 10,000 independent polls.
    final double binomial = Math.sqrt(mean * (1 - mean) / (DATASETS * 25.0));
    assertNotEquals(binomial, cell.standardError(), 1e-5);
  }

  @Test
  void clipsAnIntervalToTheUnitRangeWithoutWideningTheVerdict() {
    final double[] fractions = new double[DATASETS];
    for (int dataset = 0; dataset < DATASETS; dataset++)
      fractions[dataset] = dataset % 40 == 0 ? 0.6 : 1;
    final SyntheticCoverage.Cell cell = SyntheticCoverage.cell("M", 95, fractions, 940, 1000, true);
    assertEquals(1, cell.upper());
    assertTrue(cell.lower() > 0);
    assertEquals(SyntheticCoverage.FAILED, cell.verdict());
  }

  @Test
  void demonstratesRecoveryOnlyWhenTheWholeIntervalLiesInsideTheBandEndpointsIncluded() {
    assertEquals(SyntheticCoverage.DEMONSTRATED, verdict(95, spread(0.95, 0.0002, DATASETS)));
    assertEquals(SyntheticCoverage.FAILED, verdict(95, spread(0.9, 0.0002, DATASETS)));
    assertEquals(SyntheticCoverage.FAILED, verdict(95, spread(0.99, 0.0002, DATASETS)));
    // An interval that reaches across an endpoint is neither inside nor wholly outside.
    assertEquals(SyntheticCoverage.INCONCLUSIVE, verdict(95, spread(0.93, 0.0002, DATASETS)));
    assertEquals(SyntheticCoverage.INCONCLUSIVE, verdict(95, spread(0.97, 0.0002, DATASETS)));
    // Too few datasets to separate the mean from the band, whatever the mean is.
    assertEquals(SyntheticCoverage.INCONCLUSIVE, verdict(95, spread(0.9, 0.1, 10)));

    assertEquals(SyntheticCoverage.DEMONSTRATED, verdict(50, spread(0.5, 0.0002, DATASETS)));
    assertEquals(SyntheticCoverage.FAILED, verdict(50, spread(0.44, 0.0002, DATASETS)));
    assertEquals(SyntheticCoverage.FAILED, verdict(50, spread(0.56, 0.0002, DATASETS)));
    assertEquals(SyntheticCoverage.INCONCLUSIVE, verdict(50, spread(0.53, 0.0002, DATASETS)));

    // An interval of zero width sitting exactly on an endpoint is inside the band.
    assertEquals(
        SyntheticCoverage.DEMONSTRATED,
        SyntheticCoverage.cell("SD", 95, constant(0.97), 194, 200, true).verdict());
    assertEquals(
        SyntheticCoverage.DEMONSTRATED,
        SyntheticCoverage.cell("SD", 50, constant(0.47), 94, 200, true).verdict());
  }

  @Test
  void refusesToClaimRecoveryForAZeroVarianceCellItCannotCheck() {
    final SyntheticCoverage.Cell verified =
        SyntheticCoverage.cell("V", 95, constant(0.96), 48, 50, true);
    assertEquals(SyntheticCoverage.DEMONSTRATED, verified.verdict());
    assertEquals("verified", verified.zeroVarianceVerification());

    // A counter that disagrees with the fractions, or polls whose draws were never retained,
    // leaves the cell incomplete instead of a recovery claim.
    final SyntheticCoverage.Cell miscounted =
        SyntheticCoverage.cell("V", 95, constant(0.96), 46, 50, true);
    assertEquals(SyntheticCoverage.INCOMPLETE, miscounted.verdict());
    assertEquals("unverified", miscounted.zeroVarianceVerification());

    final SyntheticCoverage.Cell unretained =
        SyntheticCoverage.cell("V", 95, constant(0.96), 48, 50, false);
    assertEquals(SyntheticCoverage.INCOMPLETE, unretained.verdict());
    assertEquals("unverified", unretained.zeroVarianceVerification());

    // A zero-variance cell outside the band is an ordinary failure and needs no verification.
    final SyntheticCoverage.Cell failed =
        SyntheticCoverage.cell("V", 95, constant(0.6), 30, 50, false);
    assertEquals(SyntheticCoverage.FAILED, failed.verdict());
    assertEquals("not_applicable", failed.zeroVarianceVerification());
  }

  @Test
  void requiresAllEighteenCellsOfAStageAndKeepsFailuresAheadOfInconclusiveness() {
    assertEquals(SyntheticCoverage.DEMONSTRATED, stage(SyntheticCoverage.DEMONSTRATED));
    assertEquals(
        SyntheticCoverage.INCONCLUSIVE,
        stage(SyntheticCoverage.DEMONSTRATED, SyntheticCoverage.INCONCLUSIVE));
    assertEquals(
        SyntheticCoverage.FAILED,
        stage(
            SyntheticCoverage.DEMONSTRATED,
            SyntheticCoverage.INCONCLUSIVE,
            SyntheticCoverage.FAILED));
    assertEquals(
        SyntheticCoverage.INCOMPLETE,
        stage(
            SyntheticCoverage.DEMONSTRATED,
            SyntheticCoverage.FAILED,
            SyntheticCoverage.INCOMPLETE));
    assertEquals(SyntheticCoverage.INCOMPLETE, SyntheticCoverage.stageVerdict(List.of()));

    assertEquals(
        SyntheticCoverage.DEMONSTRATED,
        SyntheticCoverage.combined(
            List.of(SyntheticCoverage.DEMONSTRATED, SyntheticCoverage.DEMONSTRATED)));
    assertEquals(
        SyntheticCoverage.INCOMPLETE,
        SyntheticCoverage.combined(
            List.of(SyntheticCoverage.FAILED, SyntheticCoverage.INCOMPLETE)));
    assertEquals(
        SyntheticCoverage.FAILED,
        SyntheticCoverage.combined(
            List.of(SyntheticCoverage.FAILED, SyntheticCoverage.DEMONSTRATED)));
    assertEquals(SyntheticCoverage.INCOMPLETE, SyntheticCoverage.combined(List.of()));
  }

  /** A stage of 18 cells whose verdicts start with the ones named here. */
  private static String stage(String... verdicts) {
    final List<SyntheticCoverage.Cell> cells = new ArrayList<>();
    for (int cell = 0; cell < SyntheticCoverage.CELLS_PER_STAGE; cell++)
      cells.add(
          new SyntheticCoverage.Cell(
              "S",
              95,
              DATASETS,
              0.95,
              0,
              0,
              0,
              0.95,
              0.95,
              0,
              0,
              "not_applicable",
              cell < verdicts.length ? verdicts[cell] : verdicts[0]));
    return SyntheticCoverage.stageVerdict(cells);
  }

  private static String verdict(int level, double[] fractions) {
    return SyntheticCoverage.cell("S", level, fractions, 0, 0, true).verdict();
  }

  /** Fractions with the given mean, half of them above it and half below. */
  private static double[] spread(double mean, double spread, int datasets) {
    final double[] fractions = new double[datasets];
    for (int dataset = 0; dataset < datasets; dataset++)
      fractions[dataset] = mean + (dataset % 2 == 0 ? spread : -spread);
    return fractions;
  }

  /**
   * Two identical fractions: the sample variance is exactly zero and the mean is exactly the
   * fraction, so a cell can sit exactly on a band endpoint.
   */
  private static double[] constant(double fraction) {
    return new double[] {fraction, fraction};
  }
}
