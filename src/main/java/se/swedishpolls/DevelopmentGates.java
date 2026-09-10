package se.swedishpolls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Freezes development tolerances and gives later publication code one fail-closed gate. */
public final class DevelopmentGates {
  private DevelopmentGates() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public static final String FALLBACK_NOT_REQUIRED = "not_required";
  public static final String FALLBACK_REQUIRED = "hyperparameter_grid_mixture_required";

  public record Tolerance(
      String name,
      String units,
      double maximum,
      double maxObservedError,
      long cases,
      List<Long> seeds,
      String evidence,
      String evidenceSha256,
      String rationale) {
    public Tolerance {
      seeds = List.copyOf(seeds);
      if (!Double.isFinite(maximum)
          || maximum < 0
          || !Double.isFinite(maxObservedError)
          || maxObservedError < 0
          || cases < 1)
        throw new IllegalArgumentException("Inadmissible development tolerance " + name);
    }

    public boolean passes() {
      return maxObservedError <= maximum;
    }

    @Override
    public List<Long> seeds() {
      return List.copyOf(seeds);
    }
  }

  /** One changed source row compared with the unperturbed corrected history. */
  public record Perturbation(
      String kind,
      int rowNumber,
      String institute,
      long comparedValues,
      double maxShiftPoints,
      LocalDate maxShiftOn,
      String component) {
    public Perturbation {
      if (kind == null
          || rowNumber < 1
          || institute == null
          || comparedValues < 1
          || !Double.isFinite(maxShiftPoints)
          || maxShiftPoints < 0
          || maxShiftOn == null
          || component == null)
        throw new IllegalArgumentException("Inadmissible snapshot perturbation");
    }
  }

  public record Drift(String inputSha256, List<Perturbation> perturbations) {
    public Drift {
      perturbations = List.copyOf(perturbations);
      if (inputSha256 == null || perturbations.isEmpty())
        throw new IllegalArgumentException("Snapshot drift needs evidence");
    }

    public double maximum() {
      return perturbations.stream().mapToDouble(Perturbation::maxShiftPoints).max().orElseThrow();
    }

    public long cases() {
      return perturbations.stream().mapToLong(Perturbation::comparedValues).sum();
    }

    @Override
    public List<Perturbation> perturbations() {
      return List.copyOf(perturbations);
    }
  }

  public record ArchitectureRun(String architecture, String javaRuntime, String drawsSha256) {}

  public record CrossArchitecture(
      long comparedValues,
      long differingValues,
      double maxAbsoluteDifferencePoints,
      List<ArchitectureRun> runs) {
    public CrossArchitecture {
      runs = List.copyOf(runs);
      if (comparedValues < 1
          || differingValues < 0
          || differingValues > comparedValues
          || !Double.isFinite(maxAbsoluteDifferencePoints)
          || maxAbsoluteDifferencePoints < 0
          || runs.size() < 2
          || runs.stream().map(ArchitectureRun::architecture).distinct().count() < 2)
        throw new IllegalArgumentException("Cross-architecture reproduction needs two runs");
    }

    @Override
    public List<ArchitectureRun> runs() {
      return List.copyOf(runs);
    }
  }

  public record AllocationRules(
      int seats,
      double thresholdPercent,
      double firstDivisor,
      int majoritySeats,
      List<String> tieOrder) {
    public AllocationRules {
      tieOrder = List.copyOf(tieOrder);
      if (seats < 1
          || !Double.isFinite(thresholdPercent)
          || thresholdPercent < 0
          || !Double.isFinite(firstDivisor)
          || firstDivisor <= 0
          || majoritySeats < 1
          || majoritySeats > seats
          || tieOrder.isEmpty()
          || tieOrder.stream().distinct().count() != tieOrder.size())
        throw new IllegalArgumentException("Inadmissible national allocation rules");
    }

    @Override
    public List<String> tieOrder() {
      return List.copyOf(tieOrder);
    }
  }

  public record ProbabilityRun(
      long seed, Map<String, Double> thresholdProbabilities, double majorityProbability) {
    public ProbabilityRun {
      thresholdProbabilities = ordered(thresholdProbabilities);
      if (!Double.isFinite(majorityProbability)
          || majorityProbability < 0
          || majorityProbability > 1)
        throw new IllegalArgumentException("Inadmissible majority probability");
    }

    @Override
    public Map<String, Double> thresholdProbabilities() {
      return ordered(thresholdProbabilities);
    }
  }

