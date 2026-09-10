package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class CoveragePeriodIT {
  @Test
  void migrationRecordsRostersWithEffectivePeriodsAndKeepsCandidateFiUnvalidated() {
    final java.lang.String schema = "rosters_" + UUID.randomUUID().toString().replace("-", "");
    final org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
        TestDatabase.dataSource(schema);
    final org.flywaydb.core.Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      Flyway.configure().dataSource(dataSource).schemas(schema).target("3").load().migrate();
      flyway.migrate();
      assertEquals(0, flyway.migrate().migrationsExecuted);
      final org.springframework.jdbc.core.simple.JdbcClient db = JdbcClient.create(dataSource);
      final java.util.List<se.swedishpolls.Roster.CoveragePeriod> periods =
          new Roster(db).periods();
      assertEquals(
          List.of("eight_party_2010", "fi_candidate_2014_2018"),
          periods.stream().map(Roster.CoveragePeriod::id).toList());

      final se.swedishpolls.Roster.CoveragePeriod current = periods.getFirst();
      assertEquals(LocalDate.of(2010, 1, 1), current.effectiveFrom());
      assertNull(current.effectiveTo());
      assertEquals(List.of("S", "M", "SD", "V", "C", "KD", "L", "MP"), current.roster());
      assertFalse(current.individualFi());
      assertTrue(current.supportValidated());
      assertTrue(
          current.decisionUrl().startsWith("https://github.com/ahemberg/swedishpolls/issues/12"));

      final se.swedishpolls.Roster.CoveragePeriod candidate = periods.get(1);
      assertEquals(LocalDate.of(2014, 4, 9), candidate.effectiveFrom());
      assertEquals(LocalDate.of(2018, 9, 7), candidate.effectiveTo());
      assertTrue(candidate.individualFi());
      assertTrue(candidate.roster().contains("FI"));
      // No individual FI estimate is enabled: the segment inside the eight-party period stays
      // unvalidated.
      assertFalse(candidate.supportValidated());

      final se.swedishpolls.PollCsv.Poll inSegment =
          PollCsv.parse(PollCsvTest.csv(PollCsvTest.SEGMENT_ROW)).getFirst();
      assertEquals("eight_party_2010", Roster.supportedPeriod(periods, inSegment).id());
      assertTrue(candidate.covers(inSegment));
      final se.swedishpolls.PollCsv.Poll beforeHistory =
          PollCsv.parse(PollCsvTest.csv(PollCsvTest.ROW.replace("2020", "2009"))).getFirst();
      assertThrows(
          IllegalStateException.class, () -> Roster.supportedPeriod(periods, beforeHistory));

      // A second validated roster may not overlap an existing one; an unvalidated candidate may.
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> insert(db, "overlapping", true));
      assertEquals(1, insert(db, "second_candidate", false));
      db.sql("DELETE FROM coverage_period WHERE id = 'second_candidate'").update();

      for (java.lang.String invalid :
          List.of(
              "ARRAY['S','M','SD','V','C','KD','L']",
              "ARRAY['S','M','SD','V','C','KD','L','MP','NYD']"))
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                db.sql(
                        """
                        INSERT INTO coverage_period VALUES ('invalid_roster', '2030-01-01', NULL, %s, false, false, 'https://example.invalid', NULL)
                        """
                            .formatted(invalid))
                    .update());
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () ->
              db.sql(
                      """
                    INSERT INTO coverage_period VALUES ('undeclared_fi', '2030-01-01', NULL,
                        ARRAY['S','M','SD','V','C','KD','L','MP','FI'], false, false, 'https://example.invalid', NULL)
                    """)
                  .update());
      assertEquals(2, db.sql("SELECT count(*) FROM coverage_period").query(Integer.class).single());
      assertEquals(0, db.sql("SELECT count(*) FROM snapshot_poll").query(Integer.class).single());
    } finally {
      flyway.clean();
    }
  }

  private static int insert(JdbcClient db, String id, boolean validated) {
    return db.sql(
            """
                INSERT INTO coverage_period VALUES (?, '2015-01-01', '2016-01-01',
                    ARRAY['S','M','SD','V','C','KD','L','MP'], false, ?, 'https://example.invalid', NULL)
                """)
        .params(id, validated)
        .update();
  }
}
