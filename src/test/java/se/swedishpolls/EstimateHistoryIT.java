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
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path RESULT = Path.of("docs", "validation", "history.json");

  @Test
  void publishesOneSmoothedSeriesPerValidatedPeriodAndDatesTheHeadlineAtTheLastFieldworkDate()
      throws Exception {
    final java.lang.String schema = "history_" + UUID.randomUUID().toString().replace("-", "");
    final org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
        TestDatabase.dataSource(schema);
    final org.flywaydb.core.Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      flyway.migrate();
      final org.springframework.jdbc.core.simple.JdbcClient db = JdbcClient.create(dataSource);
      final java.util.List<se.swedishpolls.Roster.CoveragePeriod> periods =
          new Roster(db).periods();
      final java.util.List<java.time.LocalDate> elections =
          db.sql("SELECT election_date FROM election_reference ORDER BY election_date")
              .query(LocalDate.class)
              .list();
      final List<PollCsv.Poll> polls;
      try (final java.io.InputStream input = getClass().getResourceAsStream("/polls/audit.csv")) {
        polls = PollCsv.parse(input.readAllBytes());
      }
      final se.swedishpolls.CoverageValidation.Report coverage =
          CoverageValidation.validation(COVERAGE);
      final se.swedishpolls.JointUncertainty.Rules draws = JointUncertainty.rules(PROTOCOL);
      final se.swedishpolls.EstimateHistory.Publication publication =
          EstimateHistory.publication(PROTOCOL);

      final se.swedishpolls.EstimateHistory.History history =
          EstimateHistory.history(periods, polls, elections, coverage, draws, publication);

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
      final se.swedishpolls.EstimateHistory.Segment segment = history.segments().getFirst();
      assertEquals(LocalDate.of(2010, 1, 4), segment.from());
      assertEquals(
          segment.days().size(),
          (int) java.time.temporal.ChronoUnit.DAYS.between(segment.from(), segment.to()) + 1);
      for (se.swedishpolls.EstimateHistory.Day day : segment.days())
        assertEquals(
            100, day.shares().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
      assertTrue(
          history.boundaries().stream()
              .noneMatch(b -> b.kind().equals(EstimateHistory.UNSUPPORTED_GAP)),
          history.boundaries()::toString);
      assertEquals(EstimateHistory.COVERAGE_PERIOD_START, history.boundaries().getFirst().kind());

      // The published point estimate is the drawn mean, not the transform of the mean state: the
      // diagnostic runs over the same days and differs from the published series on some of them.
      final se.swedishpolls.Roster.CoveragePeriod eight =
          periods.stream().filter(p -> p.id().equals("eight_party_2010")).findFirst().orElseThrow();
      final se.swedishpolls.CoverageValidation.Validated evidence =
          coverage.periods().stream()
              .filter(v -> v.periodId().equals("eight_party_2010"))
              .findFirst()
              .orElseThrow();
      final java.util.List<se.swedishpolls.EstimateHistory.Composition> diagnostic =
          EstimateHistory.internalStateMean(
              eight, polls, elections, evidence.parameters(), coverage.rules());
      assertEquals(segment.days().size(), diagnostic.size());
      double shift = 0;
      for (int day = 0; day < diagnostic.size(); day++) {
        assertEquals(segment.days().get(day).date(), diagnostic.get(day).date());
        for (java.util.Map.Entry<java.lang.String, java.lang.Double> component :
            diagnostic.get(day).shares().entrySet())
          shift =
              Math.max(
                  shift,
                  Math.abs(
                      component.getValue()
                          - segment.days().get(day).shares().get(component.getKey())));
      }
      assertTrue(shift > 0.01, "The drawn mean and the state mean differ by " + shift);

      // Mean, lower and upper come from one pass over a day's draws, so the point estimate lies
      // inside its own interval by construction.
      for (se.swedishpolls.EstimateHistory.Day day : segment.days())
        for (se.swedishpolls.EstimateHistory.Estimate component : day.components().values())
          for (se.swedishpolls.JointUncertainty.Interval interval : component.intervals())
            assertTrue(
                interval.lower() < component.mean() && component.mean() < interval.upper(),
                component::toString);

      // The headline is dated at the last fieldwork date and estimated on the last midpoint; the
      // days between are not walked forward.
      final se.swedishpolls.EstimateHistory.Headline headline = history.headline();
      assertEquals(LocalDate.of(2021, 10, 3), headline.asOf());
      assertEquals(segment.to(), headline.estimatedOn());
      assertTrue(headline.estimatedOn().isBefore(headline.asOf()));
      assertEquals(segment.days().getLast().shares(), headline.shares());

      // No estimate here is a release value: the tuning run behind these parameters is blocked.
      assertTrue(history.gate().blocked());
      assertEquals(coverage.gate().reasons(), history.gate().reasons());

      // A change across the election cycles that changed the centering reference is suppressed.
      final java.util.Optional<se.swedishpolls.EstimateHistory.Boundary> reference =
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
