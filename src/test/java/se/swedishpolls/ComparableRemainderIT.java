package se.swedishpolls;

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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
    var schema = "remainder_" + UUID.randomUUID().toString().replace("-", "");
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
      flyway.migrate();
      var db = JdbcClient.create(dataSource);
      var periods = new Roster(db).periods();
      var elections = elections(db);
      List<PollCsv.Poll> polls;
      try (var input = getClass().getResourceAsStream("/polls/audit.csv")) {
        polls = PollCsv.parse(input.readAllBytes());
      }
      var coverage = CoverageValidation.validation(COVERAGE);
      var rules = JointUncertainty.rules(PROTOCOL);

      var report = ComparableRemainder.report(periods, polls, elections, coverage, rules);

      // Only the validated roster publishes; the candidate FI segment publishes nothing here.
      assertEquals(1, report.periods().size());
      var published = report.periods().getFirst();
      assertEquals("eight_party_2010", published.periodId());
      assertEquals(4278, published.estimatedDays());
      // FI is not separate in this period, so OTHER is already the comparable remainder.
      assertEquals(List.of("OTHER"), published.members());
      assertEquals(0, published.maxEndpointSumErrorPoints());

      var headline = published.headline();
      assertEquals(LocalDate.of(2021, 9, 20), headline.date());
      assertEquals(
          List.of(0.5, 0.95),
          headline.intervals().stream().map(JointUncertainty.Interval::level).toList());
      var half = headline.intervals().getFirst();
      var wide = headline.intervals().getLast();
      assertTrue(wide.lower() < half.lower() && half.upper() < wide.upper(), headline::toString);
      assertTrue(wide.lower() < headline.mean() && headline.mean() < wide.upper());

      // The remainder is the same draws the component summaries read, not a second run.
      var period =
          periods.stream()
              .filter(candidate -> candidate.id().equals(published.periodId()))
              .findFirst()
              .orElseThrow();
      var validated = coverage.periods().getFirst();
      var uncertainty =
          JointUncertainty.estimate(
              period,
              polls,
              elections.stream().map(ComparableRemainder.Reference::date).toList(),
              validated.parameters(),
              coverage.rules(),
              rules);
      var other =
          uncertainty.segments().getLast().days().getLast().components().stream()
              .filter(component -> component.component().equals("OTHER"))
              .findFirst()
              .orElseThrow();
      assertEquals(other.mean(), headline.mean(), 1e-12);
      assertEquals(other.intervals().getLast().lower(), wide.lower(), 1e-12);
      assertEquals(other.intervals().getLast().upper(), wide.upper(), 1e-12);

      // Every election is grouped into this period's components; the 2022 result is outside the
      // development history and is marked so rather than dropped.
      assertEquals(4, published.elections().size());
      for (var election : published.elections()) {
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
    var shares = new LinkedHashMap<LocalDate, LinkedHashMap<String, Double>>();
    for (var row :
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
