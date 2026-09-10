package se.swedishpolls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

/**
 * The release verdict: the frozen model artifact, the once-only reserved 2022 comparison and one
 * pass or fail for every gate the development protocol registered.
 *
 * <p>This class decides nothing about the estimator. It reads evidence that is already frozen,
 * states what each gate did, and refuses to call a blocked model releasable. A failed gate is
 * carried, never replaced by a different estimator and never relaxed after the audit ran.
 */
public final class ReleaseAudit {
  private ReleaseAudit() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A gate whose failure stops the release. */
  public static final String BLOCKING = "blocking";

  /** A gate that is measured and reported, whose failure the registration does not block on. */
  public static final String REPORTED = "reported";

  /** The residual component of the eight-party roster, which the audit compares like a party. */
  private static final String OTHER = "OTHER";

  public static final String STATUS_RELEASED = "released";
  public static final String STATUS_BLOCKED = "blocked";

  /** What the reserved comparison is, stated wherever its numbers appear. */
  public static final String INTERPRETATION =
      "One retrospective pre-election diagnostic from today's corrected snapshot. Its polling"
          + " inputs and its public outcome were already exposed to method selection, so it is"
          + " neither blinded validation nor election-forecast calibration.";

  /** What the two-decimal official fixture costs the reported errors. */
  public static final String ROUNDING_LIMITATION =
      "Official shares are the published two-decimal percentages, so each carries 0.01"
          + " percentage-point resolution and OTHER accumulates the rounding of the other eight.";

  /** One registered evidence file and the digest the freeze pinned it at. */
  public record Evidence(String name, String path, String sha256) {
    public Evidence {
      if (name == null || path == null || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Frozen evidence needs a path and a SHA-256");
      }
    }
  }

  /** The numeric bounds this ticket froze before the audit ran. */
  public record Tolerances(
      double compositionSumPoints,
      int seatTotal,
      int minimumScoredFolds,
      double sensitivityDisclosurePoints) {
    public Tolerances {
      if (!Double.isFinite(compositionSumPoints)
          || compositionSumPoints <= 0
          || seatTotal < 1
          || minimumScoredFolds < 1
          || !Double.isFinite(sensitivityDisclosurePoints)
          || sensitivityDisclosurePoints <= 0) {
        throw new IllegalArgumentException("Inadmissible release tolerance");
      }
    }
  }

  /** An explicit owner waiver of one named gate. There is no implicit waiver. */
  public record Waiver(String gate, String decisionUrl, String rationale) {
    public Waiver {
      if (gate == null || gate.isBlank() || decisionUrl == null || rationale == null) {
        throw new IllegalArgumentException("A waiver names a gate, a decision and a reason");
      }
    }
  }

  /** The approved behaviour where a roster does not meet the gates. */
  public record Fallback(String id, String approval, String note) {
    public Fallback {
      if (id == null || id.isBlank() || approval == null || note == null) {
        throw new IllegalArgumentException("A fallback names an id, a decision and its behaviour");
      }
    }
  }

  /** The release registration, verified against what is on disk before the audit is read. */
  public record Frozen(
      String version,
      LocalDate registeredOn,
      String developmentProtocolVersion,
      String developmentProtocolSha256,
      List<Evidence> evidence,
      Tolerances tolerances,
      List<String> reportedNotBlocking,
      Map<String, String> rules,
      Fallback fiFallback,
      List<Waiver> waivers) {
    public Frozen {
      evidence = List.copyOf(evidence);
      reportedNotBlocking = List.copyOf(reportedNotBlocking);
      rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
      waivers = List.copyOf(waivers);
      if (version == null || registeredOn == null || evidence.isEmpty() || rules.isEmpty()) {
        throw new IllegalArgumentException("An incomplete release registration cannot be frozen");
      }
    }

    @Override
    public List<Evidence> evidence() {
      return List.copyOf(evidence);
    }

    @Override
    public List<String> reportedNotBlocking() {
      return List.copyOf(reportedNotBlocking);
    }

    @Override
    public Map<String, String> rules() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    @Override
    public List<Waiver> waivers() {
      return List.copyOf(waivers);
    }
  }

