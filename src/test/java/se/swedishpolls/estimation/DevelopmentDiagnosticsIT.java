package se.swedishpolls.estimation;

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
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.testsupport.TestDatabase;

/**
 * Scores the frozen development folds on the archived pre-2022 development rows and measures the
 * registered diagnostics and both sensitivity reruns. The default run covers the first eight folds,
 * which is the smallest set the paired gate accepts; `-Ddiagnostics.full=true` runs every fold and
 * rewrites docs/validation/diagnostics.json.
 */
class DevelopmentDiagnosticsIT {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path TUNING = Path.of("docs", "validation", "tuning.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path RESULT = Path.of("docs", "validation", "diagnostics.json");

  @Test
  void scoresEveryFoldAgainstTheBaselineAndReferenceAndMeasuresBothSensitivityCases()
      throws Exception {
    final java.lang.String schema = "diagnostics_" + UUID.randomUUID().toString().replace("-", "");
    final org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
        TestDatabase.dataSource(schema);
    final org.flywaydb.core.Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      flyway.migrate();
      final org.springframework.jdbc.core.simple.JdbcClient db = JdbcClient.create(dataSource);
      final java.util.List<se.swedishpolls.source.Roster.CoveragePeriod> periods =
          new CoveragePeriodRepository(db).periods();
      final java.util.List<java.time.LocalDate> elections =
          db.sql("SELECT election_date FROM election_reference ORDER BY election_date")
              .query(LocalDate.class)
              .list();
      final List<PollCsv.Poll> polls;
      try (final java.io.InputStream input = getClass().getResourceAsStream("/polls/audit.csv")) {
        polls = PollCsv.parse(input.readAllBytes());
      }
      final se.swedishpolls.estimation.DevelopmentTuning.Protocol registered =
          DevelopmentTuning.protocol(PROTOCOL);
      final se.swedishpolls.estimation.DevelopmentTuning.Tuning tuning =
          DevelopmentTuning.tuning(TUNING);
      final se.swedishpolls.estimation.CoverageValidation.Report coverage =
          CoverageValidation.validation(COVERAGE);
      final se.swedishpolls.estimation.DevelopmentDiagnostics.Rules rules =
          DevelopmentDiagnostics.rules(PROTOCOL);
      final boolean full = Boolean.getBoolean("diagnostics.full");
      final java.util.List<se.swedishpolls.estimation.DevelopmentTuning.Fold> folds =
          full
              ? registered.folds()
              : registered.folds().subList(0, DevelopmentDiagnostics.MIN_FOLDS);
      final se.swedishpolls.estimation.DevelopmentTuning.Protocol protocol =
          new DevelopmentTuning.Protocol(registered.version(), folds, registered.grid());

      final se.swedishpolls.estimation.DevelopmentDiagnostics.Report report =
          DevelopmentDiagnostics.report(
              periods, polls, elections, protocol, tuning, coverage, rules);

      // Every fold of every roster is either scored or listed with a reason; none is dropped.
      assertEquals(folds.size() * periods.size(), report.folds().size() + report.unscored().size());
      for (se.swedishpolls.estimation.DevelopmentDiagnostics.Fold fold : report.folds()) {
        assertEquals(3, fold.meanLogScore().size());
        for (java.lang.Double score : fold.meanLogScore().values())
          assertTrue(Double.isFinite(score));
        assertTrue(fold.scoredPolls() > 0 && fold.trainingObservations() > 0);
        assertEquals(64, fold.trainingRowsSha256().length());
        assertTrue(
            registered.grid().walkVariances().contains(fold.referenceParameters().walkVariance()));
      }
      // The eight-party roster scores every fold; only the candidate FI period can lack one.
      final java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Fold> eight =
          report.folds().stream().filter(f -> f.periodId().equals("eight_party_2010")).toList();
      assertEquals(folds.size(), eight.size());
      assertTrue(
          report.unscored().stream()
              .allMatch(unscored -> unscored.periodId().equals("fi_candidate_2014_2018")),
          report.unscored()::toString);

      final se.swedishpolls.estimation.DevelopmentDiagnostics.Paired paired =
          report.paired().stream()
              .filter(comparison -> comparison.periodId().equals("eight_party_2010"))
              .findFirst()
              .orElseThrow();
      assertEquals(eight.size(), paired.folds());
      assertEquals(List.of(1, 3, 6), List.copyOf(paired.standardErrorByLag().keySet()));
      assertEquals(paired.pairedStandardError(), paired.standardErrorByLag().get(3), 1e-14);
      assertEquals(
          paired.baselineDifference() > 0
              && paired.referenceDifference() >= -paired.pairedStandardError(),
          paired.passes());

      // Coverage is pooled per candidate and broken down for the estimator under test.
      final java.util.List<se.swedishpolls.estimation.DevelopmentDiagnostics.Misfit> pooled =
          report.misfit().stream()
              .filter(row -> row.periodId().equals("eight_party_2010") && row.scope().equals("all"))
              .toList();
      assertEquals(3, pooled.size());
      for (se.swedishpolls.estimation.DevelopmentDiagnostics.Misfit row : pooled) {
        assertEquals(row.polls() * 9, row.cases());
        assertTrue(row.coverage95() >= row.coverage50(), row::toString);
      }
      final java.util.List<java.lang.String> parties =
          report.misfit().stream()
              .filter(
                  row -> row.periodId().equals("eight_party_2010") && row.scope().equals("party"))
              .map(DevelopmentDiagnostics.Misfit::name)
              .toList();
      // The roster's own component order, which is the order the ilr basis was built in.
      assertEquals(List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER"), parties);
      assertFalse(
          report.misfit().stream()
              .noneMatch(row -> row.scope().equals("institute") && row.name().equals("Sifo")));

      assertEquals(
          List.of(1, 2, 3),
          report.autocorrelation().stream()
              .filter(row -> row.periodId().equals("eight_party_2010"))
              .map(DevelopmentDiagnostics.Autocorrelation::lag)
              .toList());
      final se.swedishpolls.estimation.DevelopmentDiagnostics.Dependence dependence =
          report.dependence().stream()
              .filter(row -> row.periodId().equals("eight_party_2010"))
              .findFirst()
              .orElseThrow();
      assertTrue(dependence.overlappingPairs() > 0 && dependence.disjointPairs() > 0);

      // Both sensitivity reruns describe the one validated period, and neither is a release value.
      assertEquals(1, report.centering().size());
      final se.swedishpolls.estimation.DevelopmentDiagnostics.CenteringShift centering =
          report.centering().getFirst();
      assertEquals("eight_party_2010", centering.periodId());
      assertEquals(LocalDate.of(2021, 9, 20), centering.headlineDate());
      assertEquals(4278, centering.comparedDays());
      assertTrue(centering.maxDailyShiftPoints() >= centering.maxHeadlineShiftPoints());
      assertFalse(report.leaveOneInstituteOut().isEmpty());
      for (se.swedishpolls.estimation.DevelopmentDiagnostics.LeftOut left :
          report.leaveOneInstituteOut()) {
        assertEquals("eight_party_2010", left.periodId());
        assertTrue(left.droppedPolls() > 0);
        assertEquals(
            Math.max(left.maxShiftPoints(), left.maxDailyShiftPoints())
                > DevelopmentDiagnostics.DISCLOSED_SHIFT_POINTS,
            left.needsDisclosure());
        assertEquals(9, left.shiftPoints().size());
        // The headline is one day of the compared history, so it can never move further than the
        // largest day does.
        assertTrue(left.maxDailyShiftPoints() >= left.maxShiftPoints() - 1e-12, left::toString);
        assertTrue(left.comparedDays() > 0 && left.maxDailyShiftOn() != null);
      }

      // Nothing here is a release value: the tuning gate behind these parameters is still blocked.
      assertTrue(report.gate().blocked());
      assertTrue(
          report.gate().reasons().stream()
              .anyMatch(reason -> reason.startsWith("coverage validation: ")));

      if (full)
        Files.writeString(
            RESULT, DevelopmentDiagnostics.report(report) + "\n", StandardCharsets.UTF_8);
      else
        assertTrue(
            Files.exists(RESULT),
            "Committed diagnostic evidence is missing; rerun with -Ddiagnostics.full=true");
    } finally {
      flyway.clean();
    }
  }
}
