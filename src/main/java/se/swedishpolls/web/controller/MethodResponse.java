package se.swedishpolls.web.controller;

import se.swedishpolls.publication.MethodMetadata;
import se.swedishpolls.publication.PublicationHeader;

/** Method metadata with the publication identity required by the v1 API. */
record MethodResponse(
    PublicationIdentityResponse publication,
    int approximatedElection,
    MethodMetadata.Verdict verdict,
    MethodMetadata.Estimator estimator,
    MethodMetadata.Draws draws,
    MethodMetadata.Coverage coverage) {
  static MethodResponse from(PublicationHeader header, MethodMetadata metadata) {
    return new MethodResponse(
        new PublicationIdentityResponse(
            header.publicationId(), header.runId(), header.snapshotId()),
        header.approximatedElection(),
        metadata.verdict(),
        metadata.estimator(),
        metadata.draws(),
        metadata.coverage());
  }
}
