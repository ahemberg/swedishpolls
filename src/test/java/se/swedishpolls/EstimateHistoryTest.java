package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EstimateHistoryTest {
  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final List<LocalDate> ELECTIONS =
      List.of(LocalDate.of(2014, 9, 14), LocalDate.of(2018, 9, 9));
  private static final DailyStateSpace.Parameters POINT =
      new DailyStateSpace.Parameters(1e-4, 0.1, 1.5);

  /**
   * Few enough draws to keep the unit checks quick; the registered count is an integration cost.
   */
  private static final JointUncertainty.Rules DRAWS =
      new JointUncertainty.Rules(20260908, 400, List.of(0.5, 0.95), 4);

  private static final EstimateHistory.Publication PUBLICATION = new EstimateHistory.Publication(1);

  private static CoverageValidation.Rules rules() {
    return CoverageValidation.rules(PROTOCOL);
  }

  private static EstimateHistory.History history(
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      CoverageValidation.Report coverage) {
    return EstimateHistory.history(periods, polls, elections, coverage, DRAWS, PUBLICATION);
  }

  /** One poll whose fieldwork spans a week, so its midpoint falls three days before its end. */
  private static List<PollCsv.Poll> window(LocalDate from, LocalDate to, String institute) {
    return PollCsv.parse(
        PollCsvTest.csv(
            CoverageValidationTest.row(from, institute, 0, "1")
                .replace("," + from.plusDays(1) + ",", "," + to.plusDays(1) + ",")
                .replace("," + from + ",FALSE", "," + to + ",FALSE")));
  }

  private static CoverageValidation.Report coverage(
      CoverageValidation.Gate gate, List<CoverageValidation.Validated> periods) {
    return new CoverageValidation.Report("v1-development-1", rules(), gate, periods, List.of());
  }

  private static CoverageValidation.Validated validated(
      Roster.CoveragePeriod period, List<PollCsv.Poll> polls) {
    return new CoverageValidation.Validated(
        period.id(),
        true,
        POINT,
        CoverageValidation.support(period, polls, rules()),
        List.of(),
        List.of());
  }

  @Test
  void retainsEveryDayBetweenPollsAndStopsAtTheLastMidpointWithoutProjecting() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.List<se.swedishpolls.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    final se.swedishpolls.EstimateHistory.Estimated estimated =
        EstimateHistory.estimate(period, polls, ELECTIONS, POINT, rules(), DRAWS);

    assertEquals(1, estimated.segments().size());
    final se.swedishpolls.EstimateHistory.Segment segment = estimated.segments().getFirst();
    assertEquals(LocalDate.of(2015, 1, 5), segment.from());
    // The fit reaches the last observation midpoint and no day further.
    assertEquals(LocalDate.of(2015, 6, 1), segment.to());
    assertEquals(148, segment.days().size());
    for (int day = 0; day < segment.days().size(); day++) {
      final se.swedishpolls.EstimateHistory.Day estimate = segment.days().get(day);
      assertEquals(segment.from().plusDays(day), estimate.date());
      assertEquals(9, estimate.shares().size());
      assertEquals(
          100, estimate.shares().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }
    // A day between two polls carries an estimate rather than a hole.
    assertTrue(
        segment.days().stream().anyMatch(day -> day.date().equals(LocalDate.of(2015, 1, 8))));
    assertEquals(
        List.of(EstimateHistory.COVERAGE_PERIOD_START),
        estimated.boundaries().stream().map(EstimateHistory.Boundary::kind).toList());
    assertEquals(LocalDate.of(2015, 1, 5), estimated.boundaries().getFirst().date());
  }

  @Test
  void anOverlongGapEndsSupportInsteadOfBeingBridged() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.ArrayList<se.swedishpolls.PollCsv.Poll> polls =
        new ArrayList<>(
            CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 3, 2), "1"));
    polls.addAll(
        CoverageValidationTest.weekly(LocalDate.of(2015, 6, 1), LocalDate.of(2015, 8, 3), "1"));

    final se.swedishpolls.EstimateHistory.Estimated estimated =
        EstimateHistory.estimate(period, polls, ELECTIONS, POINT, rules(), DRAWS);

    assertEquals(2, estimated.segments().size());
    assertEquals(LocalDate.of(2015, 3, 2), estimated.segments().getFirst().to());
    assertEquals(LocalDate.of(2015, 6, 1), estimated.segments().get(1).from());
    assertEquals(LocalDate.of(2015, 8, 3), estimated.segments().get(1).to());
    // Nothing in the unsupported run is published, and nothing there is zero either.
    assertTrue(
        estimated.segments().stream()
            .flatMap(segment -> segment.days().stream())
            .noneMatch(day -> day.date().equals(LocalDate.of(2015, 4, 15))));
    assertEquals(
        List.of(EstimateHistory.COVERAGE_PERIOD_START, EstimateHistory.UNSUPPORTED_GAP),
        estimated.boundaries().stream().map(EstimateHistory.Boundary::kind).toList());
    final se.swedishpolls.EstimateHistory.Boundary gap = estimated.boundaries().get(1);
    assertEquals(LocalDate.of(2015, 6, 1), gap.date());
    assertTrue(gap.note().contains("91"), gap::toString);
    // The two sides are separate fits, not one fit sliced: each restarts its own diffuse prior.
    assertNotEquals(
        estimated.segments().getFirst().days().getLast().shares(),
        estimated.segments().get(1).days().getFirst().shares());
  }

  @Test
  void theHeadlineIsDatedAtTheLastFieldworkDateAndCarriesTheLastEstimatedDay() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.ArrayList<se.swedishpolls.PollCsv.Poll> polls =
        new ArrayList<>(
            CoverageValidationTest.weekly(
                LocalDate.of(2015, 1, 5), LocalDate.of(2015, 5, 25), "1"));
    polls.addAll(window(LocalDate.of(2015, 5, 26), LocalDate.of(2015, 6, 1), "Sifo"));

    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(period),
            polls,
            ELECTIONS,
            coverage(
                new CoverageValidation.Gate(true, List.of("development tuning: blocked")),
                List.of(validated(period, polls))));

    final se.swedishpolls.EstimateHistory.Headline headline = history.headline();
    assertEquals("eight", headline.periodId());
    // The estimand is voting intention on the last fieldwork date; the state is never walked there.
    assertEquals(LocalDate.of(2015, 6, 1), headline.asOf());
    assertEquals(LocalDate.of(2015, 5, 29), headline.estimatedOn());
    assertTrue(headline.estimatedOn().isBefore(headline.asOf()));
    final se.swedishpolls.EstimateHistory.Day last = history.segments().getLast().days().getLast();
    assertEquals(last.date(), headline.estimatedOn());
    assertEquals(last.shares(), headline.shares());
  }

  @Test
  void changesStayInsideOneSegmentAndAreSuppressedAcrossABoundary() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.ArrayList<se.swedishpolls.PollCsv.Poll> polls =
        new ArrayList<>(
            CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 3, 2), "1"));
    polls.addAll(
        CoverageValidationTest.weekly(LocalDate.of(2015, 6, 1), LocalDate.of(2015, 8, 3), "1"));
    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(period),
            polls,
            ELECTIONS,
            coverage(
                new CoverageValidation.Gate(false, List.of()), List.of(validated(period, polls))));

    final se.swedishpolls.EstimateHistory.Change inside =
        EstimateHistory.change(history, "eight", LocalDate.of(2015, 2, 4), 30);
    assertTrue(inside.available());
    assertEquals(LocalDate.of(2015, 1, 5), inside.from());
    final java.util.Map<java.lang.String, java.lang.Double> from =
        history.segments().getFirst().days().getFirst().shares();
    final java.util.Map<java.lang.String, java.lang.Double> to =
        history.segments().getFirst().days().stream()
            .filter(day -> day.date().equals(LocalDate.of(2015, 2, 4)))
            .findFirst()
            .orElseThrow()
            .shares();
    for (java.util.Map.Entry<java.lang.String, java.lang.Double> party : inside.points().entrySet())
      assertEquals(to.get(party.getKey()) - from.get(party.getKey()), party.getValue(), 1e-12);

    // 2015-02-25 is estimated, but the unsupported summer sits between the two dates.
    final se.swedishpolls.EstimateHistory.Change across =
        EstimateHistory.change(history, "eight", LocalDate.of(2015, 6, 5), 100);
    assertFalse(across.available());
    assertEquals(EstimateHistory.ACROSS_BOUNDARY, across.reason());
    assertTrue(across.points().isEmpty());

    final se.swedishpolls.EstimateHistory.Change before =
        EstimateHistory.change(history, "eight", LocalDate.of(2015, 1, 20), 30);
    assertFalse(before.available());
    assertEquals(EstimateHistory.COMPARISON_UNSUPPORTED, before.reason());

    final se.swedishpolls.EstimateHistory.Change unsupported =
        EstimateHistory.change(history, "eight", LocalDate.of(2015, 4, 15), 30);
    assertFalse(unsupported.available());
    assertEquals(EstimateHistory.DATE_UNSUPPORTED, unsupported.reason());
  }

  @Test
  void filteredPublicationTimeStatesStayOutOfThePublishedHistory() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.List<se.swedishpolls.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");
    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(period),
            polls,
            ELECTIONS,
            coverage(
                new CoverageValidation.Gate(false, List.of()), List.of(validated(period, polls))));

    final java.util.List<se.swedishpolls.EstimateHistory.Composition> filtered =
        EstimateHistory.internalFiltered(period, polls, ELECTIONS, POINT, rules());
    final java.util.List<se.swedishpolls.EstimateHistory.Day> smoothed =
        history.segments().getFirst().days();
    assertEquals(smoothed.size(), filtered.size());
    // The two differ: the filter has not yet seen the later polls that the smoother conditions on.
    assertTrue(
        filtered.stream()
            .anyMatch(
                day ->
                    smoothed.stream()
                        .anyMatch(
                            other ->
                                other.date().equals(day.date())
                                    && Math.abs(other.shares().get("S") - day.shares().get("S"))
                                        > 1e-6)));
    assertFalse(EstimateHistory.report(history).contains("filtered"));
  }

  @Test
  void publishesTheDrawnMeanAndKeepsTheTransformOfTheMeanStateAsADiagnostic() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.List<se.swedishpolls.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    final se.swedishpolls.EstimateHistory.Estimated estimated =
        EstimateHistory.estimate(period, polls, ELECTIONS, POINT, rules(), DRAWS);
    final java.util.List<se.swedishpolls.JointUncertainty.Day> drawn =
        JointUncertainty.estimate(period, polls, ELECTIONS, POINT, rules(), DRAWS)
            .segments()
            .getFirst()
            .days();
    final java.util.List<se.swedishpolls.EstimateHistory.Composition> stateMean =
        EstimateHistory.internalStateMean(period, polls, ELECTIONS, POINT, rules());

    final java.util.List<se.swedishpolls.EstimateHistory.Day> days =
        estimated.segments().getFirst().days();
    assertEquals(drawn.size(), days.size());
    assertEquals(days.size(), stateMean.size());
    double shift = 0;
    for (int day = 0; day < days.size(); day++) {
      final se.swedishpolls.EstimateHistory.Day published = days.get(day);
      final se.swedishpolls.JointUncertainty.Day summary = drawn.get(day);
      assertEquals(summary.date(), published.date());
      // The same draws behind the published mean are behind its published endpoints.
      for (se.swedishpolls.JointUncertainty.Component component : summary.components()) {
        final se.swedishpolls.EstimateHistory.Estimate estimate =
            published.components().get(component.component());
        assertEquals(component.mean(), estimate.mean());
        assertEquals(component.intervals(), estimate.intervals());
        shift =
            Math.max(
                shift,
                Math.abs(
                    component.stateMean()
                        - stateMean.get(day).shares().get(component.component())));
      }
      // Every draw is a composition, so the mean of the draws is one too.
      assertEquals(
          100, published.shares().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }
    // The diagnostic is exactly the transform of the mean state, and it is a different series.
    assertEquals(0, shift);
    assertNotEquals(
        stateMean.getLast().shares().get("S"), days.getLast().shares().get("S"), "" + shift);
    final java.lang.String report =
        EstimateHistory.report(
            history(
                List.of(period),
                polls,
                ELECTIONS,
                coverage(
                    new CoverageValidation.Gate(false, List.of()),
                    List.of(validated(period, polls)))));
    assertFalse(report.contains("stateMean"));
  }

  @Test
  void quotesPublishedNumbersAtTheRegisteredResolution() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.List<se.swedishpolls.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");
    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(period),
            polls,
            ELECTIONS,
            coverage(
                new CoverageValidation.Gate(false, List.of()), List.of(validated(period, polls))));

    assertEquals(PUBLICATION, history.publication());
    assertEquals(new EstimateHistory.Publication(1), EstimateHistory.publication(PROTOCOL));
    assertEquals(35.1, PUBLICATION.quote(35.14159));
    assertEquals(35.2, PUBLICATION.quote(35.15));
    assertThrows(IllegalArgumentException.class, () -> new EstimateHistory.Publication(-1));
    assertThrows(
        IllegalArgumentException.class,
        () -> EstimateHistory.publication(Path.of("docs", "validation", "tuning.json")));

    // The series keeps every digit it drew; the report quotes them at the registered resolution.
    assertTrue(
        history.segments().getFirst().days().getFirst().shares().values().stream()
            .anyMatch(share -> share != PUBLICATION.quote(share)));
    final java.lang.String report = EstimateHistory.report(history);
    for (java.lang.String line : report.lines().toList())
      if (line.contains("\"mean\"") || line.contains("\"lower\"") || line.contains("\"upper\"")) {
        final java.lang.String quoted =
            line.substring(line.indexOf(':') + 1).replace(",", "").trim();
        assertEquals(
            quoted, "" + PUBLICATION.quote(Double.parseDouble(quoted)), "Unquoted number " + line);
      }
  }

  @Test
  void anUnvalidatedPeriodPublishesNoCurveAndItsPartyStaysUnavailableRatherThanZero() {
    final se.swedishpolls.Roster.CoveragePeriod eight =
        CoverageValidationTest.period("eight", LocalDate.of(2014, 1, 1), null, false, true);
    final se.swedishpolls.Roster.CoveragePeriod candidate =
        CoverageValidationTest.period(
            "candidate", LocalDate.of(2015, 1, 1), LocalDate.of(2016, 12, 31), true, false);
    final java.util.List<se.swedishpolls.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2014, 6, 2), LocalDate.of(2016, 6, 27), "1");
    final se.swedishpolls.CoverageValidation.Gate gate =
        new CoverageValidation.Gate(
            true, List.of("development tuning: 38 of 94 resolved folds sit on a grid boundary"));

    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(eight, candidate),
            polls,
            ELECTIONS,
            coverage(gate, List.of(validated(eight, polls), validated(candidate, polls))));

    assertTrue(history.segments().stream().allMatch(segment -> segment.periodId().equals("eight")));
    assertEquals(
        List.of(new EstimateHistory.Unavailable("FI", EstimateHistory.NO_VALIDATED_PERIOD)),
        history.unavailable());
    assertEquals(gate, history.gate());
    assertTrue(history.gate().blocked());
  }

  @Test
  void aCycleWhoseInstituteEnsembleChangesIsMarkedAsAReferenceBreak() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2014, 1, 1), null, false, true);
    final java.util.ArrayList<se.swedishpolls.PollCsv.Poll> polls = new ArrayList<PollCsv.Poll>();
    for (java.time.LocalDate date = LocalDate.of(2014, 6, 2);
        date.isBefore(LocalDate.of(2014, 9, 14));
        date = date.plusDays(7))
      polls.addAll(
          PollCsv.parse(PollCsvTest.csv(CoverageValidationTest.row(date, "Sifo", 0, "1"))));
    for (java.time.LocalDate date = LocalDate.of(2014, 9, 15);
        !date.isAfter(LocalDate.of(2014, 12, 22));
        date = date.plusDays(7))
      polls.addAll(
          PollCsv.parse(PollCsvTest.csv(CoverageValidationTest.row(date, "Novus", 0, "1"))));

    final se.swedishpolls.EstimateHistory.Estimated estimated =
        EstimateHistory.estimate(period, polls, ELECTIONS, POINT, rules(), DRAWS);

    // One continuous series: the reference changed, the days did not stop.
    assertEquals(1, estimated.segments().size());
    final java.util.List<se.swedishpolls.EstimateHistory.Boundary> reference =
        estimated.boundaries().stream()
            .filter(boundary -> boundary.kind().equals(EstimateHistory.REFERENCE_CHANGE))
            .toList();
    assertEquals(1, reference.size());
    assertEquals(LocalDate.of(2014, 9, 14), reference.getFirst().date());
    // No change may be presented across it, even though every day around it is estimated.
    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(period),
            polls,
            ELECTIONS,
            coverage(
                new CoverageValidation.Gate(false, List.of()), List.of(validated(period, polls))));
    assertEquals(
        EstimateHistory.ACROSS_BOUNDARY,
        EstimateHistory.change(history, "eight", LocalDate.of(2014, 10, 1), 30).reason());
    assertTrue(EstimateHistory.change(history, "eight", LocalDate.of(2014, 12, 1), 30).available());
  }

  @Test
  void theReportSummarizesEverySegmentWithoutRepeatingEveryDay() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.List<se.swedishpolls.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");
    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(period),
            polls,
            ELECTIONS,
            coverage(
                new CoverageValidation.Gate(false, List.of()), List.of(validated(period, polls))));

    final java.lang.String report = EstimateHistory.report(history);

    assertTrue(report.contains("\"protocolVersion\" : \"v1-development-1\""));
    assertTrue(report.contains("\"days\" : 148"));
    assertTrue(report.contains("\"asOf\" : \"2015-06-01\""));
    // A summary, not a dump: one line per segment edge rather than 148 daily compositions.
    assertFalse(report.contains("2015-03-17"));
  }

  @Test
  void aPeriodWithoutTunedParametersOrRecordedSupportIsRejected() {
    final se.swedishpolls.Roster.CoveragePeriod period =
        CoverageValidationTest.period("eight", LocalDate.of(2015, 1, 1), null, false, true);
    final java.util.List<se.swedishpolls.PollCsv.Poll> polls =
        CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 6, 1), "1");

    final java.lang.IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                history(
                    List.of(period),
                    polls,
                    ELECTIONS,
                    coverage(new CoverageValidation.Gate(false, List.of()), List.of())));
    assertTrue(missing.getMessage().contains("eight"), missing::getMessage);

    final se.swedishpolls.CoverageValidation.Validated unsupported =
        new CoverageValidation.Validated(
            period.id(),
            false,
            POINT,
            CoverageValidation.support(period, polls, rules()),
            List.of(),
            List.of("largest internal gap of 91 days exceeds 45"));
    final se.swedishpolls.EstimateHistory.History history =
        history(
            List.of(period),
            polls,
            ELECTIONS,
            coverage(new CoverageValidation.Gate(false, List.of()), List.of(unsupported)));
    // A validated roster whose evidence failed publishes nothing and says so on the gate.
    assertTrue(history.segments().isEmpty());
    assertTrue(history.gate().blocked());
    assertTrue(
        history.gate().reasons().stream().anyMatch(reason -> reason.contains("eight")),
        history::toString);
  }
}
