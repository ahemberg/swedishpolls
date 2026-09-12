package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.testsupport.TestDatabase;

/**
 * Draws the comparable remainder of the archived pre-2022 development rows at the registered seed
 * and draw count, and groups the official election references into the same components.
 * `-Dremainder.full=true` rewrites docs/validation/remainder.json.
 */
class ComparableRemainderIT {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path RESULT = Path.of("docs", "validation", "remainder.json");

  @Test
  void summarizesTheRemainderFromTheSameDrawsAndGroupsEveryElectionTheSameWay() throws Exception {
    final java.lang.String schema = "remainder_" + UUID.randomUUID().toString().replace("-", "");
    final org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
        TestDatabase.dataSource(schema);
    final org.flywaydb.core.Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      flyway.migrate();
      final org.springframework.jdbc.core.simple.JdbcClient db = JdbcClient.create(dataSource);
      final java.util.List<se.swedishpolls.source.Roster.CoveragePeriod> periods =
          new CoveragePeriodRepository(db).periods();
      final java.util.List<se.swedishpolls.estimation.ComparableRemainder.Reference> elections =
          elections(db);
      final List<PollCsv.Poll> polls;
      try (final java.io.InputStream input = getClass().getResourceAsStream("/polls/audit.csv")) {
        polls = PollCsv.parse(input.readAllBytes());
      }
      final se.swedishpolls.estimation.CoverageValidation.Report coverage =
          CoverageValidation.validation(COVERAGE);
      final se.swedishpolls.estimation.JointUncertainty.Rules rules =
          JointUncertainty.rules(PROTOCOL);

      final se.swedishpolls.estimation.ComparableRemainder.Report report =
          ComparableRemainder.report(periods, polls, elections, coverage, rules);

      // Only the validated roster publishes; the candidate FI segment publishes nothing here.
      assertEquals(1, report.periods().size());
      final se.swedishpolls.estimation.ComparableRemainder.Published published =
          report.periods().getFirst();
      assertEquals("eight_party_2010", published.periodId());
      assertEquals(4278, published.estimatedDays());
      // FI is not separate in this period, so OTHER is already the comparable remainder.
      assertEquals(List.of("OTHER"), published.members());
      assertEquals(0, published.maxEndpointSumErrorPoints());

      final se.swedishpolls.estimation.ComparableRemainder.Day headline = published.headline();
      assertEquals(LocalDate.of(2021, 9, 20), headline.date());
      assertEquals(
          List.of(0.5, 0.95),
          headline.intervals().stream().map(JointUncertainty.Interval::level).toList());
      final se.swedishpolls.estimation.JointUncertainty.Interval half =
          headline.intervals().getFirst();
      final se.swedishpolls.estimation.JointUncertainty.Interval wide =
          headline.intervals().getLast();
      assertTrue(wide.lower() < half.lower() && half.upper() < wide.upper(), headline::toString);
      assertTrue(wide.lower() < headline.mean() && headline.mean() < wide.upper());

      // The remainder is the same draws the component summaries read, not a second run. Only the
      // final day is compared, so only the final day is summarized.
      final se.swedishpolls.source.Roster.CoveragePeriod period =
          periods.stream()
              .filter(candidate -> candidate.id().equals(published.periodId()))
              .findFirst()
              .orElseThrow();
      final se.swedishpolls.estimation.CoverageValidation.Validated validated =
          coverage.periods().getFirst();
      final se.swedishpolls.estimation.JointUncertainty.FinalDay uncertainty =
          JointUncertainty.finalDay(
              period,
              polls,
              elections.stream().map(ComparableRemainder.Reference::date).toList(),
              validated.parameters(),
              coverage.rules(),
              rules);
      final se.swedishpolls.estimation.JointUncertainty.Component other =
          uncertainty.day().components().stream()
              .filter(component -> component.component().equals("OTHER"))
              .findFirst()
              .orElseThrow();
      assertEquals(other.mean(), headline.mean(), 1e-12);
      assertEquals(other.intervals().getLast().lower(), wide.lower(), 1e-12);
      assertEquals(other.intervals().getLast().upper(), wide.upper(), 1e-12);

      // Every election is grouped into this period's components; the 2022 result is outside the
      // development history and is marked so rather than dropped.
      assertEquals(4, published.elections().size());
      for (se.swedishpolls.estimation.ComparableRemainder.Grouped election :
          published.elections()) {
        assertEquals(
            List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER"),
            List.copyOf(election.shares().keySet()));
        assertEquals(
            100, election.shares().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertEquals(election.shares().get("OTHER"), election.comparableRemainder(), 1e-12);
      }
      assertEquals(
          List.of(true, true, true, false),
          published.elections().stream()
              .map(ComparableRemainder.Grouped::insideSupportedHistory)
              .toList());

      // Changes stay inside one run between boundaries, and every boundary suppresses its own.
      assertTrue(published.changes().stream().anyMatch(ComparableRemainder.Change::available));
      assertTrue(
          published.changes().stream()
              .filter(change -> !change.available())
              .allMatch(
                  change ->
                      List.of(
                              EstimateHistory.ACROSS_BOUNDARY,
                              EstimateHistory.COMPARISON_UNSUPPORTED)
                          .contains(change.reason())),
          published.changes()::toString);

      // Nothing here is a release value: the tuning gate behind these parameters is still blocked.
      assertTrue(report.gate().blocked());
      assertEquals(coverage.gate().reasons(), report.gate().reasons());

      if (Boolean.getBoolean("remainder.full"))
        Files.writeString(
            RESULT, ComparableRemainder.report(report) + "\n", StandardCharsets.UTF_8);
      else
        assertTrue(
            Files.exists(RESULT),
            "Committed remainder evidence is missing; rerun with -Dremainder.full=true");
    } finally {
      flyway.clean();
    }
  }

  /** The official results in their own reported components, as percentages of the valid votes. */
  private static List<ComparableRemainder.Reference> elections(JdbcClient db) {
    record Share(LocalDate date, String component, double share) {}
    final java.util.LinkedHashMap<
            java.time.LocalDate, java.util.LinkedHashMap<java.lang.String, java.lang.Double>>
        shares = new LinkedHashMap<LocalDate, LinkedHashMap<String, Double>>();
    for (Share row :
        db.sql(
                """
                SELECT election_date, component, 100.0 * votes / valid_votes AS share
                FROM election_party_reference JOIN election_reference USING (election_date)
                ORDER BY election_date, component
                """)
            .query(
                (rs, index) ->
                    new Share(
                        rs.getObject("election_date", LocalDate.class),
                        rs.getString("component"),
                        rs.getDouble("share")))
            .list())
      shares
          .computeIfAbsent(row.date(), date -> new LinkedHashMap<>())
          .put(row.component(), row.share());
    return shares.entrySet().stream()
        .map(election -> new ComparableRemainder.Reference(election.getKey(), election.getValue()))
        .toList();
  }
}
