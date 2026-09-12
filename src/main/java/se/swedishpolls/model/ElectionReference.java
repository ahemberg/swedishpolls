package se.swedishpolls.model;

import java.time.LocalDate;
import java.util.List;

/** One stored official outcome used as a display reference, never as a model observation. */
public record ElectionReference(
    LocalDate electionDate,
    int electionYear,
    int validVotes,
    String sourceUrl,
    String officialSeatsSourceUrl,
    LocalDate retrievedOn,
    List<Party> results) {
  public ElectionReference {
    results = List.copyOf(results);
  }

  @Override
  public List<Party> results() {
    return List.copyOf(results);
  }

  /** One party's line of an official result. */
  public record Party(String component, int votes, int officialSeats) {}
}
