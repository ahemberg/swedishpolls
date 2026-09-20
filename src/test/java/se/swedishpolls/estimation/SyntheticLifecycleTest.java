package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The operator boundary of the registered lifecycle: what a registration has to pin, what preflight
 * measures and projects, what the watchdog does at the cap, and what reproduction finds when the
 * retained evidence has moved.
 *
 * <p>Every run here is a software check on two datasets. Nothing waits for the 24-hour cap: an
 * expired deadline is supplied through the preflight record the execution identity is committed
 * from. No formal experiment is invoked.
 */
class SyntheticLifecycleTest {
  private static final int DATASETS = 4;
  private static final int DRAWS = 8;

  /**
   * Two workers, so the parallel path is the one under test everywhere here, and a preflight window
   * small enough that the whole lifecycle stays a software check rather than a wait.
   */
  private static final int CORES = Math.min(2, Runtime.getRuntime().availableProcessors());

  private static final int WORKERS = CORES;
  private static final int SUB_WINDOW = 1;
  private static final List<String> CONVENTIONS = List.of("midpoint", "ilr_window");
  private static final int WINDOW = 2;
  private static final int WARM_UP_LIMIT = 2;
  private static final int SCORING_POLLS = 25;
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path temp;

  private static Path registration;
  private static Path preflightRecord;
  private static Path execution;
  private static Path evidence;
  private static Path reproduction;

