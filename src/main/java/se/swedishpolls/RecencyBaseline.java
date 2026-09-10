package se.swedishpolls;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.ejml.simple.SimpleMatrix;

/**
 * The registered recency baseline: a weighted arithmetic average of the recent eligible polls, with
 * no fitted house effect and no trend. It is the floor the midpoint candidate has to beat, so it
 * carries the same roster, the same training set and a predictive covariance of its own rather than
 * a point estimate.
 */
public final class RecencyBaseline {
  private RecencyBaseline() {}

  /** The registered window, half-life and fallback of the baseline. */
  public static final int WINDOW_DAYS = 120;

  public static final double HALF_LIFE_DAYS = 30;
  public static final int MIN_POLLS = 3;
  public static final int FALLBACK_POLLS = 5;

  /**
   * One fold's baseline: the composition it averages to, the uncertainty of that average and the
   * observations it read.
   */
  public record Fit(
      LocalDate asOf,
      List<PollObservations.Observation> used,
      List<Double> weights,
      double effectiveSampleSize,
      ModelValues composition,
      ModelValues mean,
      ModelValues covariance) {
    public Fit {
      used = List.copyOf(used);
      weights = List.copyOf(weights);
    }

    @Override
    public List<PollObservations.Observation> used() {
      return List.copyOf(used);
    }

    @Override
    public List<Double> weights() {
      return List.copyOf(weights);
    }
  }

  /**
   * Averages the eligible training polls of the preceding window. Below three polls in the window
   * the baseline falls back to the five most recent, so a thin fold still has a comparable
   * prediction rather than none.
   */
  public static Fit fit(PollObservations.Batch training, LocalDate asOf) {
    final java.util.List<se.swedishpolls.PollObservations.Observation> ordered =
        training.observations().stream()
            .filter(observation -> !observation.midpoint().isAfter(asOf))
            .sorted(
                Comparator.comparing(PollObservations.Observation::midpoint)
                    .thenComparing(
                        observation -> observation.poll().publicationDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingInt(observation -> observation.poll().rowNumber()))
            .toList();
    if (ordered.isEmpty())
      throw new IllegalArgumentException("The baseline needs an eligible training poll");
    final java.util.List<se.swedishpolls.PollObservations.Observation> recent =
        ordered.stream()
            .filter(
                observation -> ChronoUnit.DAYS.between(observation.midpoint(), asOf) <= WINDOW_DAYS)
            .toList();
    final java.util.List<se.swedishpolls.PollObservations.Observation> used =
        recent.size() >= MIN_POLLS
            ? recent
            : ordered.subList(Math.max(0, ordered.size() - FALLBACK_POLLS), ordered.size());
    final int size = training.components().size();
    final org.ejml.simple.SimpleMatrix basis = training.basis().copy();
    final double[] weights = new double[used.size()];
    final double[] composition = new double[size];
    double totalWeight = 0;
    double totalSquaredWeight = 0;
    double meanSampleSize = 0;
    for (int i = 0; i < used.size(); i++) {
      final se.swedishpolls.PollObservations.Observation observation = used.get(i);
      final double sampleSize = observation.poll().sampleSize().doubleValue();
      final double age = ChronoUnit.DAYS.between(observation.midpoint(), asOf);
      weights[i] = sampleSize * Math.pow(2, -age / HALF_LIFE_DAYS);
      if (!Double.isFinite(weights[i]) || weights[i] <= 0)
        throw new IllegalArgumentException(
            "Inadmissible baseline weight at row " + observation.poll().rowNumber());
      totalWeight += weights[i];
      totalSquaredWeight += weights[i] * weights[i];
      meanSampleSize += sampleSize / used.size();
      final java.util.Map<java.lang.String, java.lang.Double> shares =
          PollObservations.shares(training, observation.ilr());
      for (int c = 0; c < size; c++)
        composition[c] += weights[i] * shares.get(training.components().get(c));
    }
    final double[] proportions = new double[size];
    for (int c = 0; c < size; c++) proportions[c] = composition[c] / (100 * totalWeight);
    final org.ejml.simple.SimpleMatrix logged = new SimpleMatrix(size, 1);
    for (int c = 0; c < size; c++) logged.set(c, Math.log(proportions[c]));
    final org.ejml.simple.SimpleMatrix mean = basis.mult(logged);
    // The average's own uncertainty: sampling variation at the effective sample size, plus the
    // spread of the polls it averaged around it.
    final double effective = totalWeight * totalWeight / totalSquaredWeight * meanSampleSize;
    org.ejml.simple.SimpleMatrix covariance =
        PollObservations.deltaCovariance(basis, proportions, effective, "the recency baseline");
    for (int i = 0; i < used.size(); i++) {
      final org.ejml.simple.SimpleMatrix residual = used.get(i).ilr().minus(mean);
      covariance =
          covariance.plus(residual.mult(residual.transpose()).scale(weights[i] / totalWeight));
    }
    final double[] percent = new double[size];
    for (int c = 0; c < size; c++) percent[c] = 100 * proportions[c];
    return new Fit(
        asOf,
        used,
        java.util.Arrays.stream(weights).boxed().toList(),
        effective,
        ModelValues.copyOf(new SimpleMatrix(new double[][] {percent}).transpose()),
        ModelValues.owned(mean),
        ModelValues.owned(covariance));
  }

  /**
   * The predictive distribution of one held-out poll: the baseline's own uncertainty plus that
   * poll's sampling variation at the predicted composition and its own sample size, at multiplier
   * one. The baseline has no overdispersion parameter to inflate it with.
   */
  public static ModelValues predictiveCovariance(
      PollObservations.Batch training, Fit fit, PollObservations.Observation observation) {
    final int size = training.components().size();
    final double[] proportions = new double[size];
    for (int c = 0; c < size; c++) proportions[c] = fit.composition().get(c) / 100;
    return ModelValues.owned(
        fit.covariance()
            .copy()
            .plus(
                PollObservations.deltaCovariance(
                    training.basis().copy(),
                    proportions,
                    observation.poll().sampleSize().doubleValue(),
                    "row " + observation.poll().rowNumber())));
  }
}