  public record ProbabilityPrecision(
      String periodId,
      LocalDate date,
      String inputRowsSha256,
      String implementationSha256,
      List<String> components,
      AllocationRules rules,
      List<String> majorityCoalition,
      int drawsPerSeed,
      List<ProbabilityRun> runs) {
    public ProbabilityPrecision {
      components = List.copyOf(components);
      majorityCoalition = List.copyOf(majorityCoalition);
      runs = List.copyOf(runs);
      if (periodId == null
          || date == null
          || inputRowsSha256 == null
          || implementationSha256 == null
          || components.isEmpty()
          || drawsPerSeed < 1
          || runs.size() < 2
          || majorityCoalition.isEmpty())
        throw new IllegalArgumentException("Probability precision needs repeated joint draws");
    }

    public List<Long> seeds() {
      return runs.stream().map(ProbabilityRun::seed).toList();
    }

    public double maximumThresholdSpread() {
      double maximum = 0;
      for (java.lang.String component : runs.getFirst().thresholdProbabilities().keySet()) {
        double minimum = 1;
        double maximumProbability = 0;
        for (se.swedishpolls.DevelopmentGates.ProbabilityRun run : runs) {
          final java.lang.Double probability = run.thresholdProbabilities().get(component);
          if (probability == null)
            throw new IllegalArgumentException("Probability runs use different components");
          minimum = Math.min(minimum, probability);
          maximumProbability = Math.max(maximumProbability, probability);
        }
        maximum = Math.max(maximum, maximumProbability - minimum);
      }
      return maximum;
    }

    public double majoritySpread() {
      final double minimum =
          runs.stream().mapToDouble(ProbabilityRun::majorityProbability).min().orElseThrow();
      final double maximum =
          runs.stream().mapToDouble(ProbabilityRun::majorityProbability).max().orElseThrow();
      return maximum - minimum;
    }

    @Override
    public List<String> components() {
      return List.copyOf(components);
    }

    @Override
    public List<String> majorityCoalition() {
      return List.copyOf(majorityCoalition);
    }

    @Override
    public List<ProbabilityRun> runs() {
      return List.copyOf(runs);
    }
  }

  /** One production-shaped run, excluding database setup and evidence parsing. */
  public record Resources(
      long runtimeMillis,
      long peakHeapBytes,
      long targetMillis,
      int inputPolls,
      int estimatedDays,
      int finalDraws,
      String architecture,
      String javaRuntime,
      String vm,
      String os) {
    public Resources {
      if (runtimeMillis < 1
          || peakHeapBytes < 1
          || targetMillis < 1
          || inputPolls < 1
          || estimatedDays < 1
          || finalDraws < 1)
        throw new IllegalArgumentException("Inadmissible resource measurement");
    }

    public boolean meetsTarget() {
      return runtimeMillis <= targetMillis;
    }
  }

  public record Report(
      String protocolVersion,
      CoverageValidation.Gate gate,
      String uncertaintyFallback,
      List<Tolerance> tolerances,
      Drift drift,
      CrossArchitecture crossArchitecture,
      ProbabilityPrecision probabilityPrecision,
      Resources resources) {
    public Report {
      tolerances = List.copyOf(tolerances);
    }

    @Override
    public List<Tolerance> tolerances() {
      return List.copyOf(tolerances);
    }
  }

  /** Compares every modeled component on every day retained by both corrected histories. */
  public static Perturbation compare(
      String kind,
      PollCsv.Poll changed,
      EstimateHistory.Estimated baseline,
      EstimateHistory.Estimated perturbed) {
    final java.util.Map<java.lang.String, java.util.Map<java.lang.String, java.lang.Double>>
        before = days(baseline);
    long compared = 0;
    double maximum = -1;
    LocalDate maximumOn = null;
    String maximumComponent = null;
    for (se.swedishpolls.EstimateHistory.Segment segment : perturbed.segments())
      for (se.swedishpolls.EstimateHistory.Day day : segment.days()) {
        final java.util.Map<java.lang.String, java.lang.Double> reference =
            before.get(segment.periodId() + "|" + day.date());
        if (reference == null) continue;
        for (java.util.Map.Entry<java.lang.String, java.lang.Double> component :
            day.shares().entrySet()) {
          final java.lang.Double old = reference.get(component.getKey());
          if (old == null) continue;
          compared++;
          final double shift = Math.abs(component.getValue() - old);
          if (shift > maximum) {
            maximum = shift;
            maximumOn = day.date();
            maximumComponent = component.getKey();
          }
        }
      }
    if (compared == 0) throw new IllegalArgumentException("Perturbed histories do not overlap");
    return new Perturbation(
        kind,
        changed.rowNumber(),
        changed.institute(),
        compared,
        maximum,
        maximumOn,
        maximumComponent);
  }

