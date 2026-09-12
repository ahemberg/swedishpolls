package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.testsupport.PollCsvFixtures;

class ComparableRemainderTest {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final DailyStateSpace.Parameters POINT =
      new DailyStateSpace.Parameters(1e-4, 0.1, 1.5);

  /**
   * Few enough draws to keep the unit checks quick; the registered count is an integration cost.
   */
  private static final JointUncertainty.Rules RULES =
      new JointUncertainty.Rules(20260908, 400, List.of(0.5, 0.95), 4);

  private static final List<ComparableRemainder.Reference> ELECTIONS =
      List.of(
          reference(LocalDate.of(2014, 9, 14), 0.9, 1.1),
          reference(LocalDate.of(2018, 9, 9), 0.4, 1.6));

  /** An official result: the eight parties, then FI and the residual outside them. */
  private static ComparableRemainder.Reference reference(
      LocalDate date, double fi, double residual) {
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> shares =
        new LinkedHashMap<String, Double>();
    final double eight = (100 - fi - residual) / PollCsv.PARTIES.size();
    for (java.lang.String party : PollCsv.PARTIES) shares.put(party, eight);
    shares.put("FI", fi);
    shares.put("RESIDUAL", residual);
    return new ComparableRemainder.Reference(date, shares);
  }

  private static CoverageValidation.Rules coverage() {
    return CoverageValidation.rules(PROTOCOL);
  }

  private static ComparableRemainder.Estimated estimate(
      Roster.CoveragePeriod period, List<PollCsv.Poll> polls) {
    return ComparableRemainder.estimate(period, polls, ELECTIONS, POINT, coverage(), RULES);
  }

  private static CoverageValidation.Report coverage(
      CoverageValidation.Gate gate, List<Roster.CoveragePeriod> periods, List<PollCsv.Poll> polls) {
    final java.util.ArrayList<se.swedishpolls.estimation.CoverageValidation.Validated> validated =
        new ArrayList<CoverageValidation.Validated>();
    for (se.swedishpolls.source.Roster.CoveragePeriod period : periods)
      validated.add(
          new CoverageValidation.Validated(
              period.id(),
              true,
              POINT,
              CoverageValidation.support(period, polls, coverage()),
              List.of(),
              List.of()));
    return new CoverageValidation.Report(
        "v1-development-1", coverage(), gate, validated, List.of());
  }

  @Test
  void combinesFiAndTheResidualOnlyWhereFiIsSeparate() {
    final se.swedishpolls.source.Roster.CoveragePeriod eight =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final se.swedishpolls.source.Roster.CoveragePeriod separate =
        CoverageValidationTest.period("separate", LocalDate.of(2015, 1, 1), null, true, true);
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    final se.swedishpolls.estimation.ComparableRemainder.Estimated withoutFi =
        estimate(eight, polls);
    final se.swedishpolls.estimation.ComparableRemainder.Estimated withFi =
        estimate(separate, polls);

    assertEquals(List.of("OTHER"), withoutFi.members());
    assertEquals(List.of("FI", "RESIDUAL"), withFi.members());
    // Where OTHER already holds FI the remainder is OTHER itself, drawn from the same rows.
    final se.swedishpolls.estimation.JointUncertainty.Estimated uncertainty =
        JointUncertainty.estimate(eight, polls, dates(), POINT, coverage(), RULES);
    for (int day = 0; day < withoutFi.segments().getFirst().days().size(); day++) {
      final se.swedishpolls.estimation.ComparableRemainder.Day remainder =
          withoutFi.segments().getFirst().days().get(day);
      final se.swedishpolls.estimation.JointUncertainty.Component other =
          uncertainty.segments().getFirst().days().get(day).components().stream()
              .filter(component -> component.component().equals("OTHER"))
              .findFirst()
              .orElseThrow();
      assertEquals(other.mean(), remainder.mean(), 1e-12);
      assertEquals(
          other.intervals().getLast().upper(), remainder.intervals().getLast().upper(), 1e-12);
    }
    assertEquals(0, withoutFi.maxEndpointSumErrorPoints());

    // Where FI is separate the two are summed inside each draw, so the interval is not their sum.
    assertTrue(withFi.maxEndpointSumErrorPoints() > 0, withFi::toString);
    final java.util.List<se.swedishpolls.estimation.JointUncertainty.Component> separated =
        JointUncertainty.estimate(separate, polls, dates(), POINT, coverage(), RULES)
            .segments()
            .getFirst()
            .days()
            .getLast()
            .components();
    final se.swedishpolls.estimation.JointUncertainty.Component fi = summary(separated, "FI");
    final se.swedishpolls.estimation.JointUncertainty.Component residual =
        summary(separated, "RESIDUAL");
    final se.swedishpolls.estimation.ComparableRemainder.Day last =
        withFi.segments().getFirst().days().getLast();
    assertEquals(fi.mean() + residual.mean(), last.mean(), 1e-12);
    for (int level = 0; level < last.intervals().size(); level++) {
      final se.swedishpolls.estimation.JointUncertainty.Interval interval =
          last.intervals().get(level);
      assertNotEquals(
          fi.intervals().get(level).upper() + residual.intervals().get(level).upper(),
          interval.upper());
      assertTrue(interval.lower() < last.mean() && last.mean() < interval.upper());
    }
    // The summed draws are narrower than the summed endpoints: the two components covary.
    final se.swedishpolls.estimation.JointUncertainty.Interval wide = last.intervals().getLast();
    assertTrue(
        wide.upper() - wide.lower()
            < (fi.intervals().getLast().upper() - fi.intervals().getLast().lower())
                + (residual.intervals().getLast().upper() - residual.intervals().getLast().lower()),
        last::toString);
  }

