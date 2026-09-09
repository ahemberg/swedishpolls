package se.swedishpolls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/**
 * Establishes the supported dates of each coverage period from eligible observations, gaps and fit
 * stability, and measures the separate-fit steps at a boundary for every modeled party.
 */
public final class CoverageValidation {
  private CoverageValidation() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The comparable remainder: support outside the fixed eight, including FI in every period. */
  public static final String REMAINDER = "REMAINDER";

  /** The registered support rules. They are development rules, not release tolerances. */
  public record Rules(
      LocalDate developmentThrough,
      int minObservations,
      int minInstitutes,
      int maxInternalGapDays,
      List<Integer> boundaryShiftDays,
      int stabilityBurnInDays,
      double maxStabilityShiftPoints) {
    public Rules {
      if (developmentThrough == null)
        throw new IllegalArgumentException("Coverage validation needs a development end date");
      if (minObservations < 1
          || minInstitutes < 1
          || maxInternalGapDays < 1
          || stabilityBurnInDays < 0
          || !Double.isFinite(maxStabilityShiftPoints)
          || maxStabilityShiftPoints <= 0)
        throw new IllegalArgumentException("Inadmissible coverage validation rule");
      boundaryShiftDays = List.copyOf(boundaryShiftDays);
      if (boundaryShiftDays.isEmpty())
        throw new IllegalArgumentException("At least one boundary shift is required");
      for (int i = 0; i < boundaryShiftDays.size(); i++)
        if (boundaryShiftDays.get(i) < 1
            || i > 0 && boundaryShiftDays.get(i) <= boundaryShiftDays.get(i - 1))
          throw new IllegalArgumentException("Boundary shifts must be positive and ascending");
    }
  }

  /** The longest run without an observation midpoint inside a supported window. */
  public record Gap(LocalDate from, LocalDate to, int days) {}

  /** What the eligible observations of one period actually cover. */
  public record Support(
      LocalDate from,
      LocalDate to,
      int observations,
      int institutes,
      int excludedPolls,
      Map<String, Integer> exclusionReasons,
      Gap largestGap) {
    public Support {
      exclusionReasons = Map.copyOf(exclusionReasons);
    }
  }

  /**
   * How far the estimate of each modeled component moves when both boundaries are pulled in by the
   * same number of days, over the days both fits retain after the burn-in.
   */
  public record Stability(
      int shiftDays,
      LocalDate from,
      LocalDate to,
      int comparedDays,
      Map<String, Double> maxShiftPoints) {
    public Stability {
      maxShiftPoints = Map.copyOf(maxShiftPoints);
    }

    public double worst() {
      return maxShiftPoints.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }
  }

  /**
   * The step between two separate fits on one boundary day, in percentage points per comparable
   * component. It is a change of modeled membership, never voter movement.
   */
  public record BoundaryEffect(
      LocalDate date, String periodId, String againstPeriodId, Map<String, Double> stepPoints) {
    public BoundaryEffect {
      stepPoints = Map.copyOf(stepPoints);
    }

    public double largestStep() {
      return stepPoints.values().stream().mapToDouble(Math::abs).max().orElse(0);
    }
  }

  /**
   * One period's evidence. `supported` states only that the registered rules hold; publishing an
   * individual estimate additionally needs the report gate clear and a recorded owner decision.
   */
  public record Validated(
      String periodId,
      boolean supported,
      DailyStateSpace.Parameters parameters,
      Support support,
      List<Stability> stability,
      List<String> failures) {
    public Validated {
      stability = List.copyOf(stability);
      failures = List.copyOf(failures);
    }
  }

  /** The aggregate gate. It stays blocked until an owner records a decision on every reason. */
  public record Gate(boolean blocked, List<String> reasons) {
    public Gate {
      reasons = List.copyOf(reasons);
    }
  }

  public record Report(
      String protocolVersion,
      Rules rules,
      Gate gate,
      List<Validated> periods,
      List<BoundaryEffect> boundaryEffects) {
    public Report {
      periods = List.copyOf(periods);
      boundaryEffects = List.copyOf(boundaryEffects);
    }
  }

  public static Rules rules(Path file) {
    try {
      var rules = JSON.readTree(Files.readAllBytes(file)).get("coverage_validation");
      var shifts = new ArrayList<Integer>();
      for (var shift : rules.get("boundary_shift_days")) shifts.add(shift.intValue());
      return new Rules(
          LocalDate.parse(rules.get("development_through").asText()),
          rules.get("min_observations").intValue(),
          rules.get("min_institutes").intValue(),
          rules.get("max_internal_gap_days").intValue(),
          shifts,
          rules.get("stability_burn_in_days").intValue(),
          rules.get("max_stability_shift_points").doubleValue());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NullPointerException e) {
      throw new IllegalArgumentException("Incomplete coverage validation rules in " + file, e);
    }
  }

  /**
   * The polls this development evidence may read: fieldwork ending by the registered development
   * end date. The reserved 2022 comparison and the prospective 2026 window stay outside it.
   */
  public static List<PollCsv.Poll> development(List<PollCsv.Poll> polls, Rules rules) {
    return polls.stream()
        .filter(poll -> poll.collectionTo() != null)
        .filter(poll -> !poll.collectionTo().isAfter(rules.developmentThrough()))
        .toList();
  }

