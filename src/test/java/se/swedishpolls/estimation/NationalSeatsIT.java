package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.source.repository.NationalAllocationRuleRepository;
import se.swedishpolls.testsupport.TestDatabase;

/** Rebuilds the seat and coalition evidence with `-Dseats.full=true`. */
class NationalSeatsIT {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path RESULT = Path.of("docs", "validation", "seats.json");

  /**
   * The election the development headline would approximate. Only its rule row is read, so the
   * reserved 2022 outcome stays untouched by the estimate.
   */
  private static final int APPROXIMATED_ELECTION = 2022;

  private static final List<Integer> ELECTION_YEARS = List.of(2010, 2014, 2018, 2022, 2026);

  @Test
  void everyElectionCarriesItsEraRuleAndAnUnlistedYearHasNone() throws Exception {
    withDatabase(
        db -> {
          final NationalAllocationRuleRepository rules = allocationRules(db);
          assertEquals(1.4, rules.rule(2010).firstDivisor());
          assertEquals(1.4, rules.rule(2014).firstDivisor());
          assertEquals(1.2, rules.rule(2018).firstDivisor());
          assertEquals(1.2, rules.rule(2022).firstDivisor());
          assertEquals(1.2, rules.rule(2026).firstDivisor());
          for (final int year : ELECTION_YEARS) {
            final NationalAllocationRule rule = rules.rule(year);
            assertEquals(349, rule.seats());
            assertEquals(175, rule.majoritySeats());
            assertEquals(4.0, rule.thresholdPercent());
            assertTrue(rule.thresholdInclusive());
            assertFalse(rule.otherReceivesSeats());
            assertFalse(rule.constituencyExceptionsIncluded());
            assertEquals("lottery", rule.officialTieRule());
            assertEquals(List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI"), rule.tieOrder());
          }
          // The 2006 election predates the supported history and has no configured rule. The
          // latest row is never silently reused for it.
          assertThrows(IllegalArgumentException.class, () -> rules.rule(2006));
        });
  }

  @Test
  void theApproximationIsComparedWithEveryOfficialAllocationItCanReach() throws Exception {
    withDatabase(
        db -> {
          final List<SeatOutcomes.OfficialComparison> comparisons =
              officialComparisons(db, allocationRules(db));
          assertEquals(4, comparisons.size());
          for (final SeatOutcomes.OfficialComparison comparison : comparisons) {
            assertEquals(349, comparison.totalApproximated());
            assertEquals(349, comparison.totalOfficial());
            assertEquals(
                0,
                comparison.differenceSeats().values().stream().mapToInt(Integer::intValue).sum(),
                "A difference moves seats between parties; the board still holds 349");
          }
          // The two post-reform elections reproduce exactly. The two earlier ones differ by the
          // constituency machinery this national approximation omits.
          assertTrue(comparison(comparisons, 2018).matchesOfficial());
          assertTrue(comparison(comparisons, 2022).matchesOfficial());
          assertEquals(3, comparison(comparisons, 2010).maxAbsoluteDifference());
          assertEquals(2, comparison(comparisons, 2014).maxAbsoluteDifference());
        });
  }

  @Test
  void publishesSeatsAndCoalitionsFromOneSetOfDrawsWithPrecisionAndSensitivity() throws Exception {
    if (Boolean.getBoolean("seats.full")) {
      rebuild();
    }
    final SeatOutcomes.Report report = SeatOutcomes.validation(RESULT);
    final JointUncertainty.Rules protocol = JointUncertainty.rules(PROTOCOL);
    assertEquals(protocol.seed(), report.rules().seed());
    assertEquals(10_000, report.rules().draws());
    assertEquals(SeatOutcomes.RESERVED_AUDIT_NOTE, report.reservedAuditNote());
    assertEquals(4, report.officialComparisons().size());
    assertFalse(report.periods().isEmpty());

    for (final SeatOutcomes.Published published : report.periods()) {
      final NationalSeats.Summary seats = published.seats();
      assertEquals(349, seats.rules().seats());
      assertEquals(APPROXIMATED_ELECTION, seats.rules().electionYear());
      assertEquals(349, seats.totalPointSeats());
      assertEquals(349, Math.round(seats.totalMeanSeats()));
      assertEquals(List.of("OTHER"), seats.excludedFromAllocation());
      assertEquals(
          List.of(new NationalSeats.Unavailable("FI", NationalSeats.NO_VALIDATED_PERIOD)),
          seats.unavailable(),
          "FI has no validated coverage period, so it has no seat estimate rather than zero");
      for (final NationalSeats.PartySeats party : seats.parties()) {
        assertTrue(party.lowerSeats() <= party.meanSeats());
        assertTrue(party.meanSeats() <= party.upperSeats());
        assertTrue(party.thresholdProbability() >= 0 && party.thresholdProbability() <= 1);
      }
      assertTrue(
          seats.parties().stream()
              .anyMatch(party -> party.pointSeats() != Math.round(party.meanSeats())),
          "Integer point seats and posterior mean seats are distinct quantities");

      final Coalitions.Result coalitions = published.coalitions();
      assertEquals(175, coalitions.majoritySeats());
      assertEquals(seats.daySeed(), coalitions.daySeed(), "One day, one set of draws");
      assertEquals(seats.draws(), coalitions.draws());
      assertEquals(seats.date(), coalitions.date());
      assertEquals(
          Coalitions.PRESETS.stream().map(Coalitions.Preset::id).toList(),
          coalitions.coalitions().stream().map(Coalitions.Seats::id).toList());
      assertEquals(List.of("tido", "opposition", "left", "s_m"), coalitions.overviewDefaults());
      assertEquals(45, coalitions.comparison().size());
      for (final Coalitions.Comparison pair : coalitions.comparison()) {
        assertEquals(1.0, pair.leftLeads() + pair.rightLeads() + pair.tied(), 1e-9);
      }
      assertEquals(Coalitions.TIE_OUTCOME, coalitions.tie());

      assertEquals(
          seats.parties().size() + Coalitions.PRESETS.size(),
          published.precision().size(),
          "Every headline probability carries its own spread");
      for (final SeatOutcomes.Precision entry : published.precision()) {
        assertTrue(
            entry.spread() <= 0.03,
            entry.quantity() + " moves " + entry.spread() + " between draw seeds");
        assertEquals(10_000, entry.drawsPerSeed());
        assertEquals(8, entry.seeds());
      }

      final TreeSet<String> kinds = new TreeSet<>();
      for (final SeatOutcomes.Sensitivity alternative : published.sensitivity()) {
        kinds.add(alternative.kind());
        assertEquals(
            alternative.maxAbsoluteDifferencePoints() > SeatOutcomes.DISCLOSED_SHIFT_POINTS,
            alternative.needsDisclosure());
      }
      assertEquals(Set.of("centering", "leave_one_institute_out"), kinds);
      assertEquals(
          published.sensitivity().stream()
              .filter(SeatOutcomes.Sensitivity::needsDisclosure)
              .count(),
          published.disclosures().size());
    }
  }

  private void rebuild() throws Exception {
    withDatabase(
        db -> {
          final NationalAllocationRuleRepository rules = allocationRules(db);
          final NationalAllocationRule allocation = rules.rule(APPROXIMATED_ELECTION);
          final List<LocalDate> elections =
              db.sql(
                      "SELECT election_date FROM election_reference WHERE election_date < DATE"
                          + " '2022-09-11' ORDER BY election_date")
                  .query(LocalDate.class)
                  .list();
          final List<PollCsv.Poll> polls;
          try (final java.io.InputStream input =
              getClass().getResourceAsStream("/polls/audit.csv")) {
            polls = PollCsv.parse(input.readAllBytes());
          }
          final CoverageValidation.Report coverage = CoverageValidation.validation(COVERAGE);
          final JointUncertainty.Rules uncertainty = JointUncertainty.rules(PROTOCOL);
          final double level = uncertainty.intervalLevels().getLast();
          final List<String> reasons = new ArrayList<>(coverage.gate().reasons());
          final List<SeatOutcomes.Published> published = new ArrayList<>();
          for (final Roster.CoveragePeriod period : new CoveragePeriodRepository(db).periods()) {
            if (!period.supportValidated()) {
              continue;
            }
            final CoverageValidation.Validated validated =
                coverage.periods().stream()
                    .filter(candidate -> candidate.periodId().equals(period.id()))
                    .findFirst()
                    .orElseThrow();
            if (!validated.supported()) {
              reasons.add(period.id() + ": coverage evidence failed, so no seats are published");
              continue;
            }
            published.add(
                publish(
                    period,
                    polls,
                    elections,
                    validated.parameters(),
                    coverage.rules(),
                    uncertainty,
                    allocation,
                    level));
          }
          final SeatOutcomes.Report report =
              new SeatOutcomes.Report(
                  coverage.protocolVersion(),
                  new CoverageValidation.Gate(!reasons.isEmpty(), reasons),
                  uncertainty,
                  published,
                  officialComparisons(db, rules),
                  SeatOutcomes.RESERVED_AUDIT_NOTE);
          Files.writeString(RESULT, SeatOutcomes.report(report) + "\n", StandardCharsets.UTF_8);
        });
  }

  /**
   * One period's published seats and coalitions, the repeated-seed precision study behind their
   * probabilities, and both registered sensitivity reruns.
   */
  private static SeatOutcomes.Published publish(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules coverage,
      JointUncertainty.Rules uncertainty,
      NationalAllocationRule allocation,
      double level) {
    final DailyStateSpace.Centering equal = DailyStateSpace.Centering.EQUAL_INSTITUTE;
    final List<NationalSeats.SeatDraws> repeats = new ArrayList<>();
    for (final long seed : JointUncertainty.precisionSeeds(uncertainty)) {
      repeats.add(
          NationalSeats.allocateDraws(
              JointUncertainty.finalDay(
                      period, polls, elections, parameters, coverage, uncertainty.withSeed(seed))
                  .draws(),
              allocation));
    }
    final NationalSeats.SeatDraws drawn = repeats.getFirst();
    final SeatOutcomes.Headline headline = SeatOutcomes.headline(drawn);
    final List<SeatOutcomes.Sensitivity> sensitivity = new ArrayList<>();
    sensitivity.add(
        SeatOutcomes.sensitivity(
            "centering",
            SeatOutcomes.POLL_COUNT,
            headline,
            SeatOutcomes.headline(
                SeatOutcomes.finalDayDraws(
                    period,
                    polls,
                    elections,
                    parameters,
                    coverage,
                    uncertainty,
                    DailyStateSpace.Centering.POLL_COUNT,
                    allocation))));
    for (final String institute : institutes(polls)) {
      final List<PollCsv.Poll> kept =
          polls.stream().filter(poll -> !institute.equals(poll.institute())).toList();
      sensitivity.add(
          SeatOutcomes.sensitivity(
              "leave_one_institute_out",
              "without_" + institute,
              headline,
              SeatOutcomes.headline(
                  SeatOutcomes.finalDayDraws(
                      period,
                      kept,
                      elections,
                      parameters,
                      coverage,
                      uncertainty,
                      equal,
                      allocation))));
    }
    return new SeatOutcomes.Published(
        period.id(),
        NationalSeats.summarize(drawn, level),
        Coalitions.summarize(drawn, level),
        SeatOutcomes.precision(repeats),
        sensitivity,
        SeatOutcomes.disclosures(sensitivity));
  }

  private static List<String> institutes(List<PollCsv.Poll> polls) {
    return polls.stream()
        .filter(poll -> poll.exclusionReasons().isEmpty())
        .map(PollCsv.Poll::institute)
        .distinct()
        .sorted()
        .toList();
  }

  private static List<SeatOutcomes.OfficialComparison> officialComparisons(
      JdbcClient db, NationalAllocationRuleRepository rules) {
    final List<SeatOutcomes.OfficialComparison> comparisons = new ArrayList<>();
    for (final SeatOutcomes.OfficialResult result : officialResults(db)) {
      comparisons.add(SeatOutcomes.compare(result, rules.rule(result.electionYear())));
    }
    return List.copyOf(comparisons);
  }

  /** The stored official outcomes, in shares and seats. No poll and no model output is read. */
  private static List<SeatOutcomes.OfficialResult> officialResults(JdbcClient db) {
    record Row(LocalDate date, String component, double share, int officialSeats) {}
    final LinkedHashMap<LocalDate, LinkedHashMap<String, Double>> shares = new LinkedHashMap<>();
    final LinkedHashMap<LocalDate, LinkedHashMap<String, Integer>> seats = new LinkedHashMap<>();
    final List<Row> rows =
        db.sql(
                """
                SELECT election_date, component, 100.0 * votes / valid_votes AS share,
                       official_seats
                FROM election_party_reference JOIN election_reference USING (election_date)
                WHERE component <> 'RESIDUAL'
                ORDER BY election_date, component
                """)
            .query(
                (rs, index) ->
                    new Row(
                        rs.getObject("election_date", LocalDate.class),
                        rs.getString("component"),
                        rs.getDouble("share"),
                        rs.getInt("official_seats")))
            .list();
    for (final Row row : rows) {
      shares
          .computeIfAbsent(row.date(), date -> new LinkedHashMap<>())
          .put(row.component(), row.share());
      seats
          .computeIfAbsent(row.date(), date -> new LinkedHashMap<>())
          .put(row.component(), row.officialSeats());
    }
    final List<SeatOutcomes.OfficialResult> results = new ArrayList<>();
    for (final LocalDate date : shares.keySet()) {
      results.add(
          new SeatOutcomes.OfficialResult(date.getYear(), date, shares.get(date), seats.get(date)));
    }
    return List.copyOf(results);
  }

  private static SeatOutcomes.OfficialComparison comparison(
      List<SeatOutcomes.OfficialComparison> comparisons, int electionYear) {
    return comparisons.stream()
        .filter(comparison -> comparison.electionYear() == electionYear)
        .findFirst()
        .orElseThrow();
  }

  private static NationalAllocationRuleRepository allocationRules(JdbcClient db) {
    return new NationalAllocationRuleRepository(db);
  }

  private interface Work {
    void run(JdbcClient db) throws Exception;
  }

  private void withDatabase(Work work) throws Exception {
    final String schema = "national_seats_" + UUID.randomUUID().toString().replace("-", "");
    final DataSource dataSource = TestDatabase.dataSource(schema);
    final Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      flyway.migrate();
      work.run(JdbcClient.create(dataSource));
    } finally {
      flyway.clean();
    }
  }
}
