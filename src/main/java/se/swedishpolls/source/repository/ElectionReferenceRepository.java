package se.swedishpolls.source.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import se.swedishpolls.model.ElectionReference;

/** JDBC lookup of stored official election outcomes. */
@Component
public class ElectionReferenceRepository {
  private final JdbcClient db;

  public ElectionReferenceRepository(JdbcClient db) {
    this.db = db;
  }

  /** The latest stored election, whether or not any party result was stored with it. */
  public Optional<LocalDate> latestElectionDate() {
    return db.sql("SELECT max(election_date) FROM election_reference")
        .query(LocalDate.class)
        .optional();
  }

  /** Every stored election, oldest first. */
  public List<ElectionReference> all() {
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
    final Map<LocalDate, List<ElectionReference.Party>> parties = new LinkedHashMap<>();
    final Map<LocalDate, Row> heads = new LinkedHashMap<>();
    for (final Row row : rows) {
      heads.putIfAbsent(row.electionDate(), row);
      parties
          .computeIfAbsent(row.electionDate(), date -> new ArrayList<>())
          .add(new ElectionReference.Party(row.component(), row.votes(), row.officialSeats()));
    }
    final List<ElectionReference> elections = new ArrayList<>();
    for (final Map.Entry<LocalDate, Row> entry : heads.entrySet()) {
      final Row head = entry.getValue();
      elections.add(
          new ElectionReference(
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