  private static Map<String, Map<String, Double>> days(EstimateHistory.Estimated estimated) {
    final java.util.LinkedHashMap<
            java.lang.String, java.util.Map<java.lang.String, java.lang.Double>>
        days = new LinkedHashMap<String, Map<String, Double>>();
    for (se.swedishpolls.EstimateHistory.Segment segment : estimated.segments())
      for (se.swedishpolls.EstimateHistory.Day day : segment.days())
        days.put(segment.periodId() + "|" + day.date(), day.shares());
    return Collections.unmodifiableMap(days);
  }

  /**
   * Measures the estimator itself with the JVM's peak heap counters reset immediately before it.
   */
  public static Resources measure(
      Runnable estimator, long targetMillis, int inputPolls, int estimatedDays, int finalDraws) {
    final java.util.List<java.lang.management.MemoryPoolMXBean> pools =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .toList();
    for (java.lang.management.MemoryPoolMXBean pool : pools) pool.resetPeakUsage();
    final long started = System.nanoTime();
    estimator.run();
    final long runtimeMillis = Math.max(1, (System.nanoTime() - started) / 1_000_000);
    final long peakHeapBytes =
        pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
    final java.lang.management.RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    return new Resources(
        runtimeMillis,
        peakHeapBytes,
        targetMillis,
        inputPolls,
        estimatedDays,
        finalDraws,
        System.getProperty("os.arch"),
        Runtime.version().toString(),
        runtime.getVmName() + " " + runtime.getVmVersion(),
        System.getProperty("os.name") + " " + System.getProperty("os.version"));
  }

