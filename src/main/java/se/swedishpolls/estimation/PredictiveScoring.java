package se.swedishpolls.estimation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * The predictive scorer shared by the development folds and the synthetic recovery workflow: the
 * joint log density of one observation under its own predictive distribution, and the marginal
 * composition summaries read off joint draws of that same distribution.
 *
 * <p>A marginal share is not a coordinate of the Gaussian, so the summaries come from draws
 * transformed one at a time rather than from the coordinates themselves. Both callers score through
 * here, so neither measures a procedure of its own.
 */
final class PredictiveScoring {
  private PredictiveScoring() {}

  /** The same legacy generator the joint draws use, so a rerun reproduces every interval. */
  private static final RandomGeneratorFactory<RandomGenerator> RANDOM_FACTORY =
      RandomGeneratorFactory.of("Random");

  /** The draw encoding the retained hash is taken over. */
  static final String DRAW_ENCODING =
      "big-endian IEEE-754 binary64; draw-major, then component order";

  /** One component's predictive summary and the interval outcomes read from the same draws. */
  record Component(
      String component,
      double observed,
      double mean,
      double variance,
      double lower95,
      double upper95,
      double lower50,
      double upper50,
      double standardizedResidual,
      boolean covered95,
      boolean covered50) {}

  /** One scored observation: its joint density, whitened residual and component summaries. */
  record Summary(
      String stream,
      long seed,
      int draws,
      int dimension,
      double logScore,
      List<Double> whitened,
      List<Component> components,
      String drawsSha256) {
    Summary {
      whitened = List.copyOf(whitened);
      components = List.copyOf(components);
    }

    @Override
    public List<Double> whitened() {
      return List.copyOf(whitened);
    }

    @Override
    public List<Component> components() {
      return List.copyOf(components);
    }
  }

  /**
   * Scores one observation against a predictive mean and covariance. The draws are hashed in the
   * registered encoding as they are produced, so a caller can retain the hash without retaining the
   * arrays. A caller that does archive them reads {@code retainDraws} synchronously: the summaries
   * sort each component in place once it returns.
   */
  static Summary score(
      PollObservations.Batch batch,
      PollObservations.Observation observation,
      ModelValues mean,
      ModelValues covariance,
      int draws,
      long seed,
      String stream,
      BiConsumer<String, double[][]> retainDraws) {
    if (draws < 2) throw new IllegalArgumentException("Coverage needs at least two draws");
    final int dimension = mean.getNumRows();
    final double[][] matrix = new double[dimension][dimension];
    for (int r = 0; r < dimension; r++)
      for (int c = 0; c < dimension; c++) matrix[r][c] = covariance.get(r, c);
    final double[][] factor = WindowFilter.factor(matrix);
    final double[] innovation = new double[dimension];
    for (int i = 0; i < dimension; i++) innovation[i] = observation.ilr().get(i) - mean.get(i);
    final double[] solved = WindowFilter.solve(factor, innovation);
    double quadratic = 0;
    for (int i = 0; i < dimension; i++) quadratic += innovation[i] * solved[i];
    final double logScore =
        -0.5
            * (dimension * Math.log(2 * Math.PI) + WindowFilter.logDeterminant(factor) + quadratic);
    if (!Double.isFinite(logScore))
      throw new IllegalArgumentException("Nonfinite joint predictive log score");
    final List<Double> whitened = new ArrayList<>(dimension);
    for (int r = 0; r < dimension; r++) {
      double value = innovation[r];
      for (int c = 0; c < r; c++) value -= factor[r][c] * whitened.get(c);
      whitened.add(value / factor[r][r]);
    }
    final double[][] basis = PollObservations.transposedBasis(batch);
    final int components = batch.components().size();
    final double[][] drawn = new double[components][draws];
    final long streamSeed = streamSeed(stream, seed);
    final RandomGenerator random = stream(stream, seed);
    final MessageDigest digest = sha256();
    final byte[] encoded = new byte[components * Double.BYTES];
    final ByteBuffer buffer = ByteBuffer.wrap(encoded);
    final double[] normal = new double[dimension];
    final double[] state = new double[dimension];
    final double[] shares = new double[components];
    for (int draw = 0; draw < draws; draw++) {
      for (int i = 0; i < dimension; i++) normal[i] = random.nextGaussian();
      for (int i = 0; i < dimension; i++) {
        double value = mean.get(i);
        for (int j = 0; j <= i; j++) value += factor[i][j] * normal[j];
        state[i] = value;
      }
      PollObservations.close(basis, state, shares, batch.period().id());
      buffer.clear();
      for (int component = 0; component < components; component++) {
        drawn[component][draw] = shares[component];
        buffer.putDouble(shares[component]);
      }
      digest.update(encoded);
    }
    retainDraws.accept(stream, drawn);
    final Map<String, Double> observed = PollObservations.shares(batch, observation.ilr());
    final List<Component> summaries = new ArrayList<>(components);
    for (int component = 0; component < components; component++) {
      final double[] column = drawn[component];
      double total = 0;
      for (double value : column) total += value;
      final double drawnMean = total / column.length;
      double variance = 0;
      for (double value : column) variance += (value - drawnMean) * (value - drawnMean);
      variance /= column.length - 1;
      Arrays.sort(column);
      final double share = observed.get(batch.components().get(component));
      if (!Double.isFinite(variance) || variance <= 0)
        throw new IllegalArgumentException("Invalid predictive composition variance");
      summaries.add(
          new Component(
              batch.components().get(component),
              share,
              drawnMean,
              variance,
              JointUncertainty.quantile(column, (1 - 0.95) / 2),
              JointUncertainty.quantile(column, (1 + 0.95) / 2),
              JointUncertainty.quantile(column, 0.25),
              JointUncertainty.quantile(column, 0.75),
              (share - drawnMean) / Math.sqrt(variance),
              inside(column, share, 0.95),
              inside(column, share, 0.5)));
    }
    return new Summary(
        stream,
        streamSeed,
        draws,
        dimension,
        logScore,
        whitened,
        summaries,
        HexFormat.of().formatHex(digest.digest()));
  }

  /** An interval endpoint counts as covered. */
  private static boolean inside(double[] sorted, double value, double level) {
    return value >= JointUncertainty.quantile(sorted, (1 - level) / 2)
        && value <= JointUncertainty.quantile(sorted, (1 + level) / 2);
  }

  /**
   * The generator one named stream draws from. Generation and scoring share it, so a synthetic
   * dataset and the draws it is scored with come from the same generator and seed derivation.
   */
  static RandomGenerator stream(String stream, long seed) {
    return RANDOM_FACTORY.create(streamSeed(stream, seed));
  }

  /** Each scored observation draws from its own stream, named by the run that scores it. */
  static long streamSeed(String stream, long seed) {
    final byte[] digest = sha256().digest((stream + "|" + seed).getBytes(StandardCharsets.UTF_8));
    long value = 0;
    for (int index = 0; index < Long.BYTES; index++) value = (value << 8) | (digest[index] & 0xFF);
    return value;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
