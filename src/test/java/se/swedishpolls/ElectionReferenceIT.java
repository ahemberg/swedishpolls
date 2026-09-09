package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ElectionReferenceIT {
  @Test
  void migrationPersistsOfficialReferencesAndRulesWithoutCreatingPollObservations() {
    var schema = "elections_" + UUID.randomUUID().toString().replace("-", "");
    var url =
        System.getenv()
            .getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/swedishpolls");
    var dataSource =
        new DriverManagerDataSource(
            url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("DATABASE_USER", "swedishpolls"),
            System.getenv("DATABASE_PASSWORD"));
    var flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      // Upgrade the checkpoint-1 schema, then prove restarting does not duplicate references.
      Flyway.configure().dataSource(dataSource).schemas(schema).target("2").load().migrate();
      flyway.migrate();
      assertEquals(0, flyway.migrate().migrationsExecuted);
      var db = JdbcClient.create(dataSource);
      assertEquals(
          List.of("2010-09-19", "2014-09-14", "2018-09-09", "2022-09-11"),
          db.sql("SELECT election_date::text FROM election_reference ORDER BY election_date")
              .query(String.class)
              .list());
      assertEquals(
          List.of(5960408, 6231573, 6476725, 6477970),
          db.sql("SELECT valid_votes FROM election_reference ORDER BY election_date")
              .query(Integer.class)
              .list());
      assertEquals(
          40,
          db.sql("SELECT count(*) FROM election_party_reference").query(Integer.class).single());
      assertEquals(
          0,
          db.sql(
                  """
                    SELECT count(*) FROM (
                        SELECT election_date FROM election_party_reference JOIN election_reference USING (election_date)
                        GROUP BY election_date, valid_votes
                        HAVING sum(votes) <> valid_votes OR sum(official_seats) <> 349 OR count(*) <> 10
                    ) invalid
                    """)
              .query(Integer.class)
              .single());

      // Official counts distinguish the equal rounded 2010 V/KD shares and retain historical FP as
      // L.
      assertEquals(
          List.of(333696, 334053),
          db.sql(
                  """
                    SELECT votes FROM election_party_reference
                    WHERE election_date = '2010-09-19' AND component IN ('KD', 'V') ORDER BY component
                    """)
              .query(Integer.class)
              .list());
      assertEquals(
          List.of(420524, 337773, 355546, 298542),
          db.sql(
                  """
                    SELECT votes FROM election_party_reference WHERE component = 'L' ORDER BY election_date
                    """)
              .query(Integer.class)
              .list());
      assertEquals(
          "Folkpartiet liberalerna",
          db.sql(
                  """
                    SELECT source_label FROM election_party_reference WHERE election_date = '2014-09-14' AND component = 'L'
                    """)
              .query(String.class)
              .single());
      assertEquals(
          List.of(24139, 194719, 29665, 3157),
          db.sql(
                  """
                    SELECT votes FROM election_party_reference WHERE component = 'FI' ORDER BY election_date
                    """)
              .query(Integer.class)
              .list());
      assertEquals(
          List.of(60884, 60326, 69472, 97095),
          db.sql(
                  """
                    SELECT votes FROM election_party_reference WHERE component = 'RESIDUAL' ORDER BY election_date
                    """)
              .query(Integer.class)
              .list());
      assertEquals(
          2,
          db.sql(
                  """
                    SELECT count(*) FROM election_party_reference
                    WHERE election_date = '2010-09-19' AND component IN ('FI', 'RESIDUAL')
                    AND vote_source_url = 'https://historik.val.se/val/val2014/slutresultat/R/rike/index.html'
                    """)
              .query(Integer.class)
              .single());
      // Official 2022 seats in alphabetical component order, from the allocation decision.
      assertEquals(
          List.of(24, 0, 19, 16, 68, 18, 0, 107, 73, 24),
          db.sql(
                  """
                    SELECT official_seats FROM election_party_reference WHERE election_date = '2022-09-11' ORDER BY component
                    """)
              .query(Integer.class)
              .list());

      assertEquals(
          List.of(2010, 2014, 2018, 2022, 2026),
          db.sql(
                  """
                    SELECT election_year FROM national_allocation_rule ORDER BY election_year
                    """)
              .query(Integer.class)
              .list());
      assertEquals(
          List.of(
              new BigDecimal("1.4"),
              new BigDecimal("1.4"),
              new BigDecimal("1.2"),
              new BigDecimal("1.2"),
              new BigDecimal("1.2")),
          db.sql(
                  """
                    SELECT first_divisor FROM national_allocation_rule ORDER BY election_year
                    """)
              .query(BigDecimal.class)
              .list());
      assertEquals(
          5,
          db.sql(
                  """
                    SELECT count(*) FROM national_allocation_rule WHERE seats = 349
                    AND national_threshold_percent = 4 AND threshold_inclusive
                    AND subsequent_divisor_formula = '2 * seats_already_allocated + 1'
                    AND tie_order = ARRAY['S','M','SD','V','C','KD','L','MP','FI']
                    AND NOT other_receives_seats AND NOT constituency_exceptions_included
                    AND official_tie_rule = 'lottery'
                    """)
              .query(Integer.class)
              .single());
      assertEquals(0, db.sql("SELECT count(*) FROM poll_snapshot").query(Integer.class).single());
      assertEquals(0, db.sql("SELECT count(*) FROM snapshot_poll").query(Integer.class).single());
    } finally {
      flyway.clean();
    }
  }
}