  /** The pinned reserved inputs and the already-exposed official outcome. */
  public record Manifest(
      LocalDate cutoff,
      LocalDate electionDate,
      String sourceCommit,
      String sourceSha256,
      String rowIdentity,
      String candidateSelection,
      int candidateCount,
      String candidateRowsSha256,
      String outcomeSourceUrl,
      String exposedFixtureCommit,
      String exposedFixturePath,
      String exposedFixtureSha256,
      Map<String, String> officialShares) {
    public Manifest {
      officialShares = Collections.unmodifiableMap(new LinkedHashMap<>(officialShares));
      if (cutoff == null
          || electionDate == null
          || !electionDate.isAfter(cutoff)
          || candidateCount < 1
          || officialShares.isEmpty()) {
        throw new IllegalArgumentException("Inadmissible reserved comparison manifest");
      }
    }

    @Override
    public Map<String, String> officialShares() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(officialShares));
    }
  }

  /** The candidate rows the manifest selects, by one-based physical CSV line. */
  public record Selection(int rows, String rowsSha256, List<Integer> lines) {
    public Selection {
      lines = List.copyOf(lines);
      if (rows != lines.size()) {
        throw new IllegalArgumentException("Selected row count does not match its identities");
      }
    }

    @Override
    public List<Integer> lines() {
      return List.copyOf(lines);
    }
  }

  /** One party's estimate against the official share it is compared with. */
  public record PartyResult(
      String component,
      double estimatedPercent,
      double officialPercent,
      double errorPoints,
      Map<String, Boolean> intervalInclusion,
      List<JointUncertainty.Interval> intervals) {
    public PartyResult {
      intervalInclusion = Collections.unmodifiableMap(new LinkedHashMap<>(intervalInclusion));
      intervals = List.copyOf(intervals);
    }

    @Override
    public Map<String, Boolean> intervalInclusion() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(intervalInclusion));
    }

    @Override
    public List<JointUncertainty.Interval> intervals() {
      return List.copyOf(intervals);
    }
  }

  /** The national approximation beside the official constituency allocation, never merged. */
  public record SeatResult(
      String component, int approximateSeats, int officialSeats, int differenceSeats) {}

  /** The once-only reserved comparison. */
  public record Election(
      LocalDate cutoff,
      LocalDate electionDate,
      LocalDate estimatedOn,
      String periodId,
      Manifest manifest,
      Selection selection,
      int fittedObservations,
      List<Double> fitLogLikelihoods,
      double compositionSumPercent,
      List<PartyResult> parties,
      double meanAbsoluteErrorPoints,
      double maxAbsoluteErrorPoints,
      Map<String, Integer> intervalInclusionCounts,
      List<SeatResult> seats,
      int approximateSeatTotal,
      int officialSeatTotal,
      int tieBrokenSeats,
      String approximationNote,
      String tieNote,
      String roundingLimitation,
      String interpretation,
      JointUncertainty.Reproduction reproduction,
      JointUncertainty.Reproduced reproduced) {
    public Election {
      fitLogLikelihoods = List.copyOf(fitLogLikelihoods);
      parties = List.copyOf(parties);
      intervalInclusionCounts =
          Collections.unmodifiableMap(new LinkedHashMap<>(intervalInclusionCounts));
      seats = List.copyOf(seats);
      if (parties.isEmpty() || seats.isEmpty() || fitLogLikelihoods.isEmpty()) {
        throw new IllegalArgumentException("The reserved comparison needs a fit and a result");
      }
    }

    @Override
    public List<Double> fitLogLikelihoods() {
      return List.copyOf(fitLogLikelihoods);
    }

    @Override
    public List<PartyResult> parties() {
      return List.copyOf(parties);
    }

    @Override
    public Map<String, Integer> intervalInclusionCounts() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(intervalInclusionCounts));
    }

    @Override
    public List<SeatResult> seats() {
      return List.copyOf(seats);
    }

    public boolean fitsAreFinite() {
      return fitLogLikelihoods.stream().allMatch(Double::isFinite);
    }
  }

  /** One subgroup measured against the frozen misfit limits. */
  public record Misfit(
      String periodId,
      String scope,
      String name,
      int cases,
      double coverage95,
      double coverage50,
      double meanStandardizedResidual,
      double rootMeanSquareStandardizedResidual,
      List<String> failures) {
    public Misfit {
      failures = List.copyOf(failures);
    }

    public boolean passes() {
      return failures.isEmpty();
    }

    @Override
    public List<String> failures() {
      return List.copyOf(failures);
    }
  }

  /**
   * One roster's residual dependence: the whitened autocorrelation at each registered lag against
   * the frozen limit, and the overlapping, disjoint and same-institute pair measurements that say
   * how much of it is structural.
   */
  public record Dependence(
      String periodId,
      Map<String, Double> residualAutocorrelation,
      double maximumAbsoluteAutocorrelation,
      long overlappingPairs,
      double overlappingCorrelation,
      long disjointPairs,
      double disjointCorrelation,
      long sameInstitutePairs,
      double sameInstituteCorrelation,
      String explanation,
      List<String> failures) {
    public Dependence {
      residualAutocorrelation =
          Collections.unmodifiableMap(new LinkedHashMap<>(residualAutocorrelation));
      failures = List.copyOf(failures);
      if (residualAutocorrelation.isEmpty() || explanation == null || explanation.isBlank()) {
        throw new IllegalArgumentException("Residual dependence needs lags and an explanation");
      }
    }

    public boolean passes() {
      return failures.isEmpty();
    }

    @Override
    public Map<String, Double> residualAutocorrelation() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(residualAutocorrelation));
    }

    @Override
    public List<String> failures() {
      return List.copyOf(failures);
    }
  }

  /** The two registered sensitivity reruns, and whether either needs adjacent disclosure. */
  public record Sensitivity(
      String periodId,
      LocalDate headlineDate,
      double centeringMaxHeadlinePoints,
      double centeringMaxDailyPoints,
      String largestHeadlineInstitute,
      double leaveOneInstituteOutMaxHeadlinePoints,
      double leaveOneInstituteOutMaxDailyPoints,
      double disclosureThresholdPoints,
      boolean disclosureRequired) {}

  /** Whether the individual FI roster meets the gates, or falls back to no estimate. */
  public record IndividualFi(
      String periodId,
      LocalDate rosterFrom,
      LocalDate rosterTo,
      LocalDate supportFrom,
      LocalDate supportTo,
      boolean supportDatesExact,
      int observations,
      int separatelyFittedRuns,
      boolean gatesMet,
      String disposition,
      Fallback fallback,
      List<String> reasons) {
    public IndividualFi {
      reasons = List.copyOf(reasons);
    }

    @Override
    public List<String> reasons() {
      return List.copyOf(reasons);
    }
  }

  /** One named gate, its outcome and any explicit waiver of it. */
  public record Gate(
      String name,
      String source,
      String enforcement,
      boolean passed,
      String detail,
      Waiver waiver) {
    public Gate {
      if (!BLOCKING.equals(enforcement) && !REPORTED.equals(enforcement)) {
        throw new IllegalArgumentException("A gate is blocking or reported, not " + enforcement);
      }
      if (name == null || name.isBlank() || detail == null) {
        throw new IllegalArgumentException("A gate needs a name and a stated outcome");
      }
    }

    public boolean blocks() {
      return !passed && BLOCKING.equals(enforcement) && waiver == null;
    }
  }

  public record Verdict(
      String status, List<Gate> gates, List<String> blockingReasons, List<Waiver> waivers) {
    public Verdict {
      gates = List.copyOf(gates);
      blockingReasons = List.copyOf(blockingReasons);
      waivers = List.copyOf(waivers);
    }

    public boolean released() {
      return STATUS_RELEASED.equals(status);
    }

    @Override
    public List<Gate> gates() {
      return List.copyOf(gates);
    }

    @Override
    public List<String> blockingReasons() {
      return List.copyOf(blockingReasons);
    }

    @Override
    public List<Waiver> waivers() {
      return List.copyOf(waivers);
    }
  }

  public record Report(
      Frozen frozen,
      Election election,
      List<Misfit> misfit,
      List<Dependence> dependence,
      List<Sensitivity> sensitivity,
      IndividualFi individualFi,
      Verdict verdict) {
    public Report {
      misfit = List.copyOf(misfit);
      dependence = List.copyOf(dependence);
      sensitivity = List.copyOf(sensitivity);
    }

    @Override
    public List<Dependence> dependence() {
      return List.copyOf(dependence);
    }

    @Override
    public List<Misfit> misfit() {
      return List.copyOf(misfit);
    }

    @Override
    public List<Sensitivity> sensitivity() {
      return List.copyOf(sensitivity);
    }
  }

  /**
   * Reads the release registration and verifies every digest it pinned against the files on disk. A
   * moved development protocol or a moved evidence file fails here rather than producing an audit
   * against inputs the freeze never saw.
   */
  public static Frozen frozen(Path releaseProtocolFile, Path directory) {
    try {
      final JsonNode root = JSON.readTree(Files.readAllBytes(releaseProtocolFile));
      if (!"frozen-release".equals(required(root, "status").asString())) {
        throw new IllegalArgumentException("The release registration is not frozen");
      }
      final JsonNode development = required(root, "development_protocol");
      final String developmentSha256 = required(development, "sha256").asString();
      final Path developmentFile = directory.resolve(required(development, "path").asString());
      if (!sameDigest(developmentSha256, DevelopmentGates.sha256(developmentFile))) {
        throw new IllegalArgumentException(
            "The development protocol moved after the release freeze");
      }
      final List<Evidence> evidence = new ArrayList<>();
      for (final JsonNode node : required(root, "frozen_evidence")) {
        evidence.add(
            new Evidence(
                required(node, "name").asString(),
                required(node, "path").asString(),
                required(node, "sha256").asString()));
      }
      final JsonNode limits = required(root, "release_tolerances");
      final List<String> reported = new ArrayList<>();
      for (final JsonNode node : required(root, "reported_not_blocking")) {
        reported.add(node.asString());
      }
      final Map<String, String> rules = new LinkedHashMap<>();
      required(root, "audit_rules")
          .propertyStream()
          .forEach(rule -> rules.put(rule.getKey(), rule.getValue().asString()));
      final JsonNode fallback = required(root, "fi_fallback");
      final List<Waiver> waivers = new ArrayList<>();
      for (final JsonNode node : required(root, "waivers")) {
        waivers.add(
            new Waiver(
                required(node, "gate").asString(),
                required(node, "decisionUrl").asString(),
                required(node, "rationale").asString()));
      }
      return new Frozen(
          required(root, "version").asString(),
          LocalDate.parse(required(root, "registered_on").asString()),
          required(development, "version").asString(),
          developmentSha256,
          evidence,
          new Tolerances(
              required(limits, "composition_sum_points").doubleValue(),
              required(limits, "seat_total").intValue(),
              required(limits, "minimum_scored_folds").intValue(),
              required(limits, "sensitivity_disclosure_points").doubleValue()),
          reported,
          rules,
          new Fallback(
              required(fallback, "id").asString(),
              required(fallback, "approval").asString(),
              required(fallback, "note").asString()),
          waivers);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The reserved comparison's pinned inputs, read from the frozen development protocol. */
  public static Manifest manifest(Path developmentProtocolFile) {
    try {
      final JsonNode audit =
          required(
              JSON.readTree(Files.readAllBytes(developmentProtocolFile)), "election_audit2022");
      final JsonNode outcome = required(audit, "outcome");
      final Map<String, String> shares = new LinkedHashMap<>();
      required(outcome, "shares_percent")
          .propertyStream()
          .forEach(share -> shares.put(share.getKey(), share.getValue().asString()));
      return new Manifest(
          LocalDate.parse(required(audit, "cutoff").asString()),
          LocalDate.parse(required(audit, "election_date").asString()),
          required(audit, "source_commit").asString(),
          required(audit, "source_sha256").asString(),
          required(audit, "row_identity").asString(),
          required(audit, "candidate_selection").asString(),
          required(audit, "candidate_count").intValue(),
          required(audit, "candidate_rows_sha256").asString(),
          required(outcome, "source_url").asString(),
          required(outcome, "exposed_fixture_commit").asString(),
          required(outcome, "exposed_fixture_path").asString(),
          required(outcome, "exposed_fixture_sha256").asString(),
          shares);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Applies the pinned candidate selection to the source bytes and checks the result against the
   * manifest's count and digest. Row identities are read off the physical lines rather than the
   * parsed fields, so the manifest verifies the file and not this parser's view of it.
   */
  public static Selection select(byte[] source, Manifest manifest) {
    if (!sameDigest(manifest.sourceSha256(), hex(sha256(source)))) {
      throw new IllegalArgumentException("The reserved comparison read another source snapshot");
    }
    final List<String> lines =
        new ArrayList<>(List.of(new String(source, StandardCharsets.UTF_8).split("\n", -1)));
    if (!lines.isEmpty() && lines.getLast().isEmpty()) {
      lines.removeLast();
    }
    final List<String> header = List.of(lines.getFirst().split(",", -1));
    final int from = header.indexOf("collectPeriodFrom");
    final int to = header.indexOf("collectPeriodTo");
    final int published = header.indexOf("PublDate");
    if (from < 0 || to < 0 || published < 0) {
      throw new IllegalArgumentException("The reserved source is missing its date columns");
    }
    final String cutoff = manifest.cutoff().toString();
    final StringBuilder identities = new StringBuilder();
    final List<Integer> selected = new ArrayList<>();
    for (int line = 2; line <= lines.size(); line++) {
      final String text = lines.get(line - 1);
      final String[] fields = text.split(",", -1);
      if (fields.length != header.size()) {
        throw new IllegalArgumentException("The reserved source quotes a field on line " + line);
      }
      if (!eligibleCandidate(fields[from], fields[to], fields[published], cutoff)) {
        continue;
      }
      if (!selected.isEmpty()) {
        identities.append('\n');
      }
      identities
          .append(line)
          .append(':')
          .append(hex(sha256(text.getBytes(StandardCharsets.UTF_8))));
      selected.add(line);
    }
    final String rowsSha256 = hex(sha256(identities.toString().getBytes(StandardCharsets.UTF_8)));
    if (selected.size() != manifest.candidateCount()
        || !sameDigest(rowsSha256, manifest.candidateRowsSha256())) {
      throw new IllegalArgumentException(
          "The candidate selection does not reproduce the pinned manifest");
    }
    return new Selection(selected.size(), rowsSha256, selected);
  }

  private static boolean eligibleCandidate(
      String from, String to, String published, String cutoff) {
    return !published.isEmpty()
        && !"NA".equals(published)
        && published.compareTo(cutoff) <= 0
        && "2010-01-01".compareTo(from) <= 0
        && from.compareTo(to) <= 0
        && to.compareTo(cutoff) <= 0;
  }

  /**
   * The parsed polls the selection names. A record's one-based physical line is its row number plus
   * the header, which only holds while no field carries a newline; {@link #select} rejects a source
   * that quotes one.
   */
  public static List<PollCsv.Poll> candidates(List<PollCsv.Poll> polls, Selection selection) {
    final Set<Integer> lines = Set.copyOf(selection.lines());
    final List<PollCsv.Poll> selected =
        polls.stream().filter(poll -> lines.contains(poll.rowNumber() + 1)).toList();
    if (selected.size() != selection.rows()) {
      throw new IllegalArgumentException("The parsed snapshot does not hold every candidate row");
    }
    return selected;
  }

  /** OTHER is exactly 100 minus the eight displayed percentages, in decimal arithmetic. */
  public static Map<String, BigDecimal> outcome(Manifest manifest) {
    final Map<String, BigDecimal> shares = new LinkedHashMap<>();
    BigDecimal named = BigDecimal.ZERO;
    for (final String party : PollCsv.PARTIES) {
      final String share = manifest.officialShares().get(party);
      if (share == null) {
        throw new IllegalArgumentException("The official outcome is missing " + party);
      }
      final BigDecimal value = new BigDecimal(share);
      shares.put(party, value);
      named = named.add(value);
    }
    final BigDecimal residual = new BigDecimal("100").subtract(named);
    final String stated = manifest.officialShares().get(OTHER);
    if (stated != null && new BigDecimal(stated).compareTo(residual) != 0) {
      throw new IllegalArgumentException("The stated OTHER is not 100 minus the eight shares");
    }
    shares.put(OTHER, residual);
    return Collections.unmodifiableMap(shares);
  }

  /**
   * Compares one frozen fit with the official result. Errors are the drawn mean minus the official
   * percentage; inclusion is read off the marginal intervals of the same draws.
   */
  public static Election compare(
      Manifest manifest,
      Selection selection,
      JointUncertainty.FinalDay finalDay,
      List<Double> fitLogLikelihoods,
      int fittedObservations,
      NationalSeats.Allocation approximate,
      Map<String, Integer> officialSeats,
      JointUncertainty.Reproduced reproduced) {
    final Map<String, BigDecimal> official = outcome(manifest);
    final List<PartyResult> parties = new ArrayList<>();
    final Map<String, Integer> inclusion = new LinkedHashMap<>();
    double sum = 0;
    double totalAbsoluteError = 0;
    double maximumAbsoluteError = 0;
    for (final JointUncertainty.Component component : finalDay.day().components()) {
      sum += component.mean();
      final BigDecimal expected = official.get(component.component());
      if (expected == null) {
        throw new IllegalArgumentException(
            "The official outcome does not cover " + component.component());
      }
      final double officialPercent = expected.doubleValue();
      final double error = component.mean() - officialPercent;
      final Map<String, Boolean> included = new LinkedHashMap<>();
      for (final JointUncertainty.Interval interval : component.intervals()) {
        final String level = level(interval.level());
        final boolean within =
            officialPercent >= interval.lower() && officialPercent <= interval.upper();
        included.put(level, within);
        inclusion.merge(level, within ? 1 : 0, Integer::sum);
      }
      parties.add(
          new PartyResult(
              component.component(),
              component.mean(),
              officialPercent,
              error,
              included,
              component.intervals()));
      totalAbsoluteError += Math.abs(error);
      maximumAbsoluteError = Math.max(maximumAbsoluteError, Math.abs(error));
    }
    final List<SeatResult> seats = new ArrayList<>();
    int approximateTotal = 0;
    int officialTotal = 0;
    for (final String component : approximate.seats().keySet()) {
      final int allocated = approximate.of(component);
      final int actual = officialSeats.getOrDefault(component, 0);
      seats.add(new SeatResult(component, allocated, actual, allocated - actual));
      approximateTotal += allocated;
    }
    for (final Map.Entry<String, Integer> entry : officialSeats.entrySet()) {
      officialTotal += entry.getValue();
      if (!approximate.seats().containsKey(entry.getKey()) && entry.getValue() > 0) {
        seats.add(new SeatResult(entry.getKey(), 0, entry.getValue(), -entry.getValue()));
      }
    }
    return new Election(
        manifest.cutoff(),
        manifest.electionDate(),
        finalDay.day().date(),
        finalDay.periodId(),
        manifest,
        selection,
        fittedObservations,
        fitLogLikelihoods,
        sum,
        parties,
        totalAbsoluteError / parties.size(),
        maximumAbsoluteError,
        inclusion,
        seats,
        approximateTotal,
        officialTotal,
        approximate.tieBrokenSeats(),
        NationalSeats.APPROXIMATION_NOTE,
        NationalSeats.TIE_NOTE,
        ROUNDING_LIMITATION,
        INTERPRETATION,
        finalDay.reproduction(),
        reproduced);
  }

  private static String level(double level) {
    return BigDecimal.valueOf(level * 100).stripTrailingZeros().toPlainString() + "%";
  }

  /**
   * Itemizes every subgroup against the frozen misfit limits. The development gate already blocks
   * on these facts; this restates them per subgroup so the misfit can be read rather than inferred
   * from a list of reasons.
   */
  public static List<Misfit> misfit(Path developmentProtocolFile, Path diagnosticsFile) {
    try {
      final JsonNode protocol = JSON.readTree(Files.readAllBytes(developmentProtocolFile));
      final JsonNode diagnostics = JSON.readTree(Files.readAllBytes(diagnosticsFile));
      final JsonNode rules = required(protocol, "resolved_diagnostic_gates");
      final JsonNode band95 = required(protocol, "predictive_coverage95");
      final JsonNode band50 = required(protocol, "predictive_coverage50");
      final int minimumCases = required(rules, "minimum_subgroup_cases").intValue();
      final double maximumBias =
          required(rules, "maximum_absolute_mean_standardized_residual").doubleValue();
      final double minimumScale =
          required(rules, "minimum_root_mean_square_standardized_residual").doubleValue();
      final double maximumScale =
          required(rules, "maximum_root_mean_square_standardized_residual").doubleValue();
      final List<Misfit> subgroups = new ArrayList<>();
      for (final JsonNode row : required(diagnostics, "misfit")) {
        if (!"midpoint".equals(required(row, "candidate").asString())
            || "all".equals(required(row, "scope").asString())) {
          continue;
        }
        final int cases = required(row, "cases").intValue();
        final double coverage95 = required(row, "coverage95").doubleValue();
        final double coverage50 = required(row, "coverage50").doubleValue();
        final double bias = required(row, "meanStandardizedResidual").doubleValue();
        final double scale = required(row, "rootMeanSquareStandardizedResidual").doubleValue();
        final List<String> failures = new ArrayList<>();
        if (cases >= minimumCases) {
          if (outside(coverage95, band95)) {
            failures.add("95% coverage " + coverage95 + " outside the registered band");
          }
          if (outside(coverage50, band50)) {
            failures.add("50% coverage " + coverage50 + " outside the registered band");
          }
          if (Math.abs(bias) > maximumBias) {
            failures.add("mean standardized residual " + bias + " above " + maximumBias);
          }
          if (scale < minimumScale || scale > maximumScale) {
            failures.add(
                "root-mean-square standardized residual "
                    + scale
                    + " outside ["
                    + minimumScale
                    + ", "
                    + maximumScale
                    + "]");
          }
        }
        subgroups.add(
            new Misfit(
                required(row, "periodId").asString(),
                required(row, "scope").asString(),
                required(row, "name").asString(),
                cases,
                coverage95,
                coverage50,
                bias,
                scale,
                failures));
      }
      if (subgroups.isEmpty()) {
        throw new IllegalArgumentException("The diagnostics hold no subgroup misfit evidence");
      }
      return List.copyOf(subgroups);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads the residual autocorrelation and the overlap and institute pair measurements, and checks
   * each registered lag against the frozen limit. The pair measurements are reported beside the
   * correlations because they are what separates structural dependence from the rest; the
   * registration carries the explanation rather than this code inferring one.
   */
  public static List<Dependence> dependence(Path developmentProtocolFile, Path diagnosticsFile) {
    try {
      final JsonNode protocol = JSON.readTree(Files.readAllBytes(developmentProtocolFile));
      final JsonNode diagnostics = JSON.readTree(Files.readAllBytes(diagnosticsFile));
      final JsonNode rules = required(protocol, "resolved_diagnostic_gates");
      final double maximum =
          required(rules, "maximum_absolute_residual_autocorrelation").doubleValue();
      final String explanation = required(rules, "autocorrelation_explanation").asString();
      if (explanation.isBlank()) {
        throw new IllegalArgumentException("Residual autocorrelation needs an explanation");
      }
      final Map<String, Map<String, Double>> lags = new LinkedHashMap<>();
      for (final JsonNode row : required(diagnostics, "autocorrelation")) {
        lags.computeIfAbsent(required(row, "periodId").asString(), period -> new LinkedHashMap<>())
            .put(
                "lag " + required(row, "lag").intValue(),
                required(row, "correlation").doubleValue());
      }
      final List<Dependence> results = new ArrayList<>();
      for (final JsonNode row : required(diagnostics, "dependence")) {
        final String periodId = required(row, "periodId").asString();
        final Map<String, Double> correlations = lags.get(periodId);
        if (correlations == null) {
          throw new IllegalArgumentException("No residual autocorrelation for " + periodId);
        }
        final List<String> failures = new ArrayList<>();
        correlations.forEach(
            (lag, correlation) -> {
              if (Math.abs(correlation) > maximum) {
                failures.add(lag + " correlation " + correlation + " above " + maximum);
              }
            });
        results.add(
            new Dependence(
                periodId,
                correlations,
                maximum,
                required(row, "overlappingPairs").longValue(),
                required(row, "overlappingCorrelation").doubleValue(),
                required(row, "disjointPairs").longValue(),
                required(row, "disjointCorrelation").doubleValue(),
                required(row, "sameInstitutePairs").longValue(),
                required(row, "sameInstituteCorrelation").doubleValue(),
                explanation,
                failures));
      }
      if (results.isEmpty()) {
        throw new IllegalArgumentException("The diagnostics hold no residual dependence evidence");
      }
      return List.copyOf(results);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The registered centering and leave-one-institute-out reruns, per period. */
  public static List<Sensitivity> sensitivity(Path diagnosticsFile, Tolerances tolerances) {
    try {
      final JsonNode diagnostics = JSON.readTree(Files.readAllBytes(diagnosticsFile));
      final List<Sensitivity> results = new ArrayList<>();
      for (final JsonNode centering : required(diagnostics, "centering")) {
        final String periodId = required(centering, "periodId").asString();
        double headline = 0;
        double daily = 0;
        String worst = null;
        for (final JsonNode dropped : required(diagnostics, "leaveOneInstituteOut")) {
          if (!periodId.equals(required(dropped, "periodId").asString())) {
            continue;
          }
          final double shift = required(dropped, "maxShiftPoints").doubleValue();
          if (worst == null || shift > headline) {
            worst = required(dropped, "institute").asString();
            headline = shift;
          }
          daily = Math.max(daily, required(dropped, "maxDailyShiftPoints").doubleValue());
        }
        if (worst == null) {
          throw new IllegalArgumentException("No leave-one-institute-out rerun for " + periodId);
        }
        final double centeringHeadline =
            required(centering, "maxHeadlineShiftPoints").doubleValue();
        results.add(
            new Sensitivity(
                periodId,
                LocalDate.parse(required(centering, "headlineDate").asString()),
                centeringHeadline,
                required(centering, "maxDailyShiftPoints").doubleValue(),
                worst,
                headline,
                daily,
                tolerances.sensitivityDisclosurePoints(),
                Math.max(centeringHeadline, headline) > tolerances.sensitivityDisclosurePoints()));
      }
      if (results.isEmpty()) {
        throw new IllegalArgumentException("The diagnostics hold no sensitivity evidence");
      }
      return List.copyOf(results);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Confirms the individual FI roster's support dates and separate-fit behaviour, and names the
   * approved fallback where the frozen gates are not met. The fallback retains the source
   * observations and waives nothing else.
   */
  public static IndividualFi individualFi(
      Frozen frozen,
      Roster.CoveragePeriod period,
      CoverageValidation.Support support,
      int separatelyFittedRuns,
      DevelopmentGates.Report gates) {
    final boolean datesExact =
        support.from().equals(period.effectiveFrom()) && support.to().equals(period.effectiveTo());
    final List<String> reasons =
        gates.gate().reasons().stream().filter(reason -> reason.contains(period.id())).toList();
    final boolean met = datesExact && reasons.isEmpty() && separatelyFittedRuns > 0;
    return new IndividualFi(
        period.id(),
        period.effectiveFrom(),
        period.effectiveTo(),
        support.from(),
        support.to(),
        datesExact,
        support.observations(),
        separatelyFittedRuns,
        met,
        met ? "individual_fi_estimate_supported" : frozen.fiFallback().id(),
        met ? null : frozen.fiFallback(),
        reasons);
  }

  /**
   * Assembles the verdict. Every registered gate is recorded with a pass or a fail; a blocking
   * failure without an explicit owner waiver blocks the release.
   */
  public static Report evaluate(
      Frozen frozen,
      Path directory,
      Path diagnosticsFile,
      DevelopmentGates.Report gates,
      Election election,
      List<Misfit> misfit,
      List<Dependence> dependence,
      List<Sensitivity> sensitivity,
      IndividualFi individualFi) {
    final List<Gate> registered = new ArrayList<>();
    registered.add(evidenceGate(frozen, directory));
    registered.add(parametersGate(frozen, gates, election));
    registered.add(
        new Gate(
            "development_gates",
            "development-gates.json",
            BLOCKING,
            !gates.gate().blocked(),
            gates.gate().blocked()
                ? gates.gate().reasons().size() + " development gate reasons stand"
                : "every development gate passed",
            null));
    registered.add(compositionGate(frozen, election));
    registered.add(seatGate(frozen, election));
    registered.add(reproductionGate(election));
    registered.addAll(predictiveGates(frozen, diagnosticsFile));
    registered.addAll(misfitGates(misfit));
    registered.addAll(dependenceGates(dependence));
    for (final DevelopmentGates.Tolerance tolerance : gates.tolerances()) {
      registered.add(
          new Gate(
              "tolerance:" + tolerance.name(),
              tolerance.evidence(),
              REPORTED,
              tolerance.passes(),
              tolerance.maxObservedError()
                  + " "
                  + tolerance.units()
                  + " against the frozen maximum "
                  + tolerance.maximum(),
              null));
    }
    for (final Sensitivity rerun : sensitivity) {
      registered.add(
          new Gate(
              "sensitivity_disclosure:" + rerun.periodId(),
              "diagnostics.json",
              REPORTED,
              !rerun.disclosureRequired(),
              "centering moves the headline by at most "
                  + rerun.centeringMaxHeadlinePoints()
                  + " points and leaving out "
                  + rerun.largestHeadlineInstitute()
                  + " by "
                  + rerun.leaveOneInstituteOutMaxHeadlinePoints()
                  + "; adjacent disclosure starts at "
                  + rerun.disclosureThresholdPoints(),
              null));
    }
    registered.add(
        new Gate(
            "resource_runtime_target",
            "development-gates.json",
            BLOCKING,
            gates.resources().meetsTarget(),
            gates.resources().runtimeMillis()
                + " ms against the registered "
                + gates.resources().targetMillis()
                + " ms target",
            null));
    registered.add(
        new Gate(
            "individual_fi_support",
            "coverage.json",
            REPORTED,
            individualFi.gatesMet(),
            individualFi.gatesMet()
                ? "the individual FI roster meets the frozen gates"
                : individualFi.reasons().size()
                    + " frozen gate reasons name the roster; the approved fallback "
                    + individualFi.disposition()
                    + " applies",
            null));
    final List<Gate> resolved =
        waived(downgraded(registered, frozen.reportedNotBlocking()), frozen.waivers());
    final LinkedHashSet<String> blocking = new LinkedHashSet<>();
    for (final Gate gate : resolved) {
      if (gate.blocks()) {
        blocking.add(gate.name() + ": " + gate.detail());
      }
    }
    if (gates.gate().blocked() && !waivedByName(resolved, "development_gates")) {
      gates.gate().reasons().forEach(reason -> blocking.add("development_gates: " + reason));
    }
    return new Report(
        frozen,
        election,
        misfit,
        dependence,
        sensitivity,
        individualFi,
        new Verdict(
            blocking.isEmpty() ? STATUS_RELEASED : STATUS_BLOCKED,
            resolved,
            List.copyOf(blocking),
            frozen.waivers()));
  }

  /**
   * Applies the registration's reported-not-blocking list. A gate named there is measured and
   * recorded like any other and stops nothing, which is how the registration downgrades the runtime
   * target without deleting it.
   */
  private static List<Gate> downgraded(List<Gate> gates, List<String> reported) {
    for (final String name : reported) {
      if (gates.stream().noneMatch(gate -> gate.name().equals(name))) {
        throw new IllegalArgumentException("The registration reports an unregistered gate " + name);
      }
    }
    return gates.stream()
        .map(gate -> reported.contains(gate.name()) ? with(gate, REPORTED, gate.waiver()) : gate)
        .toList();
  }

  private static List<Gate> waived(List<Gate> gates, List<Waiver> waivers) {
    final Map<String, Waiver> byGate = new LinkedHashMap<>();
    for (final Waiver waiver : waivers) {
      if (gates.stream().noneMatch(gate -> gate.name().equals(waiver.gate()))) {
        throw new IllegalArgumentException("A waiver names an unregistered gate " + waiver.gate());
      }
      if (byGate.put(waiver.gate(), waiver) != null) {
        throw new IllegalArgumentException("Two waivers name the gate " + waiver.gate());
      }
    }
    return gates.stream()
        .map(
            gate ->
                byGate.containsKey(gate.name())
                    ? with(gate, gate.enforcement(), byGate.get(gate.name()))
                    : gate)
        .toList();
  }

  /** The same gate and outcome, re-recorded with another enforcement or waiver. */
  private static Gate with(Gate gate, String enforcement, Waiver waiver) {
    return new Gate(gate.name(), gate.source(), enforcement, gate.passed(), gate.detail(), waiver);
  }

  private static boolean waivedByName(List<Gate> gates, String name) {
    return gates.stream().anyMatch(gate -> gate.name().equals(name) && gate.waiver() != null);
  }

  private static Gate evidenceGate(Frozen frozen, Path directory) {
    final List<String> moved = new ArrayList<>();
    for (final Evidence evidence : frozen.evidence()) {
      try {
        if (!sameDigest(
            evidence.sha256(), DevelopmentGates.sha256(directory.resolve(evidence.path())))) {
          moved.add(evidence.name());
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return new Gate(
        "frozen_evidence_digests",
        frozen.version(),
        BLOCKING,
        moved.isEmpty(),
        moved.isEmpty()
            ? frozen.evidence().size() + " evidence files match the freeze"
            : "moved after the freeze: " + String.join(", ", moved),
        null);
  }

  private static Gate parametersGate(
      Frozen frozen, DevelopmentGates.Report gates, Election election) {
    final JointUncertainty.Reproduction reproduction = election.reproduction();
    final boolean matches =
        frozen.developmentProtocolVersion().equals(gates.protocolVersion())
            && reproduction.periodId().equals(election.periodId());
    return new Gate(
        "frozen_parameters",
        frozen.version(),
        BLOCKING,
        matches,
        matches
            ? "the reserved fit ran "
                + reproduction.periodId()
                + " at "
                + reproduction.parameters()
                + " and seed "
                + reproduction.seed()
            : "the reserved fit does not match the frozen registration",
        null);
  }

  private static Gate compositionGate(Frozen frozen, Election election) {
    final double error = Math.abs(election.compositionSumPercent() - 100);
    return new Gate(
        "audit_composition_sum",
        frozen.version(),
        BLOCKING,
        error <= frozen.tolerances().compositionSumPoints() && election.fitsAreFinite(),
        "the fitted composition sums to "
            + election.compositionSumPercent()
            + " from "
            + election.fitLogLikelihoods().size()
            + " finite fits",
        null);
  }

  private static Gate seatGate(Frozen frozen, Election election) {
    final boolean allocated = election.approximateSeatTotal() == frozen.tolerances().seatTotal();
    return new Gate(
        "audit_seat_total",
        frozen.version(),
        BLOCKING,
        allocated,
        "the national approximation allocates "
            + election.approximateSeatTotal()
            + " seats beside the official "
            + election.officialSeatTotal(),
        null);
  }

  private static Gate reproductionGate(Election election) {
    final JointUncertainty.Reproduced reproduced = election.reproduced();
    return new Gate(
        "audit_seeded_reproduction",
        "release-audit.json",
        BLOCKING,
        reproduced.exact(),
        reproduced.comparedValues()
            + " retained draws reproduced at seed "
            + election.reproduction().seed()
            + " with a maximum difference of "
            + reproduced.maxAbsoluteDifference(),
        null);
  }

  private static List<Gate> predictiveGates(Frozen frozen, Path diagnosticsFile) {
    try {
      final JsonNode diagnostics = JSON.readTree(Files.readAllBytes(diagnosticsFile));
      final List<Gate> gates = new ArrayList<>();
      for (final JsonNode paired : required(diagnostics, "paired")) {
        final String periodId = required(paired, "periodId").asString();
        final double baseline = required(paired, "baselineDifference").doubleValue();
        final double reference = required(paired, "referenceDifference").doubleValue();
        final double standardError = required(paired, "pairedStandardError").doubleValue();
        final int folds = required(paired, "folds").intValue();
        gates.add(
            new Gate(
                "predictive_log_score_vs_recency:" + periodId,
                "diagnostics.json",
                REPORTED,
                baseline > 0,
                "mean paired log score " + baseline + " per poll against the recency baseline",
                null));
        gates.add(
            new Gate(
                "predictive_log_score_vs_reference:" + periodId,
                "diagnostics.json",
                REPORTED,
                reference >= -standardError,
                "mean paired log score "
                    + reference
                    + " against the ilr-window reference, at a paired standard error of "
                    + standardError,
                null));
        gates.add(
            new Gate(
                "scored_folds:" + periodId,
                "diagnostics.json",
                REPORTED,
                folds >= frozen.tolerances().minimumScoredFolds(),
                folds
                    + " scored folds against the registered minimum "
                    + frozen.tolerances().minimumScoredFolds(),
                null));
      }
      final JsonNode band95 = required(diagnostics, "rules").get("coverage95");
      final JsonNode band50 = required(diagnostics, "rules").get("coverage50");
      for (final JsonNode row : required(diagnostics, "misfit")) {
        if (!"midpoint".equals(required(row, "candidate").asString())
            || !"all".equals(required(row, "scope").asString())) {
          continue;
        }
        final String periodId = required(row, "periodId").asString();
        final double coverage95 = required(row, "coverage95").doubleValue();
        final double coverage50 = required(row, "coverage50").doubleValue();
        gates.add(
            new Gate(
                "predictive_coverage95:" + periodId,
                "diagnostics.json",
                REPORTED,
                !outside(coverage95, band95),
                "pooled 95% coverage " + coverage95 + " with the observed poll noise",
                null));
        gates.add(
            new Gate(
                "predictive_coverage50:" + periodId,
                "diagnostics.json",
                REPORTED,
                !outside(coverage50, band50),
                "pooled 50% coverage " + coverage50 + " with the observed poll noise",
                null));
      }
      final Map<String, Integer> unscored = new LinkedHashMap<>();
      for (final JsonNode row : required(diagnostics, "unscored")) {
        unscored.merge(required(row, "periodId").asString(), 1, Integer::sum);
      }
      for (final JsonNode paired : required(diagnostics, "paired")) {
        final String periodId = required(paired, "periodId").asString();
        final int count = unscored.getOrDefault(periodId, 0);
        gates.add(
            new Gate(
                "unscored_folds:" + periodId,
                "diagnostics.json",
                REPORTED,
                count == 0,
                count + " registered folds scored nothing",
                null));
      }
      return List.copyOf(gates);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Gate> dependenceGates(List<Dependence> dependence) {
    return dependence.stream()
        .map(
            row ->
                new Gate(
                    "residual_dependence:" + row.periodId(),
                    "diagnostics.json",
                    REPORTED,
                    row.passes(),
                    (row.passes()
                            ? "every registered lag is inside the frozen limit "
                            : String.join("; ", row.failures()) + "; the frozen limit is ")
                        + row.maximumAbsoluteAutocorrelation()
                        + ". Overlapping pairs average "
                        + row.overlappingCorrelation()
                        + " against "
                        + row.disjointCorrelation()
                        + " for disjoint pairs and "
                        + row.sameInstituteCorrelation()
                        + " for same-institute pairs",
                    null))
        .toList();
  }

  private static List<Gate> misfitGates(List<Misfit> misfit) {
    final Map<String, List<Misfit>> byPeriod = new LinkedHashMap<>();
    for (final Misfit subgroup : misfit) {
      byPeriod.computeIfAbsent(subgroup.periodId(), period -> new ArrayList<>()).add(subgroup);
    }
    final List<Gate> gates = new ArrayList<>();
    for (final Map.Entry<String, List<Misfit>> period : byPeriod.entrySet()) {
      for (final String scope : List.of("party", "institute", "fieldwork_days")) {
        final List<Misfit> scoped =
            period.getValue().stream().filter(row -> row.scope().equals(scope)).toList();
        if (scoped.isEmpty()) {
          throw new IllegalArgumentException(
              "Missing " + scope + " misfit evidence for " + period.getKey());
        }
        final List<String> failed =
            scoped.stream().filter(row -> !row.passes()).map(Misfit::name).toList();
        gates.add(
            new Gate(
                "systematic_misfit:" + scope + ":" + period.getKey(),
                "diagnostics.json",
                REPORTED,
                failed.isEmpty(),
                failed.isEmpty()
                    ? scoped.size() + " subgroups inside the frozen misfit limits"
                    : "outside the frozen limits: " + String.join(", ", failed),
                null));
      }
    }
    return List.copyOf(gates);
  }

  /** The publication boundary calls this before exposing any released estimate. */
  public static void requireReleasable(Report report) {
    if (!report.verdict().released()) {
      throw new IllegalStateException(
          "Release audit blocked: " + String.join("; ", report.verdict().blockingReasons()));
    }
  }

  public static String report(Report report) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
  }

  public static Report validation(Path file) {
    try {
      return JSON.readValue(Files.readAllBytes(file), Report.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean outside(double value, JsonNode band) {
    return value < band.get(0).doubleValue() || value > band.get(1).doubleValue();
  }

  private static JsonNode required(JsonNode node, String field) {
    final JsonNode value = node == null ? null : node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("Missing release audit field " + field);
    }
    return value;
  }

  /** Compares two hex digests without an early exit, which is what the security scanner asks. */
  private static boolean sameDigest(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
