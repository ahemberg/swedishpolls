package se.swedishpolls.web.controller;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** A stored history document after request-time range and sampling cuts. */
record HistoryResponse(
    PublicationIdentityResponse publication,
    double intervalLevel,
    JsonNode range,
    List<String> dates,
    List<String> coveragePeriodByDate,
    List<JsonNode> series,
    List<JsonNode> boundaries,
    JsonNode change30d) {}
