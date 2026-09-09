package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoverageValidationTest {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final List<LocalDate> ELECTIONS =
      List.of(LocalDate.of(2014, 9, 14), LocalDate.of(2018, 9, 9));
  private static final DailyStateSpace.Parameters POINT =
      new DailyStateSpace.Parameters(1e-4, 0.1, 1.5);
  private static final LocalDate THROUGH = LocalDate.of(2021, 10, 5);
  private static final List<String> INSTITUTES =
      List.of("Sifo", "Novus", "Ipsos", "Skop", "Sentio", "YouGov");

  private static Roster.CoveragePeriod period(
      String id, LocalDate from, LocalDate to, boolean fi, boolean validated) {
    var roster = new ArrayList<>(List.of("S", "M", "SD", "V", "C", "KD", "L", "MP"));
    if (fi) roster.add("FI");
    return new Roster.CoveragePeriod(
        id, from, to, roster, fi, validated, "https://example.invalid/decision");
  }

  /** One single-day poll. The drift keeps the eight parties summing to 98.123. */
  private static String row(LocalDate date, String institute, double drift, String fi) {
    return String.join(
            ",",
            date.toString().substring(0, 7),
            institute,
            String.valueOf(20.123 + drift),
            "5",
            "8",
            "5",
            String.valueOf(30 - drift),
            "8",
            "5",
            "17",
            fi,
            "10",
            "1000",
            date.plusDays(1).toString(),
            institute,
            date.toString(),
            date.toString(),
            "FALSE")
        + "\n";
  }

  private static List<PollCsv.Poll> weekly(LocalDate from, LocalDate to, String fi) {
    var rows = new StringBuilder();
    int index = 0;
    for (var date = from; !date.isAfter(to); date = date.plusDays(7), index++)
      rows.append(row(date, INSTITUTES.get(index % INSTITUTES.size()), index % 5 * 0.25, fi));
    return PollCsv.parse(PollCsvTest.csv(rows.toString()));
  }

  @Test
  void readsTheRegisteredRulesAndRejectsInadmissibleOnes() {
    var rules = CoverageValidation.rules(PROTOCOL);
    assertEquals(THROUGH, rules.developmentThrough());
    assertEquals(30, rules.minObservations());
    assertEquals(5, rules.minInstitutes());
    assertEquals(45, rules.maxInternalGapDays());
    assertEquals(List.of(7, 14, 30), rules.boundaryShiftDays());
    assertEquals(60, rules.stabilityBurnInDays());
    assertEquals(0.5, rules.maxStabilityShiftPoints());

    for (var inadmissible :
        List.<org.junit.jupiter.api.function.Executable>of(
            () -> new CoverageValidation.Rules(THROUGH, 0, 5, 45, List.of(7), 60, 0.5),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 0, List.of(7), 60, 0.5),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 45, List.of(), 60, 0.5),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 45, List.of(7, 7), 60, 0.5),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 45, List.of(14, 7), 60, 0.5),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 45, List.of(0), 60, 0.5),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 45, List.of(7), -1, 0.5),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 45, List.of(7), 60, 0),
            () -> new CoverageValidation.Rules(THROUGH, 30, 5, 45, List.of(7), 60, Double.NaN),
            () -> new CoverageValidation.Rules(null, 30, 5, 45, List.of(7), 60, 0.5)))
      assertThrows(IllegalArgumentException.class, inadmissible);
    var incomplete =
        assertThrows(
            IllegalArgumentException.class,
            () -> CoverageValidation.rules(Path.of("docs", "validation", "tuning.json")));
    assertTrue(incomplete.getMessage().contains("coverage_validation"), incomplete::getMessage);
  }

  @Test
  void supportReportsTheOutermostEligibleDatesTheLargestGapAndRetainedExclusions() {
    var declared =
        period("candidate", LocalDate.of(2014, 1, 1), LocalDate.of(2018, 12, 31), true, false);
    var polls = new ArrayList<>(weekly(LocalDate.of(2015, 3, 2), LocalDate.of(2015, 5, 4), "1"));
    // A summer without any FI reading, then a poll the FI roster cannot place.
    polls.addAll(weekly(LocalDate.of(2015, 8, 3), LocalDate.of(2015, 9, 7), "1"));
    polls.addAll(weekly(LocalDate.of(2015, 6, 1), LocalDate.of(2015, 6, 1), "NA"));

    var support = CoverageValidation.support(declared, polls, CoverageValidation.rules(PROTOCOL));

    assertEquals(LocalDate.of(2015, 3, 2), support.from());
    assertEquals(LocalDate.of(2015, 9, 7), support.to());
    assertEquals(16, support.observations());
    assertEquals(6, support.institutes());
    assertEquals(1, support.excludedPolls());
    assertEquals(Map.of("missing_share:FI", 1), support.exclusionReasons());
    assertEquals(LocalDate.of(2015, 5, 4), support.largestGap().from());
    assertEquals(LocalDate.of(2015, 8, 3), support.largestGap().to());
    assertEquals(91, support.largestGap().days());
    // The trimmed period keeps every observation the declared period placed.
    var trimmed = CoverageValidation.supported(declared, support);
    assertEquals(
        support.observations(), PollObservations.prepare(trimmed, polls).observations().size());
    assertEquals(declared.roster(), trimmed.roster());
  }

  @Test
  void developmentEvidenceStopsAtTheRegisteredEndAndNeverReadsTheReservedWindow() {
    var open = period("eight", LocalDate.of(2014, 1, 1), null, false, true);
    var polls = weekly(LocalDate.of(2021, 8, 2), LocalDate.of(2021, 11, 29), "1");
    var rules = CoverageValidation.rules(PROTOCOL);

    assertEquals(10, CoverageValidation.development(polls, rules).size());
    var support = CoverageValidation.support(open, polls, rules);
    assertEquals(LocalDate.of(2021, 10, 4), support.to());
    assertFalse(support.to().isAfter(rules.developmentThrough()));
    assertEquals(10, support.observations());
    assertEquals(18, polls.size());
  }

  @Test
  void thinCoverageFailsEveryBreachedRuleAndStillRetainsItsObservations() {
    var declared =
        period("candidate", LocalDate.of(2014, 1, 1), LocalDate.of(2018, 12, 31), true, false);
    var polls = new ArrayList<>(weekly(LocalDate.of(2015, 3, 2), LocalDate.of(2015, 4, 6), "1"));
    polls.addAll(weekly(LocalDate.of(2015, 9, 7), LocalDate.of(2015, 10, 12), "1"));
    var rules = CoverageValidation.rules(PROTOCOL);

    var validated = CoverageValidation.validate(declared, polls, ELECTIONS, POINT, rules);

    assertFalse(validated.supported());
    assertEquals(12, validated.support().observations());
    assertEquals(154, validated.support().largestGap().days());
    assertTrue(
        validated.failures().stream().anyMatch(f -> f.contains("12 eligible observations")),
        validated::toString);
    assertTrue(
        validated.failures().stream().anyMatch(f -> f.contains("internal gap")),
        validated::toString);
    // Six institutes clear that rule, so a thin period never reports every rule as breached.
    assertTrue(validated.failures().stream().noneMatch(f -> f.contains("institutes")));
  }

  @Test
  void denseCoverageIsSupportedAndBoundedUnderEveryRegisteredBoundaryShift() {
    var declared =
        period("candidate", LocalDate.of(2014, 1, 1), LocalDate.of(2018, 12, 31), true, false);
    var polls = weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2017, 12, 25), "1");
    var rules = CoverageValidation.rules(PROTOCOL);

    var validated = CoverageValidation.validate(declared, polls, ELECTIONS, POINT, rules);

    assertTrue(validated.supported(), validated::toString);
    assertEquals(156, validated.support().observations());
    assertEquals(7, validated.support().largestGap().days());
    assertEquals(
        List.of(7, 14, 30), validated.stability().stream().map(s -> s.shiftDays()).toList());
    for (var stability : validated.stability()) {
      assertEquals(10, stability.maxShiftPoints().size());
      assertTrue(stability.comparedDays() > 500, stability::toString);
      assertTrue(stability.worst() <= rules.maxStabilityShiftPoints(), stability::toString);
      // Pulling a boundary in must still move the estimate somewhere; an exact zero would mean the
      // variant fit was never compared.
      assertTrue(stability.worst() > 0, stability::toString);
    }
  }

  @Test
  void everyModeledPartyCarriesAMeasuredStepAtTheCandidateBoundariesAndTheTuningBlockCarriesOver() {
    var eight = period("eight", LocalDate.of(2014, 1, 1), null, false, true);
    var candidate =
        period("candidate", LocalDate.of(2015, 1, 1), LocalDate.of(2016, 12, 31), true, false);
    var polls = weekly(LocalDate.of(2014, 6, 2), LocalDate.of(2017, 6, 26), "1");
    var fold = new DevelopmentTuning.Fold(LocalDate.of(2017, 1, 1), LocalDate.of(2017, 2, 5));
    var tuning =
        new DevelopmentTuning.Tuning(
            "v1-development-1",
            new DevelopmentTuning.Grid(List.of(1e-4), List.of(0.1), List.of(1.5)),
            new DevelopmentTuning.Gate(true, List.of("a resolved fold sits on a grid boundary")),
            List.of(resolved("eight", fold), resolved("candidate", fold)),
            List.of());

    var report =
        CoverageValidation.validateAll(
            List.of(eight, candidate),
            polls,
            ELECTIONS,
            tuning,
            CoverageValidation.rules(PROTOCOL));

    assertEquals("v1-development-1", report.protocolVersion());
    assertEquals(2, report.periods().size());
    assertTrue(report.periods().stream().allMatch(CoverageValidation.Validated::supported));
    // Both boundaries of the candidate segment are measured against the surrounding validated fit.
    assertEquals(2, report.boundaryEffects().size());
    assertEquals(
        List.of(LocalDate.of(2015, 1, 5), LocalDate.of(2016, 12, 26)),
        report.boundaryEffects().stream().map(CoverageValidation.BoundaryEffect::date).toList());
    for (var effect : report.boundaryEffects()) {
      assertEquals("candidate", effect.periodId());
      assertEquals("eight", effect.againstPeriodId());
      assertEquals(9, effect.stepPoints().size());
      assertTrue(effect.stepPoints().keySet().containsAll(PollCsv.PARTIES));
      assertTrue(effect.stepPoints().containsKey(CoverageValidation.REMAINDER));
      assertTrue(effect.stepPoints().values().stream().allMatch(Double::isFinite));
      // The two fits describe the same eight parties from the same polls, so their step is small
      // here; the archived candidate segment is where it is evidence.
      assertTrue(effect.largestStep() < 1, effect::toString);
    }
    // Nothing here is a release value while the tuning run that produced the parameters is blocked.
    assertTrue(report.gate().blocked());
    assertEquals(
        List.of("development tuning: a resolved fold sits on a grid boundary"),
        report.gate().reasons());
  }

  private static DevelopmentTuning.Resolved resolved(String periodId, DevelopmentTuning.Fold fold) {
    return new DevelopmentTuning.Resolved(periodId, fold, POINT, -1, 1, 1, 0, Map.of(), List.of());
  }
}
