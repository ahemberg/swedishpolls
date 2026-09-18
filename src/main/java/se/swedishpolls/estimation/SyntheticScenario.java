package se.swedishpolls.estimation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.ejml.simple.SimpleMatrix;

/**
 * The registered scenario of the accepted {@code synthetic-recovery-v1} protocol: its generating
 * model, its fixed observation covariance, its poll calendar and the datasets they produce.
 *
 * <p>Every number here is part of the accepted protocol rather than a tuning choice. The reference
 * composition sets the observation covariance and nothing else: the covariance is built once, from
 * the registered percentages, and never from a generated share or latent state. Observation noise
 * is added in ilr coordinates and inverse-transformed without rounding or zero replacement, so a
 * generated dataset reaches the estimator as the model's own assumptions describe it.
 */
final class SyntheticScenario {
  private SyntheticScenario() {}

  /** Day zero of the synthetic coverage period, its training cutoff and its scoring horizon. */
  static final LocalDate PERIOD_START = LocalDate.of(2016, 1, 1);

  static final int CUTOFF_OFFSET = 179;
  static final int HORIZON_OFFSET = 214;
  static final LocalDate CUTOFF = PERIOD_START.plusDays(CUTOFF_OFFSET);
  static final LocalDate HORIZON = PERIOD_START.plusDays(HORIZON_OFFSET);

  /** The generating priors: the initial state, the daily walk, the institutes and the noise. */
  static final double INITIAL_VARIANCE = 4;

  static final double WALK_VARIANCE = 0.0001;
  static final double HOUSE_SCALE = 0.05;
  static final double NOISE_MULTIPLIER = 1.5;

  /** The generating point the known-parameter control supplies to the filter. */
  static final DailyStateSpace.Parameters TRUTH =
      new DailyStateSpace.Parameters(WALK_VARIANCE, HOUSE_SCALE, NOISE_MULTIPLIER);

  /** The four generating streams of one dataset, in the order they are consumed. */
  static final List<String> GENERATING_STREAMS = List.of("initial", "walk", "houses", "noise");

  /** The five institutes, each keeping one effect throughout the cycle. */
  static final List<String> INSTITUTES = List.of("I0", "I1", "I2", "I3", "I4");

  static final int WEEKS = 28;
  static final int TRAINING_POLLS = 115;
  static final int SCORING_POLLS = 25;

  /** The nominal sample size a row carries. It takes no part in the numerical path. */
  static final BigDecimal SAMPLE_SIZE = BigDecimal.valueOf(1000);

  /**
   * The reference percentages the fixed covariance is built at, in component order. They are not
   * the initial-state mean and never re-enter generation.
   */
  static final double[] REFERENCE = {30, 25, 15, 8, 6, 4, 4, 6, 2};

  private static final double NOMINAL_SIZE = 1000;

  private static final int[] LENGTHS = {1, 10, 21};

  private static final int FIRST_END_OFFSET = 20;

  /** One scheduled poll: its window, the institute that published it and how it is used. */
  record Row(
      int rowNumber,
      String institute,
      int instituteIndex,
      int fromOffset,
      int toOffset,
      boolean training) {
    LocalDate from() {
      return PERIOD_START.plusDays(fromOffset);
    }

    LocalDate to() {
      return PERIOD_START.plusDays(toOffset);
    }

    /** The estimator's own floor-of-half-elapsed-days midpoint. */
    int midpointOffset() {
      return fromOffset + (toOffset - fromOffset) / 2;
    }
  }

  /** One generated dataset: the latent truth it came from and the observations it produced. */
  record Generated(
      List<Row> rows,
      List<double[]> latent,
      List<double[]> instituteEffects,
      List<double[]> ilr,
      List<double[]> shares) {
    Generated {
      rows = List.copyOf(rows);
      latent = List.copyOf(latent);
      instituteEffects = List.copyOf(instituteEffects);
      ilr = List.copyOf(ilr);
      shares = List.copyOf(shares);
    }
  }

  /**
   * The registered poll calendar. Every week each institute publishes one poll on the week's end
   * offset, over a window that is never truncated, and membership follows that publication date.
   */
  static List<Row> schedule() {
    final List<Row> rows = new ArrayList<>();
    for (int week = 0; week < WEEKS; week++)
      for (int institute = 0; institute < INSTITUTES.size(); institute++) {
        final int end = FIRST_END_OFFSET + 7 * week;
        final int length = LENGTHS[(institute + week) % LENGTHS.length];
        rows.add(
            new Row(
                5 * week + institute + 1,
                INSTITUTES.get(institute),
                institute,
                end - length + 1,
                end,
                end <= CUTOFF_OFFSET));
      }
    return List.copyOf(rows);
  }

