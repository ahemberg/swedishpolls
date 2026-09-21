package se.swedishpolls.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The latest estimate read from one immutable publication document. */
record LatestResponse(
    PublicationIdentityResponse publication,
    String lastFieldworkDate,
    Double intervalLevel,
    String coveragePeriod,
    List<CoveragePeriod> coveragePeriods,
    List<Component> components,
    Estimate comparableRemainder,
    Map<String, Unavailable> unavailable) {
  LatestResponse {
    Objects.requireNonNull(publication);
    Objects.requireNonNull(lastFieldworkDate);
    Objects.requireNonNull(intervalLevel);
    Objects.requireNonNull(coveragePeriod);
    coveragePeriods = List.copyOf(coveragePeriods);
    components = List.copyOf(components);
    Objects.requireNonNull(comparableRemainder);
    unavailable = Collections.unmodifiableMap(new LinkedHashMap<>(unavailable));
  }

  record CoveragePeriod(
      String id,
      String from,
      String to,
      List<String> roster,
      List<String> otherMembers,
      Boolean individualFi,
      Boolean supportValidated,
      String decision,
      String otherAlsoIncludes) {
    CoveragePeriod {
      Objects.requireNonNull(id);
      Objects.requireNonNull(from);
      roster = List.copyOf(roster);
      otherMembers = List.copyOf(otherMembers);
      Objects.requireNonNull(individualFi);
      Objects.requireNonNull(supportValidated);
      Objects.requireNonNull(decision);
      Objects.requireNonNull(otherAlsoIncludes);
    }
  }

  record Component(
      String component,
      @JsonInclude(JsonInclude.Include.NON_NULL) String label,
      Double mean,
      Double lower,
      Double upper) {
    Component {
      Objects.requireNonNull(component);
      Objects.requireNonNull(mean);
      Objects.requireNonNull(lower);
      Objects.requireNonNull(upper);
    }
  }

  record Estimate(Double mean, Double lower, Double upper, String definition) {
    Estimate {
      Objects.requireNonNull(mean);
      Objects.requireNonNull(lower);
      Objects.requireNonNull(upper);
      Objects.requireNonNull(definition);
    }
  }

  record Unavailable(Double mean, Double lower, Double upper, String reason) {
    Unavailable {
      Objects.requireNonNull(reason);
    }
  }
}