  /**
   * The dates, counts, institutes and largest internal gap of the eligible observations one period
   * places. Corrected history is used, so a poll with an unknown publication date still counts.
   */
  public static Support support(
      Roster.CoveragePeriod period, List<PollCsv.Poll> polls, Rules rules) {
    var batch = PollObservations.prepare(period, development(polls, rules));
    if (batch.observations().isEmpty())
      throw new IllegalArgumentException("No eligible observation in " + period.id());
    var from =
        batch.observations().stream()
            .map(o -> o.poll().collectionFrom())
            .min(LocalDate::compareTo)
            .orElseThrow();
    var to =
        batch.observations().stream()
            .map(o -> o.poll().collectionTo())
            .max(LocalDate::compareTo)
            .orElseThrow();
    var institutes = new TreeSet<String>();
    var midpoints = new ArrayList<LocalDate>();
    for (var observation : batch.observations()) {
      institutes.add(observation.poll().institute());
      midpoints.add(observation.midpoint());
    }
    midpoints.sort(LocalDate::compareTo);
    var largest = new Gap(midpoints.getFirst(), midpoints.getFirst(), 0);
    for (int i = 1; i < midpoints.size(); i++) {
      int days = (int) ChronoUnit.DAYS.between(midpoints.get(i - 1), midpoints.get(i));
      if (days > largest.days()) largest = new Gap(midpoints.get(i - 1), midpoints.get(i), days);
    }
    var reasons = new TreeMap<String, Integer>();
    for (var exclusion : batch.exclusions())
      for (var reason : exclusion.reasons()) reasons.merge(reason, 1, Integer::sum);
    return new Support(
        from,
        to,
        batch.observations().size(),
        institutes.size(),
        batch.exclusions().size(),
        reasons,
        largest);
  }

  /** The period trimmed to the dates its own eligible observations cover. */
  public static Roster.CoveragePeriod supported(Roster.CoveragePeriod period, Support support) {
    return new Roster.CoveragePeriod(
        period.id(),
        support.from(),
        support.to(),
        period.roster(),
        period.individualFi(),
        period.supportValidated(),
        period.decisionUrl());
  }

  /**
   * Validates one period against the registered rules: enough eligible observations across enough
   * institutes, no internal gap longer than the registered maximum, and an estimate that survives
   * pulling both boundaries in.
   */
  public static Validated validate(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      Rules rules) {
    var development = development(polls, rules);
    var support = support(period, development, rules);
    var trimmed = supported(period, support);
    var batch = PollObservations.prepare(trimmed, development);
    var baseline = daily(batch, DailyStateSpace.fit(batch, elections, parameters));
    var failures = new ArrayList<String>();
    if (support.observations() < rules.minObservations())
      failures.add(
          "only "
              + support.observations()
              + " eligible observations, below "
              + rules.minObservations());
    if (support.institutes() < rules.minInstitutes())
      failures.add("only " + support.institutes() + " institutes, below " + rules.minInstitutes());
    if (support.largestGap().days() > rules.maxInternalGapDays())
      failures.add(
          "largest internal gap of "
              + support.largestGap().days()
              + " days from "
              + support.largestGap().from()
              + " exceeds "
              + rules.maxInternalGapDays());
    var stability = new ArrayList<Stability>();
    for (int shift : rules.boundaryShiftDays()) {
      var from = support.from().plusDays(shift);
      var to = support.to().minusDays(shift);
      // Both ends burn in: the days next to a moved boundary lose the polls the shift removed, so
      // only the interior says whether the boundary choice drives the estimate.
      var comparedFrom = from.plusDays(rules.stabilityBurnInDays());
      var comparedTo = to.minusDays(rules.stabilityBurnInDays());
      if (!comparedFrom.isBefore(comparedTo)) {
        failures.add("boundary shift of " + shift + " days leaves no comparable span");
        continue;
      }
      var variant =
          new Roster.CoveragePeriod(
              period.id(),
              from,
              to,
              period.roster(),
              period.individualFi(),
              period.supportValidated(),
              period.decisionUrl());
      var variantBatch = PollObservations.prepare(variant, development);
      if (variantBatch.observations().isEmpty()) {
        failures.add("boundary shift of " + shift + " days leaves no observation");
        continue;
      }
      var shifted = daily(variantBatch, DailyStateSpace.fit(variantBatch, elections, parameters));
      var compared = compare(baseline, shifted, comparedFrom, comparedTo);
      if (compared.isEmpty()) {
        failures.add("boundary shift of " + shift + " days leaves no compared day");
        continue;
      }
      var measured =
          new Stability(
              shift,
              compared.firstKey(),
              compared.lastKey(),
              compared.size(),
              deviations(baseline, shifted, compared.navigableKeySet()));
      stability.add(measured);
      if (measured.worst() > rules.maxStabilityShiftPoints())
        failures.add(
            "boundary shift of "
                + shift
                + " days moves an estimate by "
                + measured.worst()
                + " points, above "
                + rules.maxStabilityShiftPoints());
    }
    return new Validated(period.id(), failures.isEmpty(), parameters, support, stability, failures);
  }

