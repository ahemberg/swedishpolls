package se.swedishpolls.publication;

import java.util.List;

/** The shipped method settings projected into values suitable for publication. */
public record MethodMetadata(Verdict verdict, Estimator estimator, Draws draws, Coverage coverage) {

  public record Verdict(String status, boolean released, List<String> failedGates) {}

  public record Estimator(
      String version,
      String numericalLibrary,
      String developmentProtocol,
      String releaseProtocol) {}

  public record Draws(long seed, int count, int decimals, List<Double> intervalLevels) {}

  public record Coverage(
      String developmentThrough,
      int minObservations,
      int minInstitutes,
      int maxInternalGapDays,
      List<Integer> boundaryShiftDays,
      int stabilityBurnInDays,
      double maxStabilityShiftPoints) {}

  public static MethodMetadata from(ModelFreeze freeze) {
    return new MethodMetadata(
        new Verdict(freeze.releaseStatus(), freeze.released(), freeze.failedBlockingGates()),
        new Estimator(
            freeze.estimatorVersion(),
            freeze.numericalLibrary(),
            freeze.developmentProtocolVersion(),
            freeze.releaseProtocolVersion()),
        new Draws(
            freeze.uncertainty().seed(),
            freeze.uncertainty().draws(),
            freeze.resolution().decimals(),
            freeze.uncertainty().intervalLevels()),
        new Coverage(
            freeze.developmentCoverage().developmentThrough().toString(),
            freeze.developmentCoverage().minObservations(),
            freeze.developmentCoverage().minInstitutes(),
            freeze.developmentCoverage().maxInternalGapDays(),
            freeze.developmentCoverage().boundaryShiftDays(),
            freeze.developmentCoverage().stabilityBurnInDays(),
            freeze.developmentCoverage().maxStabilityShiftPoints()));
  }
}
