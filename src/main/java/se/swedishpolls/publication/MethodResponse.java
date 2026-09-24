package se.swedishpolls.publication;

import java.util.List;

/** The method page's frozen metadata, tied to one publication. */
public record MethodResponse(
    Identity publication,
    int approximatedElection,
    Verdict verdict,
    Estimator estimator,
    Draws draws,
    Coverage coverage) {
  public record Identity(String publicationId, String runId, long snapshotId) {}

  record Verdict(String status, boolean released, List<String> failedGates) {}

  record Estimator(
      String version,
      String numericalLibrary,
      String developmentProtocol,
      String releaseProtocol) {}

  record Draws(long seed, int count, int decimals, List<Double> intervalLevels) {}

  record Coverage(
      String developmentThrough,
      int minObservations,
      int minInstitutes,
      int maxInternalGapDays,
      List<Integer> boundaryShiftDays,
      int stabilityBurnInDays,
      double maxStabilityShiftPoints) {}

  public static MethodResponse from(PublicationHeader header, ModelFreeze freeze) {
    return new MethodResponse(
        new Identity(header.publicationId(), header.runId(), header.snapshotId()),
        header.approximatedElection(),
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
