package se.swedishpolls;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.simple.SimpleMatrix;

/** Numerical inputs for one separate fit, using polls read from one archived snapshot. */
public final class PollObservations {
  private PollObservations() {}

  public record Observation(
      PollCsv.Poll poll,
      LocalDate midpoint,
      ModelValues ilr,
      ModelValues covariance,
      int replacedZeros) {}

  public record Exclusion(int rowNumber, List<String> reasons) {
    public Exclusion {
      reasons = List.copyOf(reasons);
    }
  }

  public record Batch(
      Roster.CoveragePeriod period,
      List<String> components,
      ModelValues basis,
      List<Observation> observations,
      List<Exclusion> exclusions) {
    public Batch {
      components = List.copyOf(components);
      observations = List.copyOf(observations);
      exclusions = List.copyOf(exclusions);
    }
  }

  /**
   * The composition of one ilr state, in percent and component order. The basis rows span the
   * orthogonal complement of the constant vector, so {@code p} is the closure of {@code exp(H' z)}.
   */
  public static Map<String, Double> shares(Batch batch, ModelValues ilr) {
    return shares(batch, ilr.copy());
  }

  public static Map<String, Double> shares(Batch batch, SimpleMatrix ilr) {
    if (ilr.getNumRows() != batch.components().size() - 1
        || ilr.getNumCols() != 1
        || ilr.hasUncountable())
      throw new IllegalArgumentException("Invalid ilr state for " + batch.period().id());
    var state = new double[ilr.getNumRows()];
    for (int r = 0; r < state.length; r++) state[r] = ilr.get(r);
    var values = new double[batch.components().size()];
    close(transposedBasis(batch), state, values, batch.period().id());
    var shares = new LinkedHashMap<String, Double>();
    for (int c = 0; c < values.length; c++) shares.put(batch.components().get(c), values[c]);
    return java.util.Collections.unmodifiableMap(shares);
  }

  /** The batch basis transposed once, as {@code H'[component][coordinate]}. */
  static double[][] transposedBasis(Batch batch) {
    int size = batch.components().size();
    var transposed = new double[size][size - 1];
    for (int c = 0; c < size; c++)
      for (int r = 0; r < size - 1; r++) transposed[c][r] = batch.basis().get(r, c);
    return transposed;
  }

  /**
   * Closes {@code exp(H' z)} into percent shares, writing them into the caller's buffer. Drawing
   * uncertainty transforms one state at a time, so this reuses buffers rather than allocating a
   * composition per draw, and both callers run the same arithmetic in the same order.
   */
  static void close(double[][] transposedBasis, double[] ilr, double[] shares, String periodId) {
    double max = Double.NEGATIVE_INFINITY;
    for (int c = 0; c < shares.length; c++) {
      double log = 0;
      for (int r = 0; r < ilr.length; r++) log += transposedBasis[c][r] * ilr[r];
      shares[c] = log;
      if (log > max) max = log;
    }
    double total = 0;
    for (int c = 0; c < shares.length; c++) {
      shares[c] = Math.exp(shares[c] - max);
      total += shares[c];
    }
    if (!Double.isFinite(total) || total <= 0)
      throw new IllegalArgumentException("Unrepresentable composition for " + periodId);
    for (int c = 0; c < shares.length; c++) shares[c] = 100 * shares[c] / total;
  }

  /**
   * Explicit candidate periods are allowed for development; this does not validate their support.
   */
  public static Batch prepare(Roster.CoveragePeriod period, List<PollCsv.Poll> polls) {
    var expected = new HashSet<>(PollCsv.PARTIES);
    if (period.individualFi()) expected.add("FI");
    if (period.roster().size() != expected.size()
        || !expected.equals(new HashSet<>(period.roster())))
      throw new IllegalArgumentException("Invalid roster for " + period.id());
    var components = new ArrayList<>(period.roster());
    components.add(period.individualFi() ? "RESIDUAL" : "OTHER");
    int size = components.size();
    // Rows are Helmert contrasts: first r+1 components versus component r+2.
    var basis = new SimpleMatrix(size - 1, size);
    for (int r = 0; r < size - 1; r++) {
      double scale = Math.sqrt((r + 1.0) * (r + 2.0));
      for (int c = 0; c <= r; c++) basis.set(r, c, 1 / scale);
      basis.set(r, r + 1, -(r + 1) / scale);
    }
    var observations = new ArrayList<Observation>();
    var exclusions = new ArrayList<Exclusion>();
    for (var poll : polls) {
      var composition = Roster.compose(period, poll);
      if (!composition.complete()) {
        exclusions.add(new Exclusion(poll.rowNumber(), composition.exclusionReasons()));
        continue;
      }
      double n = poll.sampleSize().doubleValue();
      if (!Double.isFinite(n) || n <= 0)
        throw new IllegalArgumentException(
            "Unrepresentable sample size at row " + poll.rowNumber());
      var proportions = new double[size];
      int zeros = 0;
      for (int c = 0; c < size; c++) {
        var share = composition.components().get(components.get(c));
        proportions[c] = share.doubleValue() / 100;
        if (!Double.isFinite(proportions[c])
            || share.signum() < 0
            || (share.signum() > 0 && proportions[c] <= 0))
          throw new IllegalArgumentException("Unrepresentable share at row " + poll.rowNumber());
        if (share.signum() == 0) zeros++;
      }
      if (zeros > 0) {
        double delta = Math.min(0.5 / n, 0.5 / zeros);
        for (int c = 0; c < size; c++)
          proportions[c] = proportions[c] == 0 ? delta : proportions[c] * (1 - zeros * delta);
      }
      var log = new SimpleMatrix(size, 1);
      for (int c = 0; c < size; c++) log.set(c, Math.log(proportions[c]));
      var covariance = new SimpleMatrix(size - 1, size - 1);
      // H diag(1/p) [diag(p)-p p'] diag(1/p) H'/n = H diag(1/p) H'/n, since H 1 = 0.
      for (int r = 0; r < size - 1; r++) {
        for (int c = 0; c <= r; c++) {
          double value = 0;
          for (int j = 0; j < size; j++)
            value += basis.get(r, j) * basis.get(c, j) / (n * proportions[j]);
          if (!Double.isFinite(value))
            throw new IllegalArgumentException(
                "Nonfinite observation covariance at row " + poll.rowNumber());
          covariance.set(r, c, value);
          covariance.set(c, r, value);
        }
      }
      if (!DecompositionFactory_DDRM.chol(size - 1, true).decompose(covariance.getDDRM().copy()))
        throw new IllegalArgumentException(
            "Observation covariance is not positive definite at row " + poll.rowNumber());
      var midpoint =
          poll.collectionFrom()
              .plusDays(ChronoUnit.DAYS.between(poll.collectionFrom(), poll.collectionTo()) / 2);
      observations.add(
          new Observation(
              poll,
              midpoint,
              ModelValues.owned(basis.mult(log)),
              ModelValues.owned(covariance),
              zeros));
    }
    return new Batch(period, components, ModelValues.owned(basis), observations, exclusions);
  }
}
