package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.json.JsonMapper;

/**
 * The recorded evidence behind the published seats and coalitions: how far the headline
 * probabilities move between draw seeds, how far they move under each defensible alternative fit,
 * and how the approximation compares with an official allocation of the same election.
 */
public final class SeatOutcomes {
  private SeatOutcomes() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A movement of a headline probability this large is disclosed beside the number. */
  public static final double DISCLOSED_SHIFT_POINTS = DevelopmentDiagnostics.DISCLOSED_SHIFT_POINTS;

  /** The published run's centering: equal weight over the institutes active in the cycle. */
  public static final String PUBLISHED = "equal_institute_centering";

  /** The alternative centering the sensitivity requirement names. */
  public static final String POLL_COUNT = "poll_count_centering";

  /** Why an official comparison is not the reserved audit. */
  public static final String RESERVED_AUDIT_NOTE =
      "The official comparison allocates a published election result and reads no poll, no fit and"
          + " no model output. It is an allocation fixture, not the reserved once-only 2022"
          + " statistical audit, and it neither consumes nor relabels it.";

  /**
   * The headline probabilities of one run: national threshold per party, majority per coalition.
   */
  public record Headline(
      Map<String, Double> thresholdProbabilities, Map<String, Double> majorityProbabilities) {
    public Headline {
      thresholdProbabilities = orderedDoubles(thresholdProbabilities);
      majorityProbabilities = orderedDoubles(majorityProbabilities);
    }

    @Override
    public Map<String, Double> thresholdProbabilities() {
      return orderedDoubles(thresholdProbabilities);
    }

    @Override
    public Map<String, Double> majorityProbabilities() {
      return orderedDoubles(majorityProbabilities);
    }
  }

  /** How far one headline probability moves between runs that differ only in their draw seed. */
  public record Precision(
      String quantity,
      double minimum,
      double maximum,
      double spread,
      int seeds,
      int drawsPerSeed) {}

  /**
   * One defensible alternative fit, compared with the published run on the same day. Differences
   * are in percentage points of probability, and a movement over ten of them is disclosed next to
   * the number rather than blocking the release.
   */
  public record Sensitivity(
      String kind,
      String label,
      Map<String, Double> thresholdDifferencePoints,
      Map<String, Double> majorityDifferencePoints,
      double maxAbsoluteDifferencePoints,
      String largestMovement,
      boolean needsDisclosure) {
    public Sensitivity {
      thresholdDifferencePoints = orderedDoubles(thresholdDifferencePoints);
      majorityDifferencePoints = orderedDoubles(majorityDifferencePoints);
    }

    @Override
    public Map<String, Double> thresholdDifferencePoints() {
      return orderedDoubles(thresholdDifferencePoints);
    }

    @Override
    public Map<String, Double> majorityDifferencePoints() {
      return orderedDoubles(majorityDifferencePoints);
    }
  }

  /**
   * One election's official allocation beside this approximation of it. A non-zero difference is
   * the constituency machinery the national approximation omits, not a disagreement about votes.
   */
  public record OfficialComparison(
      int electionYear,
      LocalDate electionDate,
      double firstDivisor,
      Map<String, Double> shares,
      Map<String, Integer> approximatedSeats,
      Map<String, Integer> officialSeats,
      Map<String, Integer> differenceSeats,
      int totalApproximated,
      int totalOfficial,
      int maxAbsoluteDifference,
      String note) {
    public OfficialComparison {
      shares = orderedDoubles(shares);
      approximatedSeats = orderedInts(approximatedSeats);
      officialSeats = orderedInts(officialSeats);
      differenceSeats = orderedInts(differenceSeats);
    }

    @Override
    public Map<String, Double> shares() {
      return orderedDoubles(shares);
    }

    @Override
    public Map<String, Integer> approximatedSeats() {
      return orderedInts(approximatedSeats);
    }

    @Override
    public Map<String, Integer> officialSeats() {
      return orderedInts(officialSeats);
    }

    @Override
    public Map<String, Integer> differenceSeats() {
      return orderedInts(differenceSeats);
    }

    public boolean matchesOfficial() {
      return maxAbsoluteDifference == 0;
    }
  }

