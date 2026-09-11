package se.swedishpolls;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads the stored official outcomes. They are display references, never model observations. */
@Component
public class ElectionReferences {
  /** One party's line of an official result. */
  public record Party(String component, int votes, int officialSeats) {}

  /** One official election, with its provenance and every reported component. */
  public record Election(
      LocalDate electionDate,
      int electionYear,
      int validVotes,
      String sourceUrl,
      String officialSeatsSourceUrl,
      LocalDate retrievedOn,
      List<Party> results) {
    public Election {
      results = List.copyOf(results);
    }

    @Override
    public List<Party> results() {
      return List.copyOf(results);
    }

    /** The same result as shares of the valid votes, unrounded. */
    public ComparableRemainder.Reference reference() {
      final LinkedHashMap<String, Double> shares = new LinkedHashMap<>();
      for (final String component : ComparableRemainder.REFERENCE_COMPONENTS) {
        shares.put(component, 0.0);
      }
      for (final Party party : results) {
        shares.put(party.component(), 100.0 * party.votes() / validVotes);
      }
      return new ComparableRemainder.Reference(electionDate, shares);
    }
  }

  private final JdbcClient db;

  public ElectionReferences(JdbcClient db) {
    this.db = db;
  }

  /** Every stored election, oldest first. */
  public List<Election> all() {
    record Row(
        LocalDate electionDate,
        int electionYear,
        int validVotes,
        String sourceUrl,
        String officialSeatsSourceUrl,
        LocalDate retrievedOn,
        String component,
        int votes,
        int officialSeats) {}
    final List<Row> rows =
        db.sql(
                """
                SELECT election_date, election_year, valid_votes, source_url,
                       official_seats_source_url, retrieved_on, component, votes, official_seats
                FROM election_reference JOIN election_party_reference USING (election_date)
                ORDER BY election_date, component
                """)
            .query(Row.class)
            .list();
    final Map<LocalDate, List<Party>> parties = new LinkedHashMap<>();
    final Map<LocalDate, Row> heads = new LinkedHashMap<>();
    for (final Row row : rows) {
      heads.putIfAbsent(row.electionDate(), row);
      parties
          .computeIfAbsent(row.electionDate(), date -> new ArrayList<>())
          .add(new Party(row.component(), row.votes(), row.officialSeats()));
    }
    final List<Election> elections = new ArrayList<>();
    for (final Map.Entry<LocalDate, Row> entry : heads.entrySet()) {
      final Row head = entry.getValue();
      elections.add(
          new Election(
              head.electionDate(),
              head.electionYear(),
              head.validVotes(),
              head.sourceUrl(),
              head.officialSeatsSourceUrl(),
              head.retrievedOn(),
              parties.get(entry.getKey())));
    }
    return List.copyOf(elections);
  }
}
