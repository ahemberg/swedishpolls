package se.swedishpolls.estimation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ejml.simple.SimpleMatrix;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Prepares and checks the frozen inputs for the development-only v2 validation run. */
public final class DevelopmentValidation {
  public static final int SUCCESS = 0;
  public static final int BLOCKED = 1;
  public static final int REJECTED = 2;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String FROZEN = "frozen";
  private static final String VERSION = "v2-development-1";
  private static final String PREPARE_COMMAND =
      "./mvnw -DskipTests spring-boot:run"
          + " -Dspring-boot.run.main-class=se.swedishpolls.estimation.DevelopmentValidation"
          + " -Dspring-boot.run.arguments='prepare"
          + " docs/validation/v2-development-1/registration-plan.json"
          + " src/test/resources/polls/audit.csv"
          + " docs/validation/v2-development-1/registration.json'";
  private static final String PREFLIGHT_COMMAND =
      "./mvnw -DskipTests spring-boot:run"
          + " -Dspring-boot.run.main-class=se.swedishpolls.estimation.DevelopmentValidation"
          + " -Dspring-boot.run.arguments='preflight"
          + " docs/validation/v2-development-1/registration.json"
          + " src/test/resources/polls/audit.csv"
          + " docs/validation/v2-development-1/preflight.json'";
  private static final String TUNE_COMMAND =
      "./mvnw -DskipTests spring-boot:run"
          + " -Dspring-boot.run.main-class=se.swedishpolls.estimation.DevelopmentValidation"
          + " -Dspring-boot.run.arguments='tune"
          + " docs/validation/v2-development-1/registration.json"
          + " src/test/resources/polls/audit.csv"
          + " docs/validation/v2-development-1/evidence/run-1/tuning.json'";
  private static final String ESTIMATE_COMMAND =
      "./mvnw -DskipTests spring-boot:run"
          + " -Dspring-boot.run.main-class=se.swedishpolls.estimation.DevelopmentValidation"
          + " -Dspring-boot.run.arguments='estimate"
          + " docs/validation/v2-development-1/registration.json"
          + " src/test/resources/polls/audit.csv"
          + " docs/validation/v2-development-1/evidence/run-1/tuning.json"
          + " docs/validation/v2-development-1/evidence/run-1/estimation.json'";

  /** The approved fitted method and fold rule the registration must name for the estimator. */
  private static final String APPROVED_METHOD = "midpoint_candidate";

  private static final String APPROVED_FOLD_RULE = "latest_resolved_active_cutoff";

  private static final String PASSED = "passed";
  private static final String FAILED = "failed";
  private static final String UNEVALUATED = "unevaluated";

  private DevelopmentValidation() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /**
   * The operator entry point. Preparation never fits; preflight never creates the evidence path.
   */
  public static int run(String... args) {
    final String command = args.length == 0 ? "" : args[0];
    final boolean estimating = command.equals("estimate");
    if (args.length != (estimating ? 5 : 4)
        || (!command.equals("prepare")
            && !command.equals("preflight")
            && !command.equals("tune")
            && !estimating)) {
      System.err.println(
          "Usage: prepare <plan.json> <source.csv> <registration.json> | preflight"
              + " <registration.json> <source.csv> <result.json> | tune"
              + " <registration.json> <source.csv> <evidence.json> | estimate"
              + " <registration.json> <source.csv> <tuning.json> <evidence.json>");
      return REJECTED;
    }
    final Path registrationInput = Path.of(args[1]);
    final Path source = Path.of(args[2]);
    final Path output = Path.of(args[args.length - 1]);
    try {
      if (command.equals("prepare")) prepare(registrationInput, source, output);
      else if (command.equals("preflight")) preflight(registrationInput, source, output);
      else if (estimating)
        return estimate(registrationInput, source, Path.of(args[3]), output) ? SUCCESS : BLOCKED;
      else return tune(registrationInput, source, output) ? SUCCESS : BLOCKED;
      return SUCCESS;
    } catch (RuntimeException e) {
      rejected(output, e.getMessage());
      System.err.println(e.getMessage());
      return REJECTED;
    }
  }