  /** One official election result, in the components it was reported in. */
  public record OfficialResult(
      int electionYear,
      LocalDate electionDate,
      Map<String, Double> shares,
      Map<String, Integer> officialSeats) {
    public OfficialResult {
      shares = orderedDoubles(shares);
      officialSeats = orderedInts(officialSeats);
    }

    @Override
    public Map<String, Double> shares() {
      return orderedDoubles(shares);
    }

    @Override
    public Map<String, Integer> officialSeats() {
      return orderedInts(officialSeats);
    }
  }

  /** One period's published seats and coalitions with the evidence recorded beside them. */
  public record Published(
      String periodId,
      NationalSeats.Summary seats,
      Coalitions.Result coalitions,
      List<Precision> precision,
      List<Sensitivity> sensitivity,
      List<String> disclosures) {
    public Published {
      precision = List.copyOf(precision);
      sensitivity = List.copyOf(sensitivity);
      disclosures = List.copyOf(disclosures);
    }

    @Override
    public List<Precision> precision() {
      return List.copyOf(precision);
    }

    @Override
    public List<Sensitivity> sensitivity() {
      return List.copyOf(sensitivity);
    }

    @Override
    public List<String> disclosures() {
      return List.copyOf(disclosures);
    }
  }

  public record Report(
      String protocolVersion,
      CoverageValidation.Gate gate,
      JointUncertainty.Rules rules,
      List<Published> periods,
      List<OfficialComparison> officialComparisons,
      String reservedAuditNote) {
    public Report {
      periods = List.copyOf(periods);
      officialComparisons = List.copyOf(officialComparisons);
    }

    @Override
    public List<Published> periods() {
      return List.copyOf(periods);
    }

    @Override
    public List<OfficialComparison> officialComparisons() {
      return List.copyOf(officialComparisons);
    }
  }

  /**
   * The drawn allocations of one period's final estimated day. The published run centers on equal
   * institute weights; a sensitivity rerun passes the alternative it is testing, and a
   * leave-one-institute-out rerun passes the polls it kept. Every run reads the same draw rules, so
   * only the named assumption separates two of them.
   */
  public static NationalSeats.SeatDraws finalDayDraws(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules coverage,
      JointUncertainty.Rules rules,
      DailyStateSpace.Centering centering,
      NationalAllocationRule allocation) {
    final EstimateHistory.Fitted fitted =
        EstimateHistory.fitted(period, polls, elections, parameters, coverage, centering);
    final EstimateHistory.Span last = fitted.spans().getLast();
    final DailyStateSpace.Day day = last.fit().days().getLast();
    final double[][] basis = PollObservations.transposedBasis(last.batch());
    final double[][] transformed =
        JointUncertainty.transformed(last.batch(), basis, period.id(), day, rules);
    return NationalSeats.allocateDraws(
        period.id(), day.date(), last.batch().components(), transformed, rules.seed(), allocation);
  }

  /** The threshold and majority probabilities one day's drawn allocations imply. */
  public static Headline headline(NationalSeats.SeatDraws seatDraws) {
    final Map<String, Double> thresholds = new LinkedHashMap<>();
    for (final String component : seatDraws.rules().tieOrder()) {
      if (seatDraws.components().contains(component)) {
        thresholds.put(component, seatDraws.thresholdProbability(component));
      }
    }
    final Map<String, Double> majorities = new LinkedHashMap<>();
    for (final Coalitions.Preset preset : Coalitions.PRESETS) {
      majorities.put(
          preset.id(),
          NationalSeats.probabilityAtOrAbove(
              seatDraws.totals(preset.parties()), seatDraws.rules().majoritySeats()));
    }
    return new Headline(thresholds, majorities);
  }

  /**
   * How far every headline probability moves across runs that differ only in their draw seed. The
   * first run is the published one at the stored seed; the rest repeat it at its successors.
   */
  public static List<Precision> precision(List<NationalSeats.SeatDraws> repeats) {
    if (repeats.size() < 2) {
      throw new IllegalArgumentException("Probability precision needs at least two seeds");
    }
    final List<Headline> headlines = repeats.stream().map(SeatOutcomes::headline).toList();
    final List<Precision> precision = new ArrayList<>();
    for (final String component : headlines.getFirst().thresholdProbabilities().keySet()) {
      precision.add(
          spread(
              "threshold:" + component,
              headlines.stream()
                  .map(headline -> headline.thresholdProbabilities().get(component))
                  .toList(),
              repeats));
    }
    for (final String coalition : headlines.getFirst().majorityProbabilities().keySet()) {
      precision.add(
          spread(
              "majority:" + coalition,
              headlines.stream()
                  .map(headline -> headline.majorityProbabilities().get(coalition))
                  .toList(),
              repeats));
    }
    return List.copyOf(precision);
  }