  /**
   * Aggregates the frozen evidence. A failed conditional-coverage gate names the approved mixture
   * fallback; every other upstream failure carries through unchanged.
   */
  public static Report evaluate(
      Path protocolFile,
      Path coverageFile,
      Path uncertaintyFile,
      Path diagnosticsFile,
      Path crossArchitectureFile,
      Path probabilityPrecisionFile,
      Drift drift,
      Resources resources) {
    try {
      final tools.jackson.databind.JsonNode protocol =
          JSON.readTree(Files.readAllBytes(protocolFile));
      final tools.jackson.databind.JsonNode coverage =
          JSON.readTree(Files.readAllBytes(coverageFile));
      final tools.jackson.databind.JsonNode uncertainty =
          JSON.readTree(Files.readAllBytes(uncertaintyFile));
      final tools.jackson.databind.JsonNode diagnostics =
          JSON.readTree(Files.readAllBytes(diagnosticsFile));
      final se.swedishpolls.DevelopmentGates.CrossArchitecture crossArchitecture =
          crossArchitecture(crossArchitectureFile);
      final se.swedishpolls.DevelopmentGates.ProbabilityPrecision probabilityPrecision =
          probabilityPrecision(probabilityPrecisionFile);
      if (!"frozen-development".equals(required(protocol, "tolerance_status").asString()))
        throw new IllegalArgumentException("Development tolerances are not frozen");
      final tools.jackson.databind.JsonNode limits = required(protocol, "resolved_tolerances");
      final tools.jackson.databind.JsonNode probabilityRules =
          required(protocol, "probability_precision");
      final se.swedishpolls.JointUncertainty.Rules uncertaintyRules =
          JointUncertainty.rules(protocolFile);
      if (!probabilityPrecision.rules().equals(allocationRules(protocolFile))
          || !probabilityPrecision
              .majorityCoalition()
              .equals(strings(required(probabilityRules, "majority_coalition")))
          || probabilityPrecision.drawsPerSeed() != uncertaintyRules.draws()
          || !probabilityPrecision
              .seeds()
              .equals(JointUncertainty.precisionSeeds(uncertaintyRules)))
        throw new IllegalArgumentException("Probability precision used unregistered rules");
      validateProbabilityEvidence(probabilityPrecision, uncertainty);
      final java.util.ArrayList<se.swedishpolls.DevelopmentGates.Tolerance> tolerances =
          new ArrayList<Tolerance>();
      final se.swedishpolls.DevelopmentGates.Tolerance sameArchitecture =
          proposed(
              "seeded_reproduction",
              required(limits, "seeded_reproduction_points").doubleValue(),
              uncertaintyFile,
              uncertainty);
      tolerances.add(
          new Tolerance(
              sameArchitecture.name(),
              sameArchitecture.units(),
              sameArchitecture.maximum(),
              sameArchitecture.maxObservedError(),
              sameArchitecture.cases(),
              sameArchitecture.seeds(),
              sameArchitecture.evidence(),
              sameArchitecture.evidenceSha256(),
              "A rerun on one implementation and architecture must return every retained draw"
                  + " unchanged."));
      tolerances.add(
          new Tolerance(
              "cross_architecture_reproduction",
              "percentage points between retained draws on two architectures",
              required(limits, "cross_architecture_reproduction_points").doubleValue(),
              crossArchitecture.maxAbsoluteDifferencePoints(),
              crossArchitecture.comparedValues(),
              sameArchitecture.seeds(),
              crossArchitectureFile.toString(),
              sha256(crossArchitectureFile),
              "The recorded amd64 and arm64 runs compare the same retained draws; their maximum"
                  + " floating-point difference sets this separate architecture bound."));
      tolerances.add(
          proposed(
              "interval_endpoint_precision",
              required(limits, "historical_interval_endpoint_precision_points").doubleValue(),
              uncertaintyFile,
              uncertainty));
      tolerances.add(
          coverageTolerance(
              required(limits, "coverage_boundary_stability_points").doubleValue(),
              coverageFile,
              coverage));
      final int finalDraws = required(protocol, "final_draws").intValue();
      tolerances.add(
          new Tolerance(
              "probability_monte_carlo_standard_error",
              "probability at the worst-case 50% event rate",
              required(limits, "probability_monte_carlo_standard_error").doubleValue(),
              monteCarloStandardError(finalDraws),
              finalDraws,
              List.of(required(protocol, "seed").longValue()),
              protocolFile.toString(),
              sha256(protocolFile),
              "At 10,000 draws the worst-case standard error is 0.005. Inclusive comparisons are"
                  + " checked immediately below, at, and immediately above both 4% support and"
                  + " 175 seats."));
      tolerances.add(
          new Tolerance(
              "threshold_probability_precision",
              "probability range over repeated development seeds",
              required(limits, "threshold_probability_precision").doubleValue(),
              probabilityPrecision.maximumThresholdSpread(),
              (long) probabilityPrecision.drawsPerSeed() * probabilityPrecision.runs().size(),
              probabilityPrecision.seeds(),
              probabilityPrecisionFile.toString(),
              sha256(probabilityPrecisionFile),
              "Largest repeated-seed range among inclusive 4% probabilities from final-day joint"
                  + " draws."));
      tolerances.add(
          new Tolerance(
              "majority_probability_precision",
              "probability range over repeated development seeds",
              required(limits, "majority_probability_precision").doubleValue(),
              probabilityPrecision.majoritySpread(),
              (long) probabilityPrecision.drawsPerSeed() * probabilityPrecision.runs().size(),
              probabilityPrecision.seeds(),
              probabilityPrecisionFile.toString(),
              sha256(probabilityPrecisionFile),
              "Repeated-seed range of the registered coalition's inclusive 175-seat probability"
                  + " after national modified Sainte-Lague allocation."));
      tolerances.add(
          new Tolerance(
              "snapshot_drift",
              "percentage points after an addition/deletion or fixed correction of one source"
                  + " row",
              required(limits, "snapshot_drift_points").doubleValue(),
              drift.maximum(),
              drift.cases(),
              List.of(),
              "src/test/resources/polls/audit.csv",
              drift.inputSha256(),
              "The largest corrected-history revision after removing each institute's last"
                  + " eligible development poll, which also measures adding it back, or moving"
                  + " 0.1 points from OTHER to M in that poll."));

      final java.util.LinkedHashSet<java.lang.String> reasons = new LinkedHashSet<String>();
      addGateReasons(reasons, coverage, "coverage");
      addGateReasons(reasons, uncertainty, "uncertainty");
      addGateReasons(reasons, diagnostics, "diagnostics");
      requireProtocolVersion(protocol, coverage, "coverage");
      requireProtocolVersion(protocol, uncertainty, "uncertainty");
      requireProtocolVersion(protocol, diagnostics, "diagnostics");
      addDiagnosticReasons(reasons, protocol, coverage, diagnostics);
      for (se.swedishpolls.DevelopmentGates.Tolerance tolerance : tolerances)
        if (!tolerance.passes())
          reasons.add(
              tolerance.name()
                  + " observed "
                  + tolerance.maxObservedError()
                  + " above the frozen maximum "
                  + tolerance.maximum());
      final boolean fallback = conditionalCoverageFailed(protocol, diagnostics);
      if (fallback)
        reasons.add(
            "conditional predictive coverage failed; rerun affected checks with the approved"
                + " hyperparameter grid mixture");
      final long registeredTarget =
          required(required(protocol, "resource_measurement"), "full_estimator_target_millis")
              .longValue();
      if (resources.targetMillis() != registeredTarget)
        throw new IllegalArgumentException("Resource measurement used the wrong runtime target");
      return new Report(
          required(protocol, "version").asString(),
          new CoverageValidation.Gate(!reasons.isEmpty(), List.copyOf(reasons)),
          fallback ? FALLBACK_REQUIRED : FALLBACK_NOT_REQUIRED,
          tolerances,
          drift,
          crossArchitecture,
          probabilityPrecision,
          resources);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void addGateReasons(Set<String> reasons, JsonNode evidence, String source) {
    final tools.jackson.databind.JsonNode gate = required(evidence, "gate");
    final tools.jackson.databind.JsonNode upstream = required(gate, "reasons");
    if (required(gate, "blocked").booleanValue() != !upstream.isEmpty())
      throw new IllegalArgumentException(source + " gate state does not match its reasons");
    for (tools.jackson.databind.JsonNode reason : upstream)
      reasons.add(source + ": " + reason.asString());
  }

  private static void requireProtocolVersion(JsonNode protocol, JsonNode evidence, String source) {
    if (!required(protocol, "version")
        .asString()
        .equals(required(evidence, "protocolVersion").asString()))
      throw new IllegalArgumentException(source + " evidence uses another protocol version");
  }

  private static void addDiagnosticReasons(
      Set<String> reasons, JsonNode protocol, JsonNode coverage, JsonNode diagnostics) {
    final tools.jackson.databind.JsonNode rules = required(protocol, "resolved_diagnostic_gates");
    if (required(rules, "autocorrelation_explanation").asString().isBlank())
      throw new IllegalArgumentException("Residual autocorrelation needs an explanation");
    final tools.jackson.databind.JsonNode band95 = required(protocol, "predictive_coverage95");
    final tools.jackson.databind.JsonNode band50 = required(protocol, "predictive_coverage50");
    final double maximumBias =
        required(rules, "maximum_absolute_mean_standardized_residual").doubleValue();
    final double minimumScale =
        required(rules, "minimum_root_mean_square_standardized_residual").doubleValue();
    final double maximumScale =
        required(rules, "maximum_root_mean_square_standardized_residual").doubleValue();
    final int minimumCases = required(rules, "minimum_subgroup_cases").intValue();
    final java.util.LinkedHashSet<java.lang.String> scopes = new LinkedHashSet<String>();
    final java.util.LinkedHashSet<java.lang.String> periods = new LinkedHashSet<String>();
    for (tools.jackson.databind.JsonNode period : required(coverage, "periods"))
      if (required(period, "supported").booleanValue())
        periods.add(required(period, "periodId").asString());
    for (tools.jackson.databind.JsonNode row : required(diagnostics, "misfit")) {
      if (!"midpoint".equals(required(row, "candidate").asString())) continue;
      final java.lang.String scope = required(row, "scope").asString();
      final java.lang.String period = required(row, "periodId").asString();
      scopes.add(period + ":" + scope);
      if ("all".equals(scope)) continue;
      final java.lang.String label = period + " " + scope + " " + required(row, "name").asString();
      if (required(row, "cases").intValue() < minimumCases) continue;
      final double coverage95 = required(row, "coverage95").doubleValue();
      final double coverage50 = required(row, "coverage50").doubleValue();
      final double bias = Math.abs(required(row, "meanStandardizedResidual").doubleValue());
      final double scale = required(row, "rootMeanSquareStandardizedResidual").doubleValue();
      if (outside(coverage95, band95) || outside(coverage50, band50))
        reasons.add(
            label + " has unexplained subgroup coverage 95/50=" + coverage95 + "/" + coverage50);
      if (bias > maximumBias)
        reasons.add(label + " has absolute mean standardized residual " + bias);
      if (scale < minimumScale || scale > maximumScale)
        reasons.add(label + " has root-mean-square standardized residual " + scale);
    }
    for (java.lang.String period : periods)
      for (java.lang.String scope : List.of("all", "party", "institute", "fieldwork_days"))
        if (!scopes.contains(period + ":" + scope))
          throw new IllegalArgumentException("Missing " + scope + " misfit evidence for " + period);
    final java.util.LinkedHashSet<java.lang.String> lags = new LinkedHashSet<String>();
    final double maximumAutocorrelation =
        required(rules, "maximum_absolute_residual_autocorrelation").doubleValue();
    for (tools.jackson.databind.JsonNode row : required(diagnostics, "autocorrelation")) {
      final java.lang.String period = required(row, "periodId").asString();
      final int lag = required(row, "lag").intValue();
      lags.add(period + ":" + lag);
      final double correlation = Math.abs(required(row, "correlation").doubleValue());
      if (correlation > maximumAutocorrelation)
        reasons.add(period + " residual autocorrelation at lag " + lag + " is " + correlation);
    }
    for (java.lang.String period : periods)
      for (tools.jackson.databind.JsonNode lagNode :
          required(required(protocol, "diagnostics"), "residual_lags")) {
        final int lag = lagNode.intValue();
        if (!lags.contains(period + ":" + lag))
          throw new IllegalArgumentException(
              "Missing residual autocorrelation for " + period + " lag " + lag);
      }
  }

  private static void validateProbabilityEvidence(
      ProbabilityPrecision precision, JsonNode uncertainty) {
    JsonNode published = null;
    for (tools.jackson.databind.JsonNode period : required(uncertainty, "periods"))
      if (precision.periodId().equals(required(period, "periodId").asString())) published = period;
    if (published == null)
      throw new IllegalArgumentException(
          "Probability precision period has no uncertainty evidence");
    final tools.jackson.databind.JsonNode reproduction = required(published, "reproduction");
    final java.util.List<java.lang.String> expectedComponents =
        strings(required(reproduction, "components"));
    if (!precision
            .date()
            .toString()
            .equals(required(required(published, "headline"), "date").asString())
        || !precision.inputRowsSha256().equals(required(reproduction, "inputRowsSha256").asString())
        || !precision
            .implementationSha256()
            .equals(required(reproduction, "implementationSha256").asString())
        || !precision.components().equals(expectedComponents))
      throw new IllegalArgumentException("Probability precision does not match its fitted run");
    final java.util.List<java.lang.String> allocated =
        expectedComponents.stream().filter(precision.rules().tieOrder()::contains).toList();
    for (se.swedishpolls.DevelopmentGates.ProbabilityRun run : precision.runs())
      if (!new ArrayList<>(run.thresholdProbabilities().keySet()).equals(allocated))
        throw new IllegalArgumentException("Probability precision uses the wrong component set");
  }

  private static Tolerance proposed(
      String suffix, double maximum, Path evidenceFile, JsonNode uncertainty) throws IOException {
    for (tools.jackson.databind.JsonNode candidate : required(uncertainty, "proposedTolerances"))
      if (required(candidate, "name").asString().endsWith(":" + suffix))
        return new Tolerance(
            suffix,
            required(candidate, "units").asString(),
            maximum,
            required(candidate, "maxObservedError").doubleValue(),
            required(candidate, "cases").longValue(),
            longs(required(candidate, "seeds")),
            evidenceFile.toString(),
            sha256(evidenceFile),
            required(candidate, "rationale").asString());
    throw new IllegalArgumentException("Missing proposed tolerance " + suffix);
  }

  private static Tolerance coverageTolerance(double maximum, Path evidenceFile, JsonNode coverage)
      throws IOException {
    double observed = 0;
    long cases = 0;
    for (tools.jackson.databind.JsonNode period : required(coverage, "periods"))
      for (tools.jackson.databind.JsonNode stability : required(period, "stability")) {
        final int days = required(stability, "comparedDays").intValue();
        final tools.jackson.databind.JsonNode shifts = required(stability, "maxShiftPoints");
        cases += (long) days * shifts.size();
        for (tools.jackson.databind.JsonNode shift : shifts)
          observed = Math.max(observed, shift.doubleValue());
      }
    return new Tolerance(
        "coverage_boundary_stability",
        "percentage points over retained days after moving both support boundaries",
        maximum,
        observed,
        cases,
        List.of(),
        evidenceFile.toString(),
        sha256(evidenceFile),
        "The registered 7, 14 and 30 day boundary shifts remain below the frozen support bound for"
            + " every modeled component.");
  }

  private static boolean conditionalCoverageFailed(JsonNode protocol, JsonNode diagnostics) {
    final tools.jackson.databind.JsonNode band95 = required(protocol, "predictive_coverage95");
    final tools.jackson.databind.JsonNode band50 = required(protocol, "predictive_coverage50");
    int candidates = 0;
    for (tools.jackson.databind.JsonNode row : required(diagnostics, "misfit"))
      if ("midpoint".equals(required(row, "candidate").asString())
          && "all".equals(required(row, "scope").asString())) {
        candidates++;
        if (outside(required(row, "coverage95").doubleValue(), band95)
            || outside(required(row, "coverage50").doubleValue(), band50)) return true;
      }
    if (candidates == 0) throw new IllegalArgumentException("No pooled midpoint coverage evidence");
    return false;
  }

  private static boolean outside(double value, JsonNode band) {
    return value < band.get(0).doubleValue() || value > band.get(1).doubleValue();
  }

  public static double monteCarloStandardError(int draws) {
    if (draws < 1) throw new IllegalArgumentException("A probability needs at least one draw");
    return 0.5 / Math.sqrt(draws);
  }

  /** Counts an inclusive threshold or majority event from retained joint-draw summaries. */
  public static double probabilityAtOrAbove(double[] values, double boundary) {
    return NationalSeats.probabilityAtOrAbove(values, boundary);
  }

  public static AllocationRules allocationRules(Path protocolFile) {
    try {
      final tools.jackson.databind.JsonNode root = JSON.readTree(Files.readAllBytes(protocolFile));
      final tools.jackson.databind.JsonNode rules = required(root, "probability_precision");
      return new AllocationRules(
          required(rules, "seats").intValue(),
          required(rules, "threshold_percent").doubleValue(),
          required(rules, "first_divisor").doubleValue(),
          required(rules, "majority_seats").intValue(),
          strings(required(rules, "tie_order")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Measures threshold and majority probabilities from repeated final-day joint draws. */
  public static ProbabilityPrecision probabilityPrecision(
      JointUncertainty.Estimated estimated,
      List<JointUncertainty.Draws> repeats,
      List<Long> seeds,
      AllocationRules rules,
      List<String> majorityCoalition) {
    if (repeats.size() != seeds.size() || repeats.size() < 2)
      throw new IllegalArgumentException("Probability precision needs one run per seed");
    final se.swedishpolls.JointUncertainty.Draws first = repeats.getFirst();
    if (!estimated.periodId().equals(first.periodId())
        || !estimated.finalDraws().date().equals(first.date())
        || !estimated.finalDraws().components().equals(first.components()))
      throw new IllegalArgumentException("Probability precision does not match its fitted run");
    final java.util.ArrayList<se.swedishpolls.DevelopmentGates.ProbabilityRun> runs =
        new ArrayList<ProbabilityRun>();
    for (int repeat = 0; repeat < repeats.size(); repeat++) {
      final se.swedishpolls.JointUncertainty.Draws draws = repeats.get(repeat);
      if (!draws.periodId().equals(first.periodId())
          || !draws.date().equals(first.date())
          || !draws.components().equals(first.components())
          || draws.count() != first.count())
        throw new IllegalArgumentException("Probability runs estimate different final days");
      final java.util.List<java.lang.String> allocatedComponents =
          rules.tieOrder().stream().filter(draws.components()::contains).toList();
      final int[] thresholdCounts = new int[allocatedComponents.size()];
      int majorities = 0;
      for (int draw = 0; draw < draws.count(); draw++) {
        final java.util.LinkedHashMap<java.lang.String, java.lang.Double> shares =
            new LinkedHashMap<String, Double>();
        for (int componentIndex = 0;
            componentIndex < allocatedComponents.size();
            componentIndex++) {
          final java.lang.String component = allocatedComponents.get(componentIndex);
          final double share = draws.shares().get(draw, draws.components().indexOf(component));
          shares.put(component, share);
          if (share >= rules.thresholdPercent()) thresholdCounts[componentIndex]++;
        }
        final java.util.Map<java.lang.String, java.lang.Integer> seats = allocate(shares, rules);
        final int coalitionSeats =
            majorityCoalition.stream()
                .mapToInt(component -> seats.getOrDefault(component, 0))
                .sum();
        if (coalitionSeats >= rules.majoritySeats()) majorities++;
      }
      final java.util.LinkedHashMap<java.lang.String, java.lang.Double> thresholds =
          new LinkedHashMap<String, Double>();
      for (int component = 0; component < allocatedComponents.size(); component++)
        thresholds.put(
            allocatedComponents.get(component),
            (double) thresholdCounts[component] / draws.count());
      runs.add(
          new ProbabilityRun(seeds.get(repeat), thresholds, (double) majorities / draws.count()));
    }
    return new ProbabilityPrecision(
        estimated.periodId(),
        first.date(),
        estimated.reproduction().inputRowsSha256(),
        estimated.reproduction().implementationSha256(),
        first.components(),
        rules,
        majorityCoalition,
        first.count(),
        runs);
  }

  /**
   * National modified Sainte-Lague approximation with the registered deterministic tie order. The
   * distribution itself is {@link NationalSeats}, so the development evidence and the published
   * allocation are one implementation rather than two that could drift apart.
   */
  static Map<String, Integer> allocate(Map<String, Double> shares, AllocationRules rules) {
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> qualified =
        new LinkedHashMap<String, Double>();
    for (java.lang.String component : rules.tieOrder()) {
      final java.lang.Double share = shares.get(component);
      if (share == null) continue;
      if (!Double.isFinite(share) || share < 0)
        throw new IllegalArgumentException("Inadmissible support for " + component);
      if (share >= rules.thresholdPercent()) qualified.put(component, share);
    }
    return Collections.unmodifiableMap(
        NationalSeats.distribute(qualified, rules.seats(), rules.firstDivisor()).seats());
  }

  private static Map<String, Double> ordered(Map<String, Double> values) {
    final java.util.LinkedHashMap<java.lang.String, java.lang.Double> copy =
        new LinkedHashMap<String, Double>();
    values.forEach(
        (key, value) -> {
          if (value == null || !Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("Inadmissible probability for " + key);
          copy.put(key, value);
        });
    return Collections.unmodifiableMap(copy);
  }

  private static List<Long> longs(JsonNode values) {
    final java.util.ArrayList<java.lang.Long> result = new ArrayList<Long>();
    for (tools.jackson.databind.JsonNode value : values) result.add(value.longValue());
    return List.copyOf(result);
  }

  private static List<String> strings(JsonNode values) {
    final java.util.ArrayList<java.lang.String> result = new ArrayList<String>();
    for (tools.jackson.databind.JsonNode value : values) result.add(value.asString());
    return List.copyOf(result);
  }

  private static JsonNode required(JsonNode node, String field) {
    final tools.jackson.databind.JsonNode value = node.get(field);
    if (value == null || value.isNull())
      throw new IllegalArgumentException("Missing development evidence field " + field);
    return value;
  }

  public static Report validation(Path file) {
    try {
      return JSON.readValue(Files.readAllBytes(file), Report.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static CrossArchitecture crossArchitecture(Path file) {
    try {
      return JSON.readValue(Files.readAllBytes(file), CrossArchitecture.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static ProbabilityPrecision probabilityPrecision(Path file) {
    try {
      return JSON.readValue(Files.readAllBytes(file), ProbabilityPrecision.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }

  public static String report(ProbabilityPrecision precision) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(precision);
  }

  /** The publication boundary calls this before exposing any estimate. */
  public static void requirePassed(Report report) {
    if (report.gate().blocked())
      throw new IllegalStateException(
          "Development gate failed: " + String.join("; ", report.gate().reasons()));
  }

  public static String sha256(Path file) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
