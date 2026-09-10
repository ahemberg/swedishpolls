package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Rebuilds the final development gate with `-Dgates.full=true`. */
class DevelopmentGatesIT {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path UNCERTAINTY = Path.of("docs", "validation", "uncertainty.json");
  private static final Path DIAGNOSTICS = Path.of("docs", "validation", "diagnostics.json");
  private static final Path CROSS_ARCHITECTURE =
      Path.of("docs", "validation", "cross-architecture.json");
  private static final Path PROBABILITY_PRECISION =
      Path.of("docs", "validation", "probability-precision.json");
  private static final Path RESULT = Path.of("docs", "validation", "development-gates.json");
  private static final int TARGET_MILLIS = 10_000;

  @Test
  void freezesDevelopmentTolerancesAndStopsAtTheFailedAggregateGate() throws Exception {
    if (Boolean.getBoolean("gates.full")) rebuild();
    final se.swedishpolls.DevelopmentGates.Report stored = DevelopmentGates.validation(RESULT);
    final se.swedishpolls.DevelopmentGates.Report rebuilt =
        DevelopmentGates.evaluate(
            PROTOCOL,
            COVERAGE,
            UNCERTAINTY,
            DIAGNOSTICS,
            CROSS_ARCHITECTURE,
            PROBABILITY_PRECISION,
            stored.drift(),
            stored.resources());
    assertEquals(DevelopmentGates.report(stored), DevelopmentGates.report(rebuilt));
    assertEquals(DevelopmentGates.FALLBACK_NOT_REQUIRED, stored.uncertaintyFallback());
    assertTrue(stored.tolerances().stream().allMatch(DevelopmentGates.Tolerance::passes));
    assertEquals(8, stored.probabilityPrecision().runs().size());
    assertEquals(
        List.of("amd64", "arm64"),
        stored.crossArchitecture().runs().stream()
            .map(DevelopmentGates.ArchitectureRun::architecture)
            .toList());
    assertTrue(stored.gate().blocked());
    assertTrue(
        stored.gate().reasons().stream()
            .anyMatch(reason -> reason.contains("loses to the ilr-window reference")));
    assertThrows(IllegalStateException.class, () -> DevelopmentGates.requirePassed(stored));
    assertFalse(stored.resources().meetsTarget());
  }

