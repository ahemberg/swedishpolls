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
  private static final Path RESULT = Path.of("docs", "validation", "development-gates.json");
  private static final int TARGET_MILLIS = 10_000;

  @Test
  void freezesDevelopmentTolerancesAndStopsAtTheFailedAggregateGate() throws Exception {
    if (Boolean.getBoolean("gates.full")) rebuild();
    var stored = DevelopmentGates.validation(RESULT);
    var rebuilt =
        DevelopmentGates.evaluate(
            PROTOCOL,
            COVERAGE,
            UNCERTAINTY,
            DIAGNOSTICS,
            stored.drift(),
            DevelopmentGates.crossArchitecture(CROSS_ARCHITECTURE),
            stored.resources());
    assertEquals(DevelopmentGates.report(stored), DevelopmentGates.report(rebuilt));
    assertEquals(DevelopmentGates.FALLBACK_NOT_REQUIRED, stored.uncertaintyFallback());
    assertTrue(stored.tolerances().stream().allMatch(DevelopmentGates.Tolerance::passes));
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
    var schema = "development_gates_" + UUID.randomUUID().toString().replace("-", "");
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
      var period =
          periods.stream()
              .filter(Roster.CoveragePeriod::supportValidated)
              .findFirst()
              .orElseThrow();
      var validated =
          coverage.periods().stream()
              .filter(candidate -> candidate.periodId().equals(period.id()))
              .findFirst()
              .orElseThrow();
      var baseline =
          EstimateHistory.estimate(
              period, polls, elections, validated.parameters(), coverage.rules());
      var drift =
          drift(period, polls, elections, coverage.rules(), validated.parameters(), baseline);
      var retained = new ArrayList<Object>();
      var uncertaintyRules = JointUncertainty.rules(PROTOCOL);
      var resources =
          DevelopmentGates.measure(
              () -> {
                retained.add(EstimateHistory.history(periods, polls, elections, coverage));
                retained.add(
                    JointUncertainty.estimate(
                        period,
                        polls,
                        elections,
                        validated.parameters(),
                        coverage.rules(),
                        uncertaintyRules));
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
      assertEquals(3, retained.size());
      var report =
          DevelopmentGates.evaluate(
              PROTOCOL,
              COVERAGE,
              UNCERTAINTY,
              DIAGNOSTICS,
              drift,
              DevelopmentGates.crossArchitecture(CROSS_ARCHITECTURE),
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
      EstimateHistory.Estimated baseline)
      throws Exception {
    var latest = new LinkedHashMap<String, PollObservations.Observation>();
    var supported = CoverageValidation.support(period, polls, rules);
    var batch = PollObservations.prepare(CoverageValidation.supported(period, supported), polls);
    var order =
        Comparator.comparing(PollObservations.Observation::midpoint)
            .thenComparing(observation -> observation.poll().rowNumber());
    for (var observation : batch.observations())
      latest.merge(
          observation.poll().institute(),
          observation,
          (first, second) -> order.compare(first, second) < 0 ? second : first);
    var perturbations = new ArrayList<DevelopmentGates.Perturbation>();
    for (var observation : latest.values()) {
      var changed = observation.poll();
      var removed = polls.stream().filter(poll -> poll.rowNumber() != changed.rowNumber()).toList();
      perturbations.add(
          DevelopmentGates.compare(
              "addition_deletion",
              changed,
              baseline,
              EstimateHistory.estimate(period, removed, elections, parameters, rules)));
      var corrected =
          polls.stream()
              .map(poll -> poll.rowNumber() == changed.rowNumber() ? corrected(poll) : poll)
              .toList();
      perturbations.add(
          DevelopmentGates.compare(
              "correction",
              changed,
              baseline,
              EstimateHistory.estimate(period, corrected, elections, parameters, rules)));
    }
    return new DevelopmentGates.Drift(
        DevelopmentGates.sha256(Path.of("src", "test", "resources", "polls", "audit.csv")),
        perturbations);
  }

  private static PollCsv.Poll corrected(PollCsv.Poll poll) {
    var amount = new BigDecimal("0.1");
    if (poll.remainder().compareTo(amount) < 0)
      throw new IllegalArgumentException("Correction exceeds OTHER in row " + poll.rowNumber());
    var shares = new LinkedHashMap<>(poll.shares());
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
