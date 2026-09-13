package se.swedishpolls.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/** Institute metadata and the house effects selected for one election cycle. */
record InstitutesResponse(
    PublicationIdentityResponse publication,
    String reference,
    List<String> electionCycles,
    List<Institute> institutes,
    String electionCycle) {
  record Institute(
      String institute,
      List<String> companies,
      int polls,
      String firstCollection,
      String lastCollection,
      List<MethodEra> methodEras,
      List<HouseEffect> houseEffects) {
    Institute forCycle(String cycle) {
      return new Institute(
          institute,
          companies,
          polls,
          firstCollection,
          lastCollection,
          methodEras,
          houseEffects.stream()
              .filter(effect -> cycle == null || cycle.equals(effect.electionCycle()))
              .toList());
    }
  }

  record MethodEra(
      String id,
      @JsonInclude(JsonInclude.Include.NON_NULL) String from,
      @JsonInclude(JsonInclude.Include.NON_NULL) String to,
      String evidence) {}

  record HouseEffect(
      String electionCycle,
      String component,
      BigDecimal mean,
      BigDecimal lower,
      BigDecimal upper,
      boolean shrunk) {}
}

/** The stored institute document before its election-cycle cut. */
record InstitutesDocument(
    PublicationIdentityResponse publication,
    String reference,
    List<String> electionCycles,
    List<InstitutesResponse.Institute> institutes) {
  InstitutesResponse select(String requested) {
    final String selected =
        requested == null
            ? (electionCycles.isEmpty() ? null : electionCycles.getLast())
            : requested;
    return new InstitutesResponse(
        publication,
        reference,
        electionCycles,
        institutes.stream().map(institute -> institute.forCycle(selected)).toList(),
        selected);
  }
}
