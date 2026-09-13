package se.swedishpolls.web.controller;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** Two columnar histories selected from one immutable artifact. */
record CoalitionHistoryResponse(
    int schemaVersion,
    String publicationId,
    String modelRunId,
    long sourceSnapshotId,
    String lastFieldworkDate,
    String publishedAt,
    JsonNode interval,
    JsonNode selection,
    JsonNode requestedRange,
    List<String> dates,
    List<String> fitIds,
    List<String> coveragePeriodIds,
    JsonNode series,
    JsonNode coveragePeriods,
    JsonNode gaps,
    JsonNode fitBoundaries,
    JsonNode latest) {}
