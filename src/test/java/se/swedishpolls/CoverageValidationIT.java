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
 * Validates the coverage periods against the archived pre-2022 development rows. The default run
 * uses the first registered boundary shift; `-Dcoverage.full=true` runs every shift and rewrites
 * docs/validation/coverage.json.
 */
class CoverageValidationIT {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path TUNING = Path.of("docs", "validation", "tuning.json");
  private static final Path RESULT = Path.of("docs", "validation", "coverage.json");

  @Test
  void establishesSupportedDatesAndMeasuresTheCandidateBoundaryStepForEveryModeledParty()
      throws Exception {
    var schema = "coverage_" + UUID.randomUUID().toString().replace("-", "");
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
      var registered = CoverageValidation.rules(PROTOCOL);
      boolean full = Boolean.getBoolean("coverage.full");
      var rules =
          full
              ? registered
              : new CoverageValidation.Rules(
                  registered.developmentThrough(),
                  registered.minObservations(),
                  registered.minInstitutes(),
                  registered.maxInternalGapDays(),
                  List.of(registered.boundaryShiftDays().getFirst()),
                  registered.stabilityBurnInDays(),
                  registered.maxStabilityShiftPoints());
      var tuning = DevelopmentTuning.tuning(TUNING);

      var report = CoverageValidation.validateAll(periods, polls, elections, tuning, rules);

      assertEquals(periods.size(), report.periods().size());
      var eight = report.periods().getFirst();
      var candidate = report.periods().get(1);
      assertEquals("eight_party_2010", eight.periodId());
      assertEquals("fi_candidate_2014_2018", candidate.periodId());
      // Supported dates come from the eligible observations, never from the declared endpoints.
      assertEquals(LocalDate.of(2014, 4, 9), candidate.support().from());
      assertEquals(LocalDate.of(2018, 9, 7), candidate.support().to());
      assertTrue(candidate.support().from().isAfter(eight.support().from()));
      assertTrue(eight.support().from().isAfter(LocalDate.of(2009, 12, 31)));
      // The reserved 2022 comparison and the prospective 2026 window stay outside this evidence.
      assertFalse(eight.support().to().isAfter(registered.developmentThrough()));
      for (var validated : report.periods()) {
        assertTrue(validated.support().observations() >= registered.minObservations());
        assertTrue(validated.support().institutes() >= registered.minInstitutes());
        assertTrue(
            validated.support().largestGap().days() <= registered.maxInternalGapDays(),
            validated::toString);
        assertEquals(rules.boundaryShiftDays().size(), validated.stability().size());
        for (var stability : validated.stability()) {
          assertEquals(
              validated.periodId().equals("eight_party_2010") ? 9 : 10,
              stability.maxShiftPoints().size());
          assertTrue(stability.comparedDays() > 0, stability::toString);
          assertTrue(
              stability.worst() <= registered.maxStabilityShiftPoints(), stability::toString);
        }
        assertTrue(validated.supported(), validated::toString);
      }
      // The candidate period places fewer polls than it excludes: a poll without an FI reading is
      // retained as a source observation and excluded from this roster with its reason.
      assertTrue(candidate.support().exclusionReasons().containsKey("missing_share:FI"));
      assertEquals(388, candidate.support().observations());

      assertEquals(2, report.boundaryEffects().size());
      for (var effect : report.boundaryEffects()) {
        assertEquals("fi_candidate_2014_2018", effect.periodId());
        assertEquals("eight_party_2010", effect.againstPeriodId());
        assertEquals(9, effect.stepPoints().size());
        assertTrue(effect.stepPoints().values().stream().allMatch(Double::isFinite));
      }
      // Every fit here uses development parameters from a blocked tuning run, so no individual FI
      // estimate is released by this evidence.
      assertTrue(report.gate().blocked());
      assertTrue(
          report.gate().reasons().stream().allMatch(reason -> reason.startsWith("development")),
          () -> report.gate().reasons().toString());
      assertFalse(periods.get(1).supportValidated());

      if (full)
        Files.writeString(RESULT, CoverageValidation.report(report) + "\n", StandardCharsets.UTF_8);
      else
        assertTrue(
            Files.exists(RESULT),
            "Committed coverage evidence is missing; rerun with -Dcoverage.full=true");
    } finally {
      flyway.clean();
    }
  }
}
