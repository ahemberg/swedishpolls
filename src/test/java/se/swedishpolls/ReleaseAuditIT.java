package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The once-only reserved 2022 comparison and the release verdict it feeds. The archived result is
 * the audit; `-Daudit.full=true` reruns it at the frozen seed, which must reproduce the archived
 * numbers and is not a second audit.
 */
class ReleaseAuditIT {
  private static final Path ROOT = Path.of("");
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path RELEASE_PROTOCOL =
      Path.of("docs", "validation", "release-protocol.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path DIAGNOSTICS = Path.of("docs", "validation", "diagnostics.json");
  private static final Path DEVELOPMENT_GATES =
      Path.of("docs", "validation", "development-gates.json");
  private static final Path RESULT = Path.of("docs", "validation", "release-audit.json");
  private static final Path SOURCE = Path.of("src", "test", "resources", "polls", "audit.csv");
  private static final LocalDate ELECTION = LocalDate.of(2022, 9, 11);

  @Test
  void auditsTheReservedElectionOnceAndBlocksTheReleaseOnTheFailedGates() throws Exception {
    if (Boolean.getBoolean("audit.full")) {
      rebuild();
    }
    final ReleaseAudit.Report stored = ReleaseAudit.validation(RESULT);
    final ReleaseAudit.Frozen frozen = ReleaseAudit.frozen(RELEASE_PROTOCOL, ROOT);
    final DevelopmentGates.Report gates = DevelopmentGates.validation(DEVELOPMENT_GATES);
    final ReleaseAudit.Report rebuilt =
        ReleaseAudit.evaluate(
            frozen,
            ROOT,
            DIAGNOSTICS,
            gates,
            stored.election(),
            ReleaseAudit.misfit(PROTOCOL, DIAGNOSTICS),
            ReleaseAudit.dependence(PROTOCOL, DIAGNOSTICS),
            ReleaseAudit.sensitivity(DIAGNOSTICS, frozen.tolerances()),
            stored.individualFi());
    assertEquals(ReleaseAudit.report(stored), ReleaseAudit.report(rebuilt));

    assertEquals("v1-release-1", stored.frozen().version());
    assertEquals("v1-development-1", stored.frozen().developmentProtocolVersion());
    assertTrue(stored.frozen().waivers().isEmpty());

    final ReleaseAudit.Election election = stored.election();
    assertEquals(LocalDate.of(2022, 9, 10), election.cutoff());
    assertEquals(ELECTION, election.electionDate());
    assertEquals(LocalDate.of(2022, 9, 7), election.estimatedOn());
    assertEquals(1192, election.selection().rows());
    assertEquals(
        ReleaseAudit.manifest(PROTOCOL).candidateRowsSha256(), election.selection().rowsSha256());
    assertEquals(9, election.parties().size());
    assertEquals(1168, election.fittedObservations());
    assertEquals(Map.of("50%", 1, "95%", 2), election.intervalInclusionCounts());
    assertEquals(100, election.compositionSumPercent(), 1e-9);
    assertTrue(election.fitsAreFinite());
    assertEquals(349, election.approximateSeatTotal());
    assertEquals(349, election.officialSeatTotal());
    assertTrue(election.reproduced().exact());

    assertFalse(stored.individualFi().gatesMet());
    assertTrue(stored.individualFi().supportDatesExact());
    assertEquals("individual_fi_estimate_unavailable", stored.individualFi().fallback().id());
    assertTrue(
        stored.sensitivity().stream().noneMatch(ReleaseAudit.Sensitivity::disclosureRequired));
    assertEquals(2, stored.dependence().size());
    assertTrue(
        stored.dependence().stream()
            .allMatch(row -> row.residualAutocorrelation().size() == 3 && row.passes()));
    assertTrue(
        stored.dependence().stream()
            .allMatch(row -> row.overlappingCorrelation() > row.disjointCorrelation()));

    assertEquals(ReleaseAudit.STATUS_BLOCKED, stored.verdict().status());
    assertTrue(
        stored.verdict().blockingReasons().stream()
            .anyMatch(reason -> reason.contains("loses to the ilr-window reference")));
    assertThrows(IllegalStateException.class, () -> ReleaseAudit.requireReleasable(stored));
    assertTrue(
        stored.verdict().gates().stream()
            .filter(gate -> ReleaseAudit.BLOCKING.equals(gate.enforcement()))
            .filter(gate -> !gate.name().equals("development_gates"))
            .allMatch(ReleaseAudit.Gate::passed));
  }

  private void rebuild() throws Exception {
    final String schema = "release_audit_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource dataSource = TestDatabase.dataSource(schema);
    final Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      flyway.migrate();
      final JdbcClient db = JdbcClient.create(dataSource);
      final ReleaseAudit.Frozen frozen = ReleaseAudit.frozen(RELEASE_PROTOCOL, ROOT);
      final ReleaseAudit.Manifest manifest = ReleaseAudit.manifest(PROTOCOL);
      final byte[] source = Files.readAllBytes(SOURCE);
      final ReleaseAudit.Selection selection = ReleaseAudit.select(source, manifest);
      final List<PollCsv.Poll> polls = PollCsv.parse(source);
      final List<PollCsv.Poll> candidates = ReleaseAudit.candidates(polls, selection);
      final List<LocalDate> elections =
          db.sql(
                  "SELECT election_date FROM election_reference WHERE election_date < :election"
                      + " ORDER BY election_date")
              .param("election", ELECTION)
              .query(LocalDate.class)
              .list();

      final CoverageValidation.Report coverage = CoverageValidation.validation(COVERAGE);
      final List<Roster.CoveragePeriod> periods = new Roster(db).periods();
      final Roster.CoveragePeriod period =
          periods.stream()
              .filter(Roster.CoveragePeriod::supportValidated)
              .findFirst()
              .orElseThrow();
      final DailyStateSpace.Parameters parameters =
          coverage.periods().stream()
              .filter(validated -> validated.periodId().equals(period.id()))
              .findFirst()
              .orElseThrow()
              .parameters();
      final CoverageValidation.Rules auditRules = through(coverage.rules(), manifest.cutoff());
      final JointUncertainty.Rules uncertaintyRules = JointUncertainty.rules(PROTOCOL);

      final EstimateHistory.Fitted fitted =
          EstimateHistory.fitted(period, candidates, elections, parameters, auditRules);
      final JointUncertainty.FinalDay finalDay =
          JointUncertainty.finalDay(
              period, candidates, elections, parameters, auditRules, uncertaintyRules);
      final JointUncertainty.FinalDay repeated =
          JointUncertainty.finalDay(
              period, candidates, elections, parameters, auditRules, uncertaintyRules);

      final Map<String, Double> composition = new LinkedHashMap<>();
      for (final JointUncertainty.Component component : finalDay.day().components()) {
        composition.put(component.component(), component.mean());
      }
      final NationalSeats.Allocation approximate =
          NationalSeats.allocate(composition, new NationalSeats(db).rules(ELECTION.getYear()));

      final ReleaseAudit.Election election =
          ReleaseAudit.compare(
              manifest,
              selection,
              finalDay,
              fitted.spans().stream().map(span -> span.fit().logLikelihood()).toList(),
              fitted.spans().stream().mapToInt(span -> span.batch().observations().size()).sum(),
              approximate,
              officialSeats(db),
              JointUncertainty.reproduced(finalDay.draws(), repeated.draws()));

      final ReleaseAudit.Report report =
          ReleaseAudit.evaluate(
              frozen,
              ROOT,
              DIAGNOSTICS,
              DevelopmentGates.validation(DEVELOPMENT_GATES),
              election,
              ReleaseAudit.misfit(PROTOCOL, DIAGNOSTICS),
              ReleaseAudit.dependence(PROTOCOL, DIAGNOSTICS),
              ReleaseAudit.sensitivity(DIAGNOSTICS, frozen.tolerances()),
              individualFi(frozen, periods, polls, elections, coverage));
      Files.writeString(RESULT, ReleaseAudit.report(report) + "\n", StandardCharsets.UTF_8);
    } finally {
      flyway.clean();
    }
  }