  private static List<LocalDate> dates() {
    return ELECTIONS.stream().map(ComparableRemainder.Reference::date).toList();
  }

  private static JointUncertainty.Component summary(
      List<JointUncertainty.Component> components, String name) {
    return components.stream()
        .filter(component -> component.component().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void nestsTheLevelsAndClosesTheRemainderWithTheEightParties() {
    final se.swedishpolls.source.Roster.CoveragePeriod period =
        CoverageValidationTest.period("separate", LocalDate.of(2015, 1, 1), null, true, true);
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    final se.swedishpolls.estimation.ComparableRemainder.Estimated estimated =
        estimate(period, polls);

    final java.util.List<se.swedishpolls.estimation.ComparableRemainder.Day> days =
        estimated.segments().getFirst().days();
    assertEquals(148, days.size());
    for (se.swedishpolls.estimation.ComparableRemainder.Day day : days) {
      assertEquals(
          List.of(0.5, 0.95),
          day.intervals().stream().map(JointUncertainty.Interval::level).toList());
      final se.swedishpolls.estimation.JointUncertainty.Interval half = day.intervals().getFirst();
      final se.swedishpolls.estimation.JointUncertainty.Interval wide = day.intervals().getLast();
      assertTrue(wide.lower() < half.lower() && half.upper() < wide.upper(), day::toString);
      assertTrue(0 < wide.lower() && wide.upper() < 100, day::toString);
    }
  }

  @Test
  void preservesFitBoundariesAndSuppressesChangesAcrossThem() {
    final se.swedishpolls.source.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.ArrayList<se.swedishpolls.source.PollCsv.Poll> polls =
        new ArrayList<>(
            CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 3, 2), "1"));
    polls.addAll(
        CoverageValidationTest.weekly(LocalDate.of(2015, 6, 1), LocalDate.of(2015, 8, 3), "1"));

    final se.swedishpolls.estimation.ComparableRemainder.Estimated estimated =
        estimate(period, polls);

    // The remainder is cut exactly where the daily history is cut, from the same fitted runs.
    final se.swedishpolls.estimation.EstimateHistory.Estimated history =
        EstimateHistory.estimate(period, polls, dates(), POINT, coverage(), RULES);
    assertEquals(
        history.segments().stream().map(EstimateHistory.Segment::from).toList(),
        estimated.segments().stream().map(ComparableRemainder.Segment::from).toList());
    assertEquals(
        history.segments().stream().map(EstimateHistory.Segment::to).toList(),
        estimated.segments().stream().map(ComparableRemainder.Segment::to).toList());
    assertEquals(history.boundaries(), estimated.boundaries());

    final se.swedishpolls.estimation.ComparableRemainder.Change inside =
        ComparableRemainder.change(estimated, LocalDate.of(2015, 2, 4), 30);
    assertTrue(inside.available());
    final se.swedishpolls.estimation.ComparableRemainder.Day from =
        estimated.segments().getFirst().days().getFirst();
    final se.swedishpolls.estimation.ComparableRemainder.Day to =
        estimated.segments().getFirst().days().stream()
            .filter(day -> day.date().equals(LocalDate.of(2015, 2, 4)))
            .findFirst()
            .orElseThrow();
    assertEquals(to.mean() - from.mean(), inside.points(), 1e-12);

    final se.swedishpolls.estimation.ComparableRemainder.Change across =
        ComparableRemainder.change(estimated, LocalDate.of(2015, 6, 5), 100);
    assertFalse(across.available());
    assertEquals(EstimateHistory.ACROSS_BOUNDARY, across.reason());
    assertEquals(0, across.points());

    assertEquals(
        EstimateHistory.DATE_UNSUPPORTED,
        ComparableRemainder.change(estimated, LocalDate.of(2015, 4, 15), 30).reason());
    assertEquals(
        EstimateHistory.COMPARISON_UNSUPPORTED,
        ComparableRemainder.change(estimated, LocalDate.of(2015, 1, 20), 30).reason());
    assertThrows(
        IllegalArgumentException.class,
        () -> ComparableRemainder.change(estimated, LocalDate.of(2015, 2, 4), 0));

    // Every published run stays inside one segment; every boundary suppresses its own change.
    final java.util.List<se.swedishpolls.estimation.ComparableRemainder.Change> changes =
        ComparableRemainder.changes(estimated);
    assertEquals(2, changes.stream().filter(ComparableRemainder.Change::available).count());
    final java.util.List<java.lang.String> suppressed =
        changes.stream()
            .filter(change -> !change.available())
            .map(ComparableRemainder.Change::reason)
            .toList();
    assertEquals(List.of(EstimateHistory.COMPARISON_UNSUPPORTED), suppressed);
  }

