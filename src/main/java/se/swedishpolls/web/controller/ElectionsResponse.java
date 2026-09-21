package se.swedishpolls.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Election references read from one immutable publication document. */
record ElectionsResponse(
    PublicationIdentityResponse publication, String note, List<Election> elections) {
  ElectionsResponse {
    Objects.requireNonNull(publication);
    Objects.requireNonNull(note);
    elections = List.copyOf(elections);
  }

  record Election(
      String electionDate,
      Integer electionYear,
      Long validVotes,
      String sourceUrl,
      String officialSeatsSourceUrl,
      String retrievedOn,
      ComparableGrouping comparableGrouping,
      Map<String, Result> results) {
    Election {
      Objects.requireNonNull(electionDate);
      Objects.requireNonNull(electionYear);
      Objects.requireNonNull(validVotes);
      Objects.requireNonNull(sourceUrl);
      Objects.requireNonNull(officialSeatsSourceUrl);
      Objects.requireNonNull(retrievedOn);
      Objects.requireNonNull(comparableGrouping);
      results = Collections.unmodifiableMap(new LinkedHashMap<>(results));
    }
  }

  record ComparableGrouping(
      String coveragePeriod,
      List<String> other,
      @JsonInclude(JsonInclude.Include.NON_NULL) String note,
      @JsonInclude(JsonInclude.Include.NON_NULL) String comparableRemainder) {
    ComparableGrouping {
      Objects.requireNonNull(coveragePeriod);
      other = List.copyOf(other);
    }
  }

  record Result(Long votes, Integer officialSeats) {
    Result {
      Objects.requireNonNull(votes);
      Objects.requireNonNull(officialSeats);
    }
  }
}
