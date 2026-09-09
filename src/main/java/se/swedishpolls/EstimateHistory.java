package se.swedishpolls;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The daily estimate history of the validated coverage periods: smoothed compositions between
 * eligible polls, refitted separately wherever support ends, with a headline dated at the last
 * fieldwork date.
 */
public final class EstimateHistory {
  private EstimateHistory() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A separate fit begins here, so the level on either side is not comparable. */
  public static final String COVERAGE_PERIOD_START = "coverage_period_start";

  /** Support ended and resumed here; the run between is unsupported and separately fitted. */
  public static final String UNSUPPORTED_GAP = "unsupported_gap";

  /** The institute ensemble the level is centered on changed here, which is not voter movement. */
  public static final String REFERENCE_CHANGE = "centering_reference_change";

  public static final String DATE_UNSUPPORTED = "date_outside_supported_history";
  public static final String COMPARISON_UNSUPPORTED = "comparison_date_outside_supported_history";
  public static final String ACROSS_BOUNDARY = "comparison_date_across_boundary";
  public static final String NO_VALIDATED_PERIOD = "no_validated_coverage_period";

  /** One day's smoothed composition, in percent and component order. */
  public record Day(LocalDate date, Map<String, Double> shares) {
    public Day {
      shares = ordered(shares);
    }

    @Override
    public Map<String, Double> shares() {
      return ordered(shares);
    }
  }

  /** One separate fit's consecutive days. Segments are never joined across a gap. */
  public record Segment(String periodId, LocalDate from, LocalDate to, List<Day> days) {
    public Segment {
      days = List.copyOf(days);
    }

    public boolean covers(LocalDate date) {
      return !date.isBefore(from) && !date.isAfter(to);
    }
  }

  /** A date the history must render as a break rather than as movement. */
  public record Boundary(String kind, LocalDate date, String periodId, String note) {}

  /**
   * The current-opinion estimate: the last estimated day, reported as of the last fieldwork date.
   * The state is never walked forward to that date.
   */
  public record Headline(
      String periodId, LocalDate asOf, LocalDate estimatedOn, Map<String, Double> shares) {
    public Headline {
      shares = ordered(shares);
    }

    @Override
    public Map<String, Double> shares() {
      return ordered(shares);
    }
  }

  /** A modeled component of some period that no validated period estimates. Never zero. */
  public record Unavailable(String component, String reason) {}

  /** One period's estimated days, with the support they were cut from. */
  public record Estimated(
      String periodId,
      CoverageValidation.Support support,
      List<Segment> segments,
      List<Boundary> boundaries) {
    public Estimated {
      segments = List.copyOf(segments);
      boundaries = List.copyOf(boundaries);
    }
  }

  /**
   * The published history. It carries smoothed estimates only; filtered publication-time states
   * stay internal to {@link #internalFiltered}.
   */
  public record History(
      String protocolVersion,
      CoverageValidation.Gate gate,
      List<Segment> segments,
      List<Boundary> boundaries,
      Headline headline,
      List<Unavailable> unavailable) {
    public History {
      segments = List.copyOf(segments);
      boundaries = List.copyOf(boundaries);
      unavailable = List.copyOf(unavailable);
    }
  }

  /** A change over a fixed number of days, or the reason there is none to present. */
  public record Change(
      boolean available, LocalDate from, LocalDate to, Map<String, Double> points, String reason) {
    public Change {
      points = ordered(points);
    }

    @Override
    public Map<String, Double> points() {
      return ordered(points);
    }
  }