  @Test
  void suppressesAChangeAcrossACenteringReferenceChangeInsideOneSegment() {
    final se.swedishpolls.source.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2014, 1, 1), null, false, true);
    final java.util.ArrayList<se.swedishpolls.source.PollCsv.Poll> polls =
        new ArrayList<PollCsv.Poll>();
    for (java.time.LocalDate date = LocalDate.of(2014, 6, 2);
        date.isBefore(LocalDate.of(2014, 9, 14));
        date = date.plusDays(7))
      polls.addAll(
          PollCsv.parse(PollCsvFixtures.csv(CoverageValidationTest.row(date, "Sifo", 0, "1"))));
    for (java.time.LocalDate date = LocalDate.of(2014, 9, 15);
        !date.isAfter(LocalDate.of(2014, 12, 22));
        date = date.plusDays(7))
      polls.addAll(
          PollCsv.parse(PollCsvFixtures.csv(CoverageValidationTest.row(date, "Novus", 0, "1"))));

    final se.swedishpolls.estimation.ComparableRemainder.Estimated estimated =
        estimate(period, polls);

    assertEquals(1, estimated.segments().size());
    assertEquals(
        List.of(EstimateHistory.COVERAGE_PERIOD_START, EstimateHistory.REFERENCE_CHANGE),
        estimated.boundaries().stream().map(EstimateHistory.Boundary::kind).toList());
    assertEquals(
        EstimateHistory.ACROSS_BOUNDARY,
        ComparableRemainder.change(estimated, LocalDate.of(2014, 10, 1), 30).reason());
    assertTrue(ComparableRemainder.change(estimated, LocalDate.of(2014, 12, 1), 30).available());
  }

  @Test
  void groupsOneElectionReferenceConsistentlyAcrossRosters() {
    final se.swedishpolls.source.Roster.CoveragePeriod eight =
        CoverageValidationTest.period("eight", LocalDate.of(2014, 1, 1), null, false, true);
    final se.swedishpolls.source.Roster.CoveragePeriod separate =
        CoverageValidationTest.period("separate", LocalDate.of(2014, 1, 1), null, true, true);
    final se.swedishpolls.estimation.ComparableRemainder.Reference election = ELECTIONS.getFirst();

    final se.swedishpolls.estimation.ComparableRemainder.Grouped grouped =
        ComparableRemainder.group(eight, election, List.of());
    final se.swedishpolls.estimation.ComparableRemainder.Grouped split =
        ComparableRemainder.group(separate, election, List.of());

    // The keys follow the period's own roster, so a display reads them in one order per period.
    assertEquals(concat(eight.roster(), "OTHER"), List.copyOf(grouped.shares().keySet()));
    assertEquals(concat(eight.roster(), "FI", "RESIDUAL"), List.copyOf(split.shares().keySet()));
    // The same official result, grouped two ways, is the same aggregate outside the eight.
    assertEquals(2.0, grouped.comparableRemainder(), 1e-12);
    assertEquals(grouped.comparableRemainder(), split.comparableRemainder());
    assertEquals(
        grouped.shares().get("OTHER"),
        split.shares().get("FI") + split.shares().get("RESIDUAL"),
        1e-12);
    for (java.lang.String party : PollCsv.PARTIES)
      assertEquals(grouped.shares().get(party), split.shares().get(party));
    for (java.util.Map<java.lang.String, java.lang.Double> shares :
        List.of(grouped.shares(), split.shares()))
      assertEquals(100, shares.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);

    for (Map<String, Double> inadmissible :
        List.of(
            Map.of("S", 100.0),
            complete(Map.of("RESIDUAL", -1.0)),
            complete(Map.of("RESIDUAL", 5.0))))
      assertThrows(
          IllegalArgumentException.class,
          () -> new ComparableRemainder.Reference(LocalDate.of(2014, 9, 14), inadmissible));
  }

  private static Map<String, Double> complete(Map<String, Double> overrides) {
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> shares =
        new LinkedHashMap<>(ELECTIONS.getFirst().shares());
    shares.putAll(overrides);
    return shares;
  }

  private static List<String> concat(List<String> head, String... tail) {
    final java.util.ArrayList<java.lang.String> all = new ArrayList<>(head);
    all.addAll(List.of(tail));
    return List.copyOf(all);
  }

  @Test
  void marksAnElectionOutsideTheEstimatedHistoryWithoutMakingItAnObservation() {
    final se.swedishpolls.source.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2014, 1, 1), null, false, true);
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2014, 6, 2), LocalDate.of(2014, 12, 22), "1");

    final se.swedishpolls.estimation.ComparableRemainder.Estimated estimated =
        estimate(period, polls);

    assertEquals(2, estimated.elections().size());
    final se.swedishpolls.estimation.ComparableRemainder.Grouped held =
        estimated.elections().getFirst();
    assertEquals(LocalDate.of(2014, 9, 14), held.date());
    assertTrue(held.insideSupportedHistory());
    assertFalse(estimated.elections().getLast().insideSupportedHistory());
    // The estimated history still stops at the last observation midpoint, not at an election.
    assertEquals(LocalDate.of(2014, 12, 22), estimated.segments().getLast().to());
    assertTrue(
        estimated.segments().stream()
            .flatMap(segment -> segment.days().stream())
            .noneMatch(day -> day.date().equals(LocalDate.of(2018, 9, 9))));
  }

  @Test
  void publishesOnlyValidatedPeriodsAndCarriesTheCoverageGateInWhole() {
    final se.swedishpolls.source.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final se.swedishpolls.source.Roster.CoveragePeriod candidate =
        CoverageValidationTest.period(
            "candidate", LocalDate.of(2015, 1, 1), LocalDate.of(2016, 12, 31), true, false);
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");
    final se.swedishpolls.estimation.CoverageValidation.Gate gate =
        new CoverageValidation.Gate(true, List.of("development tuning: blocked"));

    final se.swedishpolls.estimation.ComparableRemainder.Report report =
        ComparableRemainder.report(
            List.of(period, candidate),
            polls,
            ELECTIONS,
            coverage(gate, List.of(period), polls),
            RULES);

    assertEquals(1, report.periods().size());
    final se.swedishpolls.estimation.ComparableRemainder.Published published =
        report.periods().getFirst();
    assertEquals("eight", published.periodId());
    assertEquals(148, published.estimatedDays());
    assertEquals(2, published.segmentEdges().size());
    assertEquals(published.headline(), published.segmentEdges().getLast());
    assertEquals(gate, report.gate());
    assertTrue(report.gate().blocked());

    final java.lang.String text = ComparableRemainder.report(report);
    assertTrue(text.contains("\"protocolVersion\" : \"v1-development-1\""));
    assertTrue(text.contains("\"comparableRemainder\""));
    // A summary, not a dump: two edges per segment rather than 148 daily remainders.
    assertFalse(text.contains("2015-03-17"));

    final java.lang.IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ComparableRemainder.report(
                    List.of(period),
                    polls,
                    ELECTIONS,
                    new CoverageValidation.Report(
                        "v1-development-1", coverage(), gate, List.of(), List.of()),
                    RULES));
    assertTrue(missing.getMessage().contains("eight"), missing::getMessage);
  }
}