  private static void prepare(Path planFile, Path sourceFile, Path registrationFile) {
    refuseExisting(registrationFile);
    refuseExisting(checksum(registrationFile));
    final JsonNode plan = read(planFile);
    verifyProtocol(plan);
    verifySource(plan, sourceFile);
    verifyIdentities(plan);
    verifyImplementationCommit(plan);
    verifyEnvironment(plan);
    verifyOutputLocation(plan, true);
    grid(plan);
    final ArrayNode folds = manifests(plan, sourceFile);
    compareArchived(plan, folds);
    verifyApprovedManifest(plan, folds);

    final ObjectNode registration = JSON.createObjectNode();
    registration.put("status", FROZEN);
    registration.put("version", required(plan, "version").asString());
    registration.put("registeredOn", required(plan, "registeredOn").asString());
    final ObjectNode planIdentity = registration.putObject("planIdentity");
    planIdentity.put("path", planFile.toString());
    planIdentity.put("sha256", digest(planFile));
    registration.set("plan", plan);
    registration.set("folds", folds);
    registration.put("fitEvidence", "not_run");
    final byte[] bytes = pretty(registration);
    writeNew(registrationFile, bytes);
    writeNew(
        checksum(registrationFile),
        (sha256(bytes) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
  }

  private static void preflight(Path registrationFile, Path sourceFile, Path resultFile) {
    refuseExisting(resultFile);
    final CheckedRegistration checked = check(registrationFile, sourceFile);
    final JsonNode registration = checked.registration();
    final ArrayNode rebuilt = checked.folds();

    final ObjectNode result = JSON.createObjectNode();
    result.put("status", "ready");
    result.put("protocolVersion", required(registration, "version").asString());
    result.put("registrationSha256", checked.registrationSha256());
    result.put("sourceSha256", digest(sourceFile));
    result.put("folds", rebuilt.size());
    result.put(
        "activeFolds",
        rebuilt.valueStream().filter(row -> row.get("active").booleanValue()).count());
    result.put("fitEvidence", "not_run");
    result.putArray("reasons");
    writeNew(resultFile, pretty(result));
  }

  private static CheckedRegistration check(Path registrationFile, Path sourceFile) {
    return check(registrationFile, sourceFile, true);
  }

  /**
   * A later command in the same run reads an evidence directory its own earlier command created, so
   * only the first evidence-producing command requires the registered location to be absent.
   */
  private static CheckedRegistration check(
      Path registrationFile, Path sourceFile, boolean freshEvidence) {
    final byte[] registrationBytes = bytes(registrationFile);
    final String expected = text(checksum(registrationFile)).trim();
    require(expected.equals(sha256(registrationBytes)), "Registration checksum mismatch");
    final JsonNode registration = tree(registrationBytes, registrationFile);
    require(
        FROZEN.equals(required(registration, "status").asString()), "Registration is not frozen");
    final JsonNode plan = required(registration, "plan");
    verifyProtocol(plan);
    verifyPlanIdentity(registration);
    verifySource(plan, sourceFile);
    verifyIdentities(plan);
    verifyImplementationCommit(plan);
    verifyEnvironment(plan);
    verifyOutputLocation(plan, freshEvidence);
    grid(plan);
    final ArrayNode rebuilt = manifests(plan, sourceFile);
    require(rebuilt.equals(required(registration, "folds")), "Eligibility row manifest mismatch");
    compareArchived(plan, rebuilt);
    verifyApprovedManifest(plan, rebuilt);
    verifyFrozenLocation(plan, registrationFile);
    return new CheckedRegistration(registration, plan, rebuilt, sha256(registrationBytes));
  }

  private static boolean tune(Path registrationFile, Path sourceFile, Path resultFile) {
    refuseExisting(resultFile);
    final CheckedRegistration checked = check(registrationFile, sourceFile);
    verifyEvidenceLocation(checked.plan(), resultFile);
    final List<PollCsv.Poll> polls = PollCsv.parse(bytes(sourceFile));
    final DevelopmentTuning.Grid grid = grid(checked.plan());
    final List<LocalDate> elections = dates(checked.plan(), "electionCycleDates");
    final Map<String, Roster.CoveragePeriod> periods = new LinkedHashMap<>();
    for (JsonNode declared : required(checked.plan(), "periods")) {
      final Roster.CoveragePeriod period = period(declared);
      periods.put(period.id(), period);
    }

    final ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", required(checked.registration(), "version").asString());
    result.put("registrationSha256", checked.registrationSha256());
    result.put("sourceSha256", digest(sourceFile));
    result.put("fitEvidence", "complete");
    final ObjectNode evidenceGrid = ((ObjectNode) required(checked.plan(), "grid")).deepCopy();
    evidenceGrid.put(
        "interpretation",
        "values below one are development-only underdispersion relative to nominal multinomial covariance");
    result.set("grid", evidenceGrid);
    final ArrayNode reasons = result.putArray("reasons");
    final ArrayNode folds = result.putArray("folds");
    boolean passed = true;
    for (JsonNode manifest : checked.folds()) {
      final ObjectNode foldResult = folds.addObject();
      copy(manifest, foldResult, "periodId", "cutoff", "scoreThrough", "active");
      foldResult.set("trainingObservationRows", manifest.get("trainingObservationRows"));
      foldResult.put(
          "trainingObservationRowsSha256",
          manifest.get("trainingObservationRowsSha256").asString());
      if (!manifest.get("active").booleanValue()) {
        foldResult.put("reason", required(manifest, "reason").asString());
        continue;
      }
      final String periodId = manifest.get("periodId").asString();
      final DevelopmentTuning.Fold fold =
          new DevelopmentTuning.Fold(
              LocalDate.parse(manifest.get("cutoff").asString()),
              LocalDate.parse(manifest.get("scoreThrough").asString()));
      final PollObservations.Batch training =
          PollObservations.prepare(periods.get(periodId), DevelopmentTuning.training(polls, fold));
      final ArrayNode methods = foldResult.putArray("methods");
      for (FittedMethod method : FittedMethod.values()) {
        final ObjectNode search = search(methods, method, training, elections, grid);
        if (!search.get("gatePassed").booleanValue()) {
          passed = false;
          for (JsonNode reason : search.get("reasons"))
            reasons.add(
                periodId + " " + fold.cutoff() + " " + method.id + ": " + reason.asString());
        }
      }
    }
    result.put("gatePassed", passed);
    result.put("status", passed ? "complete" : "blocked");
    writeNew(resultFile, pretty(result));
    return passed;
  }

  /**
   * Extends the tuned fits to the separately fitted coverage-period histories, their joint
   * component uncertainty and the comparable remainder drawn inside the same draws. Every
   * downstream number reads the frozen registration and this run's own tuning evidence, never a v1
   * fitted output.
   */
  private static boolean estimate(
      Path registrationFile, Path sourceFile, Path tuningFile, Path resultFile) {
    refuseExisting(resultFile);
    final CheckedRegistration checked = check(registrationFile, sourceFile, false);
    verifyEvidenceLocation(checked.plan(), resultFile);
    verifyEvidenceLocation(checked.plan(), tuningFile);
    final JsonNode tuningEvidence = read(tuningFile);
    verifyTuningProvenance(checked, sourceFile, tuningEvidence);
    final JsonNode selection = required(checked.plan(), "parameterSelection");
    final String method = required(selection, "method").asString();
    final DevelopmentTuning.Tuning tuning = tuned(checked, tuningEvidence, method);

    final List<PollCsv.Poll> polls = PollCsv.parse(bytes(sourceFile));
    final List<LocalDate> elections = dates(checked.plan(), "electionCycleDates");
    final List<Roster.CoveragePeriod> periods = new ArrayList<>();
    for (JsonNode declared : required(checked.plan(), "periods")) periods.add(period(declared));
    final CoverageValidation.Rules coverageRules = coverageRules(checked.plan());
    final JointUncertainty.Rules uncertaintyRules = uncertaintyRules(checked.plan());
    final List<Long> seeds = precisionSeeds(checked.plan(), uncertaintyRules);

    final ObjectNode result = JSON.createObjectNode();
    result.put("protocolVersion", required(checked.registration(), "version").asString());
    result.put("registrationSha256", checked.registrationSha256());
    result.put("sourceSha256", digest(sourceFile));
    result.put("fitEvidence", "complete");
    final ObjectNode provenance = result.putObject("tunedParameters");
    provenance.put("path", tuningFile.toString());
    provenance.put("sha256", digest(tuningFile));
    provenance.put("method", method);
    provenance.put("foldRule", required(selection, "fold").asString());
    provenance.put(
        "selection",
        "the registered fitted method and fold rule, resolved from this run's tuning evidence"
            + " rather than from any v1 fitted output");
    provenance.put("resolvedFolds", tuning.resolved().size());
    provenance.put("unresolvedFolds", tuning.unresolved().size());
    final ObjectNode selected = provenance.putObject("selectedParameters");
    DevelopmentTuning.latestParameters(tuning)
        .forEach((periodId, point) -> parameters(selected.putObject(periodId), point));

    final ArrayNode checks = result.putArray("checks");
    final ArrayNode reasons = result.putArray("reasons");
    for (String reason : tuning.gate().reasons()) reasons.add("development tuning: " + reason);

    final CoverageValidation.Report coverage =
        CoverageValidation.validateAll(periods, polls, elections, tuning, coverageRules);
    result.set("coverage", JSON.valueToTree(coverage));
    for (CoverageValidation.Validated validated : coverage.periods())
      for (String failure : validated.failures())
        reasons.add("coverage " + validated.periodId() + ": " + failure);
    final boolean anySupported =
        coverage.periods().stream()
            .anyMatch(validated -> validated.supported() && validatedPublishes(periods, validated));
    check(
        checks,
        "coverage_support",
        coverage.periods().stream().allMatch(CoverageValidation.Validated::supported)
            ? PASSED
            : FAILED,
        "registered observation, institute, gap, boundary-shift and stability limits");

    final JointUncertainty.Report uncertainty =
        JointUncertainty.report(periods, polls, elections, coverage, uncertaintyRules, seeds);
    result.set("uncertainty", JSON.valueToTree(uncertainty));
    if (!anySupported) {
      check(checks, "seeded_reproduction", UNEVALUATED, "no coverage period produced draws");
      check(
          checks, "interval_endpoint_precision", UNEVALUATED, "no coverage period produced draws");
      check(checks, "comparable_remainder", UNEVALUATED, "no coverage period produced draws");
      result.putArray("remainder");
      return finish(result, checks, reasons, resultFile);
    }
    reproduction(checks, reasons, uncertainty);
    precision(checks, reasons, uncertainty, maxSpread(checked.plan()));
    remainder(
        checks,
        reasons,
        result,
        periods,
        polls,
        elections,
        coverage,
        coverageRules,
        uncertaintyRules,
        maxSumError(checked.plan()));
    return finish(result, checks, reasons, resultFile);
  }

  private static boolean validatedPublishes(
      List<Roster.CoveragePeriod> periods, CoverageValidation.Validated validated) {
    return periods.stream()
        .anyMatch(period -> period.id().equals(validated.periodId()) && period.supportValidated());
  }

  private static boolean finish(
      ObjectNode result, ArrayNode checks, ArrayNode reasons, Path resultFile) {
    final boolean passed =
        reasons.isEmpty()
            && checks
                .valueStream()
                .allMatch(entry -> entry.get("status").asString().equals(PASSED));
    result.put("gatePassed", passed);
    result.put("status", passed ? "complete" : "blocked");
    writeNew(resultFile, pretty(result));
    return passed;
  }

  /** An exact rerun at the registered seed must return every retained draw unchanged. */
  private static void reproduction(
      ArrayNode checks, ArrayNode reasons, JointUncertainty.Report uncertainty) {
    double worst = 0;
    for (JointUncertainty.Published published : uncertainty.periods())
      worst = Math.max(worst, published.reproduced().maxAbsoluteDifference());
    final boolean exact =
        uncertainty.periods().stream().allMatch(published -> published.reproduced().exact());
    check(
        checks,
        "seeded_reproduction",
        exact ? PASSED : FAILED,
        "largest retained-draw difference of a rerun at the registered seed: " + worst + " points");
    if (!exact)
      reasons.add(
          "seeded reproduction moved a retained draw by "
              + worst
              + " points, so the run does not"
              + " reproduce");
  }

  /** Eight seeds bound how much of a published interval endpoint is sampling noise. */
  private static void precision(
      ArrayNode checks, ArrayNode reasons, JointUncertainty.Report uncertainty, double limit) {
    double worst = 0;
    boolean measured = false;
    for (JointUncertainty.Published published : uncertainty.periods())
      for (JointUncertainty.Precision entry : published.precision()) {
        measured = true;
        worst = Math.max(worst, Math.max(entry.lowerSpreadPoints(), entry.upperSpreadPoints()));
      }
    if (!measured) {
      check(
          checks,
          "interval_endpoint_precision",
          UNEVALUATED,
          "no repeated-seed run produced a comparable interval endpoint");
      reasons.add("interval endpoint precision was not measured, so it is not a passed check");
      return;
    }
    check(
        checks,
        "interval_endpoint_precision",
        worst <= limit ? PASSED : FAILED,
        "largest interval endpoint spread across the registered seeds: "
            + worst
            + " points against a "
            + limit
            + " point limit");
    if (worst > limit)
      reasons.add(
          "interval endpoint spread of " + worst + " points exceeds the registered " + limit);
  }

  /**
   * The comparable remainder combines its components inside each shared draw and only then takes
   * the mean and the quantiles, so a summed marginal endpoint never appears.
   */
  private static void remainder(
      ArrayNode checks,
      ArrayNode reasons,
      ObjectNode result,
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      CoverageValidation.Report coverage,
      CoverageValidation.Rules coverageRules,
      JointUncertainty.Rules uncertaintyRules,
      double limit) {
    final Map<String, CoverageValidation.Validated> evidence = new LinkedHashMap<>();
    for (CoverageValidation.Validated validated : coverage.periods())
      evidence.put(validated.periodId(), validated);
    final ArrayNode published = result.putArray("remainder");
    double worst = 0;
    boolean drawn = false;
    for (Roster.CoveragePeriod period : periods) {
      if (!period.supportValidated()) continue;
      final CoverageValidation.Validated validated = evidence.get(period.id());
      require(validated != null, "No recorded coverage evidence for " + period.id());
      final ObjectNode entry = published.addObject();
      entry.put("periodId", period.id());
      if (!validated.supported()) {
        entry.put("status", UNEVALUATED);
        entry.put("reason", "coverage evidence failed, so no remainder is drawn");
        continue;
      }
      final ComparableRemainder.Estimated estimated =
          ComparableRemainder.estimate(
              EstimateHistory.fitted(
                  period, polls, elections, validated.parameters(), coverageRules),
              period,
              List.of(),
              coverageRules,
              uncertaintyRules);
      drawn = true;
      worst = Math.max(worst, estimated.maxEndpointSumErrorPoints());
      entry.put("status", "drawn");
      entry.set("members", JSON.valueToTree(estimated.members()));
      entry.set("boundaries", JSON.valueToTree(estimated.boundaries()));
      entry.put("maxEndpointSumErrorPoints", estimated.maxEndpointSumErrorPoints());
      final ArrayNode segments = entry.putArray("segmentEdges");
      int days = 0;
      for (ComparableRemainder.Segment segment : estimated.segments()) {
        days += segment.days().size();
        for (ComparableRemainder.Day day :
            List.of(segment.days().getFirst(), segment.days().getLast()))
          segments.add(JSON.valueToTree(day));
        for (ComparableRemainder.Day day : segment.days()) verifyDrawnDay(period, day);
      }
      entry.put("estimatedDays", days);
    }
    if (!drawn) {
      check(
          checks, "comparable_remainder", UNEVALUATED, "no supported period produced a remainder");
      reasons.add("the comparable remainder was not drawn, so it is not a passed check");
      return;
    }
    check(
        checks,
        "comparable_remainder",
        worst <= limit ? PASSED : FAILED,
        "largest interval endpoint composition sum error: "
            + worst
            + " points against a "
            + limit
            + " point limit");
    if (worst > limit)
      reasons.add(
          "comparable remainder endpoint sum error of "
              + worst
              + " points exceeds the registered "
              + limit);
  }

  /** A drawn remainder day must stay a finite share of a composition. */
  private static void verifyDrawnDay(Roster.CoveragePeriod period, ComparableRemainder.Day day) {
    require(
        Double.isFinite(day.mean()) && day.mean() >= 0 && day.mean() <= 100,
        "Remainder outside the composition range on " + period.id() + " " + day.date());
    for (JointUncertainty.Interval interval : day.intervals())
      require(
          Double.isFinite(interval.lower())
              && Double.isFinite(interval.upper())
              && interval.lower() >= 0
              && interval.upper() <= 100
              && interval.lower() <= interval.upper(),
          "Remainder interval outside the composition range on " + period.id() + " " + day.date());
  }

  private static void check(ArrayNode checks, String name, String status, String detail) {
    final ObjectNode entry = checks.addObject();
    entry.put("check", name);
    entry.put("status", status);
    entry.put("detail", detail);
  }

  /** The tuning evidence must come from this registration, this source and this run. */
  private static void verifyTuningProvenance(
      CheckedRegistration checked, Path sourceFile, JsonNode tuning) {
    require(
        required(tuning, "protocolVersion")
            .asString()
            .equals(required(checked.registration(), "version").asString()),
        "Tuning evidence protocol version differs from the registration");
    require(
        required(tuning, "registrationSha256").asString().equals(checked.registrationSha256()),
        "Tuning evidence was produced against another registration");
    require(
        required(tuning, "sourceSha256").asString().equals(digest(sourceFile)),
        "Tuning evidence was produced against another source");
    require(
        required(tuning, "fitEvidence").asString().equals("complete"),
        "Tuning evidence carries no completed fits");
  }

  /**
   * Rebuilds the tuned fits from this run's evidence, keeping every registered fold disposition. An
   * unresolved or inactive fold is listed, never dropped, so the selection stays explicit.
   */
  private static DevelopmentTuning.Tuning tuned(
      CheckedRegistration checked, JsonNode evidence, String method) {
    final Map<String, JsonNode> registered = new LinkedHashMap<>();
    for (JsonNode manifest : checked.folds()) registered.put(key(manifest), manifest);
    final List<DevelopmentTuning.Resolved> resolved = new ArrayList<>();
    final List<DevelopmentTuning.Unresolved> unresolved = new ArrayList<>();
    for (JsonNode fold : required(evidence, "folds")) {
      final JsonNode manifest = registered.get(key(fold));
      require(manifest != null, "Tuning evidence holds an unregistered fold: " + key(fold));
      require(
          manifest.get("active").booleanValue() == required(fold, "active").booleanValue(),
          "Tuning evidence fold disposition differs from the registration: " + key(fold));
      require(
          manifest
              .get("trainingObservationRowsSha256")
              .equals(required(fold, "trainingObservationRowsSha256")),
          "Tuning evidence training rows differ from the registration: " + key(fold));
      final String periodId = required(fold, "periodId").asString();
      final DevelopmentTuning.Fold identity =
          new DevelopmentTuning.Fold(
              LocalDate.parse(required(fold, "cutoff").asString()),
              LocalDate.parse(required(fold, "scoreThrough").asString()));
      if (!fold.get("active").booleanValue()) {
        unresolved.add(
            new DevelopmentTuning.Unresolved(
                periodId, identity, required(fold, "reason").asString()));
        continue;
      }
      final JsonNode fitted = fitted(fold, method);
      if (!required(fitted, "numericallyAvailable").booleanValue()) {
        unresolved.add(
            new DevelopmentTuning.Unresolved(
                periodId, identity, "no parameter point resolved finitely"));
        continue;
      }
      resolved.add(
          new DevelopmentTuning.Resolved(
              periodId,
              identity,
              point(required(fitted, "selectedParameters")),
              required(fitted, "selectedLogLikelihood").doubleValue(),
              manifest.get("trainingRows").size(),
              manifest.get("trainingObservationRows").size(),
              manifest.get("trainingExclusions").size(),
              exclusionReasons(manifest),
              strings(fitted, "gridBoundaries")));
    }
    require(!resolved.isEmpty(), "Tuning evidence resolved no fold, so nothing can be estimated");
    final List<String> reasons = new ArrayList<>(strings(evidence, "reasons"));
    return new DevelopmentTuning.Tuning(
        required(evidence, "protocolVersion").asString(),
        grid(checked.plan()),
        new DevelopmentTuning.Gate(!reasons.isEmpty(), reasons),
        resolved,
        unresolved);
  }

  private static JsonNode fitted(JsonNode fold, String method) {
    for (JsonNode candidate : required(fold, "methods"))
      if (required(candidate, "method").asString().equals(method)) return candidate;
    throw new IllegalArgumentException(
        "Tuning evidence holds no " + method + " fit for " + key(fold));
  }

  private static Map<String, Integer> exclusionReasons(JsonNode manifest) {
    final Map<String, Integer> counts = new LinkedHashMap<>();
    for (JsonNode exclusion : required(manifest, "trainingExclusions"))
      for (JsonNode reason : required(exclusion, "reasons"))
        counts.merge(reason.asString(), 1, Integer::sum);
    return Map.copyOf(counts);
  }

  private static DailyStateSpace.Parameters point(JsonNode parameters) {
    return new DailyStateSpace.Parameters(
        required(parameters, "walkVariance").doubleValue(),
        required(parameters, "houseScale").doubleValue(),
        required(parameters, "covarianceMultiplier").doubleValue());
  }

  private static CoverageValidation.Rules coverageRules(JsonNode plan) {
    final JsonNode coverage = required(plan, "coverage");
    return new CoverageValidation.Rules(
        LocalDate.parse(required(coverage, "developmentThrough").asString()),
        required(coverage, "minObservations").intValue(),
        required(coverage, "minInstitutes").intValue(),
        required(coverage, "maxInternalGapDays").intValue(),
        integers(coverage, "boundaryShiftDays"),
        required(coverage, "stabilityBurnInDays").intValue(),
        required(coverage, "maxStabilityShiftPoints").doubleValue());
  }

  private static JointUncertainty.Rules uncertaintyRules(JsonNode plan) {
    final JsonNode uncertainty = required(plan, "uncertainty");
    return new JointUncertainty.Rules(
        required(required(plan, "seeds"), "master").longValue(),
        required(uncertainty, "draws").intValue(),
        doubles(uncertainty, "intervalLevels"),
        required(uncertainty, "precisionRepeats").intValue());
  }

  private static double maxSpread(JsonNode plan) {
    return required(required(plan, "uncertainty"), "maxIntervalEndpointSpreadPoints").doubleValue();
  }

  private static double maxSumError(JsonNode plan) {
    return required(required(plan, "uncertainty"), "maxEndpointSumErrorPoints").doubleValue();
  }

  /** The registered repeat seeds, which must be the declared ones rather than a chosen subset. */
  private static List<Long> precisionSeeds(JsonNode plan, JointUncertainty.Rules rules) {
    final List<Long> declared = new ArrayList<>();
    for (JsonNode seed : required(required(plan, "seeds"), "precision"))
      declared.add(seed.longValue());
    require(
        declared.equals(JointUncertainty.precisionSeeds(rules)),
        "Registered precision seeds must run from the master seed");
    return List.copyOf(declared);
  }

  private static ObjectNode search(
      ArrayNode methods,
      FittedMethod method,
      PollObservations.Batch training,
      List<LocalDate> elections,
      DevelopmentTuning.Grid grid) {
    final List<SearchAttempt> attempts =
        grid.points().parallelStream()
            .map(point -> attempt(method, training, elections, point))
            .toList();
    final ObjectNode result = methods.addObject();
    result.put("method", method.id);
    final ArrayNode evidence = result.putArray("attempts");
    int best = -1;
    final ArrayNode reasons = result.putArray("reasons");
    for (int index = 0; index < attempts.size(); index++) {
      final SearchAttempt attempt = attempts.get(index);
      final ObjectNode point = evidence.addObject();
      parameters(point.putObject("parameters"), attempt.parameters());
      if (attempt.reason() == null) {
        point.put("status", "resolved");
        point.put("logLikelihood", attempt.logLikelihood());
        if (best < 0 || attempt.logLikelihood() > attempts.get(best).logLikelihood()) best = index;
      } else {
        point.put("status", "failed");
        point.put("reason", attempt.reason());
        reasons.add(
            "required grid point failed at " + attempt.parameters() + ": " + attempt.reason());
      }
    }
    final ArrayNode boundaries = result.putArray("gridBoundaries");
    boolean available = best >= 0;
    if (available) {
      final SearchAttempt selected = attempts.get(best);
      parameters(result.putObject("selectedParameters"), selected.parameters());
      result.put("selectedLogLikelihood", selected.logLikelihood());
      boundary(
          boundaries,
          "walkVariance",
          grid.walkVariances().indexOf(selected.parameters().walkVariance()),
          grid.walkVariances().size());
      boundary(
          boundaries,
          "houseScale",
          grid.houseScales().indexOf(selected.parameters().houseScale()),
          grid.houseScales().size());
      boundary(
          boundaries,
          "covarianceMultiplier",
          grid.covarianceMultipliers().indexOf(selected.parameters().covarianceMultiplier()),
          grid.covarianceMultipliers().size());
      try {
        confirm(method, training, elections, selected);
      } catch (RuntimeException e) {
        available = false;
        reasons.add("selected fit could not be retained: " + e.getMessage());
      }
      if (!boundaries.isEmpty()) reasons.add("selected parameters sit on a grid boundary");
    } else {
      reasons.add("no parameter point resolved finitely");
    }
    result.put("numericallyAvailable", available);
    result.put("gatePassed", available && reasons.isEmpty());
    return result;
  }

  private static SearchAttempt attempt(
      FittedMethod method,
      PollObservations.Batch training,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters) {
    try {
      final double likelihood = likelihood(method, training, elections, parameters);
      if (!Double.isFinite(likelihood))
        return new SearchAttempt(parameters, null, "nonfinite marginal likelihood");
      return new SearchAttempt(parameters, likelihood, null);
    } catch (RuntimeException e) {
      return new SearchAttempt(
          parameters, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private static double likelihood(
      FittedMethod method,
      PollObservations.Batch training,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters) {
    return method == FittedMethod.MIDPOINT_CANDIDATE
        ? DailyStateSpace.logLikelihood(training, elections, parameters)
        : WindowFilter.logLikelihood(
            training, elections, parameters, WindowFilter.Convention.FIELDWORK);
  }

  private static void confirm(
      FittedMethod method,
      PollObservations.Batch training,
      List<LocalDate> elections,
      SearchAttempt selected) {
    final double retained =
        method == FittedMethod.MIDPOINT_CANDIDATE
            ? DailyStateSpace.fit(training, elections, selected.parameters()).logLikelihood()
            : WindowFilter.score(
                    training,
                    List.of(),
                    elections,
                    selected.parameters(),
                    WindowFilter.Convention.FIELDWORK)
                .logLikelihood();
    require(
        Double.compare(retained, selected.logLikelihood()) == 0,
        "retained fit disagrees with its tuning likelihood");
  }

  private static void parameters(ObjectNode target, DailyStateSpace.Parameters parameters) {
    target.put("walkVariance", parameters.walkVariance());
    target.put("houseScale", parameters.houseScale());
    target.put("covarianceMultiplier", parameters.covarianceMultiplier());
  }

  private static void boundary(ArrayNode target, String name, int index, int size) {
    if (index == 0) target.add(name + ":lower");
    if (index == size - 1) target.add(name + ":upper");
  }

  private static void copy(JsonNode source, ObjectNode target, String... fields) {
    for (String field : fields) target.set(field, required(source, field));
  }

  private static ArrayNode manifests(JsonNode plan, Path sourceFile) {
    final List<PollCsv.Poll> polls = PollCsv.parse(bytes(sourceFile));
    final List<DevelopmentTuning.Fold> folds = folds(plan);
    final ArrayNode manifests = JSON.createArrayNode();
    for (JsonNode declared : required(plan, "periods")) {
      final Roster.CoveragePeriod period = period(declared);
      final LocalDate activeFrom = LocalDate.parse(required(declared, "activeFrom").asString());
      final LocalDate activeThrough =
          LocalDate.parse(required(declared, "activeThrough").asString());
      for (DevelopmentTuning.Fold fold : folds) {
        final boolean active =
            !fold.cutoff().isBefore(activeFrom) && !fold.cutoff().isAfter(activeThrough);
        manifests.add(manifest(period, polls, fold, active));
      }
    }
    return manifests;
  }

  private static ObjectNode manifest(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      DevelopmentTuning.Fold fold,
      boolean active) {
    final List<PollCsv.Poll> training = DevelopmentTuning.training(polls, fold);
    final PollObservations.Batch preparedTraining = PollObservations.prepare(period, training);
    final List<PollCsv.Poll> heldOut =
        DevelopmentDiagnostics.heldOut(
            polls,
            fold,
            new DevelopmentDiagnostics.Rules(
                1,
                35,
                10,
                List.of(3),
                List.of(1),
                List.of(1),
                List.of(0.9, 0.98),
                List.of(0.4, 0.6)));
    final PollObservations.Batch preparedHeldOut = PollObservations.prepare(period, heldOut);
    if (active) {
      require(
          !preparedTraining.observations().isEmpty(),
          "Unexpected empty active training fold " + period.id() + " " + fold.cutoff());
      require(
          !preparedHeldOut.observations().isEmpty(),
          "Unexpected empty active scoring fold " + period.id() + " " + fold.cutoff());
    }
    final ObjectNode row = JSON.createObjectNode();
    row.put("periodId", period.id());
    row.put("cutoff", fold.cutoff().toString());
    row.put("scoreThrough", fold.scoreThrough().toString());
    row.put("active", active);
    if (!active)
      row.put(
          "reason",
          preparedTraining.observations().isEmpty()
              ? "no_eligible_training_observation"
              : "no_held_out_composition");
    rows(row.putArray("trainingRows"), training);
    row.put("trainingRowsSha256", rowsSha256(training));
    final List<PollCsv.Poll> trainingObservations =
        preparedTraining.observations().stream().map(PollObservations.Observation::poll).toList();
    rows(row.putArray("trainingObservationRows"), trainingObservations);
    row.put("trainingObservationRowsSha256", rowsSha256(trainingObservations));
    exclusions(row.putArray("trainingExclusions"), preparedTraining.exclusions());
    rows(row.putArray("scoringCandidateRows"), heldOut);
    final List<PollCsv.Poll> scoring =
        preparedHeldOut.observations().stream().map(PollObservations.Observation::poll).toList();
    rows(row.putArray("scoringRows"), scoring);
    row.put("scoringRowsSha256", rowsSha256(scoring));
    exclusions(row.putArray("scoringExclusions"), preparedHeldOut.exclusions());
    return row;
  }

  private static void rows(ArrayNode target, List<PollCsv.Poll> polls) {
    for (PollCsv.Poll poll : polls) target.add(poll.rowNumber());
  }

  private static void exclusions(ArrayNode target, List<PollObservations.Exclusion> exclusions) {
    for (PollObservations.Exclusion exclusion : exclusions) {
      final ObjectNode row = target.addObject();
      row.put("row", exclusion.rowNumber());
      final ArrayNode reasons = row.putArray("reasons");
      for (String reason : exclusion.reasons()) reasons.add(reason);
    }
  }

  private static Roster.CoveragePeriod period(JsonNode declared) {
    final boolean individualFi = required(declared, "individualFi").booleanValue();
    final List<String> roster =
        individualFi
            ? List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI")
            : List.of("S", "M", "SD", "V", "C", "KD", "L", "MP");
    final JsonNode to = required(declared, "to");
    return new Roster.CoveragePeriod(
        required(declared, "id").asString(),
        LocalDate.parse(required(declared, "from").asString()),
        to.isNull() ? null : LocalDate.parse(to.asString()),
        roster,
        individualFi,
        !individualFi,
        "development-validation-registration");
  }

  private static List<DevelopmentTuning.Fold> folds(JsonNode plan) {
    final List<DevelopmentTuning.Fold> folds = new ArrayList<>();
    final JsonNode declaredFolds;
    final boolean inherited;
    if (plan.has("folds")) {
      declaredFolds = required(plan, "folds");
      inherited = false;
    } else {
      declaredFolds =
          required(read(Path.of(required(plan, "foldsFrom").asString())), "development_folds");
      inherited = true;
    }
    for (JsonNode declared : declaredFolds) {
      final DevelopmentTuning.Fold fold =
          new DevelopmentTuning.Fold(
              LocalDate.parse(required(declared, "cutoff").asString()),
              LocalDate.parse(
                  required(declared, inherited ? "score_through" : "scoreThrough").asString()));
      require(
          fold.scoreThrough().equals(fold.cutoff().plusDays(35)),
          "Every fold must use the registered 35-day horizon");
      folds.add(fold);
    }
    require(!folds.isEmpty(), "No development folds registered");
    return List.copyOf(folds);
  }

  private static DevelopmentTuning.Grid grid(JsonNode plan) {
    final JsonNode grid = required(plan, "grid");
    return new DevelopmentTuning.Grid(
        doubles(grid, "walkVariances"),
        doubles(grid, "houseScales"),
        doubles(grid, "covarianceMultipliers"));
  }

  private static void verifyProtocol(JsonNode plan) {
    final String version = required(plan, "version").asString();
    if (!VERSION.equals(version)) {
      require(
          version.startsWith("test-") && required(plan, "testFixture").booleanValue(),
          "Unsupported development protocol version");
      return;
    }
    require(
        required(plan, "source")
            .get("sha256")
            .asString()
            .equals("27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608"),
        "Approved source identity mismatch");
    require(
        required(required(plan, "authority"), "proposal")
            .asString()
            .equals("v2-development-1-proposal-1"),
        "Approved authority mismatch");
    required(plan, "implementationCommit");
    require(
        strings(plan, "electionCycleDates")
            .equals(List.of("2010-09-19", "2014-09-14", "2018-09-09", "2022-09-11")),
        "Approved election cycle dates mismatch");
    final JsonNode seeds = required(plan, "seeds");
    require(required(seeds, "master").intValue() == 20260908, "Approved master seed mismatch");
    require(
        integers(seeds, "precision")
            .equals(
                List.of(
                    20260908, 20260909, 20260910, 20260911, 20260912, 20260913, 20260914,
                    20260915)),
        "Approved precision seeds mismatch");
    require(
        strings(plan, "commands")
            .equals(List.of(PREPARE_COMMAND, PREFLIGHT_COMMAND, TUNE_COMMAND, ESTIMATE_COMMAND)),
        "Approved commands mismatch");
    final JsonNode coverage = required(plan, "coverage");
    require(
        required(coverage, "developmentThrough").asString().equals("2021-10-05")
            && required(coverage, "minObservations").intValue() == 30
            && required(coverage, "minInstitutes").intValue() == 5
            && required(coverage, "maxInternalGapDays").intValue() == 45
            && integers(coverage, "boundaryShiftDays").equals(List.of(7, 14, 30))
            && required(coverage, "stabilityBurnInDays").intValue() == 60
            && Double.compare(required(coverage, "maxStabilityShiftPoints").doubleValue(), 0.5)
                == 0,
        "Approved coverage limits mismatch");
    final JsonNode selection = required(plan, "parameterSelection");
    require(
        required(selection, "method").asString().equals(APPROVED_METHOD)
            && required(selection, "fold").asString().equals(APPROVED_FOLD_RULE),
        "Approved parameter selection mismatch");
    final JsonNode uncertainty = required(plan, "uncertainty");
    require(
        required(uncertainty, "draws").intValue() == 10000
            && doubles(uncertainty, "intervalLevels").equals(List.of(0.5, 0.95))
            && required(uncertainty, "precisionRepeats").intValue() == 8
            && Double.compare(
                    required(uncertainty, "maxIntervalEndpointSpreadPoints").doubleValue(), 0.12)
                == 0
            && Double.compare(
                    required(uncertainty, "maxEndpointSumErrorPoints").doubleValue(), 1e-9)
                == 0,
        "Approved uncertainty limits mismatch");
    require(
        required(plan, "outputLocation")
            .asString()
            .equals("docs/validation/v2-development-1/evidence/run-1"),
        "Approved output location mismatch");
    require(
        required(plan, "foldsFrom").asString().equals("docs/validation/protocol.json"),
        "Approved fold source mismatch");
    require(
        required(plan, "archivedDiagnostics").asString().equals("docs/validation/diagnostics.json"),
        "Approved diagnostic source mismatch");
    final Set<String> identities = new HashSet<>();
    for (JsonNode identity : required(plan, "identities")) {
      identities.add(required(identity, "path").asString());
    }
    for (String path :
        List.of(
            "src/main/java/se/swedishpolls/estimation/DevelopmentValidation.java",
            "src/main/java/se/swedishpolls/estimation/DevelopmentTuning.java",
            "src/main/java/se/swedishpolls/estimation/DevelopmentDiagnostics.java",
            "src/main/java/se/swedishpolls/estimation/PollObservations.java",
            "src/main/java/se/swedishpolls/estimation/DailyStateSpace.java",
            "src/main/java/se/swedishpolls/estimation/WindowFilter.java",
            "src/main/java/se/swedishpolls/estimation/CoverageValidation.java",
            "src/main/java/se/swedishpolls/estimation/EstimateHistory.java",
            "src/main/java/se/swedishpolls/estimation/JointUncertainty.java",
            "src/main/java/se/swedishpolls/estimation/ComparableRemainder.java",
            "docs/validation/protocol.json",
            "docs/validation/diagnostics.json",
            "docs/validation/coverage.json",
            "docs/validation/tuning.json",
            "docs/validation/history.json",
            "docs/validation/uncertainty.json",
            "docs/validation/remainder.json",
            "docs/validation/seats.json",
            "docs/validation/development-gates.json",
            "docs/validation/release-audit.json",
            "src/main/resources/publication/model-freeze.json")) {
      require(identities.contains(path), "Missing required identity " + path);
    }
    final JsonNode gates = required(plan, "gateDefinitions");
    require(
        required(gates, "protocol").asString().equals("docs/validation/protocol.json")
            && required(gates, "development")
                .asString()
                .equals("docs/validation/development-gates.json")
            && required(gates, "proposal")
                .asString()
                .equals("docs/validation/remediation-protocol-v2-proposal.md"),
        "Approved gate definitions mismatch");
    final Map<String, String> environment =
        Map.ofEntries(
            Map.entry("javaVersion", "25.0.4"),
            Map.entry("osName", "Linux"),
            Map.entry("osArch", "amd64"),
            Map.entry("mavenVersion", "3.9.16"),
            Map.entry("nodeVersion", "24.13.1"),
            Map.entry("npmVersion", "11.8.0"),
            Map.entry("ejmlVersion", "0.46.1"),
            Map.entry(
                "mavenCoreSha256",
                "5d45c72e3dbfab8b68d15ad4f12777b7d9b5fe4d4adc99c3bd51fb9641fab009"),
            Map.entry(
                "nodeSha256", "d95de52ccb76fb2c5775bf176f29f3025e4b19352c092aad51ce5d930717359f"),
            Map.entry(
                "npmCliSha256", "8e5f6f3429f8cdbe693cdc29904e9d5a7b127a494bd15c804bd54c7403bfcbe7"),
            Map.entry(
                "ejmlJarSha256",
                "9f5dc17cde87c00570278aae3d9b2efcdd5b5e058728d6929ae892816c419f25"),
            Map.entry(
                "postgresImage",
                "postgres:18.4@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636"),
            Map.entry(
                "runtimeImage",
                "eclipse-temurin@sha256:b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e"));
    for (Map.Entry<String, String> entry : environment.entrySet()) {
      require(
          required(required(plan, "environment"), entry.getKey())
              .asString()
              .equals(entry.getValue()),
          "Approved environment identity mismatch: " + entry.getKey());
    }
    require(
        doubles(required(plan, "grid"), "walkVariances")
            .equals(List.of(0.000003, 0.00001, 0.00003, 0.0001, 0.0003, 0.001)),
        "Approved walk-variance grid mismatch");
    require(
        doubles(required(plan, "grid"), "houseScales").equals(List.of(0.01, 0.02, 0.05, 0.1, 0.2)),
        "Approved house-scale grid mismatch");
    require(
        doubles(required(plan, "grid"), "covarianceMultipliers")
            .equals(List.of(0.5, 0.75, 1.0, 1.5, 2.0, 3.0)),
        "Approved covariance grid mismatch");
  }

  private static void verifyApprovedManifest(JsonNode plan, ArrayNode manifests) {
    if (!VERSION.equals(required(plan, "version").asString())) return;
    require(manifests.size() == 96, "Approved protocol must contain 96 fold entries");
    final long active =
        manifests.valueStream().filter(fold -> fold.get("active").booleanValue()).count();
    require(active == 75, "Approved protocol must contain 75 active fold entries");
    final long missingTraining =
        manifests
            .valueStream()
            .filter(fold -> fold.has("reason"))
            .filter(
                fold -> fold.get("reason").asString().equals("no_eligible_training_observation"))
            .count();
    final long missingComposition =
        manifests
            .valueStream()
            .filter(fold -> fold.has("reason"))
            .filter(fold -> fold.get("reason").asString().equals("no_held_out_composition"))
            .count();
    require(missingTraining == 2, "Approved protocol must contain two missing-training folds");
    require(
        missingComposition == 19, "Approved protocol must contain 19 missing-composition folds");
  }

  private static List<Double> doubles(JsonNode parent, String field) {
    final List<Double> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.doubleValue());
    return List.copyOf(values);
  }

  private static List<String> strings(JsonNode parent, String field) {
    final List<String> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.asString());
    return List.copyOf(values);
  }

  private static List<Integer> integers(JsonNode parent, String field) {
    final List<Integer> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(value.intValue());
    return List.copyOf(values);
  }

  private static List<LocalDate> dates(JsonNode parent, String field) {
    final List<LocalDate> values = new ArrayList<>();
    for (JsonNode value : required(parent, field)) values.add(LocalDate.parse(value.asString()));
    return List.copyOf(values);
  }

  private static void compareArchived(JsonNode plan, ArrayNode manifests) {
    final JsonNode archivedPath = plan.get("archivedDiagnostics");
    if (archivedPath == null || archivedPath.isNull()) return;
    final JsonNode archived = read(Path.of(archivedPath.asString()));
    final Map<String, JsonNode> expected = new LinkedHashMap<>();
    for (JsonNode fold : required(archived, "folds")) expected.put(key(fold), fold);
    for (JsonNode manifest : manifests) {
      if (!manifest.get("active").booleanValue()) continue;
      final JsonNode old = expected.get(key(manifest));
      require(old != null, "Active fold missing from archived diagnostics: " + key(manifest));
      require(
          old.get("trainingRowsSha256").equals(manifest.get("trainingRowsSha256")),
          "Active training rows differ from archived diagnostics: " + key(manifest));
      require(
          old.get("scoredRowsSha256").equals(manifest.get("scoringRowsSha256")),
          "Active scoring rows differ from archived diagnostics: " + key(manifest));
    }
  }

  private static String key(JsonNode fold) {
    return required(fold, "periodId").asString() + "|" + required(fold, "cutoff").asString();
  }

  private static void verifyPlanIdentity(JsonNode registration) {
    final JsonNode identity = required(registration, "planIdentity");
    verifyIdentity(identity, "Plan");
  }

  private static void verifySource(JsonNode plan, Path supplied) {
    final JsonNode source = required(plan, "source");
    final Path registered = Path.of(required(source, "path").asString());
    require(
        registered.toAbsolutePath().normalize().equals(supplied.toAbsolutePath().normalize()),
        "Supplied source path differs from registration");
    require(
        required(source, "sha256").asString().equals(digest(supplied)), "Source SHA-256 mismatch");
  }

  private static void verifyIdentities(JsonNode plan) {
    for (JsonNode identity : required(plan, "identities")) verifyIdentity(identity, "Input");
  }

  private static void verifyImplementationCommit(JsonNode plan) {
    if (!VERSION.equals(required(plan, "version").asString())) return;
    final String commit = required(plan, "implementationCommit").asString();
    require(commit.matches("[0-9a-f]{40}"), "Invalid implementation commit identity");
  }

  private static void verifyIdentity(JsonNode identity, String kind) {
    final Path path = Path.of(required(identity, "path").asString());
    require(Files.isRegularFile(path), kind + " identity is missing: " + path);
    require(
        required(identity, "sha256").asString().equals(digest(path)),
        kind + " identity mismatch: " + path);
  }

  private static void verifyEnvironment(JsonNode plan) {
    final JsonNode environment = required(plan, "environment");
    environment(environment, "javaVersion", "java.version");
    environment(environment, "osName", "os.name");
    environment(environment, "osArch", "os.arch");
    if (!VERSION.equals(required(plan, "version").asString())) return;
    final Path mavenDistribution =
        Path.of(
            System.getProperty("user.home"),
            ".m2",
            "wrapper",
            "dists",
            "apache-maven-" + required(environment, "mavenVersion").asString());
    require(
        containsDigest(
            mavenDistribution,
            "maven-core-" + required(environment, "mavenVersion").asString() + ".jar",
            required(environment, "mavenCoreSha256").asString()),
        "Environment identity mismatch: mavenVersion");
    require(
        digest(Path.of("target/node/node")).equals(required(environment, "nodeSha256").asString()),
        "Environment identity mismatch: nodeVersion");
    require(
        digest(Path.of("target/node/node_modules/npm/bin/npm-cli.js"))
            .equals(required(environment, "npmCliSha256").asString()),
        "Environment identity mismatch: npmVersion");
    final Path ejml =
        Path.of(
            URI.create(
                SimpleMatrix.class.getProtectionDomain().getCodeSource().getLocation().toString()));
    require(
        digest(ejml).equals(required(environment, "ejmlJarSha256").asString()),
        "Environment identity mismatch: ejmlVersion");
    require(
        text(Path.of("compose.yaml"))
            .contains("image: " + required(environment, "postgresImage").asString()),
        "Environment identity mismatch: postgresImage");
    require(
        text(Path.of("pom.xml"))
            .contains("<runtime.image>" + required(environment, "runtimeImage").asString()),
        "Environment identity mismatch: runtimeImage");
  }

  private static void environment(JsonNode registered, String field, String property) {
    require(
        required(registered, field).asString().equals(System.getProperty(property)),
        "Environment identity mismatch: " + field);
  }

  private static void verifyOutputLocation(JsonNode plan, boolean fresh) {
    final Path output =
        Path.of(required(plan, "outputLocation").asString()).toAbsolutePath().normalize();
    if (fresh)
      require(!Files.exists(output), "Registered evidence output already exists: " + output);
    for (JsonNode protectedLocation : required(plan, "protectedLocations")) {
      final Path protectedPath = Path.of(protectedLocation.asString()).toAbsolutePath().normalize();
      require(
          !output.startsWith(protectedPath) && !protectedPath.startsWith(output),
          "Registered evidence output overlaps protected path " + protectedPath);
    }
  }

  private static void verifyEvidenceLocation(JsonNode plan, Path result) {
    final Path evidence =
        Path.of(required(plan, "outputLocation").asString()).toAbsolutePath().normalize();
    final Path parent = result.toAbsolutePath().normalize().getParent();
    require(evidence.equals(parent), "Run evidence must use the registered output location");
  }

  private static String rowsSha256(List<PollCsv.Poll> polls) {
    final StringBuilder rows = new StringBuilder();
    for (PollCsv.Poll poll : polls)
      rows.append(poll.rowNumber())
          .append(':')
          .append(sha256(String.join(",", poll.raw().values()).getBytes(StandardCharsets.UTF_8)))
          .append('\n');
    return sha256(rows.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void rejected(Path output, String reason) {
    if (Files.exists(output)) return;
    final ObjectNode result = JSON.createObjectNode();
    result.put("status", "rejected");
    result.putArray("reasons").add(reason == null ? "Validation failed" : reason);
    result.put("fitEvidence", "not_run");
    try {
      writeNew(output, pretty(result));
    } catch (RuntimeException ignored) {
      // A rejection must never overwrite or obscure the original failure.
    }
  }

  private static void refuseExisting(Path path) {
    require(!Files.exists(path), "Output already exists: " + path);
  }

  private static void verifyFrozenLocation(JsonNode plan, Path registration) {
    if (!VERSION.equals(required(plan, "version").asString())) return;
    final Path frozen =
        Path.of("docs/validation/v2-development-1/registration.json").toAbsolutePath().normalize();
    require(
        registration.toAbsolutePath().normalize().equals(frozen),
        "Production preflight requires the committed registration location");
  }

  private static boolean containsDigest(Path directory, String filename, String expected) {
    try {
      try (final java.util.stream.Stream<Path> files = Files.walk(directory, 4)) {
        return files
            .filter(Files::isRegularFile)
            .filter(path -> path.endsWith(filename))
            .anyMatch(path -> digest(path).equals(expected));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path checksum(Path registration) {
    return registration.resolveSibling(registration.getFileName() + ".sha256");
  }

  private static JsonNode required(JsonNode parent, String field) {
    final JsonNode value = parent == null ? null : parent.get(field);
    if (value == null) throw new IllegalArgumentException("Missing registration field " + field);
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  private static JsonNode read(Path file) {
    return tree(bytes(file), file);
  }

  private static JsonNode tree(byte[] bytes, Path file) {
    try {
      return JSON.readTree(bytes);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid JSON in " + file, e);
    }
  }

  private static byte[] pretty(JsonNode value) {
    return (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)
            + System.lineSeparator())
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(Path file) {
    try {
      return Files.readAllBytes(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String text(Path file) {
    return new String(bytes(file), StandardCharsets.UTF_8);
  }

  private static String digest(Path file) {
    return sha256(bytes(file));
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeNew(Path path, byte[] bytes) {
    try {
      final Path parent = path.toAbsolutePath().normalize().getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private enum FittedMethod {
    MIDPOINT_CANDIDATE("midpoint_candidate"),
    ILR_WINDOW_REFERENCE("ilr_window_reference");

    private final String id;

    FittedMethod(String id) {
      this.id = id;
    }
  }

  private record SearchAttempt(
      DailyStateSpace.Parameters parameters, Double logLikelihood, String reason) {}

  private record CheckedRegistration(
      JsonNode registration, JsonNode plan, ArrayNode folds, String registrationSha256) {}
}