  private static Map<String, Double> ordered(Map<String, Double> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  /**
   * One separately fitted run of support, with the unsupported gap that opened it. Joint
   * uncertainty draws from the same runs, so the split lives here rather than beside each reader.
   */
  record Span(PollObservations.Batch batch, DailyStateSpace.Fit fit, int gapDays) {}

  record Fitted(CoverageValidation.Support support, List<Span> spans) {}

  /**
   * Splits the period's eligible observations wherever the run between two consecutive midpoints
   * exceeds the registered maximum, and fits each run separately. Splitting the output of one long
   * fit would leave the walk propagating through the unsupported run, which is the bridge the
   * protocol forbids; each run gets its own window and its own diffuse prior instead.
   */
  static Fitted fitted(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    var development = CoverageValidation.development(polls, rules);
    var support = CoverageValidation.support(period, development, rules);
    var whole =
        PollObservations.prepare(CoverageValidation.supported(period, support), development);
    var observations =
        whole.observations().stream()
            .sorted(Comparator.comparing(PollObservations.Observation::midpoint))
            .toList();
    var spans = new ArrayList<Span>();
    int start = 0;
    int opening = 0;
    for (int i = 1; i <= observations.size(); i++) {
      int gap =
          i == observations.size()
              ? 0
              : (int)
                  ChronoUnit.DAYS.between(
                      observations.get(i - 1).midpoint(), observations.get(i).midpoint());
      if (i < observations.size() && gap <= rules.maxInternalGapDays()) continue;
      spans.add(span(period, observations.subList(start, i), elections, parameters, opening));
      opening = gap;
      start = i;
    }
    return new Fitted(support, spans);
  }

  /** One run's own fit, over the outermost collection dates of the polls it retained. */
  private static Span span(
      Roster.CoveragePeriod period,
      List<PollObservations.Observation> observations,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      int gapDays) {
    var polls = observations.stream().map(PollObservations.Observation::poll).toList();
    var window =
        new Roster.CoveragePeriod(
            period.id(),
            polls.stream()
                .map(PollCsv.Poll::collectionFrom)
                .min(LocalDate::compareTo)
                .orElseThrow(),
            polls.stream().map(PollCsv.Poll::collectionTo).max(LocalDate::compareTo).orElseThrow(),
            period.roster(),
            period.individualFi(),
            period.supportValidated(),
            period.decisionUrl());
    var batch = PollObservations.prepare(window, polls);
    return new Span(batch, DailyStateSpace.fit(batch, elections, parameters), gapDays);
  }

  /**
   * The smoothed daily estimates of one period, one segment per separately fitted run of support.
   * Each series ends at its own last observation midpoint: no day is projected past the evidence.
   */
  public static Estimated estimate(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    var fitted = fitted(period, polls, elections, parameters, rules);
    var segments = new ArrayList<Segment>();
    for (var span : fitted.spans()) {
      var days = new ArrayList<Day>();
      for (var day : span.fit().days())
        days.add(new Day(day.date(), PollObservations.shares(span.batch(), day.smoothedMean())));
      segments.add(new Segment(period.id(), days.getFirst().date(), days.getLast().date(), days));
    }
    return new Estimated(
        period.id(), fitted.support(), segments, boundaries(period, fitted, rules));
  }

  /**
   * The dates the history must render as a break rather than as movement. The joint draws read the
   * same fitted runs, so every summary drawn from them marks these same boundaries instead of
   * deriving its own.
   */
  static List<Boundary> boundaries(
      Roster.CoveragePeriod period, Fitted fitted, CoverageValidation.Rules rules) {
    var boundaries = new ArrayList<Boundary>();
    for (var span : fitted.spans()) {
      boundaries.add(opening(period, span.fit().days().getFirst().date(), span.gapDays(), rules));
      for (int cycle = 1; cycle < span.fit().cycles().size(); cycle++)
        if (!span.fit()
            .cycles()
            .get(cycle)
            .effects()
            .equals(span.fit().cycles().get(cycle - 1).effects()))
          boundaries.add(
              new Boundary(
                  REFERENCE_CHANGE,
                  span.fit().cycles().get(cycle).start(),
                  period.id(),
                  "The institutes the level is centered on changed at this cycle reset, so the"
                      + " level can step against a changed reference rather than against voter"
                      + " movement."));
    }
    boundaries.sort(Comparator.comparing(Boundary::date));
    return List.copyOf(boundaries);
  }

  private static Boundary opening(
      Roster.CoveragePeriod period, LocalDate date, int gapDays, CoverageValidation.Rules rules) {
    if (gapDays == 0)
      return new Boundary(
          COVERAGE_PERIOD_START,
          date,
          period.id(),
          "A separate fit starts here with its own diffuse prior; the step at this date is a change"
              + " of modeled membership, never voter movement.");
    return new Boundary(
        UNSUPPORTED_GAP,
        date,
        period.id(),
        "Support ended and resumed here after a run of "
            + gapDays
            + " days between observation midpoints, above the registered maximum of "
            + rules.maxInternalGapDays()
            + "; the run between is unsupported rather than zero, and the two sides are separate"
            + " fits.");
  }

  /**
   * The filtered states of the same days, which condition only on the polls seen up to each day.
   * These are the internal publication-time estimates; the published history is smoothed.
   */
  public static List<Day> internalFiltered(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    var filtered = new ArrayList<Day>();
    for (var span : fitted(period, polls, elections, parameters, rules).spans())
      for (var day : span.fit().days())
        filtered.add(
            new Day(day.date(), PollObservations.shares(span.batch(), day.filteredMean())));
    return List.copyOf(filtered);
  }

  /**
   * Assembles the history of every validated period from the coverage evidence that established its
   * dates and parameters. A period whose evidence failed publishes nothing and blocks the gate, and
   * that gate carries the tuning run behind those parameters.
   */
  public static History history(
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      CoverageValidation.Report coverage) {
    var evidence = new LinkedHashMap<String, CoverageValidation.Validated>();
    for (var validated : coverage.periods()) evidence.put(validated.periodId(), validated);
    var reasons = new ArrayList<>(coverage.gate().reasons());
    var segments = new ArrayList<Segment>();
    var boundaries = new ArrayList<Boundary>();
    var lastFieldwork = new LinkedHashMap<String, LocalDate>();
    var published = new LinkedHashSet<String>();
    for (var period : periods) {
      if (!period.supportValidated()) continue;
      var validated = evidence.get(period.id());
      if (validated == null)
        throw new IllegalArgumentException("No recorded coverage evidence for " + period.id());
      if (!validated.supported()) {
        reasons.add(period.id() + ": coverage evidence failed, so no history is published");
        continue;
      }
      var result = estimate(period, polls, elections, validated.parameters(), coverage.rules());
      // The published curve and the recorded evidence must describe the same supported window.
      if (!result.support().from().equals(validated.support().from())
          || !result.support().to().equals(validated.support().to()))
        throw new IllegalArgumentException(
            "Recorded support of " + period.id() + " does not match these polls");
      lastFieldwork.put(period.id(), result.support().to());
      segments.addAll(result.segments());
      boundaries.addAll(result.boundaries());
      published.addAll(period.roster());
      published.add(period.individualFi() ? "RESIDUAL" : "OTHER");
    }
    var unavailable = new ArrayList<Unavailable>();
    var seen = new LinkedHashSet<String>();
    for (var period : periods)
      for (var component : period.roster())
        if (!published.contains(component) && seen.add(component))
          unavailable.add(new Unavailable(component, NO_VALIDATED_PERIOD));
    return new History(
        coverage.protocolVersion(),
        new CoverageValidation.Gate(!reasons.isEmpty(), reasons),
        segments,
        boundaries,
        headline(segments, lastFieldwork),
        unavailable);
  }

  /**
   * The current-opinion estimate is the last estimated day of the latest segment, reported as of
   * that period's last fieldwork date. Nothing is walked forward to close the difference.
   */
  private static Headline headline(List<Segment> segments, Map<String, LocalDate> lastFieldwork) {
    Segment latest = null;
    for (var segment : segments)
      if (latest == null || segment.to().isAfter(latest.to())) latest = segment;
    if (latest == null) return null;
    var last = latest.days().getLast();
    return new Headline(
        latest.periodId(), lastFieldwork.get(latest.periodId()), last.date(), last.shares());
  }

  /**
   * The change over a fixed number of days within one coverage period, available only when both
   * days are estimated by that period and no boundary of it lies between them. Coverage periods
   * overlap in time, so a change is never resolved across them. A break is never movement.
   */
  public static Change change(History history, String periodId, LocalDate to, int days) {
    if (days < 1) throw new IllegalArgumentException("A change spans at least one day");
    var from = to.minusDays(days);
    var target = segment(history, periodId, to);
    if (target == null) return new Change(false, from, to, Map.of(), DATE_UNSUPPORTED);
    var comparison = segment(history, periodId, from);
    if (comparison == null) return new Change(false, from, to, Map.of(), COMPARISON_UNSUPPORTED);
    if (!comparison.from().equals(target.from())
        || history.boundaries().stream()
            .filter(boundary -> boundary.periodId().equals(periodId))
            .anyMatch(boundary -> boundary.date().isAfter(from) && !boundary.date().isAfter(to)))
      return new Change(false, from, to, Map.of(), ACROSS_BOUNDARY);
    var before = day(comparison, from).shares();
    var after = day(target, to).shares();
    var points = new LinkedHashMap<String, Double>();
    for (var component : after.entrySet())
      points.put(component.getKey(), component.getValue() - before.get(component.getKey()));
    return new Change(true, from, to, points, null);
  }

  private static Segment segment(History history, String periodId, LocalDate date) {
    return history.segments().stream()
        .filter(segment -> segment.periodId().equals(periodId) && segment.covers(date))
        .findFirst()
        .orElse(null);
  }

  private static Day day(Segment segment, LocalDate date) {
    return segment.days().get((int) ChronoUnit.DAYS.between(segment.from(), date));
  }

  /** One segment's edges. The daily series itself is an estimator output, not stored evidence. */
  public record SegmentSummary(
      String periodId,
      LocalDate from,
      LocalDate to,
      int days,
      Map<String, Double> firstShares,
      Map<String, Double> lastShares) {
    public SegmentSummary {
      firstShares = ordered(firstShares);
      lastShares = ordered(lastShares);
    }

    @Override
    public Map<String, Double> firstShares() {
      return ordered(firstShares);
    }

    @Override
    public Map<String, Double> lastShares() {
      return ordered(lastShares);
    }
  }

  public record Summary(
      String protocolVersion,
      CoverageValidation.Gate gate,
      List<SegmentSummary> segments,
      List<Boundary> boundaries,
      Headline headline,
      List<Unavailable> unavailable) {
    public Summary {
      segments = List.copyOf(segments);
      boundaries = List.copyOf(boundaries);
      unavailable = List.copyOf(unavailable);
    }
  }

  public static String report(History history) {
    var segments = new ArrayList<SegmentSummary>();
    for (var segment : history.segments())
      segments.add(
          new SegmentSummary(
              segment.periodId(),
              segment.from(),
              segment.to(),
              segment.days().size(),
              segment.days().getFirst().shares(),
              segment.days().getLast().shares()));
    return JSON.writerWithDefaultPrettyPrinter()
        .writeValueAsString(
            new Summary(
                history.protocolVersion(),
                history.gate(),
                segments,
                history.boundaries(),
                history.headline(),
                history.unavailable()));
  }
}
