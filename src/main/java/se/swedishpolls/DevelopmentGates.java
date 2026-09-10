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
import java.util.List;
import java.util.Map;
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

  /** One cold production-shaped run, excluding database setup and evidence parsing. */
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
    var before = days(baseline);
    long compared = 0;
    double maximum = -1;
    LocalDate maximumOn = null;
    String maximumComponent = null;
    for (var segment : perturbed.segments())
      for (var day : segment.days()) {
        var reference = before.get(segment.periodId() + "|" + day.date());
        if (reference == null) continue;
        for (var component : day.shares().entrySet()) {
          var old = reference.get(component.getKey());
          if (old == null) continue;
          compared++;
          double shift = Math.abs(component.getValue() - old);
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
    var days = new LinkedHashMap<String, Map<String, Double>>();
    for (var segment : estimated.segments())
      for (var day : segment.days()) days.put(segment.periodId() + "|" + day.date(), day.shares());
    return Collections.unmodifiableMap(days);
  }

  /**
   * Measures the estimator itself with the JVM's peak heap counters reset immediately before it.
   */
  public static Resources measure(
      Runnable estimator, long targetMillis, int inputPolls, int estimatedDays, int finalDraws) {
    var pools =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .toList();
    for (var pool : pools) pool.resetPeakUsage();
    long started = System.nanoTime();
    estimator.run();
    long runtimeMillis = Math.max(1, (System.nanoTime() - started) / 1_000_000);
    long peakHeapBytes = pools.stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
    var runtime = ManagementFactory.getRuntimeMXBean();
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
      Drift drift,
      CrossArchitecture crossArchitecture,
      Resources resources) {
    try {
      var protocol = JSON.readTree(Files.readAllBytes(protocolFile));
      var coverage = JSON.readTree(Files.readAllBytes(coverageFile));
      var uncertainty = JSON.readTree(Files.readAllBytes(uncertaintyFile));
      var diagnostics = JSON.readTree(Files.readAllBytes(diagnosticsFile));
      if (!"frozen-development".equals(required(protocol, "tolerance_status").asString()))
        throw new IllegalArgumentException("Development tolerances are not frozen");
      var limits = required(protocol, "resolved_tolerances");
      var tolerances = new ArrayList<Tolerance>();
      var sameArchitecture =
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
              Math.max(
                  sameArchitecture.maxObservedError(),
                  crossArchitecture.maxAbsoluteDifferencePoints()),
              sameArchitecture.cases() + crossArchitecture.comparedValues(),
              sameArchitecture.seeds(),
              sameArchitecture.evidence(),
              sameArchitecture.evidenceSha256(),
              "A rerun on one implementation and architecture must return every retained draw"
                  + " unchanged. The recorded amd64 and arm64 runs compare the same values; their"
                  + " maximum floating-point difference sets the cross-architecture bound."));
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

      var reasons = new ArrayList<String>();
      var diagnosticGate = required(diagnostics, "gate");
      for (var reason : required(diagnosticGate, "reasons")) reasons.add(reason.asString());
      if (required(diagnosticGate, "blocked").booleanValue() != !reasons.isEmpty())
        throw new IllegalArgumentException("Diagnostic gate state does not match its reasons");
      for (var tolerance : tolerances)
        if (!tolerance.passes())
          reasons.add(
              tolerance.name()
                  + " observed "
                  + tolerance.maxObservedError()
                  + " above the frozen maximum "
                  + tolerance.maximum());
      boolean fallback = conditionalCoverageFailed(protocol, diagnostics);
      if (fallback)
        reasons.add(
            "conditional predictive coverage failed; rerun affected checks with the approved"
                + " hyperparameter grid mixture");
      long registeredTarget =
          required(required(protocol, "resource_measurement"), "full_estimator_target_millis")
              .longValue();
      if (resources.targetMillis() != registeredTarget)
        throw new IllegalArgumentException("Resource measurement used the wrong runtime target");
      return new Report(
          required(protocol, "version").asString(),
          new CoverageValidation.Gate(!reasons.isEmpty(), reasons),
          fallback ? FALLBACK_REQUIRED : FALLBACK_NOT_REQUIRED,
          tolerances,
          drift,
          crossArchitecture,
          resources);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Tolerance proposed(
      String suffix, double maximum, Path evidenceFile, JsonNode uncertainty) throws IOException {
    for (var candidate : required(uncertainty, "proposedTolerances"))
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
    for (var period : required(coverage, "periods"))
      for (var stability : required(period, "stability")) {
        int days = required(stability, "comparedDays").intValue();
        var shifts = required(stability, "maxShiftPoints");
        cases += (long) days * shifts.size();
        for (var shift : shifts) observed = Math.max(observed, shift.doubleValue());
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
    var band95 = required(protocol, "predictive_coverage95");
    var band50 = required(protocol, "predictive_coverage50");
    int candidates = 0;
    for (var row : required(diagnostics, "misfit"))
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

  private static List<Long> longs(JsonNode values) {
    var result = new ArrayList<Long>();
    for (var value : values) result.add(value.longValue());
    return List.copyOf(result);
  }

  private static JsonNode required(JsonNode node, String field) {
    var value = node.get(field);
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

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
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
