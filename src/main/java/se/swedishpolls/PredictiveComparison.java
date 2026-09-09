package se.swedishpolls;

/** The frozen score gate, given equally weighted, paired fold means for one roster. */
public final class PredictiveComparison {
  private PredictiveComparison() {}

  public record Result(
      double baselineDifference, double referenceDifference, double pairedStandardError) {
    public boolean passes() {
      return baselineDifference > 0 && referenceDifference >= -pairedStandardError;
    }
  }

  public static Result evaluate(double[] candidate, double[] baseline, double[] reference) {
    if (candidate == null
        || baseline == null
        || reference == null
        || candidate.length < 8
        || baseline.length != candidate.length
        || reference.length != candidate.length) {
      throw new IllegalArgumentException("At least eight paired folds are required");
    }
    int count = candidate.length;
    double[] differences = new double[count];
    double baselineMean = 0;
    double referenceMean = 0;
    for (int i = 0; i < count; i++) {
      if (!Double.isFinite(candidate[i])
          || !Double.isFinite(baseline[i])
          || !Double.isFinite(reference[i])) {
        throw new IllegalArgumentException("Every fold score must be finite");
      }
      differences[i] = candidate[i] - reference[i];
      referenceMean += differences[i] / count;
      baselineMean += (candidate[i] - baseline[i]) / count;
    }
    double variance = 0;
    for (int lag = 0; lag <= 3; lag++) {
      double covariance = 0;
      for (int i = lag; i < count; i++) {
        covariance +=
            (differences[i] - referenceMean) * (differences[i - lag] - referenceMean) / count;
      }
      variance += (lag == 0 ? 1 : 2 * (1 - lag / 4.0)) * covariance / count;
    }
    if (!Double.isFinite(baselineMean)
        || !Double.isFinite(referenceMean)
        || !Double.isFinite(variance)
        || variance < 0) {
      throw new IllegalArgumentException("Invalid paired score variance or mean");
    }
    return new Result(baselineMean, referenceMean, Math.sqrt(variance));
  }
}
