package se.swedishpolls.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** National seat approximations read from one immutable publication document. */
record SeatsResponse(
    PublicationIdentityResponse publication,
    String lastFieldworkDate,
    Integer totalSeats,
    Double intervalLevel,
    String note,
    AllocationRule allocationRule,
    List<Party> parties,
    Map<String, Unavailable> unavailable,
    List<String> excludedFromAllocation,
    @JsonInclude(JsonInclude.Include.NON_NULL) String sensitivity) {
  SeatsResponse {
    Objects.requireNonNull(publication);
    Objects.requireNonNull(lastFieldworkDate);
    Objects.requireNonNull(totalSeats);
    Objects.requireNonNull(intervalLevel);
    Objects.requireNonNull(note);
    Objects.requireNonNull(allocationRule);
    parties = List.copyOf(parties);
    unavailable = Collections.unmodifiableMap(new LinkedHashMap<>(unavailable));
    excludedFromAllocation = List.copyOf(excludedFromAllocation);
  }

  record AllocationRule(
      Integer electionYear,
      Integer seats,
      Double firstDivisor,
      String subsequentDivisorFormula,
      Double thresholdPercent,
      Boolean thresholdInclusive,
      Boolean otherReceivesSeats,
      List<String> tieOrder,
      String officialTieRule,
      Boolean constituencyExceptionsIncluded,
      String sourceUrl,
      @JsonInclude(JsonInclude.Include.NON_NULL) String tieNote) {
    AllocationRule {
      Objects.requireNonNull(electionYear);
      Objects.requireNonNull(seats);
      Objects.requireNonNull(firstDivisor);
      Objects.requireNonNull(subsequentDivisorFormula);
      Objects.requireNonNull(thresholdPercent);
      Objects.requireNonNull(thresholdInclusive);
      Objects.requireNonNull(otherReceivesSeats);
      tieOrder = List.copyOf(tieOrder);
      Objects.requireNonNull(officialTieRule);
      Objects.requireNonNull(constituencyExceptionsIncluded);
      Objects.requireNonNull(sourceUrl);
    }
  }

  record Party(
      String component,
      @JsonInclude(JsonInclude.Include.NON_NULL) String label,
      Integer pointSeats,
      Double meanSeats,
      List<Integer> seatInterval,
      Double thresholdProbability) {
    Party {
      Objects.requireNonNull(component);
      Objects.requireNonNull(pointSeats);
      Objects.requireNonNull(meanSeats);
      seatInterval = List.copyOf(seatInterval);
      Objects.requireNonNull(thresholdProbability);
    }
  }

  record Unavailable(
      Integer pointSeats, Double meanSeats, Double thresholdProbability, String reason) {
    Unavailable {
      Objects.requireNonNull(reason);
    }
  }
}