  /**
   * The fixed observation covariance {@code R = H diag(1/p) H' / 1000} at the reference
   * composition.
   */
  static ModelValues observationCovariance() {
    final double[] proportions = new double[REFERENCE.length];
    for (int c = 0; c < REFERENCE.length; c++) proportions[c] = REFERENCE[c] / 100;
    return ModelValues.owned(
        PollObservations.deltaCovariance(
            basis(), proportions, NOMINAL_SIZE, "the registered reference composition"));
  }

  /** The lower Cholesky factor of {@code m R}, carrying the generating multiplier exactly once. */
  static double[][] noiseFactor() {
    final ModelValues covariance = observationCovariance();
    final int dimension = covariance.getNumRows();
    final double[][] scaled = new double[dimension][dimension];
    for (int r = 0; r < dimension; r++)
      for (int c = 0; c < dimension; c++) scaled[r][c] = NOISE_MULTIPLIER * covariance.get(r, c);
    return WindowFilter.factor(scaled);
  }

  /**
   * One dataset of one convention, drawn from the four generating streams of {@code prefix}. The
   * initial state, the daily innovations, the institute effects and the observation noise are
   * consumed in the registered order, so a rerun of the same stream reproduces the same dataset.
   */
  static Generated generate(String prefix, long masterSeed, WindowFilter.Convention convention) {
    final int dimension = REFERENCE.length - 1;
    final double[][] latent = new double[HORIZON_OFFSET + 1][dimension];
    final RandomGenerator initial = PredictiveScoring.stream(prefix + "|initial", masterSeed);
    final double initialScale = Math.sqrt(INITIAL_VARIANCE);
    for (int i = 0; i < dimension; i++) latent[0][i] = initialScale * initial.nextGaussian();
    // The first day carries no innovation; every later day adds one of variance q per coordinate.
    final RandomGenerator walk = PredictiveScoring.stream(prefix + "|walk", masterSeed);
    final double walkScale = Math.sqrt(WALK_VARIANCE);
    for (int day = 1; day <= HORIZON_OFFSET; day++)
      for (int i = 0; i < dimension; i++)
        latent[day][i] = latent[day - 1][i] + walkScale * walk.nextGaussian();
    final RandomGenerator houses = PredictiveScoring.stream(prefix + "|houses", masterSeed);
    final List<double[]> effects = new ArrayList<>();
    for (int institute = 0; institute < INSTITUTES.size(); institute++) {
      final double[] effect = new double[dimension];
      for (int i = 0; i < dimension; i++) effect[i] = HOUSE_SCALE * houses.nextGaussian();
      effects.add(effect);
    }

    final RandomGenerator noise = PredictiveScoring.stream(prefix + "|noise", masterSeed);
    final double[][] factor = noiseFactor();
    final double[][] transposed = transposedBasis();
    final List<Row> rows = schedule();
    final List<double[]> observations = new ArrayList<>();
    final List<double[]> shares = new ArrayList<>();
    final double[] normal = new double[dimension];
    for (Row row : rows) {
      for (int i = 0; i < dimension; i++) normal[i] = noise.nextGaussian();
      final double[] mean = mean(latent, row, convention);
      final double[] effect = effects.get(row.instituteIndex());
      final double[] observed = new double[dimension];
      for (int r = 0; r < dimension; r++) {
        double value = mean[r] + effect[r];
        for (int c = 0; c <= r; c++) value += factor[r][c] * normal[c];
        observed[r] = value;
      }
      final double[] composition = new double[REFERENCE.length];
      PollObservations.close(transposed, observed, composition, prefix);
      observations.add(observed);
      shares.add(composition);
    }
    return new Generated(rows, List.of(latent), effects, observations, shares);
  }

  /**
   * What one poll observes before its institute effect and its noise: the latent state on the
   * stored midpoint, or the inclusive average of the daily ilr states across its window.
   */
  private static double[] mean(double[][] latent, Row row, WindowFilter.Convention convention) {
    if (convention == WindowFilter.Convention.MIDPOINT) return latent[row.midpointOffset()].clone();
    final double[] mean = new double[latent[0].length];
    for (int day = row.fromOffset(); day <= row.toOffset(); day++)
      for (int i = 0; i < mean.length; i++) mean[i] += latent[day][i];
    final int days = row.toOffset() - row.fromOffset() + 1;
    for (int i = 0; i < mean.length; i++) mean[i] /= days;
    return mean;
  }

  private static SimpleMatrix basis() {
    return PollObservations.helmertBasis(REFERENCE.length);
  }

  /** The Helmert basis transposed once, as {@code H'[component][coordinate]}. */
  private static double[][] transposedBasis() {
    final SimpleMatrix basis = basis();
    final double[][] transposed = new double[REFERENCE.length][REFERENCE.length - 1];
    for (int c = 0; c < REFERENCE.length; c++)
      for (int r = 0; r < REFERENCE.length - 1; r++) transposed[c][r] = basis.get(r, c);
    return transposed;
  }
}
