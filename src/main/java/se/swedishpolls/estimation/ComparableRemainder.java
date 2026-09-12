package se.swedishpolls.estimation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import se.swedishpolls.model.ElectionReference;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.json.JsonMapper;

/**
 * The comparable remainder of the estimated composition: support outside the fixed eight parties,
 * summarized from the same joint draws the component summaries read. Its interval is a quantile of
 * the summed draws, never the sum of two marginal endpoints, and it is grouped the same way in
 * every period so two periods' remainders describe the same aggregate.
 */
public final class ComparableRemainder {
  private ComparableRemainder() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The ten components an official election result is reported in before it is grouped. */
  public static final List<String> REFERENCE_COMPONENTS = referenceComponents();

  private static List<String> referenceComponents() {
    final java.util.ArrayList<java.lang.String> components = new ArrayList<>(PollCsv.PARTIES);
    components.add("FI");
    components.add("RESIDUAL");
    return List.copyOf(components);
  }

  /** Converts one stored official outcome to unrounded shares of valid votes. */
  public static Reference reference(ElectionReference election) {
    final LinkedHashMap<String, Double> shares = new LinkedHashMap<>();
    for (final String component : REFERENCE_COMPONENTS) {
      shares.put(component, 0.0);
    }
    for (final ElectionReference.Party party : election.results()) {
      shares.put(party.component(), 100.0 * party.votes() / election.validVotes());
    }
    return new Reference(election.electionDate(), shares);
  }

  /** One day's comparable remainder, in percent, read off that day's transformed joint draws. */
  public record Day(LocalDate date, double mean, List<JointUncertainty.Interval> intervals) {
    public Day {
      intervals = List.copyOf(intervals);
    }

    @Override
    public List<JointUncertainty.Interval> intervals() {
      return List.copyOf(intervals);
    }
  }

  /** One separately fitted run's remainder. Segments are never joined across a boundary. */
  public record Segment(String periodId, LocalDate from, LocalDate to, List<Day> days) {
    public Segment {
      days = List.copyOf(days);
    }

    public boolean covers(LocalDate date) {
      return !date.isBefore(from) && !date.isAfter(to);
    }
  }

  /**
   * An official election result in its own reported components. It is a reference the history is
   * displayed against, never an observation of voting intention.
   */
  public record Reference(LocalDate date, Map<String, Double> shares) {
    public Reference {
      shares = ordered(shares);
      if (!new LinkedHashSet<>(REFERENCE_COMPONENTS).equals(shares.keySet()))
        throw new IllegalArgumentException("An election reference reports " + REFERENCE_COMPONENTS);
      double total = 0;
      for (double share : shares.values()) {
        if (!Double.isFinite(share) || share < 0)
          throw new IllegalArgumentException("Inadmissible election reference share " + share);
        total += share;
      }
      if (Math.abs(total - 100) > 1e-6)
        throw new IllegalArgumentException("An election reference closes to 100, not " + total);
    }

    @Override
    public Map<String, Double> shares() {
      return ordered(shares);
    }

    /** Support outside the fixed eight, which includes FI whatever the period does with it. */
    public double comparableRemainder() {
      return shares.get("FI") + shares.get("RESIDUAL");
    }
  }

  /**
   * One election result grouped into a period's own components. The grouping follows the roster, so
   * the same result reads as OTHER where FI is not separate and as FI beside RESIDUAL where it is;
   * the comparable remainder is the same number either way.
   */
  public record Grouped(
      LocalDate date,
      String periodId,
      Map<String, Double> shares,
      double comparableRemainder,
      boolean insideSupportedHistory) {
    public Grouped {
      shares = ordered(shares);
    }

    @Override
    public Map<String, Double> shares() {
      return ordered(shares);
    }
  }

  /** A remainder change over a fixed number of days, or the reason there is none to present. */
  public record Change(
      boolean available, LocalDate from, LocalDate to, double points, String reason) {}