  @BeforeAll
  static void runTheRegisteredLifecycle() {
    final Path output = temp.resolve("run/evidence");
    registration = temp.resolve("run/registration.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "register",
            write(temp.resolve("run/plan.json"), plan(output, temp.resolve("run/preflight")))
                .toString(),
            registration.toString()));
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "preflight", registration.toString(), temp.resolve("run/preflight").toString()));
    preflightRecord = temp.resolve("run/preflight/preflight.json");
    execution = temp.resolve("run/execution.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "commit", registration.toString(), preflightRecord.toString(), execution.toString()));
    evidence = output;
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run("experiment", execution.toString(), evidence.toString()));
    reproduction = temp.resolve("run/reproduction.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "reproduce", execution.toString(), evidence.toString(), reproduction.toString()));
  }

  @Test
  void refusesEveryRegistrationIdentityThatMovedAwayFromTheAcceptedProtocol() {
    assertRegistrationRejected(
        "protocol", plan -> field(plan, "protocol").put("sha256", "0".repeat(64)));
    assertRegistrationRejected("commit", plan -> plan.put("implementationCommit", "not-a-commit"));
    assertRegistrationRejected(
        "dependency", plan -> field(plan.get("dependencies").get(0)).put("sha256", "0".repeat(64)));
    assertRegistrationRejected(
        "toolchain", plan -> field(plan, "toolchain").put("javaVersion", "0.0.1"));
    assertRegistrationRejected("host", plan -> field(plan, "host").put("hostname", "other-host"));
    assertRegistrationRejected("workers", plan -> field(plan, "host").put("workers", CORES + 1));
    assertRegistrationRejected(
        "cores",
        plan ->
            field(plan, "host")
                .put("physicalCores", Runtime.getRuntime().availableProcessors() + 1));
    assertRegistrationRejected(
        "window", plan -> field(plan, "limits").put("preflightWindowDatasets", 1));
    assertRegistrationRejected("covariance", plan -> plan.put("covarianceSha256", "0".repeat(64)));
    assertRegistrationRejected("schedule", plan -> field(plan, "schedule").put("weeks", 27));
    assertRegistrationRejected(
        "streams", plan -> field(plan, "streams").put("predictive", "predictions"));
    assertRegistrationRejected(
        "tolerance", plan -> field(plan, "numericalTolerances").put("interval", -1));
    assertRegistrationRejected("limits", plan -> field(plan, "limits").put("wallClockHours", 48));
    assertRegistrationRejected("commands", plan -> field(plan, "commands").put("reproduce", " "));
    assertRegistrationRejected(
        "protected",
        plan -> field(plan.get("protectedLocations").get(0)).put("sha256", "0".repeat(64)));
    assertRegistrationRejected(
        "grid", plan -> field(plan, "run").put("gridPoints", SyntheticSearch.POINTS - 1));
  }

  @Test
  void requiresTheCommittedRegistrationBeforePreflightAndTheCommittedIdentityBeforeGeneration() {
    // A formal registration is refused anywhere but its committed location, before any fit runs.
    final Path formalPlan = temp.resolve("formal/plan.json");
    final ObjectNode formal = plan(temp.resolve("formal/evidence"), temp.resolve("formal/pre"));
    formal.put("phase", "formal");
    field(formal, "streams").put("masterSeed", 20260916);
    field(formal, "run").put("datasets", 10000);
    field(formal, "run").put("draws", 4000);
    field(formal, "container").put("image", runtimeImage());
    // A formal registration carries the registered preflight window, not a software check's.
    field(formal, "limits")
        .put("preflightSubWindowDatasets", SyntheticRecovery.PREFLIGHT_SUB_WINDOW_DATASETS)
        .put("preflightWindowDatasets", SyntheticRecovery.PREFLIGHT_WINDOW_DATASETS)
        .put("preflightWarmUpLimit", SyntheticRecovery.PREFLIGHT_WARM_UP_LIMIT);
    final Path formalRegistration = temp.resolve("formal/registration.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "register", write(formalPlan, formal).toString(), formalRegistration.toString()));
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "preflight", formalRegistration.toString(), temp.resolve("formal/pre").toString()));
    assertFalse(Files.exists(temp.resolve("formal/pre")), "No preflight dataset was generated");

    // A registration whose checksum no longer covers it is refused at the same boundary.
    final Path tampered = temp.resolve("tampered/registration.json");
    copy(registration, tampered);
    copy(SyntheticRegistration.checksum(registration), SyntheticRegistration.checksum(tampered));
    final ObjectNode moved = (ObjectNode) read(tampered);
    field(field(moved, "plan"), "numericalTolerances").put("interval", 1e-3);
    write(tampered, moved);
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "preflight", tampered.toString(), temp.resolve("tampered/pre").toString()));

    // An execution identity is only committed from a feasible preflight of its own registration.
    final ObjectNode infeasible = (ObjectNode) read(preflightRecord);
    infeasible.put("feasibility", "infeasible");
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "commit",
            registration.toString(),
            write(temp.resolve("blocked/preflight.json"), infeasible).toString(),
            temp.resolve("blocked/execution.json").toString()));

    // A run reads the identity back with its own checksum before it generates anything.
    final Path forged = temp.resolve("forged/execution.json");
    copy(execution, forged);
    copy(SyntheticRegistration.checksum(execution), SyntheticRegistration.checksum(forged));
    final ObjectNode identity = (ObjectNode) read(forged);
    identity.put("implementationCommit", "f".repeat(40));
    write(forged, identity);
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "experiment", forged.toString(), temp.resolve("forged/evidence").toString()));
  }

  @Test
  void retainsTheFixedCovarianceAndItsNoiseFactorAsNumbersTheDatasetEvidenceCanBeCheckedAgainst() {
    final JsonNode covariance = read(registration).get("covariance");
    assertEquals(SyntheticRegistration.covarianceSha256(), covariance.get("sha256").asString());

    final ModelValues expected = SyntheticScenario.observationCovariance();
    final JsonNode retained = covariance.get("observationCovariance");
    assertEquals(expected.getNumRows(), retained.size());
    for (int r = 0; r < expected.getNumRows(); r++)
      for (int c = 0; c < expected.getNumCols(); c++)
        assertEquals(expected.get(r, c), retained.get(r).get(c).doubleValue());

    final double[][] factor = SyntheticScenario.noiseFactor();
    final JsonNode retainedFactor = covariance.get("noiseFactor");
    assertEquals(factor.length, retainedFactor.size());
    for (int r = 0; r < factor.length; r++)
      for (int c = 0; c < factor[r].length; c++)
        assertEquals(factor[r][c], retainedFactor.get(r).get(c).doubleValue());

    // The point of retaining it: the matrix a dataset generated through is comparable to the
    // registration without recomputing it from the implementation under test.
    assertEquals(
        retained, read(evidence.resolve("datasets/midpoint/0.json")).get("observationCovariance"));
  }

  @Test
  void refusesARegistrationWhoseRetainedCovarianceMovedOrIsMissingEvenWithItsChecksumRewritten() {
    assertRetainedCovarianceRejected(
        "moved",
        document ->
            ((ArrayNode) document.get("covariance").get("observationCovariance").get(0))
                .set(0, 1.0));
    assertRetainedCovarianceRejected(
        "moved-factor",
        document -> ((ArrayNode) document.get("covariance").get("noiseFactor").get(2)).set(1, 1.0));
    assertRetainedCovarianceRejected("missing", document -> document.remove("covariance"));
  }

  @Test
  void freezesNoLifecycleStatusFieldThatNothingEverWritesBack() {
    // Preflight status lives in the preflight record and formal-generation status in the committed
    // execution identity. A frozen document pinned by digest cannot carry either.
    final JsonNode frozen = read(registration);
    assertNull(frozen.get("preflight"));
    assertNull(frozen.get("formalGeneration"));
    assertEquals("measured", read(preflightRecord).get("status").asString());
    assertEquals(SyntheticRegistration.FROZEN, read(execution).get("status").asString());
  }

  @Test
  void measuresSustainedThroughputAtTheRegisteredWorkerCountAfterThermalSteadyState() {
    final JsonNode record = read(preflightRecord);
    assertEquals("measured", record.get("status").asString());
    assertEquals("preflight", record.get("streamPhase").asString());
    assertEquals(WORKERS, record.get("workers").intValue());
    assertEquals(SUB_WINDOW, record.get("subWindowDatasets").intValue());
    assertEquals(WINDOW, record.get("windowDatasets").intValue());
    assertEquals(WARM_UP_LIMIT, record.get("warmUpLimit").intValue());
    assertNotNull(Instant.parse(record.get("deadlineStartedAt").asString()));
    assertTrue(
        record.get("measurement").asString().startsWith("sustained throughput"),
        "The preflight is not a cold single-worker burst");

    // The steady-state criterion, the measurements establishing it and the worker count are
    // recorded alongside the timings, as the amendment requires.
    assertEquals(CONVENTIONS, conventions(record.get("steadyState")));
    for (JsonNode convention : record.get("steadyState")) {
      assertTrue(convention.get("criterion").asString().contains("consecutive"));
      assertEquals(0.05, convention.get("tolerance").doubleValue());
      assertEquals(SUB_WINDOW, convention.get("subWindowDatasets").intValue());
      assertTrue(convention.get("warmUpDatasets").intValue() > 0);
      assertTrue(convention.get("subWindows").size() > 0);
    }

    // The measured window of each convention splits into consecutive sub-windows, and each one
    // carries the wall clock the host sustained over it.
    assertEquals(CONVENTIONS, conventions(record.get("windows")));
    for (JsonNode window : record.get("windows")) {
      assertEquals(WINDOW, window.get("datasets").intValue());
      assertEquals(WINDOW / SUB_WINDOW, window.get("subWindows").size());
      assertTrue(window.get("charge").asString().contains("largest elapsed time per dataset"));
      for (JsonNode sub : window.get("subWindows")) {
        assertEquals(SUB_WINDOW, sub.get("datasets").intValue());
        assertTrue(sub.get("elapsedNanos").longValue() > 0);
        assertTrue(sub.get("perDatasetNanos").doubleValue() > 0);
        assertTrue(sub.get("knownStageNanos").doubleValue() > 0);
        assertTrue(sub.get("estimatedStageNanos").doubleValue() > 0);
      }
    }

    final JsonNode measurements = record.get("measurements");
    assertEquals(2 * (WARM_UP_LIMIT + WINDOW), measurements.size());
    for (JsonNode measured : measurements) {
      assertEquals(SyntheticSearch.POINTS, measured.get("gridPoints").intValue());
      assertTrue(List.of("warm_up", "timed").contains(measured.get("role").asString()));
      // Generation, both prediction paths, the whole training grid, the evidence work and both
      // reproductions are each measured on their own.
      for (String phase :
          List.of(
              "generationNanos",
              "knownPredictionNanos",
              "knownEvidenceNanos",
              "knownReproductionNanos",
              "likelihoodNanos",
              "tunedPredictionNanos",
              "estimatedEvidenceNanos",
              "estimatedReproductionNanos"))
        assertTrue(measured.get(phase).longValue() > 0, phase + " was measured");
      assertTrue(measured.get("knownBytes").longValue() > 0);
      assertTrue(measured.get("estimatedBytes").longValue() > 0);
    }

    // The estimated path ran here for its cost only, in streams of its own, and no coverage from
    // these datasets reaches a formal collection.
    assertTrue(
        Files.isRegularFile(temp.resolve("run/preflight/preflight/estimated/midpoint/0.json")),
        "The estimated path is measured in preflight");
    assertEquals(
        "synthetic-recovery-v1|preflight|midpoint|0",
        read(temp.resolve("run/preflight/preflight/datasets/midpoint/0.json"))
            .get("streamPrefix")
            .asString());
    assertFalse(record.has("cells"));
    assertFalse(record.has("coverageCells"));
    assertEquals(
        "not inspected; preflight datasets never enter the formal collection or any summary",
        record.get("coverage").asString());
  }

  @Test
  void projectsEachStageFromItsSlowestSustainedSubWindowAndGatesOnlyTheFirstOne() {
    final JsonNode budget = read(preflightRecord).get("budget");
    assertEquals(
        List.of("midpoint/known", "midpoint/estimated", "ilr_window/known", "ilr_window/estimated"),
        budget
            .get("stages")
            .valueStream()
            .map(stage -> stage.get("convention").asString() + "/" + stage.get("stage").asString())
            .toList());
    long sustained = 0;
    long largest = 0;
    for (JsonNode stage : budget.get("stages")) {
      assertTrue(stage.get("sustainedNanos").longValue() > 0);
      sustained += stage.get("sustainedNanos").longValue();
      largest += stage.get("largestBytes").longValue();
    }
    assertEquals(sustained, budget.get("perRepetitionNanos").longValue());
    assertEquals(1.25, budget.get("projectionMargin").doubleValue());
    assertEquals(
        Math.round(DATASETS * (double) sustained * 1.25), budget.get("projectedNanos").longValue());
    assertEquals(
        budget.get("projectedNanos").longValue() + budget.get("preflightNanos").longValue(),
        budget.get("totalNanos").longValue());
    assertEquals(24L * 3600 * 1_000_000_000L, budget.get("capNanos").longValue());
    assertEquals(DATASETS * largest, budget.get("projectedBytes").longValue());
    assertEquals(
        2 * budget.get("projectedBytes").longValue() + (1L << 30),
        budget.get("requiredBytes").longValue());
    assertTrue(budget.get("sufficientDisk").booleanValue());

    // Feasibility is the storage rule and the first stage alone. A later stage the elapsed run no
    // longer affords is a deferral when its turn comes, not an infeasible preflight.
    final JsonNode first = budget.get("firstStage");
    assertEquals("midpoint", first.get("convention").asString());
    assertEquals("known", first.get("stage").asString());
    assertEquals("the sustained preflight window", first.get("source").asString());
    assertEquals(
        Math.round(DATASETS * (double) first.get("perDatasetNanos").longValue() * 1.25),
        first.get("projectedNanos").longValue());
    assertTrue(first.get("fits").booleanValue());
    assertEquals("feasible", read(preflightRecord).get("feasibility").asString());
    assertTrue(read(preflightRecord).get("budgeting").asString().contains("the first stage alone"));
  }

  private static List<String> conventions(JsonNode entries) {
    return entries.valueStream().map(entry -> entry.get("convention").asString()).toList();
  }

  @Test
  void retainsAnInfeasibleProjectionAndStopsWithoutChangingTheScientificSettings() {
    final Path base = temp.resolve("infeasible");
    final ObjectNode plan = plan(base.resolve("evidence"), base.resolve("pre"));
    field(plan, "run").put("datasets", 20_000_000);
    final Path frozen = base.resolve("registration.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "register", write(base.resolve("plan.json"), plan).toString(), frozen.toString()));
    assertEquals(
        SyntheticRecovery.INFEASIBLE,
        SyntheticRecovery.run("preflight", frozen.toString(), base.resolve("pre").toString()));

    final JsonNode record = read(base.resolve("pre/preflight.json"));
    assertEquals("infeasible", record.get("feasibility").asString());
    assertEquals(
        List.of(
            "the first stage does not project within the cap less the elapsed preflight",
            "free disk is below twice the projected retained output plus 1 GiB"),
        record.get("reasons").valueStream().map(JsonNode::asString).toList());
    assertEquals(20_000_000, record.get("budget").get("datasets").intValue());
    assertFalse(record.get("budget").get("firstStage").get("fits").booleanValue());
    assertTrue(
        record.get("settings").asString().startsWith("unchanged;"),
        "An infeasible projection changes no scientific setting");

    // Nothing is generated under an infeasible preflight.
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "commit",
            frozen.toString(),
            base.resolve("pre/preflight.json").toString(),
            base.resolve("execution.json").toString()));
    assertFalse(Files.exists(base.resolve("execution.json")));

    // The infeasibility is reportable on its own, with every stage accounted as not run.
    final Path report = base.resolve("report.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "report", base.resolve("pre/preflight.json").toString(), report.toString()));
    assertEquals("stopped_at_preflight_infeasible", read(report).get("outcome").asString());
    assertEquals("not_run", read(report).get("recovery").asString());
    assertEquals(4, read(report).get("stages").size());
    for (JsonNode stage : read(report).get("stages"))
      assertEquals("not_run", stage.get("status").asString());
  }

  @Test
  void refusesAnOutputThatAlreadyExistsRatherThanReplacingIt() {
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "register", temp.resolve("run/plan.json").toString(), registration.toString()));
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "preflight", registration.toString(), temp.resolve("run/preflight").toString()));
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "commit", registration.toString(), preflightRecord.toString(), execution.toString()));
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run("experiment", execution.toString(), evidence.toString()));
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "reproduce", execution.toString(), evidence.toString(), reproduction.toString()));

    // The registered destination is the only one a run writes.
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "experiment", execution.toString(), temp.resolve("elsewhere").toString()));
  }

  @Test
  void runsUnderOneHostAndTheRegisteredWorkersAndLeavesTheProtectedEvidenceUnchanged() {
    final JsonNode report = read(evidence.resolve("report.json"));
    assertEquals("completed", report.get("status").asString());
    assertEquals(evidence.toString(), report.get("evidenceLocation").asString());

    final JsonNode identities = report.get("identities");
    assertEquals("synthetic-recovery-v2", identities.get("protocol").asString());
    assertEquals(COMMIT, identities.get("implementationCommit").asString());
    assertEquals(hostname(), identities.get("hostname").asString());
    assertEquals(WORKERS, identities.get("workers").intValue());
    assertEquals(4, identities.get("stageProjections").size());
    assertEquals(digest(registration), identities.get("registrationSha256").asString());
    assertEquals(digest(execution), identities.get("executionSha256").asString());
    assertEquals(
        read(execution).get("deadlineStartedAt").asString(),
        identities.get("deadlineStartedAt").asString());
    assertTrue(identities.has("commands"));

    final JsonNode worker = read(evidence.resolve("worker.lock"));
    assertEquals(hostname(), worker.get("hostname").asString());
    assertEquals(WORKERS, worker.get("workers").intValue());

    // The 24-hour clock is the one preflight started, not one this command restarted.
    final Instant started =
        Instant.parse(read(preflightRecord).get("deadlineStartedAt").asString());
    assertEquals(
        started.plusNanos(24L * 3600 * 1_000_000_000L).toString(),
        identities.get("deadlineAt").asString());

    for (JsonNode location : report.get("protectedEvidence")) {
      assertEquals(
          location.get("registeredSha256").asString(), location.get("beforeSha256").asString());
      assertEquals(location.get("beforeSha256").asString(), location.get("afterSha256").asString());
      assertTrue(location.get("unchanged").booleanValue());
    }
    assertTrue(report.get("protectedEvidence").size() >= 2);
  }

  @Test
  void stopsSchedulingAtTheCapPreservesWhatCompletedAndRefusesToResumeOnItsOwn() {
    // The cap is reached by committing an execution identity whose clock started 25 hours ago,
    // rather than by waiting for one.
    final Path base = temp.resolve("interrupted");
    final Path directory = base.resolve("evidence");
    final Path frozen = base.resolve("registration.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "register",
            write(base.resolve("plan.json"), plan(directory, base.resolve("pre"))).toString(),
            frozen.toString()));
    final ObjectNode expired = (ObjectNode) read(preflightRecord);
    expired.put("registrationSha256", digest(frozen));
    expired.put("deadlineStartedAt", Instant.now().minusSeconds(25 * 3600).toString());
    final Path executionFile = base.resolve("execution.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "commit",
            frozen.toString(),
            write(base.resolve("preflight.json"), expired).toString(),
            executionFile.toString()));

    assertEquals(
        SyntheticRecovery.INTERRUPTED,
        SyntheticRecovery.run("experiment", executionFile.toString(), directory.toString()));
    final JsonNode interruption = read(directory.resolve("interruption.json"));
    assertEquals("interrupted", interruption.get("status").asString());
    assertEquals("the 24-hour wall-clock cap was reached", interruption.get("reason").asString());
    assertEquals("known", interruption.get("stage").asString());
    assertTrue(Files.isRegularFile(directory.resolve("worker.lock")));

    final JsonNode report = read(directory.resolve("report.json"));
    assertEquals("interrupted", report.get("status").asString());
    assertEquals("incomplete_interrupted", report.get("experiment").asString());
    assertEquals("incomplete", report.get("recovery").asString());
    for (JsonNode method : report.get("methods"))
      assertEquals("incomplete", method.get("verdict").asString());

    // Nothing resumes it on its own, and a decision that does not match the preserved output is
    // refused rather than taken as authority to continue.
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "experiment", executionFile.toString(), base.resolve("resumed").toString()));
    final ObjectNode decision = JSON.createObjectNode();
    decision.put("version", "synthetic-recovery-v2");
    decision.put("executionSha256", digest(executionFile));
    decision.put("decidedOn", "2026-09-17");
    decision.put("decidedBy", "the model owner");
    decision.put("reason", "the cap was reached before the first repetition");
    final ObjectNode interrupted = decision.putObject("interruptedRun");
    interrupted.put("path", directory.toString());
    interrupted.put("sha256", "0".repeat(64));
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "experiment",
            executionFile.toString(),
            base.resolve("resumed").toString(),
            write(base.resolve("decision.json"), decision).toString()));

    // With the decision recorded against the partial output it names, the run continues in a
    // destination of its own and the interrupted output stays exactly as the watchdog left it.
    final String preserved = SyntheticRegistration.treeDigest(directory);
    interrupted.put("sha256", preserved);
    assertEquals(
        SyntheticRecovery.INTERRUPTED,
        SyntheticRecovery.run(
            "experiment",
            executionFile.toString(),
            base.resolve("resumed-accepted").toString(),
            write(base.resolve("accepted.json"), decision).toString()));
    assertEquals(preserved, SyntheticRegistration.treeDigest(directory));
    final JsonNode resumed = read(base.resolve("resumed-accepted/report.json"));
    assertEquals(directory.toString(), resumed.get("resumedFrom").get("path").asString());
    assertEquals("the model owner", resumed.get("resumedFrom").get("decidedBy").asString());
  }

  @Test
  void defersAStageThatDoesNotProjectWithinTheBudgetRemainingWhenItsTurnComes() {
    // The first control is charged a rate it can carry; the second is charged one it cannot. The
    // budget is a timing input: nothing here reads a cell, a fraction or an endpoint frequency.
    final Path base = temp.resolve("deferred");
    final Path directory = base.resolve("evidence");
    final ObjectNode budget = JSON.createObjectNode();
    final Path executionFile =
        forged(
            base,
            directory,
            DATASETS,
            24L * 3600 * 1_000_000_000L,
            stages -> {
              field(stages.get(0)).put("sustainedNanos", 1);
              field(stages.get(1)).put("sustainedNanos", 1);
              field(stages.get(2)).put("sustainedNanos", Long.MAX_VALUE / 1_000);
              field(stages.get(3)).put("sustainedNanos", 1);
              budget.set("stages", stages);
            });

    assertEquals(
        SyntheticRecovery.DEFERRED,
        SyntheticRecovery.run("experiment", executionFile.toString(), directory.toString()));

    final JsonNode deferral = read(directory.resolve("deferral.json"));
    assertEquals("stage_budget_deferred", deferral.get("status").asString());
    assertEquals("ilr_window", deferral.get("convention").asString());
    assertEquals("known", deferral.get("stage").asString());
    assertTrue(
        deferral.get("projectedNanos").longValue() > deferral.get("remainingNanos").longValue());
    assertEquals(1.25, deferral.get("projectionMargin").doubleValue());
    assertTrue(deferral.get("inputs").asString().startsWith("timing only;"));

    final JsonNode report = read(directory.resolve("report.json"));
    assertEquals("stage_budget_deferred", report.get("status").asString());

    // Both control verdicts stand at the top, each as a complete result in its own right: the one
    // that ran every repetition and reported all 18 cells, and the one the budget deferred.
    assertEquals(CONVENTIONS, conventions(report.get("controlVerdicts")));
    assertEquals("completed", report.get("controlVerdicts").get(0).get("status").asString());
    assertEquals(18, report.get("controlVerdicts").get(0).get("cells").intValue());
    assertEquals(
        DATASETS, report.get("controlVerdicts").get(0).get("completedDatasets").intValue());
    assertEquals("not_run", report.get("controlVerdicts").get(1).get("status").asString());
    assertEquals("incomplete", report.get("controlVerdicts").get(1).get("verdict").asString());
    assertEquals("incomplete_stage_budget_deferred", report.get("experiment").asString());
    assertEquals("incomplete", report.get("recovery").asString());

    // The control whose budget held is a complete result in its own right; the deferred stage is
    // accounted as not run, with the reason, the budget remaining and the projection that exceeded
    // it.
    final JsonNode ran = report.get("methods").get(0);
    assertEquals("completed", ran.get("status").asString());
    assertEquals(DATASETS, ran.get("completedDatasets").intValue());
    assertEquals(18, ran.get("cells").size());
    final JsonNode stopped = report.get("methods").get(1);
    assertEquals("not_run", stopped.get("status").asString());
    assertEquals("incomplete", stopped.get("verdict").asString());
    assertEquals(0, stopped.get("cells").size());
    assertTrue(stopped.get("reason").asString().contains("unmeasured rather than shortened"));

    // A deferral changes nothing about the design: not N, not the grid, not the schedule and not
    // the confidence rule.
    assertEquals(DATASETS, report.get("plannedDatasets").intValue());
    assertEquals(72, report.get("confidence").get("primaryCells").intValue());
    assertEquals(3.391763140587952, report.get("confidence").get("criticalValue").doubleValue());
    assertEquals(28, report.get("calendar").get("weeks").intValue());
    assertEquals(
        read(evidence.resolve("report.json")).get("recoveryBands"), report.get("recoveryBands"));
    for (JsonNode estimated : report.get("estimatedStages"))
      assertEquals("not_run", estimated.get("status").asString());
  }

  @Test
  void letsTheRepetitionsInFlightFinishAtTheCapAndRecordsHowManyCompleted() {
    // A run whose stage budgets all fit, against a clock that runs out partway through the first
    // control. Scheduling stops at the cap; nothing is left half-written behind it.
    final int planned = 400;
    final Path base = temp.resolve("drained");
    final Path directory = base.resolve("evidence");
    final Path executionFile =
        forged(
            base,
            directory,
            planned,
            20_000_000_000L,
            stages -> {
              for (JsonNode stage : stages) field(stage).put("sustainedNanos", 0);
            });

    assertEquals(
        SyntheticRecovery.INTERRUPTED,
        SyntheticRecovery.run("experiment", executionFile.toString(), directory.toString()));

    final JsonNode interruption = read(directory.resolve("interruption.json"));
    assertEquals("interrupted", interruption.get("status").asString());
    final String convention = interruption.get("convention").asString();
    assertEquals("known", interruption.get("stage").asString());
    final int completed = interruption.get("completedRepetitions").intValue();
    assertTrue(completed < planned, "The cap stopped the stage short");
    assertTrue(completed > 0, "The cap stopped scheduling after the run had made progress");
    assertTrue(interruption.get("overrunMillis").longValue() >= 0);
    assertTrue(interruption.get("drain").asString().contains("allowed to finish"));

    // Every repetition that was in flight finished and was retained, and nothing beyond them was
    // scheduled: the retained files are exactly the completed count, on a batch boundary.
    assertEquals(completed, retained(directory.resolve("datasets").resolve(convention)));
    assertEquals(0, completed % WORKERS);

    // A stage the watchdog stopped short is incomplete and reports no coverage at all. N stays at
    // the registered repetition count.
    final JsonNode method =
        read(directory.resolve("report.json"))
            .get("methods")
            .valueStream()
            .filter(candidate -> candidate.get("convention").asString().equals(convention))
            .findFirst()
            .orElseThrow();
    assertEquals("incomplete", method.get("status").asString());
    assertEquals("incomplete", method.get("verdict").asString());
    assertEquals(0, method.get("cells").size());
    assertEquals(completed, method.get("completedDatasets").intValue());
    assertEquals(planned, method.get("plannedDatasets").intValue());
  }

  /**
   * A registration of its own, with a preflight record forged into the stage rates and cap the case
   * under test needs. Nothing waits for a real 24-hour clock or a real sustained window.
   */
  private static Path forged(
      Path base, Path directory, int datasets, long capNanos, StageRates rates) {
    final ObjectNode plan = plan(directory, base.resolve("pre"));
    field(plan, "run").put("datasets", datasets);
    field(plan, "run").put("draws", datasets > DATASETS ? 2000 : DRAWS);
    final Path frozen = base.resolve("registration.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "register", write(base.resolve("plan.json"), plan).toString(), frozen.toString()));
    final ObjectNode record = (ObjectNode) read(preflightRecord);
    record.put("registrationSha256", digest(frozen));
    record.put("deadlineStartedAt", Instant.now().toString());
    record.put("capNanos", capNanos);
    rates.apply((ArrayNode) record.get("budget").get("stages"));
    final Path executionFile = base.resolve("execution.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "commit",
            frozen.toString(),
            write(base.resolve("preflight.json"), record).toString(),
            executionFile.toString()));
    return executionFile;
  }

  private interface StageRates {
    void apply(ArrayNode stages);
  }

  private static int retained(Path stage) {
    if (!Files.isDirectory(stage)) return 0;
    try (final Stream<Path> files = Files.list(stage)) {
      return (int) files.count();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void reproducesEveryCompletedPredictiveArrayAndRecomputesWhatWasReadFromIt() {
    final JsonNode record = read(reproduction);
    assertEquals("reproduced", record.get("outcome").asString());
    assertEquals(2 * DATASETS, record.get("reproducedDatasets").intValue());
    assertEquals(2 * DATASETS * SCORING_POLLS, record.get("reproducedArrays").intValue());
    assertEquals(List.of(), record.get("findings").valueStream().map(JsonNode::asString).toList());
    assertFalse(record.get("interrupted").booleanValue());
    assertEquals(digest(execution), record.get("identities").get("executionSha256").asString());

    // The known stages were reproduced cell by cell; the estimated stages were never run, so they
    // have no predictive output to regenerate.
    assertEquals(4, record.get("stages").size());
    for (JsonNode stage : record.get("stages"))
      if (stage.get("stage").asString().equals("known")) {
        assertEquals(DATASETS, stage.get("datasets").intValue());
        assertEquals(18, stage.get("cells").size());
      } else {
        assertEquals("not_run", stage.get("verdict").asString());
        assertEquals(0, stage.get("arrays").intValue());
      }

    // The full report accounts for every stage and carries the reproduction beside the verdict.
    final Path report = temp.resolve("run/final-report.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "report",
            execution.toString(),
            evidence.toString(),
            reproduction.toString(),
            report.toString()));
    final JsonNode published = read(report);
    assertEquals(
        "complete_" + read(evidence.resolve("report.json")).get("recovery").asString(),
        published.get("outcome").asString());
    assertEquals(4, published.get("stages").size());
    assertEquals("reproduced", published.get("reproduction").get("outcome").asString());
    assertTrue(published.get("protectedEvidenceUnchanged").booleanValue());
    assertEquals(
        2 * DATASETS * SCORING_POLLS, published.get("reproduction").get("arrays").intValue());
  }

  @Test
  void summarizesEveryPrimaryCellAndOneRollupDigestPerStageAndConvention() {
    final Path results = temp.resolve("run/results.json");
    assertEquals(
        SyntheticRecovery.SUCCESS,
        SyntheticRecovery.run(
            "summarize", execution.toString(), evidence.toString(), results.toString()));
    final JsonNode record = read(results);
    assertEquals("synthetic-recovery-v2", record.get("version").asString());
    assertEquals(72, record.get("cells").size());
    assertEquals(72, record.get("primaryCells").intValue());
    assertEquals(3.391763140587952, record.get("criticalValue").doubleValue());

    // The known stages published their cells; the estimated stages never ran, so their cells are
    // accounted as unmeasured rather than as coverage over the part that exists.
    int measured = 0;
    for (JsonNode cell : record.get("cells")) {
      assertTrue(List.of("midpoint", "ilr_window").contains(cell.get("convention").asString()));
      if (!cell.get("measured").booleanValue()) {
        assertEquals("estimated", cell.get("stage").asString());
        assertEquals("incomplete", cell.get("verdict").asString());
        assertFalse(cell.has("coverage"));
        continue;
      }
      measured++;
      assertEquals("known", cell.get("stage").asString());
      assertTrue(cell.get("coverage").doubleValue() >= 0);
      assertTrue(cell.get("standardError").doubleValue() >= 0);
      assertTrue(cell.get("lower").doubleValue() <= cell.get("upper").doubleValue());
    }
    assertEquals(36, measured);

    // One rollup per stage and convention, each digesting a full per-file manifest that lives on
    // the volume beside the evidence rather than in the repository.
    assertEquals(4, record.get("rollups").size());
    for (JsonNode rollup : record.get("rollups")) {
      if (rollup.get("status").asString().equals("not_run")) {
        assertEquals("estimated", rollup.get("stage").asString());
        assertEquals(0, rollup.get("files").intValue());
        continue;
      }
      final Path manifest = evidence.resolve(rollup.get("manifest").asString());
      assertTrue(
          Files.isRegularFile(manifest), () -> manifest + " was written beside the evidence");
      assertEquals(DATASETS, rollup.get("files").intValue());
      assertEquals(digest(manifest), rollup.get("manifestSha256").asString());
      assertEquals(DATASETS, text(manifest).strip().split(System.lineSeparator()).length);
    }
  }

  @Test
  void detectsTamperedAndMissingReproductionEvidence() {
    assertReproductionFinding(
        "changed-hash",
        run -> {
          final ObjectNode dataset = (ObjectNode) read(run.resolve("datasets/midpoint/0.json"));
          field(dataset.get("polls").get(3)).put("drawsSha256", "a".repeat(64));
          write(run.resolve("datasets/midpoint/0.json"), dataset);
        },
        "regenerated a different predictive draw array");
    assertReproductionFinding(
        "missing-hash",
        run -> {
          final ObjectNode dataset = (ObjectNode) read(run.resolve("datasets/midpoint/0.json"));
          field(dataset.get("polls").get(1)).put("drawsSha256", "");
          write(run.resolve("datasets/midpoint/0.json"), dataset);
        },
        "retains no predictive draw hash");
    assertReproductionFinding(
        "changed-summary",
        run -> {
          final ObjectNode dataset = (ObjectNode) read(run.resolve("datasets/ilr_window/1.json"));
          field(dataset.get("polls").get(0).get("components").get(2)).put("upper95", 99.0);
          write(run.resolve("datasets/ilr_window/1.json"), dataset);
        },
        "recomputed a different upper95");
    assertReproductionFinding(
        "flipped-indicator",
        run -> {
          final ObjectNode dataset = (ObjectNode) read(run.resolve("datasets/midpoint/1.json"));
          final ObjectNode component = field(dataset.get("polls").get(0).get("components").get(0));
          component.put("covered95", !component.get("covered95").booleanValue());
          write(run.resolve("datasets/midpoint/1.json"), dataset);
        },
        "recomputed a different 95% coverage indicator");
    assertReproductionFinding(
        "missing-dataset",
        run -> delete(run.resolve("datasets/midpoint/1.json")),
        "summaries are missing");
    assertReproductionFinding(
        "changed-cell",
        run -> {
          final ObjectNode report = (ObjectNode) read(run.resolve("report.json"));
          field(report.get("methods").get(0).get("cells").get(0)).put("coverage", 0.5);
          write(run.resolve("report.json"), report);
        },
        "recomputed a different coverage");
  }

  private void assertReproductionFinding(String name, Tamper tamper, String expected) {
    final Path run = temp.resolve("tamper/" + name);
    copyTree(evidence, run);
    tamper.apply(run);
    final Path record = temp.resolve("tamper/" + name + ".json");
    assertEquals(
        SyntheticRecovery.BLOCKED,
        SyntheticRecovery.run("reproduce", execution.toString(), run.toString(), record.toString()),
        name);
    final List<String> findings =
        read(record).get("findings").valueStream().map(JsonNode::asString).toList();
    assertTrue(
        findings.stream().anyMatch(finding -> finding.contains(expected)),
        () -> name + " reported " + findings);
    assertEquals("not_reproduced", read(record).get("outcome").asString());
  }

  private interface Tamper {
    void apply(Path run);
  }

  private void assertRegistrationRejected(String name, PlanChange change) {
    final ObjectNode plan =
        plan(temp.resolve("rejected/" + name + "/evidence"), temp.resolve("rejected/" + name));
    change.apply(plan);
    final Path registrationFile = temp.resolve("rejected/" + name + "/registration.json");
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "register",
            write(temp.resolve("rejected/" + name + "/plan.json"), plan).toString(),
            registrationFile.toString()),
        name);
    // A refusal leaves no registration and no checksum behind.
    assertFalse(Files.exists(registrationFile), name);
    assertFalse(Files.exists(SyntheticRegistration.checksum(registrationFile)), name);
  }

  private interface PlanChange {
    void apply(ObjectNode plan);
  }

  /**
   * Rewrites the frozen registration and its checksum sidecar together, so the refusal comes from
   * the retained covariance rather than from the document checksum that would otherwise catch it.
   */
  private void assertRetainedCovarianceRejected(String name, RegistrationChange change) {
    final Path moved = temp.resolve("covariance/" + name + "/registration.json");
    copy(registration, moved);
    final ObjectNode document = (ObjectNode) read(moved);
    change.apply(document);
    write(moved, document);
    writeText(SyntheticRegistration.checksum(moved), digest(moved) + System.lineSeparator());
    assertEquals(
        SyntheticRecovery.REJECTED,
        SyntheticRecovery.run(
            "preflight", moved.toString(), temp.resolve("covariance/" + name + "/pre").toString()),
        name);
    assertFalse(Files.exists(temp.resolve("covariance/" + name + "/pre")), name);
  }

  private interface RegistrationChange {
    void apply(ObjectNode registration);
  }

  /** A software-check registration of the accepted protocol, on two datasets and eight draws. */
  private static ObjectNode plan(Path output, Path preflight) {
    final ObjectNode plan = JSON.createObjectNode();
    plan.put("version", "synthetic-recovery-v2");
    plan.put("phase", "software_check");
    plan.put("registeredOn", "2026-09-17");
    final ObjectNode protocol = plan.putObject("protocol");
    protocol.put("path", "docs/validation/synthetic-recovery-protocol.md");
    protocol.put("sha256", digest(Path.of("docs/validation/synthetic-recovery-protocol.md")));
    plan.put("implementationCommit", COMMIT);
    final ObjectNode dependency = plan.putArray("dependencies").addObject();
    dependency.put("name", "ejml");
    dependency.put("path", ejml().toString());
    dependency.put("sha256", digest(ejml()));
    final ObjectNode toolchain = plan.putObject("toolchain");
    toolchain.put("javaVersion", System.getProperty("java.version"));
    toolchain.put("javaRuntime", Runtime.version().toString());
    toolchain.put(
        "vm",
        java.lang.management.ManagementFactory.getRuntimeMXBean().getVmName()
            + " "
            + java.lang.management.ManagementFactory.getRuntimeMXBean().getVmVersion());
    toolchain.put("osName", System.getProperty("os.name"));
    toolchain.put("osArch", System.getProperty("os.arch"));
    plan.putObject("container").put("image", "test-runtime");
    final ObjectNode host = plan.putObject("host");
    host.put("hostname", hostname());
    host.put("physicalCores", CORES);
    host.put("workers", WORKERS);
    final ObjectNode commands = plan.putObject("commands");
    for (String name :
        List.of(
            "register", "preflight", "commit", "experiment", "reproduce", "report", "summarize"))
      commands.put(name, "./mvnw spring-boot:run -Dspring-boot.run.arguments='" + name + " ...'");
    final ObjectNode tolerances = plan.putObject("numericalTolerances");
    tolerances.put("interval", 1e-9);
    tolerances.put("coverage", 1e-9);
    tolerances.put("likelihood", 1e-6);
    final ObjectNode schedule = plan.putObject("schedule");
    schedule.put("periodStart", "2016-01-01");
    schedule.put("cutoff", "2016-06-28");
    schedule.put("scoreThrough", "2016-08-02");
    schedule.put("weeks", 28);
    schedule.put("trainingPolls", 115);
    schedule.put("scoringPolls", 25);
    final ArrayNode institutes = schedule.putArray("institutes");
    for (String institute : List.of("I0", "I1", "I2", "I3", "I4")) institutes.add(institute);
    plan.put("covarianceSha256", SyntheticRegistration.covarianceSha256());
    final ObjectNode streams = plan.putObject("streams");
    streams.put("masterSeed", 4711);
    streams.put("formalPrefix", "synthetic-recovery-v1|formal");
    streams.put("preflightPrefix", "synthetic-recovery-v1|preflight");
    final ArrayNode generating = streams.putArray("generating");
    for (String stream : List.of("initial", "walk", "houses", "noise")) generating.add(stream);
    streams.put("predictive", "predictive");
    streams.put(
        "seedDerivation",
        "SHA-256 of UTF-8 \"stream|masterSeed\"; first eight bytes big-endian signed");
    final ObjectNode run = plan.putObject("run");
    final ArrayNode conventions = run.putArray("conventions");
    conventions.add("midpoint");
    conventions.add("ilr_window");
    final ArrayNode stages = run.putArray("stages");
    stages.add("known");
    stages.add("estimated");
    run.put("gridPoints", SyntheticSearch.POINTS);
    run.put("datasets", DATASETS);
    run.put("draws", DRAWS);
    final ObjectNode limits = plan.putObject("limits");
    limits.put("wallClockHours", 24);
    limits.put("projectionMargin", 1.25);
    limits.put("logReserveBytes", 1L << 30);
    limits.put("preflightSubWindowDatasets", SUB_WINDOW);
    limits.put("preflightWindowDatasets", WINDOW);
    limits.put("preflightWarmUpLimit", WARM_UP_LIMIT);
    plan.put("outputLocation", output.toString());
    plan.put("preflightLocation", preflight.toString());
    final ArrayNode protectedLocations = plan.putArray("protectedLocations");
    for (String path :
        List.of(
            "src/main/resources/publication/model-freeze.json",
            "docs/validation/v2-development-1/evidence/run-2"))
      protectedLocations.addObject().put("path", path).put("sha256", treeDigest(path));
    return plan;
  }

  private static String runtimeImage() {
    final String pom = text(Path.of("pom.xml"));
    final int start = pom.indexOf("<runtime.image>") + "<runtime.image>".length();
    return pom.substring(start, pom.indexOf("</runtime.image>", start));
  }

  private static ObjectNode field(JsonNode parent, String name) {
    return (ObjectNode) parent.get(name);
  }

  private static ObjectNode field(JsonNode node) {
    return (ObjectNode) node;
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path ejml() {
    return Path.of(
        URI.create(
            SimpleMatrix.class.getProtectionDomain().getCodeSource().getLocation().toString()));
  }

  private static String treeDigest(String path) {
    return SyntheticRegistration.treeDigest(Path.of(path));
  }

  private static String digest(Path file) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void copy(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Files.copy(source, target);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void copyTree(Path source, Path target) {
    try (final Stream<Path> files = Files.walk(source)) {
      for (Path file : files.toList()) {
        final Path destination = target.resolve(source.relativize(file).toString());
        if (Files.isDirectory(file)) Files.createDirectories(destination);
        else copy(file, destination);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void delete(Path file) {
    try {
      Files.delete(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path write(Path file, JsonNode document) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, JSON.writeValueAsString(document), StandardCharsets.UTF_8);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeText(Path file, String content) {
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode read(Path file) {
    try {
      return JSON.readTree(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
