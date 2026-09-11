package se.swedishpolls.source.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import se.swedishpolls.source.Roster;

/** JDBC lookup of the coverage periods. Grouping stays in {@link Roster}. */
@Component
public class CoveragePeriodRepository {
  private final JdbcClient db;

  public CoveragePeriodRepository(JdbcClient db) {
    this.db = db;
  }

  public List<Roster.CoveragePeriod> periods() {
    return db.sql(
            """
                SELECT id, effective_from, effective_to, roster, individual_fi, support_validated, decision_url
                FROM coverage_period ORDER BY effective_from, id
                """)
        .query(
            (rs, row) ->
                new Roster.CoveragePeriod(
                    rs.getString("id"),
                    rs.getObject("effective_from", LocalDate.class),
                    rs.getObject("effective_to", LocalDate.class),
                    List.of((String[]) rs.getArray("roster").getArray()),
                    rs.getBoolean("individual_fi"),
                    rs.getBoolean("support_validated"),
                    rs.getString("decision_url")))
        .list();
  }
}
