package se.swedishpolls.web.controller;

import tools.jackson.databind.JsonNode;

/** Publication metadata rendered by Spring rather than assembled by the controller. */
record PublicationResponse(
    String publicationId,
    String publishedAt,
    String sourceCheckedAt,
    String lastFieldworkDate,
    boolean stale,
    String staleSince,
    String permalink,
    ModelRun modelRun,
    Snapshot snapshot,
    JsonNode assets,
    String history) {
  record ModelRun(
      String runId,
      String codeVersion,
      String estimatorVersion,
      long seed,
      String runtime,
      String numericalLibrary) {}

  record Snapshot(long snapshotId, String sha256, String sourceUrl, String capturedAt) {}
}
