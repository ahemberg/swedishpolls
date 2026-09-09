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

/**
 * Builds the daily estimate history of the validated periods from the archived pre-2022 development
 * rows and the committed coverage evidence. `-Dhistory.full=true` rewrites
 * docs/validation/history.json.
 */
class EstimateHistoryIT {
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path RESULT = Path.of("docs", "validation", "history.json");

  @Test
  void publishesOneSmoothedSeriesPerValidatedPeriodAndDatesTheHeadlineAtTheLastFieldworkDate()
      throws Exception {
    var schema = "history_" + UUID.randomUUID().toString().replace("-", "");
    var dataSource = TestDatabase.dataSource(schema);
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

      var history = EstimateHistory.history(periods, polls, elections, coverage);

      // Only the validated roster publishes a curve; the candidate segment stays unavailable.
      assertTrue(
          history.segments().stream().allMatch(s -> s.periodId().equals("eight_party_2010")),
          history.segments()::toString);
      assertEquals(
          List.of(new EstimateHistory.Unavailable("FI", EstimateHistory.NO_VALIDATED_PERIOD)),
          history.unavailable());
      // The largest internal gap of the eight-party period is 43 days, inside the registered 45,
      // so support never breaks and the whole window is one segment.
      assertEquals(1, history.segments().size());
      var segment = history.segments().getFirst();
      assertEquals(LocalDate.of(2010, 1, 4), segment.from());
      assertEquals(
          segment.days().size(),
          (int) java.time.temporal.ChronoUnit.DAYS.between(segment.from(), segment.to()) + 1);
      for (var day : segment.days())
        assertEquals(
            100, day.shares().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
      assertTrue(
          history.boundaries().stream()
              .noneMatch(b -> b.kind().equals(EstimateHistory.UNSUPPORTED_GAP)),
          history.boundaries()::toString);
      assertEquals(EstimateHistory.COVERAGE_PERIOD_START, history.boundaries().getFirst().kind());

      // The headline is dated at the last fieldwork date and estimated on the last midpoint; the
      // days between are not walked forward.
      var headline = history.headline();
      assertEquals(LocalDate.of(2021, 10, 3), headline.asOf());
      assertEquals(segment.to(), headline.estimatedOn());
      assertTrue(headline.estimatedOn().isBefore(headline.asOf()));
      assertEquals(segment.days().getLast().shares(), headline.shares());

      // No estimate here is a release value: the tuning run behind these parameters is blocked.
      assertTrue(history.gate().blocked());
      assertEquals(coverage.gate().reasons(), history.gate().reasons());

      // A change across the election cycles that changed the centering reference is suppressed.
      var reference =
          history.boundaries().stream()
              .filter(b -> b.kind().equals(EstimateHistory.REFERENCE_CHANGE))
              .findFirst();
      assertTrue(reference.isPresent(), history.boundaries()::toString);
      assertEquals(
          EstimateHistory.ACROSS_BOUNDARY,
          EstimateHistory.change(
                  history, "eight_party_2010", reference.orElseThrow().date().plusDays(10), 30)
              .reason());
      assertTrue(
          EstimateHistory.change(history, "eight_party_2010", headline.estimatedOn(), 30)
              .available());

      if (Boolean.getBoolean("history.full"))
        Files.writeString(RESULT, EstimateHistory.report(history) + "\n", StandardCharsets.UTF_8);
      else
        assertTrue(
            Files.exists(RESULT),
            "Committed estimate history is missing; rerun with -Dhistory.full=true");
    } finally {
      flyway.clean();
    }
  }
}
