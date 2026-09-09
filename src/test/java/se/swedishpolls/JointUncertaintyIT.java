package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Draws the joint uncertainty of the archived pre-2022 development rows at the registered seed and
 * draw count. `-Duncertainty.full=true` also runs the repeated-seed precision study and rewrites
 * docs/validation/uncertainty.json.
 */
class JointUncertaintyIT {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path RESULT = Path.of("docs", "validation", "uncertainty.json");

  @Test
  void drawsTheFinalDayTenThousandTimesAndReproducesEveryDrawAtTheRegisteredSeed()
      throws Exception {
    var schema = "uncertainty_" + UUID.randomUUID().toString().replace("-", "");
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
      var elections =
          db.sql("SELECT election_date FROM election_reference ORDER BY election_date")
              .query(LocalDate.class)
              .list();
      List<PollCsv.Poll> polls;
      try (var input = getClass().getResourceAsStream("/polls/audit.csv")) {
        polls = PollCsv.parse(input.readAllBytes());
      }
      var coverage = CoverageValidation.validation(COVERAGE);
      var rules = JointUncertainty.rules(PROTOCOL);
      boolean full = Boolean.getBoolean("uncertainty.full");
      var seeds = full ? JointUncertainty.precisionSeeds(rules) : List.of(rules.seed());

      var report = JointUncertainty.report(periods, polls, elections, coverage, rules, seeds);

      // Only the validated roster draws; the candidate segment publishes nothing here either.
      assertEquals(1, report.periods().size());
      var published = report.periods().getFirst();
      assertEquals("eight_party_2010", published.periodId());
      assertEquals(4278, published.estimatedDays());

      // The headline is the last estimated day of the last segment, drawn 10,000 times.
      var headline = published.headline();
      assertEquals(LocalDate.of(2021, 9, 20), headline.date());
      assertEquals(9, headline.components().size());
      assertEquals(
          100,
          headline.components().stream().mapToDouble(JointUncertainty.Component::mean).sum(),
          1e-9);
      for (var summary : headline.components()) {
        assertEquals(
            List.of(0.5, 0.95),
            summary.intervals().stream().map(JointUncertainty.Interval::level).toList());
        var half = summary.intervals().getFirst();
        var wide = summary.intervals().getLast();
        assertTrue(wide.lower() < half.lower() && half.upper() < wide.upper(), summary::toString);
        assertTrue(
            wide.lower() < summary.mean() && summary.mean() < wide.upper(), summary::toString);
      }

      // A rerun at the registered seed returns every retained draw unchanged.
      assertTrue(published.reproduced().exact(), published.reproduced()::toString);
      assertEquals(10000 * 9, published.reproduced().comparedValues());

      // Averaging transformed draws is not transforming the mean state, and the archived run says
      // by how much the two differ.
      assertTrue(published.maxStateMeanShiftPoints() > 0);
      assertTrue(published.maxStateMeanShiftPoints() < 1, published::toString);

      var reproduction = published.reproduction();
      assertEquals(rules.seed(), reproduction.seed());
      assertEquals(10000, reproduction.draws());
      assertEquals(1038, reproduction.inputRows());
      assertEquals(coverage.periods().getFirst().parameters(), reproduction.parameters());
      assertEquals(Runtime.version().toString(), reproduction.javaRuntimeVersion());
      for (var digest :
          List.of(
              reproduction.basisSha256(),
              reproduction.inputRowsSha256(),
              reproduction.implementationSha256())) assertEquals(64, digest.length(), digest);

      // The seeded-reproduction bound is proposed at zero; no tolerance here is resolved.
      assertTrue(
          report.proposedTolerances().stream()
              .anyMatch(bound -> bound.name().endsWith(":seeded_reproduction")),
          report.proposedTolerances()::toString);

      // Nothing here is a release value: the tuning gate behind these parameters is still blocked.
      assertTrue(report.gate().blocked());
      assertEquals(coverage.gate().reasons(), report.gate().reasons());

      if (full) {
        assertEquals(8, published.precision().getFirst().seeds());
        assertEquals(18, published.precision().size());
        Files.writeString(RESULT, JointUncertainty.report(report) + "\n", StandardCharsets.UTF_8);
      } else {
        assertTrue(published.precision().isEmpty());
        assertTrue(
            Files.exists(RESULT),
            "Committed uncertainty evidence is missing; rerun with -Duncertainty.full=true");
      }
    } finally {
      flyway.clean();
    }
  }
}
