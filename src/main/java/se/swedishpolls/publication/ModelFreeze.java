package se.swedishpolls.publication;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.swedishpolls.estimation.CoalitionPrecision;
import se.swedishpolls.estimation.CoverageValidation;
import se.swedishpolls.estimation.DailyStateSpace;
import se.swedishpolls.estimation.EstimateHistory;
import se.swedishpolls.estimation.JointUncertainty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frozen estimator the publication worker runs: protocol versions, seed, draw count, interval
 * levels, coverage rules, per-period fit parameters and the release verdict. The development
 * evidence under {@code docs/validation} stays the source of truth; this resource is the shipped
 * copy of the few values a running publication needs, and {@code ModelFreezeIT} keeps the two in
 * step.
 */
public final class ModelFreeze {
  /** The classpath resource the running application reads. */
  static final String RESOURCE = "/publication/model-freeze.json";

  public enum Authorization {
    NONE,
    TREND,
    CALIBRATED
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** One coverage period's frozen fit, as the release audit resolved it. */
  public record Period(String periodId, boolean supported, DailyStateSpace.Parameters parameters) {}

  private final String developmentProtocolVersion;
  private final String releaseProtocolVersion;
  private final String releaseStatus;
  private final List<String> failedBlockingGates;
  private final Authorization authorizedLevel;
  private final List<String> failedTrendGates;
  private final JointUncertainty.Rules uncertainty;
  private final CoverageValidation.Rules coverage;
  private final EstimateHistory.Publication resolution;
  private final Map<String, Period> periods;
  private final String estimatorVersion;
  private final String numericalLibrary;
  private final CoalitionPrecision.Rules coalitionPrecision;

  private ModelFreeze(
      String developmentProtocolVersion,
      String releaseProtocolVersion,
      String releaseStatus,
      List<String> failedBlockingGates,
      Authorization authorizedLevel,
      List<String> failedTrendGates,
      JointUncertainty.Rules uncertainty,
      CoverageValidation.Rules coverage,
      EstimateHistory.Publication resolution,
      Map<String, Period> periods,
      String estimatorVersion,
      String numericalLibrary,
      CoalitionPrecision.Rules coalitionPrecision) {
    this.developmentProtocolVersion = developmentProtocolVersion;
    this.releaseProtocolVersion = releaseProtocolVersion;
    this.releaseStatus = releaseStatus;
    this.failedBlockingGates = List.copyOf(failedBlockingGates);
    this.authorizedLevel = authorizedLevel;
    this.failedTrendGates = List.copyOf(failedTrendGates);
    this.uncertainty = uncertainty;
    this.coverage = coverage;
    this.resolution = resolution;
    this.periods = Map.copyOf(periods);
    this.estimatorVersion = estimatorVersion;
    this.numericalLibrary = numericalLibrary;
    this.coalitionPrecision = coalitionPrecision;
  }