  /**
   * Validates every period and measures the separate-fit step at each candidate boundary against
   * the validated period that surrounds it. A blocked tuning gate carries into this gate: the
   * parameters behind every fit here are still development values.
   */
  public static Report validateAll(
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DevelopmentTuning.Tuning tuning,
      Rules rules) {
    var development = development(polls, rules);
    var parameters = DevelopmentTuning.latestParameters(tuning);
    var validated = new ArrayList<Validated>();
    var supports = new LinkedHashMap<String, Support>();
    for (var period : periods) {
      var point = parameters.get(period.id());
      if (point == null)
        throw new IllegalArgumentException("No tuned parameters for " + period.id());
      var result = validate(period, development, elections, point, rules);
      validated.add(result);
      supports.put(period.id(), result.support());
    }
    var effects = new ArrayList<BoundaryEffect>();
    for (var period : periods)
      if (period.individualFi())
        for (var against : periods)
          if (!against.individualFi() && against.supportValidated())
            effects.addAll(
                boundaryEffects(
                    period,
                    supports.get(period.id()),
                    against,
                    supports.get(against.id()),
                    development,
                    elections,
                    parameters));
    var reasons = new ArrayList<String>();
    for (var reason : tuning.gate().reasons()) reasons.add("development tuning: " + reason);
    for (var result : validated)
      for (var failure : result.failures()) reasons.add(result.periodId() + ": " + failure);
    return new Report(
        tuning.protocolVersion(), rules, new Gate(!reasons.isEmpty(), reasons), validated, effects);
  }

  /**
   * The comparable step at the first and last retained day of a candidate period, between its own
   * fit and the surrounding validated fit. FI and the residual are summed into the comparable
   * remainder, so both sides describe the same aggregate.
   */
  private static List<BoundaryEffect> boundaryEffects(
      Roster.CoveragePeriod period,
      Support support,
      Roster.CoveragePeriod against,
      Support againstSupport,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      Map<String, DailyStateSpace.Parameters> parameters) {
    var batch = PollObservations.prepare(supported(period, support), polls);
    var inside = daily(batch, DailyStateSpace.fit(batch, elections, parameters.get(period.id())));
    var againstBatch = PollObservations.prepare(supported(against, againstSupport), polls);
    var outside =
        daily(
            againstBatch,
            DailyStateSpace.fit(againstBatch, elections, parameters.get(against.id())));
    var effects = new ArrayList<BoundaryEffect>();
    // The candidate estimate exists from its period start through its last observation midpoint,
    // so those retained days are where its edges can be compared at all.
    for (var date : List.of(inside.firstKey(), inside.lastKey())) {
      var here = inside.get(date);
      var there = outside.get(date);
      if (there == null)
        throw new IllegalArgumentException(
            "The surrounding fit of " + against.id() + " does not reach " + date);
      var steps = new LinkedHashMap<String, Double>();
      for (var party : PollCsv.PARTIES) steps.put(party, here.get(party) - there.get(party));
      steps.put(REMAINDER, remainder(here) - remainder(there));
      effects.add(new BoundaryEffect(date, period.id(), against.id(), steps));
    }
    return effects;
  }

  private static double remainder(Map<String, Double> shares) {
    return shares.entrySet().stream()
        .filter(share -> !PollCsv.PARTIES.contains(share.getKey()))
        .mapToDouble(Map.Entry::getValue)
        .sum();
  }

  /** The smoothed composition of every retained day, in percent. */
  private static TreeMap<LocalDate, Map<String, Double>> daily(
      PollObservations.Batch batch, DailyStateSpace.Fit fit) {
    var daily = new TreeMap<LocalDate, Map<String, Double>>();
    for (var day : fit.days())
      daily.put(day.date(), PollObservations.shares(batch, day.smoothedMean()));
    return daily;
  }

  private static TreeMap<LocalDate, Map<String, Double>> compare(
      TreeMap<LocalDate, Map<String, Double>> baseline,
      TreeMap<LocalDate, Map<String, Double>> shifted,
      LocalDate from,
      LocalDate to) {
    var compared = new TreeMap<LocalDate, Map<String, Double>>();
    for (var day : shifted.subMap(from, true, to, true).entrySet())
      if (baseline.containsKey(day.getKey())) compared.put(day.getKey(), day.getValue());
    return compared;
  }

  private static Map<String, Double> deviations(
      TreeMap<LocalDate, Map<String, Double>> baseline,
      TreeMap<LocalDate, Map<String, Double>> shifted,
      java.util.NavigableSet<LocalDate> days) {
    var deviations = new LinkedHashMap<String, Double>();
    for (var date : days) {
      var here = shifted.get(date);
      var there = baseline.get(date);
      for (var component : here.entrySet())
        deviations.merge(
            component.getKey(),
            Math.abs(component.getValue() - there.get(component.getKey())),
            Math::max);
    }
    return deviations;
  }

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }
}
