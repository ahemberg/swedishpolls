package se.swedishpolls.estimation;

import java.util.List;

/**
 * The coverage assessment of the accepted {@code synthetic-recovery-v1} protocol: each dataset
 * contributes one coverage fraction, the fractions are weighted equally, and the Monte Carlo
 * uncertainty of their mean decides what the cell may claim.
 *
 * <p>The 25 scoring polls of a dataset are correlated through one latent process, so they are
 * reduced to a single number before anything is averaged. The confidence interval is adjusted
 * across all 72 primary cells whether or not the estimated stages run, and is approximate
 * large-sample simultaneous confidence rather than a finite-sample guarantee.
 */
final class SyntheticCoverage {
  private SyntheticCoverage() {}

  /** Nine components, two interval levels, two conventions and two parameter stages. */
  static final int PRIMARY_CELLS = 72;

  static final double ALPHA = 0.05;

  /** The two-sided normal critical value of {@code alpha / (2 x 72)}. */
  static final double CRITICAL_VALUE = 3.391763140587952;

  /** The registered nominal interval levels, in percent. */
  static final List<Integer> LEVELS = List.of(95, 50);

  static final int CELLS_PER_STAGE = 18;

  static final String DEMONSTRATED = "demonstrated";
  static final String FAILED = "failed";
  static final String INCONCLUSIVE = "inconclusive";
  static final String INCOMPLETE = "incomplete";

  private static final String NOT_APPLICABLE = "not_applicable";
  private static final String VERIFIED = "verified";
  private static final String UNVERIFIED = "unverified";

  /** The acceptable coverage of one nominal interval level, endpoints included. */
  record Band(double low, double high) {}

  static Band band(int level) {
    if (level == 95) return new Band(0.93, 0.97);
    if (level == 50) return new Band(0.47, 0.53);
    throw new IllegalArgumentException("Unregistered interval level " + level);
  }

  /** One primary result: its estimated coverage, its simulation uncertainty and its verdict. */
  record Cell(
      String component,
      int level,
      int datasets,
      double coverage,
      double variance,
      double standardError,
      double halfWidth,
      double lower,
      double upper,
      long coveredPolls,
      long scoredPolls,
      String zeroVarianceVerification,
      String verdict) {}

  /**
   * One cell from the per-dataset fractions that produced it. An apparent zero-variance cell that
   * would otherwise demonstrate recovery is checked against its coverage counter and the retained
   * draws of the polls behind it; an unverifiable one is incomplete rather than a recovery claim.
   */
  static Cell cell(
      String component,
      int level,
      double[] fractions,
      long coveredPolls,
      long scoredPolls,
      boolean drawsRetained) {
    final int datasets = fractions.length;
    if (datasets < 2)
      throw new IllegalArgumentException("A sample variance needs at least two datasets");
    double total = 0;
    for (double fraction : fractions) total += fraction;
    final double coverage = total / datasets;
    double squares = 0;
    for (double fraction : fractions) squares += (fraction - coverage) * (fraction - coverage);
    final double variance = squares / (datasets - 1);
    final double standardError = Math.sqrt(variance / datasets);
    final double halfWidth = CRITICAL_VALUE * standardError;
    final double lower = Math.max(0, coverage - halfWidth);
    final double upper = Math.min(1, coverage + halfWidth);
    final Band band = band(level);
    final String verdict;
    if (lower >= band.low() && upper <= band.high()) verdict = DEMONSTRATED;
    else if (upper < band.low() || lower > band.high()) verdict = FAILED;
    else verdict = INCONCLUSIVE;
    String verification = NOT_APPLICABLE;
    String reported = verdict;
    if (variance == 0 && verdict.equals(DEMONSTRATED)) {
      final boolean counted =
          scoredPolls > 0 && Math.abs(coverage - (double) coveredPolls / scoredPolls) <= 1e-12;
      verification = counted && drawsRetained ? VERIFIED : UNVERIFIED;
      if (!verification.equals(VERIFIED)) reported = INCOMPLETE;
    }
    return new Cell(
        component,
        level,
        datasets,
        coverage,
        variance,
        standardError,
        halfWidth,
        lower,
        upper,
        coveredPolls,
        scoredPolls,
        verification,
        reported);
  }

  /**
   * One method and stage: recovery only when every one of its 18 cells demonstrates it. Missing or
   * numerically failed evidence leaves the stage incomplete while its established failures stay in
   * the cells beside it.
   */
  static String stageVerdict(List<Cell> cells) {
    if (cells.size() != CELLS_PER_STAGE) return INCOMPLETE;
    if (cells.stream().anyMatch(cell -> cell.verdict().equals(INCOMPLETE))) return INCOMPLETE;
    if (cells.stream().anyMatch(cell -> cell.verdict().equals(FAILED))) return FAILED;
    if (cells.stream().anyMatch(cell -> cell.verdict().equals(INCONCLUSIVE))) return INCONCLUSIVE;
    return DEMONSTRATED;
  }

  /** Both conventions of one stage, under the same precedence a single stage uses. */
  static String combined(List<String> verdicts) {
    if (verdicts.isEmpty() || verdicts.stream().anyMatch(verdict -> verdict.equals(INCOMPLETE)))
      return INCOMPLETE;
    if (verdicts.stream().anyMatch(verdict -> verdict.equals(FAILED))) return FAILED;
    if (verdicts.stream().anyMatch(verdict -> verdict.equals(INCONCLUSIVE))) return INCONCLUSIVE;
    return DEMONSTRATED;
  }
}