  public static ModelFreeze load() {
    try (final InputStream input = ModelFreeze.class.getResourceAsStream(RESOURCE)) {
      if (input == null) {
        throw new IllegalStateException("Missing frozen model resource " + RESOURCE);
      }
      return parse(JSON.readTree(input.readAllBytes()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static ModelFreeze parse(JsonNode root) {
    final JsonNode release = required(root, "release");
    final List<String> gates = new ArrayList<>();
    for (final JsonNode gate : required(release, "failedBlockingGates")) {
      gates.add(gate.asString());
    }
    final List<String> trendGates = new ArrayList<>();
    for (final JsonNode gate : required(required(root, "trendGate"), "failedGates")) {
      trendGates.add(gate.asString());
    }
    final List<Double> levels = new ArrayList<>();
    for (final JsonNode level : required(root, "intervalLevels")) {
      levels.add(level.doubleValue());
    }
    final JsonNode rules = required(root, "coverageValidation");
    final List<Integer> shifts = new ArrayList<>();
    for (final JsonNode shift : required(rules, "boundary_shift_days")) {
      shifts.add(shift.intValue());
    }
    final LinkedHashMap<String, Period> periods = new LinkedHashMap<>();
    for (final JsonNode period : required(root, "periods")) {
      final JsonNode parameters = required(period, "parameters");
      periods.put(
          required(period, "periodId").asString(),
          new Period(
              required(period, "periodId").asString(),
              required(period, "supported").booleanValue(),
              new DailyStateSpace.Parameters(
                  required(parameters, "walkVariance").doubleValue(),
                  required(parameters, "houseScale").doubleValue(),
                  required(parameters, "covarianceMultiplier").doubleValue())));
    }
    final JsonNode coalition = required(root, "coalitionHistoryPrecision");
    final List<Long> coalitionSeeds = new ArrayList<>();
    for (final JsonNode seed : required(coalition, "seeds")) coalitionSeeds.add(seed.longValue());
    return new ModelFreeze(
        required(root, "developmentProtocolVersion").asString(),
        required(root, "releaseProtocolVersion").asString(),
        required(release, "status").asString(),
        gates,
        Authorization.valueOf(
            required(root, "authorizedLevel").asString().toUpperCase(java.util.Locale.ROOT)),
        trendGates,
        new JointUncertainty.Rules(
            required(root, "seed").longValue(),
            required(root, "draws").intValue(),
            levels,
            // A publication draws the registered seed once. Repeated-seed precision belongs to
            // the development study, which reads its own registered repeat count.
            1),
        new CoverageValidation.Rules(
            LocalDate.parse(required(rules, "development_through").asString()),
            required(rules, "min_observations").intValue(),
            required(rules, "min_institutes").intValue(),
            required(rules, "max_internal_gap_days").intValue(),
            shifts,
            required(rules, "stability_burn_in_days").intValue(),
            required(rules, "max_stability_shift_points").doubleValue()),
        new EstimateHistory.Publication(required(root, "publicationDecimals").intValue()),
        periods,
        required(root, "estimatorVersion").asString(),
        required(root, "numericalLibrary").asString(),
        new CoalitionPrecision.Rules(
            required(coalition, "draws").intValue(),
            coalitionSeeds,
            required(coalition, "referenceDraws").intValue(),
            required(coalition, "referenceSeed").longValue(),
            required(coalition, "maxMeanErrorPoints").doubleValue(),
            required(coalition, "maxEndpointErrorPoints").doubleValue()));
  }

  private static JsonNode required(JsonNode parent, String field) {
    final JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Incomplete frozen model: missing " + field);
    }
    return value;
  }

  public String developmentProtocolVersion() {
    return developmentProtocolVersion;
  }

  public String releaseProtocolVersion() {
    return releaseProtocolVersion;
  }

  public String releaseStatus() {
    return releaseStatus;
  }

  public Authorization authorization() {
    if (authorizedLevel == Authorization.CALIBRATED
        && "released".equals(releaseStatus)
        && failedBlockingGates.isEmpty()
        && failedTrendGates.isEmpty()) {
      return Authorization.CALIBRATED;
    }
    if (authorizedLevel != Authorization.NONE && failedTrendGates.isEmpty()) {
      return Authorization.TREND;
    }
    return Authorization.NONE;
  }

  public List<String> failedTrendGates() {
    return failedTrendGates;
  }

  public List<String> failedBlockingGates() {
    return failedBlockingGates;
  }

  public JointUncertainty.Rules uncertainty() {
    return uncertainty;
  }

  /** The published interval level: the widest registered one. */
  public double intervalLevel() {
    return uncertainty.intervalLevels().getLast();
  }

  /**
   * The frozen coverage rules, with the development end moved to the last fieldwork date being
   * published. Development evidence is cut at the registered development end; a publication reads
   * corrected history through its own snapshot, at the same parameters.
   */
  public CoverageValidation.Rules coverageThrough(LocalDate lastFieldworkDate) {
    return new CoverageValidation.Rules(
        lastFieldworkDate,
        coverage.minObservations(),
        coverage.minInstitutes(),
        coverage.maxInternalGapDays(),
        coverage.boundaryShiftDays(),
        coverage.stabilityBurnInDays(),
        coverage.maxStabilityShiftPoints());
  }

  /** The registered resolution a published number is quoted at. */
  public EstimateHistory.Publication resolution() {
    return resolution;
  }

  public CoverageValidation.Rules developmentCoverage() {
    return coverage;
  }

  public Period period(String periodId) {
    final Period period = periods.get(periodId);
    if (period == null) {
      throw new IllegalArgumentException("No frozen fit for coverage period " + periodId);
    }
    return period;
  }

  public String estimatorVersion() {
    return estimatorVersion;
  }

  public CoalitionPrecision.Rules coalitionPrecision() {
    return coalitionPrecision;
  }

  public String numericalLibrary() {
    return numericalLibrary;
  }
}
