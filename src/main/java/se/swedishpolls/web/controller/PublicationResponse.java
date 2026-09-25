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
    String history,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        JsonNode capabilities,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        JsonNode coalitionHistory) {
  record ModelRun(
      String runId,
      String codeVersion,
      String estimatorVersion,
      long seed,
      String runtime,
      String numericalLibrary) {}

  record Snapshot(long snapshotId, String sha256, String sourceUrl, String capturedAt) {}
}