  private void rebuild() throws Exception {
    final java.lang.String schema =
        "development_gates_" + UUID.randomUUID().toString().replace("-", "");
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
          db.sql(
                  "SELECT election_date FROM election_reference WHERE election_date < DATE"
                      + " '2022-09-11' ORDER BY election_date")
              .query(LocalDate.class)
              .list();
      final List<PollCsv.Poll> polls;
      try (final java.io.InputStream input = getClass().getResourceAsStream("/polls/audit.csv")) {
        polls = PollCsv.parse(input.readAllBytes());
      }
      final se.swedishpolls.CoverageValidation.Report coverage =
          CoverageValidation.validation(COVERAGE);
      final se.swedishpolls.Roster.CoveragePeriod period =
          periods.stream()
              .filter(Roster.CoveragePeriod::supportValidated)
              .findFirst()
              .orElseThrow();
      final se.swedishpolls.CoverageValidation.Validated validated =
          coverage.periods().stream()
              .filter(candidate -> candidate.periodId().equals(period.id()))
              .findFirst()
              .orElseThrow();
      final se.swedishpolls.JointUncertainty.Rules uncertaintyRules =
          JointUncertainty.rules(PROTOCOL);
      final se.swedishpolls.EstimateHistory.Publication publication =
          EstimateHistory.publication(PROTOCOL);
      final se.swedishpolls.EstimateHistory.Estimated baseline =
          EstimateHistory.estimate(
              period, polls, elections, validated.parameters(), coverage.rules(), uncertaintyRules);
      final se.swedishpolls.DevelopmentGates.Drift drift =
          drift(
              period,
              polls,
              elections,
              coverage.rules(),
              validated.parameters(),
              uncertaintyRules,
              baseline);
      final java.util.ArrayList<java.lang.Object> retained = new ArrayList<Object>();
      final java.util.ArrayList<se.swedishpolls.DevelopmentGates.ProbabilityPrecision> probability =
          new ArrayList<DevelopmentGates.ProbabilityPrecision>();
      final java.util.List<java.lang.Long> precisionSeeds =
          JointUncertainty.precisionSeeds(uncertaintyRules);
      final se.swedishpolls.DevelopmentGates.AllocationRules allocationRules =
          DevelopmentGates.allocationRules(PROTOCOL);
      final java.util.List<java.lang.String> coalition = List.of("M", "L", "KD", "SD");
      final se.swedishpolls.DevelopmentGates.Resources resources =
          DevelopmentGates.measure(
              () -> {
                retained.add(
                    EstimateHistory.history(
                        periods, polls, elections, coverage, uncertaintyRules, publication));
                final se.swedishpolls.JointUncertainty.Estimated joint =
                    JointUncertainty.estimate(
                        period,
                        polls,
                        elections,
                        validated.parameters(),
                        coverage.rules(),
                        uncertaintyRules);
                retained.add(joint);
                final java.util.ArrayList<se.swedishpolls.JointUncertainty.Draws> probabilityDraws =
                    new ArrayList<JointUncertainty.Draws>();
                probabilityDraws.add(joint.finalDraws());
                for (java.lang.Long seed : precisionSeeds.subList(1, precisionSeeds.size()))
                  probabilityDraws.add(
                      JointUncertainty.finalDay(
                              period,
                              polls,
                              elections,
                              validated.parameters(),
                              coverage.rules(),
                              uncertaintyRules.withSeed(seed))
                          .draws());
                final se.swedishpolls.DevelopmentGates.ProbabilityPrecision measured =
                    DevelopmentGates.probabilityPrecision(
                        joint, probabilityDraws, precisionSeeds, allocationRules, coalition);
                probability.add(measured);
                retained.add(measured);
                retained.add(
                    ComparableRemainder.estimate(
                        period,
                        polls,
                        references(db),
                        validated.parameters(),
                        coverage.rules(),
                        uncertaintyRules));
              },
              TARGET_MILLIS,
              validated.support().observations(),
              baseline.segments().stream().mapToInt(segment -> segment.days().size()).sum(),
              uncertaintyRules.draws());
      assertEquals(4, retained.size());
      Files.writeString(
          PROBABILITY_PRECISION,
          DevelopmentGates.report(probability.getFirst()) + "\n",
          StandardCharsets.UTF_8);
      final se.swedishpolls.DevelopmentGates.Report report =
          DevelopmentGates.evaluate(
              PROTOCOL,
              COVERAGE,
              UNCERTAINTY,
              DIAGNOSTICS,
              CROSS_ARCHITECTURE,
              PROBABILITY_PRECISION,
              drift,
              resources);
      Files.writeString(RESULT, DevelopmentGates.report(report) + "\n", StandardCharsets.UTF_8);
    } finally {
      flyway.clean();
    }
  }

  private static DevelopmentGates.Drift drift(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      CoverageValidation.Rules rules,
      DailyStateSpace.Parameters parameters,
      JointUncertainty.Rules uncertaintyRules,
      EstimateHistory.Estimated baseline)
      throws Exception {
    final java.util.LinkedHashMap<java.lang.String, se.swedishpolls.PollObservations.Observation>
        latest = new LinkedHashMap<String, PollObservations.Observation>();
    final se.swedishpolls.CoverageValidation.Support supported =
        CoverageValidation.support(period, polls, rules);
    final se.swedishpolls.PollObservations.Batch batch =
        PollObservations.prepare(CoverageValidation.supported(period, supported), polls);
    final java.util.Comparator<se.swedishpolls.PollObservations.Observation> order =
        Comparator.comparing(PollObservations.Observation::midpoint)
            .thenComparingInt(observation -> observation.poll().rowNumber());
    for (se.swedishpolls.PollObservations.Observation observation : batch.observations())
      latest.merge(
          observation.poll().institute(),
          observation,
          (first, second) -> order.compare(first, second) < 0 ? second : first);
    final java.util.ArrayList<se.swedishpolls.DevelopmentGates.Perturbation> perturbations =
        new ArrayList<DevelopmentGates.Perturbation>();
    for (se.swedishpolls.PollObservations.Observation observation : latest.values()) {
      final se.swedishpolls.PollCsv.Poll changed = observation.poll();
      final java.util.List<se.swedishpolls.PollCsv.Poll> removed =
          polls.stream().filter(poll -> poll.rowNumber() != changed.rowNumber()).toList();
      perturbations.add(
          DevelopmentGates.compare(
              "addition_deletion",
              changed,
              baseline,
              EstimateHistory.estimate(
                  period, removed, elections, parameters, rules, uncertaintyRules)));
      final java.util.List<se.swedishpolls.PollCsv.Poll> corrected =
          polls.stream()
              .map(poll -> poll.rowNumber() == changed.rowNumber() ? corrected(poll) : poll)
              .toList();
      perturbations.add(
          DevelopmentGates.compare(
              "correction",
              changed,
              baseline,
              EstimateHistory.estimate(
                  period, corrected, elections, parameters, rules, uncertaintyRules)));
    }
    return new DevelopmentGates.Drift(
        DevelopmentGates.sha256(Path.of("src", "test", "resources", "polls", "audit.csv")),
        perturbations);
  }

  private static PollCsv.Poll corrected(PollCsv.Poll poll) {
    final java.math.BigDecimal amount = new BigDecimal("0.1");
    if (poll.remainder().compareTo(amount) < 0)
      throw new IllegalArgumentException("Correction exceeds OTHER in row " + poll.rowNumber());
    final java.util.LinkedHashMap<java.lang.String, java.math.BigDecimal> shares =
        new LinkedHashMap<>(poll.shares());
    shares.put("M", shares.get("M").add(amount));
    return new PollCsv.Poll(
        poll.rowNumber(),
        poll.raw(),
        poll.company(),
        poll.institute(),
        poll.methodEra(),
        poll.methodEvidence(),
        poll.surveyType(),
        poll.denominatorNote(),
        poll.publicationDate(),
        poll.collectionFrom(),
        poll.collectionTo(),
        poll.sampleSize(),
        shares,
        poll.remainder().subtract(amount),
        poll.exclusionReasons());
  }

  private static List<ComparableRemainder.Reference> references(JdbcClient db) {
    record Share(LocalDate date, String component, double share) {}
    final java.util.LinkedHashMap<
            java.time.LocalDate, java.util.LinkedHashMap<java.lang.String, java.lang.Double>>
        shares = new LinkedHashMap<LocalDate, LinkedHashMap<String, Double>>();
    for (Share row :
        db.sql(
                """
                SELECT election_date, component, 100.0 * votes / valid_votes AS share
                FROM election_party_reference JOIN election_reference USING (election_date)
                WHERE election_date < DATE '2022-09-11'
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