  /**
   * One period's drawn remainder, with the boundaries a change may not cross and its elections
   * grouped into this period's components.
   */
  public record Estimated(
      String periodId,
      List<String> members,
      List<Segment> segments,
      List<EstimateHistory.Boundary> boundaries,
      List<Grouped> elections,
      double maxEndpointSumErrorPoints) {
    public Estimated {
      members = List.copyOf(members);
      segments = List.copyOf(segments);
      boundaries = List.copyOf(boundaries);
      elections = List.copyOf(elections);
    }

    @Override
    public List<String> members() {
      return List.copyOf(members);
    }

    @Override
    public List<Segment> segments() {
      return List.copyOf(segments);
    }

    @Override
    public List<EstimateHistory.Boundary> boundaries() {
      return List.copyOf(boundaries);
    }

    @Override
    public List<Grouped> elections() {
      return List.copyOf(elections);
    }
  }

  private static Map<String, Double> ordered(Map<String, Double> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  /**
   * The comparable remainder of every estimated day of one period, drawn over the same separately
   * fitted runs the daily history and the component summaries publish. The election dates that set
   * the cycle resets are the dates of these same references, so the fit and the display group one
   * list of elections rather than two.
   */
  public static Estimated estimate(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<Reference> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules coverage,
      JointUncertainty.Rules rules) {
    final java.util.List<java.time.LocalDate> dates =
        elections.stream().map(Reference::date).toList();
    final se.swedishpolls.estimation.EstimateHistory.Fitted fitted =
        EstimateHistory.fitted(period, polls, dates, parameters, coverage);
    final java.util.ArrayList<se.swedishpolls.estimation.ComparableRemainder.Segment> segments =
        new ArrayList<Segment>();
    double endpointSumError = 0;
    for (se.swedishpolls.estimation.EstimateHistory.Span span : fitted.spans()) {
      final double[][] basis = PollObservations.transposedBasis(span.batch());
      final int[] members = members(span.batch(), period);
      final java.util.List<se.swedishpolls.estimation.ComparableRemainder.Summarized> summarized =
          span.fit().days().parallelStream()
              .map(day -> summarize(span.batch(), basis, period.id(), day, rules, members))
              .toList();
      final java.util.List<se.swedishpolls.estimation.ComparableRemainder.Day> days =
          summarized.stream().map(Summarized::day).toList();
      for (se.swedishpolls.estimation.ComparableRemainder.Summarized summary : summarized)
        endpointSumError = Math.max(endpointSumError, summary.endpointSumErrorPoints());
      segments.add(new Segment(period.id(), days.getFirst().date(), days.getLast().date(), days));
    }
    final java.util.List<se.swedishpolls.estimation.EstimateHistory.Boundary> boundaries =
        EstimateHistory.boundaries(period, fitted, coverage);
    final java.util.ArrayList<se.swedishpolls.estimation.ComparableRemainder.Grouped> grouped =
        new ArrayList<Grouped>();
    for (se.swedishpolls.estimation.ComparableRemainder.Reference election : elections)
      grouped.add(group(period, election, segments));
    return new Estimated(
        period.id(), memberNames(period), segments, boundaries, grouped, endpointSumError);
  }

  /** The components the remainder combines: FI and the residual only where FI is separate. */
  public static List<String> memberNames(Roster.CoveragePeriod period) {
    return period.individualFi() ? List.of("FI", "RESIDUAL") : List.of("OTHER");
  }

  private static int[] members(PollObservations.Batch batch, Roster.CoveragePeriod period) {
    final java.util.List<java.lang.String> names = memberNames(period);
    final int[] indexes = new int[names.size()];
    for (int member = 0; member < names.size(); member++) {
      indexes[member] = batch.components().indexOf(names.get(member));
      if (indexes[member] < 0)
        throw new IllegalArgumentException(
            "The roster of " + period.id() + " has no " + names.get(member));
    }
    return indexes;
  }

  /** A day's summary and how far its endpoints sit from the inadmissible sum of marginal ones. */
  private record Summarized(Day day, double endpointSumErrorPoints) {}

  /**
   * The remainder of one day, summed inside each draw and only then averaged and cut into
   * quantiles. Summing the members' own interval endpoints instead would ignore how they covary and
   * is measured here purely to show the two are not the same number.
   */
  private static Summarized summarize(
      PollObservations.Batch batch,
      double[][] basis,
      String periodId,
      DailyStateSpace.Day day,
      JointUncertainty.Rules rules,
      int[] members) {
    final double[][] draws = JointUncertainty.transformed(batch, basis, periodId, day, rules);
    final double[] summed = new double[draws.length];
    final double[][] marginal = new double[members.length][draws.length];
    double total = 0;
    for (int draw = 0; draw < draws.length; draw++) {
      double remainder = 0;
      for (int member = 0; member < members.length; member++) {
        final double share = draws[draw][members[member]];
        marginal[member][draw] = share;
        remainder += share;
      }
      summed[draw] = remainder;
      total += remainder;
    }
    Arrays.sort(summed);
    for (double[] component : marginal) Arrays.sort(component);
    final java.util.ArrayList<se.swedishpolls.estimation.JointUncertainty.Interval> intervals =
        new ArrayList<JointUncertainty.Interval>(rules.intervalLevels().size());
    double error = 0;
    for (double level : rules.intervalLevels()) {
      final double lower = JointUncertainty.quantile(summed, (1 - level) / 2);
      final double upper = JointUncertainty.quantile(summed, (1 + level) / 2);
      intervals.add(new JointUncertainty.Interval(level, lower, upper));
      double summedLower = 0;
      double summedUpper = 0;
      for (double[] component : marginal) {
        summedLower += JointUncertainty.quantile(component, (1 - level) / 2);
        summedUpper += JointUncertainty.quantile(component, (1 + level) / 2);
      }
      error =
          Math.max(error, Math.max(Math.abs(lower - summedLower), Math.abs(upper - summedUpper)));
    }
    return new Summarized(new Day(day.date(), total / draws.length, intervals), error);
  }

  /**
   * One election result read in a period's own components. The eight parties pass through, FI and
   * the residual are kept apart only where the roster keeps them apart, and the remainder combines
   * them in every period so the aggregate means the same thing across a roster change.
   */
  public static Grouped group(
      Roster.CoveragePeriod period, Reference election, List<Segment> segments) {
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> shares =
        new LinkedHashMap<String, Double>();
    for (java.lang.String component : period.roster())
      shares.put(component, election.shares().get(component));
    if (period.individualFi()) shares.put("RESIDUAL", election.shares().get("RESIDUAL"));
    else shares.put("OTHER", election.comparableRemainder());
    return new Grouped(
        election.date(),
        period.id(),
        shares,
        election.comparableRemainder(),
        segments.stream().anyMatch(segment -> segment.covers(election.date())));
  }

  /**
   * The remainder's change over a fixed number of days, available only when one segment estimates
   * both days and no boundary of the period lies between them. A separate fit, an unsupported run
   * and a changed centering reference are all breaks, and a break is never movement.
   */
  public static Change change(Estimated estimated, LocalDate to, int days) {
    if (days < 1) throw new IllegalArgumentException("A change spans at least one day");
    final java.time.LocalDate from = to.minusDays(days);
    final se.swedishpolls.estimation.ComparableRemainder.Segment target = segment(estimated, to);
    if (target == null) return new Change(false, from, to, 0, EstimateHistory.DATE_UNSUPPORTED);
    final se.swedishpolls.estimation.ComparableRemainder.Segment comparison =
        segment(estimated, from);
    if (comparison == null)
      return new Change(false, from, to, 0, EstimateHistory.COMPARISON_UNSUPPORTED);
    if (!comparison.from().equals(target.from())
        || estimated.boundaries().stream()
            .anyMatch(boundary -> boundary.date().isAfter(from) && !boundary.date().isAfter(to)))
      return new Change(false, from, to, 0, EstimateHistory.ACROSS_BOUNDARY);
    return new Change(true, from, to, day(target, to).mean() - day(comparison, from).mean(), null);
  }

  private static Segment segment(Estimated estimated, LocalDate date) {
    return estimated.segments().stream()
        .filter(segment -> segment.covers(date))
        .findFirst()
        .orElse(null);
  }

  private static Day day(Segment segment, LocalDate date) {
    return segment.days().get((int) ChronoUnit.DAYS.between(segment.from(), date));
  }

  /**
   * The change over each run a segment is cut into by its own boundaries, and the one-day change
   * across every boundary the period carries. Together they show what a change may span and what it
   * may not, without a horizon any registered rule would have to name.
   */
  public static List<Change> changes(Estimated estimated) {
    final java.util.ArrayList<se.swedishpolls.estimation.ComparableRemainder.Change> changes =
        new ArrayList<Change>();
    for (se.swedishpolls.estimation.ComparableRemainder.Segment segment : estimated.segments()) {
      final java.util.ArrayList<java.time.LocalDate> starts = new ArrayList<LocalDate>();
      starts.add(segment.from());
      for (se.swedishpolls.estimation.EstimateHistory.Boundary boundary : estimated.boundaries())
        if (boundary.date().isAfter(segment.from()) && !boundary.date().isAfter(segment.to()))
          starts.add(boundary.date());
      for (int run = 0; run < starts.size(); run++) {
        final java.time.LocalDate end =
            run + 1 < starts.size() ? starts.get(run + 1).minusDays(1) : segment.to();
        if (end.isAfter(starts.get(run)))
          changes.add(change(estimated, end, (int) ChronoUnit.DAYS.between(starts.get(run), end)));
      }
    }
    for (se.swedishpolls.estimation.EstimateHistory.Boundary boundary : estimated.boundaries())
      if (!boundary.date().equals(estimated.segments().getFirst().from()))
        changes.add(change(estimated, boundary.date(), 1));
    return List.copyOf(changes);
  }

  /** One period's recorded evidence. The daily remainder itself is an estimator output. */
  public record Published(
      String periodId,
      List<String> members,
      Day headline,
      List<Day> segmentEdges,
      int estimatedDays,
      double maxEndpointSumErrorPoints,
      List<Grouped> elections,
      List<Change> changes) {
    public Published {
      members = List.copyOf(members);
      segmentEdges = List.copyOf(segmentEdges);
      elections = List.copyOf(elections);
      changes = List.copyOf(changes);
    }

    @Override
    public List<String> members() {
      return List.copyOf(members);
    }

    @Override
    public List<Day> segmentEdges() {
      return List.copyOf(segmentEdges);
    }

    @Override
    public List<Grouped> elections() {
      return List.copyOf(elections);
    }

    @Override
    public List<Change> changes() {
      return List.copyOf(changes);
    }
  }

  public record Report(
      String protocolVersion,
      CoverageValidation.Gate gate,
      JointUncertainty.Rules rules,
      List<Published> periods) {
    public Report {
      periods = List.copyOf(periods);
    }

    @Override
    public List<Published> periods() {
      return List.copyOf(periods);
    }
  }

  /**
   * Draws the remainder of every validated period whose coverage evidence passed. The coverage gate
   * carries in whole, so no number here is a release value.
   */
  public static Report report(
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<Reference> elections,
      CoverageValidation.Report coverage,
      JointUncertainty.Rules rules) {
    final java.util.LinkedHashMap<
            java.lang.String, se.swedishpolls.estimation.CoverageValidation.Validated>
        evidence = new LinkedHashMap<String, CoverageValidation.Validated>();
    for (se.swedishpolls.estimation.CoverageValidation.Validated validated : coverage.periods())
      evidence.put(validated.periodId(), validated);
    final java.util.ArrayList<java.lang.String> reasons =
        new ArrayList<>(coverage.gate().reasons());
    final java.util.ArrayList<se.swedishpolls.estimation.ComparableRemainder.Published> published =
        new ArrayList<Published>();
    for (se.swedishpolls.source.Roster.CoveragePeriod period : periods) {
      if (!period.supportValidated()) continue;
      final se.swedishpolls.estimation.CoverageValidation.Validated validated =
          evidence.get(period.id());
      if (validated == null)
        throw new IllegalArgumentException("No recorded coverage evidence for " + period.id());
      if (!validated.supported()) {
        reasons.add(period.id() + ": coverage evidence failed, so no remainder is published");
        continue;
      }
      final se.swedishpolls.estimation.ComparableRemainder.Estimated estimated =
          estimate(period, polls, elections, validated.parameters(), coverage.rules(), rules);
      final java.util.ArrayList<se.swedishpolls.estimation.ComparableRemainder.Day> edges =
          new ArrayList<Day>();
      int days = 0;
      for (se.swedishpolls.estimation.ComparableRemainder.Segment segment : estimated.segments()) {
        edges.add(segment.days().getFirst());
        edges.add(segment.days().getLast());
        days += segment.days().size();
      }
      published.add(
          new Published(
              period.id(),
              estimated.members(),
              estimated.segments().getLast().days().getLast(),
              edges,
              days,
              estimated.maxEndpointSumErrorPoints(),
              estimated.elections(),
              changes(estimated)));
    }
    return new Report(
        coverage.protocolVersion(),
        new CoverageValidation.Gate(!reasons.isEmpty(), reasons),
        rules,
        published);
  }

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }
}
