package se.swedishpolls.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;

/** Coverage periods read back from an immutable publication document. */
public final class PublicationCoverage {
  private PublicationCoverage() {}

  public static List<Roster.CoveragePeriod> from(JsonNode latest) {
    final List<Roster.CoveragePeriod> periods = new ArrayList<>();
    for (final JsonNode published : latest.get("coveragePeriods")) {
      final List<String> roster = new ArrayList<>();
      for (final JsonNode component : published.get("roster")) {
        roster.add(component.asString());
      }
      final JsonNode to = published.get("to");
      periods.add(
          new Roster.CoveragePeriod(
              published.get("id").asString(),
              LocalDate.parse(published.get("from").asString()),
              to.isNull() ? null : LocalDate.parse(to.asString()),
              roster,
              published.get("individualFi").asBoolean(),
              published.get("supportValidated").asBoolean(),
              published.get("decision").asString()));
    }
    return List.copyOf(periods);
  }
}
