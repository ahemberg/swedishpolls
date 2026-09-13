package se.swedishpolls.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** A stored coalition document, optionally cut to requested coalition identifiers. */
record CoalitionsResponse(
    PublicationIdentityResponse publication,
    String lastFieldworkDate,
    int majoritySeats,
    double intervalLevel,
    int electionYear,
    List<String> overviewDefaults,
    String note,
    List<Coalition> coalitions,
    JsonNode comparison,
    @JsonInclude(JsonInclude.Include.NON_NULL) String sensitivity) {
  CoalitionsResponse select(List<String> requested) {
    return new CoalitionsResponse(
        publication,
        lastFieldworkDate,
        majoritySeats,
        intervalLevel,
        electionYear,
        overviewDefaults,
        note,
        coalitions.stream().filter(coalition -> requested.contains(coalition.id())).toList(),
        comparison,
        sensitivity);
  }

  record Coalition(
      String id,
      String label,
      List<String> parties,
      int pointSeats,
      double meanSeats,
      List<Integer> seatInterval,
      double majorityProbability) {}
}
