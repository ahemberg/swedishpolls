package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedishpolls.testsupport.PollCsvFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class DevelopmentValidationTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path REGISTRATION =
      Path.of("docs", "validation", "v2-development-1", "registration.json");

  @TempDir Path temp;

  @Test
  void preparesAndPreflightsFrozenEligibilityWithoutFitting() throws Exception {
    final Path source = temp.resolve("polls.csv");
    final byte[] sourceBytes =
        PollCsvFixtures.csv(
            row("2014-01-10", "2014-01-01", "2014-01-09", "NA")
                + row("2014-01-16", "2014-01-10", "2014-01-14", "NA")
                + row("2014-04-12", "2014-04-09", "2014-04-11", "1")
                + row("2014-05-16", "2014-04-20", "2014-04-30", "1")
                + row("2014-06-01", "2014-05-20", "2014-05-30", "1"));
    Files.write(source, sourceBytes);
    final Path identity = temp.resolve("implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("evidence");
    final Path plan = temp.resolve("plan.json");
    Files.writeString(plan, plan(source, identity, evidence), StandardCharsets.UTF_8);
    final Path registration = temp.resolve("registration.json");

    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));
    assertTrue(Files.exists(registration.resolveSibling("registration.json.sha256")));
    final JsonNode frozen = JSON.readTree(Files.readAllBytes(registration));
    assertEquals("frozen", frozen.get("status").asString());
    assertEquals(6, frozen.get("folds").size());
    assertEquals(
        3,
        frozen
            .get("folds")
            .valueStream()
            .filter(fold -> fold.get("active").booleanValue())
            .count());
    final JsonNode sparse = fold(frozen, "fi", "2014-05-15");
    assertTrue(sparse.get("active").booleanValue());
    assertEquals(1, sparse.get("trainingObservationRows").size());
    assertFalse(contains(sparse.get("trainingObservationRows"), 4));
    assertTrue(contains(sparse.get("scoringRows"), 4));
    assertEquals(
        "no_eligible_training_observation",
        fold(frozen, "fi", "2014-01-15").get("reason").asString());
    assertEquals(
        "no_held_out_composition", fold(frozen, "fi", "2018-10-21").get("reason").asString());

    final Path result = temp.resolve("preflight.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "preflight", registration.toString(), source.toString(), result.toString()));
    assertEquals("ready", JSON.readTree(Files.readAllBytes(result)).get("status").asString());
    assertFalse(Files.exists(evidence));

    final Path changedRegistration = temp.resolve("changed-registration.json");
    Files.write(changedRegistration, Files.readAllBytes(registration));
    Files.writeString(
        changedRegistration.resolveSibling("changed-registration.json.sha256"),
        "0".repeat(64),
        StandardCharsets.UTF_8);
    final Path changedRegistrationResult = temp.resolve("changed-registration-result.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "preflight",
            changedRegistration.toString(),
            source.toString(),
            changedRegistrationResult.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(changedRegistrationResult))
            .get("reasons")
            .toString()
            .contains("checksum"));

    final JsonNode changedRows = frozen.deepCopy();
    ((ObjectNode) fold(changedRows, "fi", "2014-05-15")).put("active", false);
    final Path rowRegistration = temp.resolve("row-registration.json");
    Files.writeString(
        rowRegistration,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(changedRows) + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        rowRegistration.resolveSibling("row-registration.json.sha256"),
        DevelopmentGates.sha256(rowRegistration) + "\n",
        StandardCharsets.UTF_8);
    final Path rowResult = temp.resolve("row-result.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "preflight", rowRegistration.toString(), source.toString(), rowResult.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(rowResult))
            .get("reasons")
            .toString()
            .contains("row manifest"));

    final Path wrongEnvironment = temp.resolve("wrong-environment.json");
    Files.writeString(
        wrongEnvironment,
        plan(source, identity, evidence)
            .replace(
                "\"javaVersion\": \"" + System.getProperty("java.version") + "\"",
                "\"javaVersion\": \"wrong\""),
        StandardCharsets.UTF_8);
    final Path wrongEnvironmentResult = temp.resolve("wrong-environment-registration.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "prepare",
            wrongEnvironment.toString(),
            source.toString(),
            wrongEnvironmentResult.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(wrongEnvironmentResult))
            .get("reasons")
            .toString()
            .contains("Environment"));

    final byte[] protectedBytes = Files.readAllBytes(identity);
    Files.writeString(source, "changed", StandardCharsets.UTF_8);
    final Path rejected = temp.resolve("rejected.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "preflight", registration.toString(), source.toString(), rejected.toString()));
    assertEquals("rejected", JSON.readTree(Files.readAllBytes(rejected)).get("status").asString());
    assertArrayEquals(protectedBytes, Files.readAllBytes(identity));
    assertFalse(Files.exists(evidence));

    Files.write(source, sourceBytes);
    Files.createDirectory(evidence);
    final Path collision = temp.resolve("collision.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "preflight", registration.toString(), source.toString(), collision.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(collision)).get("reasons").toString().contains("exists"));
  }

  @Test
  void registeredV2ManifestKeepsEveryReviewedFoldAndTheApprovedGrid() throws Exception {
    final JsonNode registration = JSON.readTree(Files.readAllBytes(REGISTRATION));
    final JsonNode folds = registration.get("folds");

    assertEquals("v2-development-1", registration.get("version").asString());
    assertEquals("not_run", registration.get("fitEvidence").asString());
    assertEquals(96, folds.size());
    assertEquals(75, folds.valueStream().filter(fold -> fold.get("active").booleanValue()).count());
    assertEquals(
        21,
        folds
            .valueStream()
            .filter(fold -> fold.get("periodId").asString().equals("fi_candidate_2014_2018"))
            .filter(fold -> !fold.get("active").booleanValue())
            .count());
    assertEquals(
        2,
        fold(registration, "fi_candidate_2014_2018", "2014-05-15")
            .get("trainingObservationRows")
            .size());
    assertEquals(
        "2018-09-26",
        fold(registration, "fi_candidate_2014_2018", "2018-08-22").get("scoreThrough").asString());
    final JsonNode grid = registration.get("plan").get("grid");
    assertEquals(
        180,
        grid.get("walkVariances").size()
            * grid.get("houseScales").size()
            * grid.get("covarianceMultipliers").size());
    assertEquals(4, registration.get("plan").get("commands").size());
    assertTrue(registration.get("plan").get("commands").get(2).asString().contains("'tune "));
    assertTrue(registration.get("plan").get("commands").get(3).asString().contains("'estimate "));
    // The estimator's parameter selection is named by the registration, never by the code alone.
    assertEquals(
        "midpoint_candidate",
        registration.get("plan").get("parameterSelection").get("method").asString());
    assertEquals(
        "latest_resolved_active_cutoff",
        registration.get("plan").get("parameterSelection").get("fold").asString());
    assertTrue(
        registration
            .get("plan")
            .get("identities")
            .valueStream()
            .map(identity -> identity.get("path").asString())
            .anyMatch(path -> path.endsWith("/WindowFilter.java")));

    final Path result = temp.resolve("registered-preflight.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "preflight",
            REGISTRATION.toString(),
            Path.of("src", "test", "resources", "polls", "audit.csv").toString(),
            result.toString()));
    assertEquals("ready", JSON.readTree(Files.readAllBytes(result)).get("status").asString());

    final JsonNode wrongToolchain = registration.deepCopy();
    ((ObjectNode) wrongToolchain.get("plan").get("environment")).put("npmVersion", "wrong");
    final Path wrongToolchainRegistration = temp.resolve("wrong-toolchain.json");
    Files.writeString(
        wrongToolchainRegistration,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrongToolchain) + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        wrongToolchainRegistration.resolveSibling("wrong-toolchain.json.sha256"),
        DevelopmentGates.sha256(wrongToolchainRegistration) + "\n",
        StandardCharsets.UTF_8);
    final Path wrongToolchainResult = temp.resolve("wrong-toolchain-result.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "preflight",
            wrongToolchainRegistration.toString(),
            Path.of("src", "test", "resources", "polls", "audit.csv").toString(),
            wrongToolchainResult.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(wrongToolchainResult))
            .get("reasons")
            .toString()
            .contains("environment"));
  }

  @Test
  void tunesBothFittedMethodsAndRetainsTheWholeSearch() throws Exception {
    final Path source = temp.resolve("tuning-polls.csv");
    Files.write(
        source,
        PollCsvFixtures.csv(
            row("2014-01-10", "2014-01-01", "2014-01-09", "NA")
                + row("2014-01-16", "2014-01-10", "2014-01-14", "NA")
                + row("2014-04-12", "2014-04-09", "2014-04-11", "1")
                + row("2014-05-10", "2014-05-01", "2014-05-05", "1")
                + row("2014-05-16", "2014-04-20", "2014-04-30", "1")
                + row("2014-06-01", "2014-05-20", "2014-05-30", "1")));
    final Path identity = temp.resolve("tuning-implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("tuning-evidence");
    final Path plan = temp.resolve("tuning-plan.json");
    Files.writeString(plan, plan(source, identity, evidence), StandardCharsets.UTF_8);
    final Path registration = temp.resolve("tuning-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));

    final Path result = evidence.resolve("tuning.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "tune", registration.toString(), source.toString(), result.toString()));

    final JsonNode tuning = JSON.readTree(Files.readAllBytes(result));
    assertEquals("blocked", tuning.get("status").asString());
    assertEquals("complete", tuning.get("fitEvidence").asString());
    assertTrue(tuning.get("grid").get("interpretation").asString().contains("underdispersion"));
    assertEquals(6, tuning.get("folds").size());
    assertEquals(
        3,
        tuning
            .get("folds")
            .valueStream()
            .filter(fold -> fold.get("active").booleanValue())
            .count());
    assertEquals(
        3,
        tuning
            .get("folds")
            .valueStream()
            .filter(fold -> !fold.get("active").booleanValue())
            .count());
    final JsonNode sparse = fold(tuning, "fi", "2014-05-15");
    assertEquals(2, sparse.get("trainingObservationRows").size());
    assertFalse(contains(sparse.get("trainingObservationRows"), 5));
    assertEquals(2, sparse.get("methods").size());
    assertEquals("midpoint_candidate", sparse.get("methods").get(0).get("method").asString());
    assertEquals("ilr_window_reference", sparse.get("methods").get(1).get("method").asString());
    for (JsonNode method : sparse.get("methods")) {
      assertEquals(2, method.get("attempts").size());
      assertTrue(method.get("numericallyAvailable").booleanValue());
      assertFalse(method.get("gatePassed").booleanValue());
      assertTrue(method.has("selectedParameters"));
      assertFalse(method.get("gridBoundaries").isEmpty());
    }
    assertFalse(tuning.get("gatePassed").booleanValue());
    assertFalse(tuning.get("reasons").isEmpty());
  }

  @Test
  void aFailedGridPointIsRetainedWithoutErasingTheAvailableSelection() throws Exception {
    final Path source = temp.resolve("failing-polls.csv");
    Files.write(
        source,
        PollCsvFixtures.csv(
            row("2014-01-10", "2014-01-01", "2014-01-09", "NA")
                + row("2014-01-16", "2014-01-10", "2014-01-14", "NA")
                + row("2014-04-12", "2014-04-09", "2014-04-11", "1")
                + row("2014-05-10", "2014-05-01", "2014-05-05", "1")
                + row("2014-05-16", "2014-04-20", "2014-04-30", "1")
                + row("2014-06-01", "2014-05-20", "2014-05-30", "1")));
    final Path identity = temp.resolve("failing-implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("failing-evidence");
    final Path plan = temp.resolve("failing-plan.json");
    Files.writeString(
        plan,
        plan(source, identity, evidence)
            .replace("[0.000003, 0.00001]", "[0.000003, 1.7976931348623157E308]"),
        StandardCharsets.UTF_8);
    final Path registration = temp.resolve("failing-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));

    final Path result = evidence.resolve("tuning.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "tune", registration.toString(), source.toString(), result.toString()));

    final JsonNode method =
        fold(JSON.readTree(Files.readAllBytes(result)), "eight", "2014-05-15")
            .get("methods")
            .get(0);
    assertEquals("resolved", method.get("attempts").get(0).get("status").asString());
    assertEquals("failed", method.get("attempts").get(1).get("status").asString());
    assertTrue(method.get("attempts").get(1).has("reason"));
    assertTrue(method.get("numericallyAvailable").booleanValue());
    assertFalse(method.get("gatePassed").booleanValue());
    assertEquals(0.000003, method.get("selectedParameters").get("walkVariance").doubleValue());
  }

  @Test
  void anExactTieKeepsTheFirstPointInAscendingGridOrder() throws Exception {
    final Path source = temp.resolve("tie-polls.csv");
    Files.write(
        source,
        PollCsvFixtures.csv(
            row("2014-01-01", "2014-01-01", "2014-01-01", "NA")
                + row("2014-01-03", "2014-01-03", "2014-01-03", "NA")));
    final Path identity = temp.resolve("tie-implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("tie-evidence");
    final Path plan = temp.resolve("tie-plan.json");
    Files.writeString(plan, tiePlan(source, identity, evidence), StandardCharsets.UTF_8);
    final Path registration = temp.resolve("tie-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));

    final Path result = evidence.resolve("tuning.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "tune", registration.toString(), source.toString(), result.toString()));
    final JsonNode methods =
        fold(JSON.readTree(Files.readAllBytes(result)), "eight", "2014-01-02").get("methods");
    for (JsonNode method : methods) {
      assertEquals(
          method.get("attempts").get(0).get("logLikelihood").doubleValue(),
          method.get("attempts").get(1).get("logLikelihood").doubleValue(),
          0);
      assertEquals(0.000003, method.get("selectedParameters").get("walkVariance").doubleValue());
    }
  }

  @Test
  void heldOutPollChangesCannotSelectTuningParameters() throws Exception {
    final String training =
        row("2014-01-10", "2014-01-01", "2014-01-09", "NA")
            + row("2014-01-16", "2014-01-10", "2014-01-14", "NA")
            + row("2014-04-12", "2014-04-09", "2014-04-11", "1")
            + row("2014-05-10", "2014-05-01", "2014-05-05", "1");
    final String ipsosHeldOut = row("2014-05-16", "2014-04-20", "2014-04-30", "1");
    final JsonNode ipsos = tune("ipsos", training + ipsosHeldOut);
    final JsonNode novus = tune("novus", training + ipsosHeldOut.replace("Ipsos", "Novus"));

    assertEquals(
        fold(ipsos, "fi", "2014-05-15").get("methods"),
        fold(novus, "fi", "2014-05-15").get("methods"));
  }

  @Test
  void anUnexpectedEmptyActiveFoldStopsBeforeFitting() throws Exception {
    final Path source = temp.resolve("empty-active-polls.csv");
    Files.write(source, PollCsvFixtures.csv(row("2014-01-10", "2014-01-01", "2014-01-09", "NA")));
    final Path identity = temp.resolve("empty-active-implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("empty-active-evidence");
    final Path plan = temp.resolve("empty-active-plan.json");
    Files.writeString(
        plan,
        plan(source, identity, evidence)
            .replace("\"activeThrough\": \"2014-05-15\"", "\"activeThrough\": \"2018-10-21\""),
        StandardCharsets.UTF_8);
    final Path registration = temp.resolve("empty-active-registration.json");

    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(registration))
            .get("reasons")
            .toString()
            .contains("Unexpected empty active"));
    assertEquals(
        "not_run", JSON.readTree(Files.readAllBytes(registration)).get("fitEvidence").asString());
  }

  @Test
  void estimatesSeparateHistoriesJointUncertaintyAndTheSharedDrawRemainder() throws Exception {
    final Estimated run = estimate("shared");

    final JsonNode evidence = run.evidence();
    assertEquals("complete", evidence.get("fitEvidence").asString());
    assertEquals("blocked", evidence.get("status").asString());
    assertFalse(evidence.get("gatePassed").booleanValue());

    // The tuned point comes from this run's own tuning evidence, never from a v1 fitted output.
    final JsonNode provenance = evidence.get("tunedParameters");
    assertEquals("midpoint_candidate", provenance.get("method").asString());
    assertEquals(DevelopmentGates.sha256(run.tuning()), provenance.get("sha256").asString());
    assertEquals(
        0.000003,
        provenance.get("selectedParameters").get("eight").get("walkVariance").doubleValue());
    assertTrue(provenance.get("resolvedFolds").intValue() > 0);

    // Each coverage period keeps its own separately fitted history.
    assertEquals(2, evidence.get("coverage").get("periods").size());
    final JsonNode eight = period(evidence, "eight");
    final JsonNode candidate = period(evidence, "fi");
    assertEquals("2014-01-01", eight.get("support").get("from").asString());
    assertEquals("2014-04-09", candidate.get("support").get("from").asString());
    // An unsupported gap is measured inside the supported window rather than hidden.
    assertEquals(88, eight.get("support").get("largestGap").get("days").intValue());

    // Every registered boundary shift is refitted, and the separate-fit step at the candidate
    // boundary is recorded as a change of modeled membership rather than suppressed.
    assertEquals(1, eight.get("stability").size());
    assertEquals(7, eight.get("stability").get(0).get("shiftDays").intValue());
    final JsonNode boundary = evidence.get("coverage").get("boundaryEffects").get(0);
    assertEquals("2014-04-09", boundary.get("date").asString());
    assertEquals("fi", boundary.get("periodId").asString());
    assertEquals("eight", boundary.get("againstPeriodId").asString());

    // Only the validated roster draws; the FI candidate period publishes nothing.
    assertEquals(1, evidence.get("uncertainty").get("periods").size());
    assertEquals(
        "eight", evidence.get("uncertainty").get("periods").get(0).get("periodId").asString());

    final JsonNode remainder = evidence.get("remainder");
    assertEquals(1, remainder.size());
    assertEquals("drawn", remainder.get(0).get("status").asString());
    assertEquals("OTHER", remainder.get(0).get("members").get(0).asString());
    assertTrue(remainder.get(0).get("maxEndpointSumErrorPoints").doubleValue() <= 1e-9);
    assertTrue(remainder.get(0).get("estimatedDays").intValue() > 0);

    assertEquals("passed", check(evidence, "seeded_reproduction").get("status").asString());
    assertEquals("passed", check(evidence, "comparable_remainder").get("status").asString());
    assertEquals("passed", check(evidence, "interval_endpoint_precision").get("status").asString());
  }

  @Test
  void anUpstreamTuningBlockerIsCarriedIntoTheEstimationResult() throws Exception {
    final JsonNode evidence = estimate("carried").evidence();

    assertTrue(
        evidence
            .get("reasons")
            .valueStream()
            .anyMatch(reason -> reason.asString().startsWith("development tuning: ")),
        evidence.get("reasons")::toString);
    assertFalse(evidence.get("gatePassed").booleanValue());
  }

  @Test
  void tuningEvidenceFromAnotherRegistrationCannotFeedTheEstimate() throws Exception {
    final Estimated run = estimate("foreign");
    final Estimated other = estimate("other");
    // The foreign evidence sits in the registered location, so only its provenance can reject it.
    final Path planted = run.evidenceDirectory().resolve("planted-tuning.json");
    Files.write(planted, Files.readAllBytes(other.tuning()));
    final Path result = run.evidenceDirectory().resolve("foreign-estimation.json");

    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "estimate",
            run.registration().toString(),
            run.source().toString(),
            planted.toString(),
            result.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(result))
            .get("reasons")
            .toString()
            .contains("another registration"));
  }

  @Test
  void aRerunNeverOverwritesRetainedEstimationEvidence() throws Exception {
    final Estimated run = estimate("retained");
    final byte[] retained = Files.readAllBytes(run.result());

    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "estimate",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            run.result().toString()));
    assertArrayEquals(retained, Files.readAllBytes(run.result()));
  }

  private static JsonNode period(JsonNode evidence, String periodId) {
    return evidence
        .get("coverage")
        .get("periods")
        .valueStream()
        .filter(entry -> entry.get("periodId").asString().equals(periodId))
        .findFirst()
        .orElseThrow();
  }

  private static JsonNode check(JsonNode evidence, String name) {
    return evidence
        .get("checks")
        .valueStream()
        .filter(entry -> entry.get("check").asString().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private record Estimated(
      Path source, Path registration, Path tuning, Path result, JsonNode evidence) {
    Path evidenceDirectory() {
      return result.getParent();
    }
  }

  private Estimated estimate(String name) throws Exception {
    final Path source = temp.resolve(name + "-polls.csv");
    Files.write(
        source,
        PollCsvFixtures.csv(
            row("2014-01-10", "2014-01-01", "2014-01-09", "NA")
                + row("2014-01-16", "2014-01-10", "2014-01-14", "NA")
                + row("2014-04-12", "2014-04-09", "2014-04-11", "1")
                + row("2014-05-10", "2014-05-01", "2014-05-05", "1")
                + row("2014-05-16", "2014-04-20", "2014-04-30", "1")
                + row("2014-06-01", "2014-05-20", "2014-05-30", "1")));
    final Path identity = temp.resolve(name + "-implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidenceDirectory = temp.resolve(name + "-evidence");
    final Path plan = temp.resolve(name + "-plan.json");
    Files.writeString(plan, plan(source, identity, evidenceDirectory), StandardCharsets.UTF_8);
    final Path registration = temp.resolve(name + "-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));
    final Path tuning = evidenceDirectory.resolve("tuning.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "tune", registration.toString(), source.toString(), tuning.toString()));
    final Path result = evidenceDirectory.resolve("estimation.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "estimate",
            registration.toString(),
            source.toString(),
            tuning.toString(),
            result.toString()));
    return new Estimated(
        source, registration, tuning, result, JSON.readTree(Files.readAllBytes(result)));
  }

  private static JsonNode fold(JsonNode registration, String period, String cutoff) {
    return registration
        .get("folds")
        .valueStream()
        .filter(row -> row.get("periodId").asString().equals(period))
        .filter(row -> row.get("cutoff").asString().equals(cutoff))
        .findFirst()
        .orElseThrow();
  }

  private static boolean contains(JsonNode rows, int row) {
    return rows.valueStream().anyMatch(value -> value.intValue() == row);
  }

  private static String row(String published, String from, String to, String fi) {
    return "2014-01,Ipsos,20,5,8,5,30,8,5,17,"
        + fi
        + ",10,1000,"
        + published
        + ",Ipsos,"
        + from
        + ","
        + to
        + ",FALSE\n";
  }

  private static String plan(Path source, Path identity, Path output) throws Exception {
    return """
        {
          "version": "test-v2",
          "testFixture": true,
          "registeredOn": "2026-09-13",
          "source": {"path": "%s", "sha256": "%s"},
          "identities": [{"path": "%s", "sha256": "%s"}],
          "environment": {"javaVersion": "%s", "osName": "%s", "osArch": "%s"},
          "outputLocation": "%s",
          "protectedLocations": ["%s"],
          "grid": {
            "walkVariances": [0.000003, 0.00001],
            "houseScales": [0.01],
            "covarianceMultipliers": [0.5]
          },
          "parameterSelection": {"method": "midpoint_candidate", "fold": "latest_resolved_active_cutoff"},
          "coverage": {
            "developmentThrough": "2014-12-31",
            "minObservations": 1,
            "minInstitutes": 1,
            "maxInternalGapDays": 400,
            "boundaryShiftDays": [7],
            "stabilityBurnInDays": 0,
            "maxStabilityShiftPoints": 100.0
          },
          "uncertainty": {
            "draws": 64,
            "intervalLevels": [0.5, 0.95],
            "precisionRepeats": 2,
            "maxIntervalEndpointSpreadPoints": 100.0,
            "maxEndpointSumErrorPoints": 1e-9
          },
          "seeds": {"master": 20260908, "precision": [20260908, 20260909]},
          "periods": [
            {"id": "eight", "from": "2014-01-01", "to": null, "individualFi": false, "activeFrom": "2014-01-15", "activeThrough": "2014-05-15"},
            {"id": "fi", "from": "2014-04-09", "to": "2018-09-07", "individualFi": true, "activeFrom": "2014-05-15", "activeThrough": "2014-05-15"}
          ],
          "folds": [
            {"cutoff": "2014-01-15", "scoreThrough": "2014-02-19"},
            {"cutoff": "2014-05-15", "scoreThrough": "2014-06-19"},
            {"cutoff": "2018-10-21", "scoreThrough": "2018-11-25"}
          ],
          "electionCycleDates": ["2014-09-14"],
          "commands": ["prepare", "preflight"]
        }
        """
        .formatted(
            source,
            DevelopmentGates.sha256(source),
            identity,
            DevelopmentGates.sha256(identity),
            System.getProperty("java.version"),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            output,
            identity);
  }

  private static String tiePlan(Path source, Path identity, Path output) throws Exception {
    return """
        {
          "version": "test-v2",
          "testFixture": true,
          "registeredOn": "2026-09-13",
          "source": {"path": "%s", "sha256": "%s"},
          "identities": [{"path": "%s", "sha256": "%s"}],
          "environment": {"javaVersion": "%s", "osName": "%s", "osArch": "%s"},
          "outputLocation": "%s",
          "protectedLocations": ["%s"],
          "grid": {
            "walkVariances": [0.000003, 0.00001],
            "houseScales": [0.01],
            "covarianceMultipliers": [0.5]
          },
          "periods": [
            {"id": "eight", "from": "2014-01-01", "to": null, "individualFi": false, "activeFrom": "2014-01-02", "activeThrough": "2014-01-02"}
          ],
          "folds": [
            {"cutoff": "2014-01-02", "scoreThrough": "2014-02-06"}
          ],
          "electionCycleDates": ["2014-09-14"],
          "commands": ["prepare", "preflight"]
        }
        """
        .formatted(
            source,
            DevelopmentGates.sha256(source),
            identity,
            DevelopmentGates.sha256(identity),
            System.getProperty("java.version"),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            output,
            identity);
  }

  private JsonNode tune(String name, String rows) throws Exception {
    final Path source = temp.resolve(name + "-polls.csv");
    Files.write(source, PollCsvFixtures.csv(rows));
    final Path identity = temp.resolve(name + "-implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve(name + "-evidence");
    final Path plan = temp.resolve(name + "-plan.json");
    Files.writeString(plan, plan(source, identity, evidence), StandardCharsets.UTF_8);
    final Path registration = temp.resolve(name + "-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));
    final Path result = evidence.resolve("tuning.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "tune", registration.toString(), source.toString(), result.toString()));
    return JSON.readTree(Files.readAllBytes(result));
  }
}
