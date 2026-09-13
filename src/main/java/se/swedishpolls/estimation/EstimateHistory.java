package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
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

  /**
   * The resolution a published number is quoted at. It is registered rather than chosen by a
   * reader, because a quoted digit that moves with the draw seed is noise presented as an estimate.
   */
  public record Publication(int decimals) {
    public Publication {
      if (decimals < 0 || decimals > 6)
        throw new IllegalArgumentException("A publication resolution quotes 0 to 6 decimals");
    }

    /** The value as it is published. The series itself keeps every digit it was drawn with. */
    public double quote(double value) {
      return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }
  }

  /** The registered publication resolution. */
  public static Publication publication(Path file) {
    try {
      final tools.jackson.databind.JsonNode root = JSON.readTree(Files.readAllBytes(file));
      final tools.jackson.databind.JsonNode publication = root.get("publication");
      final tools.jackson.databind.JsonNode decimals =
          publication == null ? null : publication.get("decimals");
      if (decimals == null || decimals.isNull())
        throw new IllegalArgumentException(
            "Incomplete publication rules in " + file + ": missing decimals");
      return new Publication(decimals.intValue());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One component's published estimate on one day: the arithmetic mean of the transformed joint
   * draws, with the marginal intervals read off those same draws. The transform of the mean state
   * is a different number and stays out of here; {@link #internalStateMean} keeps it.
   */
  public record Estimate(double mean, List<JointUncertainty.Interval> intervals) {
    public Estimate {
      intervals = List.copyOf(intervals);
    }

    @Override
    public List<JointUncertainty.Interval> intervals() {
      return List.copyOf(intervals);
    }
  }

  /** One day's published composition, in percent and component order. */
  public record Day(LocalDate date, Map<String, Estimate> components) {
    public Day {
      components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }

    @Override
    public Map<String, Estimate> components() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }

    /** The point estimate of every component: the drawn mean, without its intervals. */
    public Map<String, Double> shares() {
      final java.util.LinkedHashMap<java.lang.String, java.lang.Double> shares =
          new LinkedHashMap<String, Double>();
      for (java.util.Map.Entry<
              java.lang.String, se.swedishpolls.estimation.EstimateHistory.Estimate>
          component : components.entrySet())
        shares.put(component.getKey(), component.getValue().mean());
      return ordered(shares);
    }
  }

  /**
   * One day's composition in percent and component order, with no interval. The internal diagnostic
   * series are shaped like this; a published day carries its draws with it.
   */
  public record Composition(LocalDate date, Map<String, Double> shares) {
    public Composition {
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
      Publication publication,
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

  private static Map<String, Estimate> orderedEstimates(Map<String, Estimate> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  /**
   * One separately fitted run of support, with the unsupported gap that opened it. Joint
   * uncertainty draws from the same runs, so the split lives here rather than beside each reader.
   */
  record Span(PollObservations.Batch batch, DailyStateSpace.Fit fit, int gapDays) {}

  /**
   * A period's support and fitted runs, which every summarizer of the period shares rather than
   * refitting. A publication fits once and passes this to its history, remainder, final-day draws
   * and house effects.
   */
  public record Fitted(CoverageValidation.Support support, List<Span> spans) {
    public Fitted {
      spans = List.copyOf(spans);
    }

    @Override
    public List<Span> spans() {
      return List.copyOf(spans);
    }
  }

  /**
   * Splits the period's eligible observations wherever the run between two consecutive midpoints
   * exceeds the registered maximum, and fits each run separately. Splitting the output of one long
   * fit would leave the walk propagating through the unsupported run, which is the bridge the
   * protocol forbids; each run gets its own window and its own diffuse prior instead.
   */
  public static Fitted fitted(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    return fitted(
        period, polls, elections, parameters, rules, DailyStateSpace.Centering.EQUAL_INSTITUTE);
  }

  /**
   * The same runs, centered on the given ensemble. Centering changes the reference, not the fit.
   */
  static Fitted fitted(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules,
      DailyStateSpace.Centering centering) {
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> development =
        CoverageValidation.development(polls, rules);
    final se.swedishpolls.estimation.CoverageValidation.Support support =
        CoverageValidation.support(period, development, rules);
    final se.swedishpolls.estimation.PollObservations.Batch whole =
        PollObservations.prepare(CoverageValidation.supported(period, support), development);
    final java.util.List<se.swedishpolls.estimation.PollObservations.Observation> observations =
        whole.observations().stream()
            .sorted(Comparator.comparing(PollObservations.Observation::midpoint))
            .toList();
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.Span> spans =
        new ArrayList<Span>();
    int start = 0;
    int opening = 0;
    for (int i = 1; i <= observations.size(); i++) {
      final int gap =
          i == observations.size()
              ? 0
              : (int)
                  ChronoUnit.DAYS.between(
                      observations.get(i - 1).midpoint(), observations.get(i).midpoint());
      if (i < observations.size() && gap <= rules.maxInternalGapDays()) continue;
      spans.add(
          span(period, observations.subList(start, i), elections, parameters, opening, centering));
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
      int gapDays,
      DailyStateSpace.Centering centering) {
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        observations.stream().map(PollObservations.Observation::poll).toList();
    final se.swedishpolls.source.Roster.CoveragePeriod window =
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
    final se.swedishpolls.estimation.PollObservations.Batch batch =
        PollObservations.prepare(window, polls);
    return new Span(batch, DailyStateSpace.fit(batch, elections, parameters, centering), gapDays);
  }

  /**
   * The smoothed daily estimates of one period, one segment per separately fitted run of support.
   * Each series ends at its own last observation midpoint: no day is projected past the evidence.
   *
   * <p>Every day is drawn once and summarized once, over the same runs, seed and draw count the
   * joint uncertainty uses, so the published mean and the published interval endpoints of a day
   * describe one sample rather than two.
   */
  public static Estimated estimate(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules,
      JointUncertainty.Rules draws) {
    return estimate(fitted(period, polls, elections, parameters, rules), period, rules, draws);
  }

  /** The same estimate over a fit the publication already took. */
  public static Estimated estimate(
      Fitted fitted,
      Roster.CoveragePeriod period,
      CoverageValidation.Rules rules,
      JointUncertainty.Rules draws) {
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.Segment> segments =
        new ArrayList<Segment>();
    for (se.swedishpolls.estimation.EstimateHistory.Span span : fitted.spans()) {
      final double[][] basis = PollObservations.transposedBasis(span.batch());
      final java.util.List<se.swedishpolls.estimation.EstimateHistory.Day> days =
          span.fit().days().parallelStream()
              .map(
                  day ->
                      published(
                          JointUncertainty.summarize(span.batch(), basis, period.id(), day, draws)))
              .toList();
      segments.add(new Segment(period.id(), days.getFirst().date(), days.getLast().date(), days));
    }
    return new Estimated(
        period.id(), fitted.support(), segments, boundaries(period, fitted, rules));
  }

  /**
   * The published half of a drawn day. The drawn mean and its intervals carry over; the transform
   * of the mean state beside them does not, because two numbers cannot both be the point estimate.
   */
  static Day published(JointUncertainty.Day day) {
    final java.util.LinkedHashMap<
            java.lang.String, se.swedishpolls.estimation.EstimateHistory.Estimate>
        components = new LinkedHashMap<String, Estimate>();
    for (se.swedishpolls.estimation.JointUncertainty.Component component : day.components())
      components.put(component.component(), new Estimate(component.mean(), component.intervals()));
    return new Day(day.date(), components);
  }

  /**
   * The dates the history must render as a break rather than as movement. The joint draws read the
   * same fitted runs, so every summary drawn from them marks these same boundaries instead of
   * deriving its own.
   */
  static List<Boundary> boundaries(
      Roster.CoveragePeriod period, Fitted fitted, CoverageValidation.Rules rules) {
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.Boundary> boundaries =
        new ArrayList<Boundary>();
    for (se.swedishpolls.estimation.EstimateHistory.Span span : fitted.spans()) {
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
  public static List<Composition> internalFiltered(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    return states(period, polls, elections, parameters, rules, DailyStateSpace.Day::filteredMean);
  }

  /**
   * The transform of the mean smoothed state of the same days. It is deterministic and costs no
   * draws, which is worth keeping as a diagnostic; it is not the published point estimate, because
   * the transform is nonlinear and the drawn mean is what the draws support.
   */
  public static List<Composition> internalStateMean(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules) {
    return states(period, polls, elections, parameters, rules, DailyStateSpace.Day::smoothedMean);
  }

  private static List<Composition> states(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules rules,
      Function<DailyStateSpace.Day, ModelValues> state) {
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.Composition> compositions =
        new ArrayList<Composition>();
    for (se.swedishpolls.estimation.EstimateHistory.Span span :
        fitted(period, polls, elections, parameters, rules).spans())
      for (se.swedishpolls.estimation.DailyStateSpace.Day day : span.fit().days())
        compositions.add(
            new Composition(day.date(), PollObservations.shares(span.batch(), state.apply(day))));
    return List.copyOf(compositions);
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
      CoverageValidation.Report coverage,
      JointUncertainty.Rules draws,
      Publication publication) {
    final java.util.LinkedHashMap<
            java.lang.String, se.swedishpolls.estimation.CoverageValidation.Validated>
        evidence = new LinkedHashMap<String, CoverageValidation.Validated>();
    for (se.swedishpolls.estimation.CoverageValidation.Validated validated : coverage.periods())
      evidence.put(validated.periodId(), validated);
    final java.util.ArrayList<java.lang.String> reasons =
        new ArrayList<>(coverage.gate().reasons());
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.Segment> segments =
        new ArrayList<Segment>();
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.Boundary> boundaries =
        new ArrayList<Boundary>();
    final java.util.LinkedHashMap<java.lang.String, java.time.LocalDate> lastFieldwork =
        new LinkedHashMap<String, LocalDate>();
    final java.util.LinkedHashSet<java.lang.String> published = new LinkedHashSet<String>();
    for (se.swedishpolls.source.Roster.CoveragePeriod period : periods) {
      if (!period.supportValidated()) continue;
      final se.swedishpolls.estimation.CoverageValidation.Validated validated =
          evidence.get(period.id());
      if (validated == null)
        throw new IllegalArgumentException("No recorded coverage evidence for " + period.id());
      if (!validated.supported()) {
        reasons.add(period.id() + ": coverage evidence failed, so no history is published");
        continue;
      }
      final se.swedishpolls.estimation.EstimateHistory.Estimated result =
          estimate(period, polls, elections, validated.parameters(), coverage.rules(), draws);
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
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.Unavailable> unavailable =
        new ArrayList<Unavailable>();
    final java.util.LinkedHashSet<java.lang.String> seen = new LinkedHashSet<String>();
    for (se.swedishpolls.source.Roster.CoveragePeriod period : periods)
      for (java.lang.String component : period.roster())
        if (!published.contains(component) && seen.add(component))
          unavailable.add(new Unavailable(component, NO_VALIDATED_PERIOD));
    return new History(
        coverage.protocolVersion(),
        publication,
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
    for (se.swedishpolls.estimation.EstimateHistory.Segment segment : segments)
      if (latest == null || segment.to().isAfter(latest.to())) latest = segment;
    if (latest == null) return null;
    final se.swedishpolls.estimation.EstimateHistory.Day last = latest.days().getLast();
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
    final java.time.LocalDate from = to.minusDays(days);
    final se.swedishpolls.estimation.EstimateHistory.Segment target =
        segment(history, periodId, to);
    if (target == null) return new Change(false, from, to, Map.of(), DATE_UNSUPPORTED);
    final se.swedishpolls.estimation.EstimateHistory.Segment comparison =
        segment(history, periodId, from);
    if (comparison == null) return new Change(false, from, to, Map.of(), COMPARISON_UNSUPPORTED);
    if (!comparison.from().equals(target.from())
        || history.boundaries().stream()
            .filter(boundary -> boundary.periodId().equals(periodId))
            .anyMatch(boundary -> boundary.date().isAfter(from) && !boundary.date().isAfter(to)))
      return new Change(false, from, to, Map.of(), ACROSS_BOUNDARY);
    final java.util.Map<java.lang.String, java.lang.Double> before = day(comparison, from).shares();
    final java.util.Map<java.lang.String, java.lang.Double> after = day(target, to).shares();
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> points =
        new LinkedHashMap<String, Double>();
    for (java.util.Map.Entry<java.lang.String, java.lang.Double> component : after.entrySet())
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
      Map<String, Estimate> first,
      Map<String, Estimate> last) {
    public SegmentSummary {
      first = orderedEstimates(first);
      last = orderedEstimates(last);
    }

    @Override
    public Map<String, Estimate> first() {
      return orderedEstimates(first);
    }

    @Override
    public Map<String, Estimate> last() {
      return orderedEstimates(last);
    }
  }

  public record Summary(
      String protocolVersion,
      Publication publication,
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

  /** The report quotes every number at the registered resolution; the series keeps its digits. */
  public static String report(History history) {
    final se.swedishpolls.estimation.EstimateHistory.Publication publication =
        history.publication();
    final java.util.ArrayList<se.swedishpolls.estimation.EstimateHistory.SegmentSummary> segments =
        new ArrayList<SegmentSummary>();
    for (se.swedishpolls.estimation.EstimateHistory.Segment segment : history.segments())
      segments.add(
          new SegmentSummary(
              segment.periodId(),
              segment.from(),
              segment.to(),
              segment.days().size(),
              quote(segment.days().getFirst().components(), publication),
              quote(segment.days().getLast().components(), publication)));
    final se.swedishpolls.estimation.EstimateHistory.Headline headline = history.headline();
    return JSON.writerWithDefaultPrettyPrinter()
        .writeValueAsString(
            new Summary(
                history.protocolVersion(),
                publication,
                history.gate(),
                segments,
                history.boundaries(),
                headline == null
                    ? null
                    : new Headline(
                        headline.periodId(),
                        headline.asOf(),
                        headline.estimatedOn(),
                        quoteShares(headline.shares(), publication)),
                history.unavailable()));
  }

  private static Map<String, Estimate> quote(
      Map<String, Estimate> components, Publication publication) {
    final java.util.LinkedHashMap<
            java.lang.String, se.swedishpolls.estimation.EstimateHistory.Estimate>
        quoted = new LinkedHashMap<String, Estimate>();
    for (java.util.Map.Entry<java.lang.String, se.swedishpolls.estimation.EstimateHistory.Estimate>
        component : components.entrySet()) {
      final java.util.ArrayList<se.swedishpolls.estimation.JointUncertainty.Interval> intervals =
          new ArrayList<JointUncertainty.Interval>();
      for (se.swedishpolls.estimation.JointUncertainty.Interval interval :
          component.getValue().intervals())
        intervals.add(
            new JointUncertainty.Interval(
                interval.level(),
                publication.quote(interval.lower()),
                publication.quote(interval.upper())));
      quoted.put(
          component.getKey(),
          new Estimate(publication.quote(component.getValue().mean()), intervals));
    }
    return quoted;
  }

  private static Map<String, Double> quoteShares(
      Map<String, Double> shares, Publication publication) {
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> quoted =
        new LinkedHashMap<String, Double>();
    for (java.util.Map.Entry<java.lang.String, java.lang.Double> share : shares.entrySet())
      quoted.put(share.getKey(), publication.quote(share.getValue()));
    return quoted;
  }
}