  private static Precision spread(
      String quantity, List<Double> values, List<NationalSeats.SeatDraws> repeats) {
    if (values.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Probability runs summarize different quantities");
    }
    final double minimum = values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    final double maximum = values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    return new Precision(
        quantity, minimum, maximum, maximum - minimum, repeats.size(), repeats.getFirst().count());
  }

  /**
   * One alternative run's difference from the published one. The two runs read the same day and the
   * same draw rules, so what separates them is the assumption the alternative changed.
   */
  public static Sensitivity sensitivity(
      String kind, String label, Headline published, Headline alternative) {
    final Map<String, Double> thresholds = new LinkedHashMap<>();
    final Map<String, Double> majorities = new LinkedHashMap<>();
    double worst = 0;
    String largest = null;
    for (final Map.Entry<String, Double> entry : published.thresholdProbabilities().entrySet()) {
      final Double other = alternative.thresholdProbabilities().get(entry.getKey());
      if (other == null) {
        continue;
      }
      final double difference = (other - entry.getValue()) * 100;
      thresholds.put(entry.getKey(), difference);
      if (Math.abs(difference) > Math.abs(worst)) {
        worst = difference;
        largest = "threshold:" + entry.getKey();
      }
    }
    for (final Map.Entry<String, Double> entry : published.majorityProbabilities().entrySet()) {
      final Double other = alternative.majorityProbabilities().get(entry.getKey());
      if (other == null) {
        continue;
      }
      final double difference = (other - entry.getValue()) * 100;
      majorities.put(entry.getKey(), difference);
      if (Math.abs(difference) > Math.abs(worst)) {
        worst = difference;
        largest = "majority:" + entry.getKey();
      }
    }
    final double magnitude = Math.abs(worst);
    return new Sensitivity(
        kind,
        label,
        thresholds,
        majorities,
        magnitude,
        largest,
        magnitude > DISCLOSED_SHIFT_POINTS);
  }

  /** The disclosure a sensitivity case earns, in the words that sit next to the number. */
  public static List<String> disclosures(List<Sensitivity> sensitivity) {
    final List<String> disclosures = new ArrayList<>();
    for (final Sensitivity alternative : sensitivity) {
      if (alternative.needsDisclosure()) {
        disclosures.add(
            alternative.largestMovement()
                + " moves "
                + Math.round(alternative.maxAbsoluteDifferencePoints())
                + " percentage points under "
                + alternative.label()
                + ", which is disclosed beside the number.");
      }
    }
    return List.copyOf(disclosures);
  }

  /**
   * The official allocation of one election beside this approximation of the same shares under that
   * election's own rule. Both totals are checked, so a comparison that lost a seat cannot pass as a
   * constituency effect.
   */
  public static OfficialComparison compare(OfficialResult result, NationalAllocationRule rules) {
    if (result.electionYear() != rules.electionYear()) {
      throw new IllegalArgumentException(
          "The " + result.electionYear() + " result needs its own election-era rule");
    }
    final NationalSeats.Allocation approximated = NationalSeats.allocate(result.shares(), rules);
    final Map<String, Integer> differences = new LinkedHashMap<>();
    int worst = 0;
    for (final String component : rules.tieOrder()) {
      final int official = result.officialSeats().getOrDefault(component, 0);
      final int estimated = approximated.of(component);
      if (official == 0 && estimated == 0) {
        continue;
      }
      differences.put(component, estimated - official);
      worst = Math.max(worst, Math.abs(estimated - official));
    }
    return new OfficialComparison(
        result.electionYear(),
        result.electionDate(),
        rules.firstDivisor(),
        result.shares(),
        approximated.seats(),
        result.officialSeats(),
        differences,
        approximated.total(),
        result.officialSeats().values().stream().mapToInt(Integer::intValue).sum(),
        worst,
        NationalSeats.APPROXIMATION_NOTE);
  }

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }

  /** The recorded evidence, read back so a run can be checked without recomputing it. */
  public static Report validation(Path file) {
    try {
      return JSON.readValue(Files.readAllBytes(file), Report.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, Double> orderedDoubles(Map<String, Double> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  private static Map<String, Integer> orderedInts(Map<String, Integer> values) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }
}