  /**
   * The individual FI roster, fitted separately on the development window its own evidence was
   * measured on. The reserved comparison never reads it: the period ends in 2018.
   */
  private static ReleaseAudit.IndividualFi individualFi(
      ReleaseAudit.Frozen frozen,
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      CoverageValidation.Report coverage) {
    final Roster.CoveragePeriod fi =
        periods.stream().filter(Roster.CoveragePeriod::individualFi).findFirst().orElseThrow();
    final DailyStateSpace.Parameters parameters =
        coverage.periods().stream()
            .filter(validated -> validated.periodId().equals(fi.id()))
            .findFirst()
            .orElseThrow()
            .parameters();
    final CoverageValidation.Support support =
        CoverageValidation.support(fi, polls, coverage.rules());
    final EstimateHistory.Fitted fitted =
        EstimateHistory.fitted(fi, polls, elections, parameters, coverage.rules());
    return ReleaseAudit.individualFi(
        frozen, fi, support, fitted.spans().size(), DevelopmentGates.validation(DEVELOPMENT_GATES));
  }

  /** The official constituency allocation, reported beside the approximation and never merged. */
  private static Map<String, Integer> officialSeats(JdbcClient db) {
    final Map<String, Integer> seats = new LinkedHashMap<>();
    final List<Map.Entry<String, Integer>> rows =
        db.sql(
                """
                SELECT component, official_seats FROM election_party_reference
                WHERE election_date = :election AND official_seats > 0
                ORDER BY official_seats DESC, component
                """)
            .param("election", ELECTION)
            .query(
                (rs, index) ->
                    Map.entry(
                        rs.getString("component"), Integer.valueOf(rs.getInt("official_seats"))))
            .list();
    for (final Map.Entry<String, Integer> row : rows) {
      seats.put(row.getKey(), row.getValue());
    }
    return seats;
  }

  /** The registered coverage rules, read through the reserved comparison's own cutoff. */
  private static CoverageValidation.Rules through(
      CoverageValidation.Rules rules, LocalDate cutoff) {
    return new CoverageValidation.Rules(
        cutoff,
        rules.minObservations(),
        rules.minInstitutes(),
        rules.maxInternalGapDays(),
        new ArrayList<>(rules.boundaryShiftDays()),
        rules.stabilityBurnInDays(),
        rules.maxStabilityShiftPoints());
  }
}
