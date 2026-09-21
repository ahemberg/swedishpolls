package se.swedishpolls.web.controller;

import java.util.Objects;

/** The publication identity repeated by every dependent API response. */
record PublicationIdentityResponse(String publicationId, String runId, Long snapshotId) {
  PublicationIdentityResponse {
    Objects.requireNonNull(publicationId);
    Objects.requireNonNull(runId);
    Objects.requireNonNull(snapshotId);
  }
}
