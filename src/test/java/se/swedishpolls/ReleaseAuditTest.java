package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseAuditTest {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path SOURCE = Path.of("src", "test", "resources", "polls", "audit.csv");

  @Test
  void theCandidateSelectionReproducesThePinnedManifestAndRejectsAnythingElse() throws Exception {
    final ReleaseAudit.Manifest manifest = ReleaseAudit.manifest(PROTOCOL);
    assertEquals(LocalDate.of(2022, 9, 10), manifest.cutoff());
    assertEquals(LocalDate.of(2022, 9, 11), manifest.electionDate());
    final byte[] source = Files.readAllBytes(SOURCE);
    final ReleaseAudit.Selection selection = ReleaseAudit.select(source, manifest);
    assertEquals(manifest.candidateCount(), selection.rows());
    assertEquals(manifest.candidateRowsSha256(), selection.rowsSha256());

    final List<PollCsv.Poll> polls = PollCsv.parse(source);
    final List<PollCsv.Poll> candidates = ReleaseAudit.candidates(polls, selection);
    assertEquals(selection.rows(), candidates.size());
    assertTrue(
        candidates.stream()
            .allMatch(
                poll ->
                    poll.publicationDate() != null
                        && !poll.publicationDate().isAfter(manifest.cutoff())
                        && !poll.collectionTo().isAfter(manifest.cutoff())));

    final byte[] changed = Files.readAllBytes(SOURCE);
    changed[changed.length - 2] = (byte) '0';
    assertThrows(IllegalArgumentException.class, () -> ReleaseAudit.select(changed, manifest));
  }

  @Test
  void officialOtherIsExactlyOneHundredMinusTheEightDisplayedPercentages() {
    final Map<String, BigDecimal> outcome = ReleaseAudit.outcome(ReleaseAudit.manifest(PROTOCOL));
    assertEquals(new BigDecimal("1.54"), outcome.get("OTHER"));
    assertEquals(
        new BigDecimal("100.00"),
        outcome.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  @Test
  void aFrozenRegistrationRefusesAMovedProtocolOrAMovedEvidenceFile(@TempDir Path directory)
      throws Exception {
    write(directory, "protocol.json", protocol());
    write(directory, "diagnostics.json", diagnostics(-0.01));
    final Path registration = write(directory, "release-protocol.json", registration(directory));
    final ReleaseAudit.Frozen frozen = ReleaseAudit.frozen(registration, directory);
    assertEquals("v1-development-1", frozen.developmentProtocolVersion());
    assertEquals(349, frozen.tolerances().seatTotal());

    write(directory, "protocol.json", protocol().replace("development-1", "development-2"));
    assertThrows(
        IllegalArgumentException.class, () -> ReleaseAudit.frozen(registration, directory));
  }

  @Test
  void aBlockedDevelopmentGateBlocksTheReleaseUntilAnExplicitWaiverNamesIt(@TempDir Path directory)
      throws Exception {
    write(directory, "protocol.json", protocol());
    final Path diagnostics = write(directory, "diagnostics.json", diagnostics(-0.01));
    final Path registration = write(directory, "release-protocol.json", registration(directory));
    final ReleaseAudit.Frozen frozen = ReleaseAudit.frozen(registration, directory);
    final ReleaseAudit.Report blocked =
        evaluate(frozen, directory, diagnostics, gates(List.of("the reference comparison fails")));
    assertEquals(ReleaseAudit.STATUS_BLOCKED, blocked.verdict().status());
    assertTrue(
        blocked.verdict().blockingReasons().stream()
            .anyMatch(reason -> reason.contains("the reference comparison fails")));
    assertThrows(IllegalStateException.class, () -> ReleaseAudit.requireReleasable(blocked));
    assertFalse(
        blocked.verdict().gates().stream()
            .filter(gate -> gate.name().equals("predictive_log_score_vs_reference:period"))
            .findFirst()
            .orElseThrow()
            .passed());

    write(directory, "release-protocol.json", waiving(directory, "development_gates"));
    final ReleaseAudit.Report waived =
        evaluate(
            ReleaseAudit.frozen(registration, directory),
            directory,
            diagnostics,
            gates(List.of("the reference comparison fails")));
    assertEquals(ReleaseAudit.STATUS_RELEASED, waived.verdict().status());
    assertTrue(waived.verdict().blockingReasons().isEmpty());
    ReleaseAudit.requireReleasable(waived);

    write(directory, "release-protocol.json", waiving(directory, "no_such_gate"));
    final ReleaseAudit.Frozen unknown = ReleaseAudit.frozen(registration, directory);
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluate(unknown, directory, diagnostics, gates(List.of("a reason"))));
  }

  @Test
  void aFailedReleaseToleranceBlocksOnItsOwnEvidence(@TempDir Path directory) throws Exception {
    write(directory, "protocol.json", protocol());
    final Path diagnostics = write(directory, "diagnostics.json", diagnostics(0.01));
    final Path registration = write(directory, "release-protocol.json", registration(directory));
    final ReleaseAudit.Frozen frozen = ReleaseAudit.frozen(registration, directory);
    final ReleaseAudit.Report passed = evaluate(frozen, directory, diagnostics, gates(List.of()));
    assertEquals(ReleaseAudit.STATUS_RELEASED, passed.verdict().status());

    final ReleaseAudit.Report short349 =
        ReleaseAudit.evaluate(
            frozen,
            directory,
            diagnostics,
            gates(List.of()),
            election(99.5, 348, true),
            ReleaseAudit.misfit(directory.resolve("protocol.json"), diagnostics),
            ReleaseAudit.sensitivity(diagnostics, frozen.tolerances()),
            individualFi(frozen, gates(List.of())));
    assertEquals(ReleaseAudit.STATUS_BLOCKED, short349.verdict().status());
    assertEquals(
        2,
        short349.verdict().blockingReasons().size(),
        short349.verdict().blockingReasons()::toString);

    final ReleaseAudit.Report irreproducible =
        ReleaseAudit.evaluate(
            frozen,
            directory,
            diagnostics,
            gates(List.of()),
            election(100, 349, false),
            ReleaseAudit.misfit(directory.resolve("protocol.json"), diagnostics),
            ReleaseAudit.sensitivity(diagnostics, frozen.tolerances()),
            individualFi(frozen, gates(List.of())));
    assertTrue(
        irreproducible.verdict().blockingReasons().stream()
            .anyMatch(reason -> reason.startsWith("audit_seeded_reproduction")));
  }

  @Test
  void theRuntimeTargetBlocksUnlessTheRegistrationReportsIt(@TempDir Path directory)
      throws Exception {
    write(directory, "protocol.json", protocol());
    final Path diagnostics = write(directory, "diagnostics.json", diagnostics(0.01));
    final Path registration =
        write(
            directory,
            "release-protocol.json",
            registration(directory).replace("\"resource_runtime_target\"", ""));
    final ReleaseAudit.Frozen enforced = ReleaseAudit.frozen(registration, directory);
    final ReleaseAudit.Report blocked =
        evaluate(enforced, directory, diagnostics, gates(List.of()));
    assertEquals(ReleaseAudit.STATUS_BLOCKED, blocked.verdict().status());
    assertEquals(1, blocked.verdict().blockingReasons().size());
    assertTrue(
        blocked.verdict().blockingReasons().getFirst().startsWith("resource_runtime_target"));

    write(
        directory,
        "release-protocol.json",
        registration(directory).replace("resource_runtime_target", "no_such_gate"));
    final ReleaseAudit.Frozen unknown = ReleaseAudit.frozen(registration, directory);
    assertThrows(
        IllegalArgumentException.class,
        () -> evaluate(unknown, directory, diagnostics, gates(List.of())));
  }

  @Test
  void theReservedComparisonReportsErrorsInclusionAndSeatsBesideTheOfficialAllocation() {
    final ReleaseAudit.Election election = election(100, 349, true);
    final ReleaseAudit.PartyResult first = election.parties().getFirst();
    assertEquals("S", first.component());
    assertEquals(0.5, first.errorPoints(), 1e-12);
    assertEquals(Map.of("50%", false, "95%", true), first.intervalInclusion());
    assertEquals(Map.of("50%", 7, "95%", 9), election.intervalInclusionCounts());
    assertEquals(0.5, election.maxAbsoluteErrorPoints(), 1e-12);
    assertEquals(1.0 / 9, election.meanAbsoluteErrorPoints(), 1e-12);
    assertEquals(349, election.approximateSeatTotal());
    assertEquals(349, election.officialSeatTotal());
    assertEquals(
        List.of(-2, 2),
        election.seats().stream().map(ReleaseAudit.SeatResult::differenceSeats).toList());
    assertTrue(election.fitsAreFinite());
    assertEquals(ReleaseAudit.INTERPRETATION, election.interpretation());
  }

  @Test
  void theIndividualFiFallbackAppliesWhereTheRosterDoesNotMeetTheGates(@TempDir Path directory)
      throws Exception {
    write(directory, "protocol.json", protocol());
    write(directory, "diagnostics.json", diagnostics(0.01));
    final Path registration = write(directory, "release-protocol.json", registration(directory));
    final ReleaseAudit.Frozen frozen = ReleaseAudit.frozen(registration, directory);
    final ReleaseAudit.IndividualFi met = individualFi(frozen, gates(List.of()));
    assertTrue(met.gatesMet());
    assertNull(met.fallback());

    final ReleaseAudit.IndividualFi fallback =
        individualFi(frozen, gates(List.of("fi_candidate_2014_2018 party FI misfits")));
    assertFalse(fallback.gatesMet());
    assertEquals("individual_fi_estimate_unavailable", fallback.disposition());
    assertEquals(1, fallback.reasons().size());
    assertTrue(fallback.supportDatesExact());
  }

  private static ReleaseAudit.Report evaluate(
      ReleaseAudit.Frozen frozen, Path directory, Path diagnostics, DevelopmentGates.Report gates) {
    return ReleaseAudit.evaluate(
        frozen,
        directory,
        diagnostics,
        gates,
        election(100, 349, true),
        ReleaseAudit.misfit(directory.resolve("protocol.json"), diagnostics),
        ReleaseAudit.sensitivity(diagnostics, frozen.tolerances()),
        individualFi(frozen, gates));
  }

  private static ReleaseAudit.IndividualFi individualFi(
      ReleaseAudit.Frozen frozen, DevelopmentGates.Report gates) {
    final Roster.CoveragePeriod period =
        new Roster.CoveragePeriod(
            "fi_candidate_2014_2018",
            LocalDate.of(2014, 4, 9),
            LocalDate.of(2018, 9, 7),
            List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI"),
            true,
            false,
            "https://example.invalid/decision");
    final CoverageValidation.Support support =
        new CoverageValidation.Support(
            period.effectiveFrom(),
            period.effectiveTo(),
            388,
            10,
            0,
            Map.of(),
            new CoverageValidation.Gap(period.effectiveFrom(), period.effectiveFrom(), 0));
    return ReleaseAudit.individualFi(frozen, period, support, 1, gates);
  }

  /**
   * S is half a point high and outside its 50% interval, M half a point low and inside both, and
   * OTHER exact. The seat maps differ by two so the approximation and the official allocation stay
   * visibly separate.
   */
  private static ReleaseAudit.Election election(
      double compositionSum, int approximateSeats, boolean reproduced) {
    final Map<String, String> shares = new LinkedHashMap<>();
    shares.put("S", "30.00");
    shares.put("M", "20.00");
    shares.put("SD", "20.00");
    shares.put("V", "6.00");
    shares.put("C", "6.00");
    shares.put("KD", "6.00");
    shares.put("L", "6.00");
    shares.put("MP", "5.00");
    final ReleaseAudit.Manifest manifest =
        new ReleaseAudit.Manifest(
            LocalDate.of(2022, 9, 10),
            LocalDate.of(2022, 9, 11),
            "commit",
            "sha",
            "line",
            "selection",
            2,
            "rows",
            "https://example.invalid/outcome",
            "fixture",
            "docs/research/audit-polls.py",
            "fixture-sha",
            shares);
    final List<JointUncertainty.Component> components =
        List.of(
            component("S", 30.5, compositionSum - 100),
            component("M", 19.5, 0),
            component("SD", 20, 0),
            component("V", 6, 0),
            component("C", 6, 0),
            component("KD", 6, 0),
            component("L", 6, 0),
            component("MP", 5, 0),
            component("OTHER", 1, 0));
    final JointUncertainty.Reproduction reproduction =
        new JointUncertainty.Reproduction(
            20260908,
            10_000,
            "eight_party_2010",
            components.stream().map(JointUncertainty.Component::component).toList(),
            "basis",
            "rows",
            2,
            new DailyStateSpace.Parameters(1e-4, 0.1, 1.5),
            "implementation",
            "25",
            "25+1",
            "Linux",
            "amd64",
            "0.44");
    final JointUncertainty.FinalDay finalDay =
        new JointUncertainty.FinalDay(
            "eight_party_2010",
            new JointUncertainty.Day(LocalDate.of(2022, 9, 9), components),
            null,
            reproduction);
    final Map<String, Integer> approximate = new LinkedHashMap<>();
    approximate.put("S", 120);
    approximate.put("M", approximateSeats - 120);
    final Map<String, Integer> official = new LinkedHashMap<>();
    official.put("S", 122);
    official.put("M", 227);
    return ReleaseAudit.compare(
        manifest,
        new ReleaseAudit.Selection(2, "rows", List.of(2, 3)),
        finalDay,
        List.of(-1234.5),
        2,
        new NationalSeats.Allocation(approximate, List.of("S", "M"), List.of("OTHER"), 0),
        official,
        new JointUncertainty.Reproduced(1, 90_000, reproduced ? 0 : 1e-15));
  }

  /** A component whose mean carries any residual needed to move the composition off 100. */
  private static JointUncertainty.Component component(String name, double mean, double adjustment) {
    return new JointUncertainty.Component(
        name,
        mean + adjustment,
        mean + adjustment,
        List.of(
            new JointUncertainty.Interval(0.5, mean - 0.2, mean + 0.2),
            new JointUncertainty.Interval(0.95, mean - 1, mean + 1)));
  }

  private static DevelopmentGates.Report gates(List<String> reasons) {
    return new DevelopmentGates.Report(
        "v1-development-1",
        new CoverageValidation.Gate(!reasons.isEmpty(), reasons),
        DevelopmentGates.FALLBACK_NOT_REQUIRED,
        List.of(
            new DevelopmentGates.Tolerance(
                "seeded_reproduction",
                "percentage points",
                0,
                0,
                1,
                List.of(20260908L),
                "docs/validation/uncertainty.json",
                "0".repeat(64),
                "A rerun reproduces every retained draw.")),
        new DevelopmentGates.Drift(
            "0".repeat(64),
            List.of(
                new DevelopmentGates.Perturbation(
                    "addition_deletion", 1, "Novus", 10, 0.1, LocalDate.of(2021, 9, 4), "S"))),
        new DevelopmentGates.CrossArchitecture(
            1,
            0,
            0,
            List.of(
                new DevelopmentGates.ArchitectureRun("amd64", "25", "a"),
                new DevelopmentGates.ArchitectureRun("arm64", "25", "a"))),
        precision(),
        new DevelopmentGates.Resources(
            124_234, 1, 10_000, 1038, 4278, 10_000, "amd64", "25", "vm", "linux"));
  }

  private static DevelopmentGates.ProbabilityPrecision precision() {
    return new DevelopmentGates.ProbabilityPrecision(
        "eight_party_2010",
        LocalDate.of(2021, 9, 20),
        "rows",
        "implementation",
        List.of("S", "M"),
        new DevelopmentGates.AllocationRules(349, 4, 1.2, 175, List.of("S", "M")),
        List.of("M"),
        10_000,
        List.of(
            new DevelopmentGates.ProbabilityRun(1, Map.of("S", 0.5), 0.5),
            new DevelopmentGates.ProbabilityRun(2, Map.of("S", 0.51), 0.51)));
  }

  /** The protocol fields the release audit reads: the frozen bands and subgroup misfit limits. */
  private static String protocol() {
    return """
        {
          "version": "v1-development-1",
          "predictive_coverage95": [0.9, 0.98],
          "predictive_coverage50": [0.4, 0.6],
          "resolved_diagnostic_gates": {
            "minimum_subgroup_cases": 100,
            "maximum_absolute_mean_standardized_residual": 0.5,
            "minimum_root_mean_square_standardized_residual": 0.5,
            "maximum_root_mean_square_standardized_residual": 1.5,
            "maximum_absolute_residual_autocorrelation": 0.45,
            "autocorrelation_explanation": "structural, reported beside the pair measurements"
          }
        }
        """;
  }

  /** One period, one subgroup per registered scope, and a paired comparison of the given sign. */
  private static String diagnostics(double referenceDifference) {
    return """
        {
          "rules": {"coverage95": [0.9, 0.98], "coverage50": [0.4, 0.6]},
          "paired": [{"periodId": "period", "folds": 48, "baselineDifference": 1.16,
                      "referenceDifference": %s, "pairedStandardError": 0.005}],
          "unscored": [],
          "misfit": [
            {"periodId": "period", "candidate": "midpoint", "scope": "all", "name": "all",
             "cases": 3330, "coverage95": 0.927, "coverage50": 0.518,
             "meanStandardizedResidual": 0.01, "rootMeanSquareStandardizedResidual": 1.05},
            {"periodId": "period", "candidate": "midpoint", "scope": "party", "name": "S",
             "cases": 370, "coverage95": 0.95, "coverage50": 0.5,
             "meanStandardizedResidual": 0.01, "rootMeanSquareStandardizedResidual": 1.0},
            {"periodId": "period", "candidate": "midpoint", "scope": "institute", "name": "Novus",
             "cases": 370, "coverage95": 0.95, "coverage50": 0.5,
             "meanStandardizedResidual": 0.01, "rootMeanSquareStandardizedResidual": 1.0},
            {"periodId": "period", "candidate": "midpoint", "scope": "fieldwork_days",
             "name": "1-7", "cases": 370, "coverage95": 0.95, "coverage50": 0.5,
             "meanStandardizedResidual": 0.01, "rootMeanSquareStandardizedResidual": 1.0}
          ],
          "centering": [{"periodId": "period", "headlineDate": "2021-09-20",
                         "maxHeadlineShiftPoints": 0.28, "maxDailyShiftPoints": 0.33}],
          "leaveOneInstituteOut": [{"periodId": "period", "institute": "Sentio",
                                    "maxShiftPoints": 0.44, "maxDailyShiftPoints": 1.02}]
        }
        """
        .formatted(referenceDifference);
  }

  /** A release registration whose evidence is the temporary diagnostics file. */
  private static String registration(Path directory) throws Exception {
    return """
        {
          "version": "v1-release-1",
          "registered_on": "2026-09-10",
          "status": "frozen-release",
          "development_protocol": {"version": "v1-development-1", "path": "protocol.json",
                                   "sha256": "%s"},
          "frozen_evidence": [{"name": "diagnostics", "path": "diagnostics.json",
                               "sha256": "%s"}],
          "release_tolerances": {"composition_sum_points": 1.0E-9, "seat_total": 349,
                                 "minimum_scored_folds": 8,
                                 "sensitivity_disclosure_points": 10.0},
          "reported_not_blocking": ["resource_runtime_target"],
          "audit_rules": {"fit": "one frozen fit"},
          "fi_fallback": {"id": "individual_fi_estimate_unavailable",
                          "approval": "https://example.invalid/decision",
                          "note": "estimates unavailable, observations retained"},
          "waivers": []
        }
        """
        .formatted(
            ReleaseAudit.sha256(directory.resolve("protocol.json")),
            ReleaseAudit.sha256(directory.resolve("diagnostics.json")));
  }

  /** The same registration with one explicit owner waiver of the named gate. */
  private static String waiving(Path directory, String gate) throws Exception {
    return registration(directory)
        .replace(
            "\"waivers\": []",
            "\"waivers\": [{\"gate\": \""
                + gate
                + "\", \"decisionUrl\": \"https://example.invalid/decision\","
                + " \"rationale\": \"the owner accepted the loss\"}]");
  }

  private static Path write(Path directory, String name, String content) throws Exception {
    final Path file = directory.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }
}
