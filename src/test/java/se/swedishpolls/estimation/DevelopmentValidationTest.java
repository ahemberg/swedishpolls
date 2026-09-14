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
  private static final Path PREFLIGHT =
      Path.of("docs", "validation", "v2-development-1", "preflight.json");

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
    assertEquals(9, registration.get("plan").get("commands").size());
    assertTrue(registration.get("plan").get("commands").get(2).asString().contains("'tune "));
    assertTrue(registration.get("plan").get("commands").get(3).asString().contains("'estimate "));
    assertTrue(registration.get("plan").get("commands").get(4).asString().contains("'diagnose "));
    assertTrue(registration.get("plan").get("commands").get(5).asString().contains("'measure "));
    assertTrue(registration.get("plan").get("commands").get(6).asString().contains("'reproduce "));
    assertTrue(registration.get("plan").get("commands").get(8).asString().contains("'compare "));
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
    final JsonNode outcomes = registration.get("plan").get("outcomes");
    assertEquals(10_000, registration.get("plan").get("uncertainty").get("draws").intValue());
    assertEquals(0.005, outcomes.get("maxMonteCarloStandardError").doubleValue());
    assertEquals(0.03, outcomes.get("maxThresholdProbabilitySpread").doubleValue());
    assertEquals(0.03, outcomes.get("maxMajorityProbabilitySpread").doubleValue());
    assertEquals(5, outcomes.get("allocationRules").size());
    assertEquals(1.4, outcomes.get("allocationRules").get(0).get("firstDivisor").doubleValue());
    assertEquals(1.2, outcomes.get("allocationRules").get(4).get("firstDivisor").doubleValue());
    final JsonNode measurements = registration.get("plan").get("measurements");
    assertEquals(0.44, measurements.get("snapshot").get("maxShiftPoints").doubleValue());
    assertEquals(2e-14, measurements.get("reproduction").get("maxDifferencePoints").doubleValue());
    assertEquals(
        java.util.List.of("linux/amd64", "linux/arm64"),
        measurements
            .get("reproduction")
            .get("platforms")
            .valueStream()
            .map(JsonNode::asString)
            .toList());
    assertFalse(measurements.get("resources").get("runtimeTargetBlocking").booleanValue());
    assertEquals(
        1_800_000, measurements.get("resources").get("hostPipelineRequirementMillis").longValue());

    final JsonNode preflight = JSON.readTree(Files.readAllBytes(PREFLIGHT));
    assertEquals("ready", preflight.get("status").asString());
    assertEquals(
        DevelopmentGates.sha256(REGISTRATION), preflight.get("registrationSha256").asString());
    assertEquals(75, preflight.get("activeFolds").intValue());

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
  void retainsPredictiveRowsAndBlockedDiagnosticsThroughTheOperatorEntryPoint() throws Exception {
    final Path source = temp.resolve("diagnostic-polls.csv");
    Files.write(
        source,
        PollCsvFixtures.csv(
            row("2014-01-10", "2014-01-01", "2014-01-09", "NA")
                + row("2014-01-16", "2014-01-10", "2014-01-14", "NA")
                + row("2014-04-12", "2014-04-09", "2014-04-11", "1")
                + row("2014-05-10", "2014-05-01", "2014-05-05", "1")
                + row("2014-05-16", "2014-04-20", "2014-04-30", "1")
                + row("2014-06-01", "2014-05-20", "2014-05-30", "1")));
    final Path identity = temp.resolve("implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("diagnostic-evidence");
    final Path plan = temp.resolve("plan.json");
    Files.writeString(plan, plan(source, identity, evidence), StandardCharsets.UTF_8);
    final Path registration = temp.resolve("registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));
    final Path result = evidence.resolve("diagnostics.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "diagnose", registration.toString(), source.toString(), result.toString()));
    final JsonNode report = JSON.readTree(Files.readAllBytes(result));
    assertEquals("complete", report.get("diagnosticEvidence").asString());
    assertFalse(report.get("gatePassed").booleanValue());
    assertTrue(report.get("reasons").toString().contains("grid boundary"));
    final JsonNode diagnostic = fold(report, "fi", "2014-05-15");
    assertEquals(2, diagnostic.get("polls").size());
    assertEquals(2, diagnostic.get("scoredPolls").intValue());
    final JsonNode poll = diagnostic.get("polls").get(0);
    assertEquals(5, poll.get("rowNumber").intValue());
    assertEquals(11, poll.get("fieldworkDays").intValue());
    assertEquals(1000, poll.get("sampleSize").intValue());
    assertEquals(3, poll.get("methods").size());
    assertEquals(10, poll.get("observedComposition").size());
    for (JsonNode method : poll.get("methods")) {
      assertTrue(Double.isFinite(method.get("jointLogScore").doubleValue()));
      assertEquals(9, method.get("whitenedIlrResiduals").size());
      assertEquals(10, method.get("components").size());
      assertEquals(4000, method.get("draws").intValue());
      assertTrue(method.get("stream").asString().contains("fi|2014-05-15|"));
      final JsonNode artifact = method.get("drawArtifact");
      assertEquals("complete", artifact.get("status").asString());
      final Path draws = evidence.resolve(artifact.get("path").asString());
      assertEquals(4000 * 10 * Double.BYTES, Files.size(draws));
      assertEquals(DevelopmentGates.sha256(draws), artifact.get("sha256").asString());
      try (final java.io.DataInputStream values =
          new java.io.DataInputStream(
              new java.io.BufferedInputStream(Files.newInputStream(draws)))) {
        for (int draw = 0; draw < 4000; draw++) {
          double sum = 0;
          for (int index = 0; index < 10; index++) {
            final double value = values.readDouble();
            assertTrue(value >= 0 && value <= 100);
            sum += value;
          }
          assertEquals(100, sum, 1e-9);
        }
        assertEquals(-1, values.read());
      }
      final JsonNode component = method.get("components").get(0);
      assertTrue(component.get("lower95").doubleValue() <= component.get("lower50").doubleValue());
      assertTrue(component.get("upper95").doubleValue() >= component.get("upper50").doubleValue());
    }
    assertEquals(2, report.get("diagnostics").size());
    assertEquals(
        "unevaluated", report.get("diagnostics").get(0).get("paired").get("status").asString());
    final ObjectNode archive = JSON.createObjectNode();
    final tools.jackson.databind.node.ArrayNode oldFolds = archive.putArray("folds");
    final JsonNode frozen = JSON.readTree(Files.readAllBytes(registration));
    for (JsonNode manifest : frozen.get("folds")) {
      if (!manifest.get("active").booleanValue()) continue;
      final ObjectNode old = oldFolds.addObject();
      old.set("periodId", manifest.get("periodId"));
      old.set("cutoff", manifest.get("cutoff"));
      old.set("trainingRowsSha256", manifest.get("trainingRowsSha256"));
      old.set("scoredRowsSha256", manifest.get("scoringRowsSha256"));
      old.put("scoredPolls", manifest.get("scoringRows").size());
      old.putObject("meanLogScore").put("midpoint", 1).put("recency", 0).put("ilr_window", 2);
      old.putObject("candidateParameters").put("walkVariance", 0.001);
      old.putObject("referenceParameters").put("walkVariance", 0.001);
    }
    final tools.jackson.databind.node.ArrayNode oldMisfit = archive.putArray("misfit");
    for (JsonNode summary : report.get("diagnostics"))
      for (JsonNode misfit : summary.get("misfit")) {
        if (!misfit.get("candidate").asString().equals("midpoint")) continue;
        final ObjectNode old = (ObjectNode) misfit.deepCopy();
        old.put("coverage95", 0.95);
        oldMisfit.add(old);
      }
    final Path archiveFile = temp.resolve("old-diagnostics.json");
    Files.writeString(archiveFile, JSON.writeValueAsString(archive), StandardCharsets.UTF_8);
    final ObjectNode comparisonPlan = (ObjectNode) JSON.readTree(Files.readAllBytes(plan));
    comparisonPlan.put("archivedDiagnostics", archiveFile.toString());
    ((tools.jackson.databind.node.ArrayNode) comparisonPlan.get("identities"))
        .addObject()
        .put("path", archiveFile.toString())
        .put("sha256", DevelopmentGates.sha256(archiveFile));
    comparisonPlan.put("outputLocation", temp.resolve("comparison-evidence").toString());
    final Path comparisonPlanFile = temp.resolve("comparison-plan.json");
    Files.writeString(
        comparisonPlanFile, JSON.writeValueAsString(comparisonPlan), StandardCharsets.UTF_8);
    final Path comparisonRegistration = temp.resolve("comparison-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare",
            comparisonPlanFile.toString(),
            source.toString(),
            comparisonRegistration.toString()));
    final Path comparisonResult = temp.resolve("comparison-evidence/diagnostics.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "diagnose",
            comparisonRegistration.toString(),
            source.toString(),
            comparisonResult.toString()));
    final JsonNode comparisonReport = JSON.readTree(Files.readAllBytes(comparisonResult));
    assertEquals(report.get("folds"), comparisonReport.get("folds"));
    final JsonNode comparison = comparisonReport.get("historicalComparison");
    assertEquals("compared", comparison.get("folds").get(0).get("status").asString());
    assertEquals(
        1.0, comparison.get("folds").get(0).get("oldMeanLogScore").get("midpoint").doubleValue());
    assertEquals(
        0.95,
        comparison.get("midpointSummaries").get(0).get("old").get("coverage95").doubleValue());
    assertTrue(comparison.get("referenceSubgroups").asString().contains("unavailable"));
    final byte[] retained = Files.readAllBytes(result);
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "diagnose", registration.toString(), source.toString(), result.toString()));
    assertArrayEquals(retained, Files.readAllBytes(result));
  }

  @Test
  void pairedScoresWeightFoldsEquallyAndSubgroupFailureSurvivesPooledCoverage() throws Exception {
    final Path source = temp.resolve("paired-polls.csv");
    final java.time.LocalDate start = java.time.LocalDate.of(2014, 1, 1);
    final java.util.Random random = new java.util.Random(156);
    final StringBuilder rows = new StringBuilder();
    final String[] institutes = {"Ipsos", "Novus", "Sifo", "Demoskop", "SCB"};
    for (int day = 0; day < 20; day++)
      rows.append(syntheticRow(start.plusDays(day), institutes[day % 5], random, false));
    for (int fold = 0; fold < 8; fold++) {
      final int count = fold == 0 ? 30 : 20;
      for (int poll = 0; poll < count; poll++)
        rows.append(
            syntheticRow(
                start.plusDays(21 + 40 * fold + poll),
                institutes[poll % 5],
                random,
                poll % 5 == 0));
    }
    Files.write(source, PollCsvFixtures.csv(rows.toString()));
    final Path identity = temp.resolve("paired-implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("paired-evidence");
    final ObjectNode declaration = (ObjectNode) JSON.readTree(tiePlan(source, identity, evidence));
    final ObjectNode period = (ObjectNode) declaration.get("periods").get(0);
    period.put("activeFrom", start.plusDays(20).toString());
    period.put("activeThrough", start.plusDays(300).toString());
    final tools.jackson.databind.node.ArrayNode folds = declaration.putArray("folds");
    for (int fold = 0; fold < 8; fold++) {
      final java.time.LocalDate cutoff = start.plusDays(20 + fold * 40);
      folds
          .addObject()
          .put("cutoff", cutoff.toString())
          .put("scoreThrough", cutoff.plusDays(35).toString());
    }
    final ObjectNode grid = (ObjectNode) declaration.get("grid");
    grid.putArray("walkVariances").add(0.000003);
    grid.putArray("houseScales").add(0.01);
    grid.putArray("covarianceMultipliers").add(1.0);
    final Path plan = temp.resolve("paired-plan.json");
    Files.writeString(plan, JSON.writeValueAsString(declaration), StandardCharsets.UTF_8);
    final Path registration = temp.resolve("paired-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));
    final Path result = evidence.resolve("diagnostics.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "diagnose", registration.toString(), source.toString(), result.toString()));
    final JsonNode report = JSON.readTree(Files.readAllBytes(result));
    final JsonNode summary = report.get("diagnostics").get(0);
    final JsonNode paired = summary.get("paired");
    assertEquals(8, paired.get("scoredFolds").intValue());
    assertEquals(3, paired.get("standardErrorByLag").size());
    double equalFoldMean = 0;
    double pollWeighted = 0;
    int totalPolls = 0;
    for (JsonNode fold : paired.get("folds")) {
      final double difference =
          fold.get("midpoint").doubleValue() - fold.get("recency").doubleValue();
      equalFoldMean += difference / 8;
      pollWeighted += difference * fold.get("polls").intValue();
      totalPolls += fold.get("polls").intValue();
    }
    assertEquals(170, totalPolls);
    assertEquals(
        170, report.get("folds").valueStream().mapToInt(fold -> fold.get("polls").size()).sum());
    for (JsonNode fold : report.get("folds")) {
      assertEquals(fold.get("scoringRows").size(), fold.get("polls").size());
      for (String method : java.util.List.of("midpoint", "ilr_window", "recency")) {
        final double rowMean =
            fold.get("polls")
                .valueStream()
                .mapToDouble(
                    poll ->
                        poll.get("methods")
                            .valueStream()
                            .filter(
                                prediction -> prediction.get("method").asString().equals(method))
                            .findFirst()
                            .orElseThrow()
                            .get("jointLogScore")
                            .doubleValue())
                .average()
                .orElseThrow();
        assertEquals(rowMean, fold.get("meanLogScore").get(method).doubleValue(), 1e-12);
      }
    }
    assertEquals(equalFoldMean, paired.get("baselineDifference").doubleValue(), 1e-12);
    assertNotEquals(
        pollWeighted / totalPolls, paired.get("baselineDifference").doubleValue(), 1e-6);
    final JsonNode pooled =
        summary
            .get("misfit")
            .valueStream()
            .filter(
                row ->
                    row.get("scope").asString().equals("all")
                        && row.get("candidate").asString().equals("midpoint"))
            .findFirst()
            .orElseThrow();
    assertEquals(1530, pooled.get("cases").intValue());
    assertTrue(
        pooled.get("coverage95").doubleValue() >= 0.90
            && pooled.get("coverage95").doubleValue() <= 0.98,
        pooled.toPrettyString());
    assertTrue(
        pooled.get("coverage50").doubleValue() >= 0.40
            && pooled.get("coverage50").doubleValue() <= 0.60,
        pooled.toPrettyString());
    assertTrue(
        summary
            .get("misfit")
            .valueStream()
            .anyMatch(
                row ->
                    row.get("required").booleanValue()
                        && !row.get("scope").asString().equals("all")
                        && row.get("status").asString().equals("fail")));
    for (JsonNode row : summary.get("misfit")) {
      if (row.get("scope").asString().contains("party")) assertFalse(row.has("meanLogScore"));
      if (row.get("cases").intValue() < 100 && !row.get("scope").asString().equals("all"))
        assertEquals("unevaluated", row.get("status").asString());
    }
  }

  private static String syntheticRow(
      java.time.LocalDate date, String institute, java.util.Random random, boolean biased) {
    final double[] shares = {20, 5, 8, 5, 30, 8, 5, 12, 7};
    final double[] noise = new double[shares.length];
    double sum = 0;
    for (int i = 0; i < shares.length; i++) {
      noise[i] = random.nextGaussian() * Math.sqrt(shares[i] / 100 / 1000) * 100;
      sum += noise[i];
    }
    final StringBuilder row = new StringBuilder("2014-01," + institute);
    for (int i = 0; i < shares.length - 1; i++) {
      final double bias = biased ? (i == 0 ? 2 : i == 4 ? -2 : 0) : 0;
      row.append(',')
          .append(
              String.format(
                  java.util.Locale.ROOT,
                  "%.5f",
                  shares[i] + noise[i] - shares[i] / 100 * sum + bias));
    }
    return row + ",NA,10,1000," + date + "," + institute + "," + date + "," + date + ",FALSE\n";
  }

  @Test
  void unavailableFitsLeaveDiagnosticsIncompleteAndRetainEveryFailedSearch() throws Exception {
    final Path source = temp.resolve("unavailable-polls.csv");
    Files.write(
        source,
        PollCsvFixtures.csv(
            row("2014-01-01", "2014-01-01", "2014-01-01", "NA")
                + row("2014-01-02", "2014-01-02", "2014-01-02", "NA")
                + row("2014-01-03", "2014-01-03", "2014-01-03", "NA")));
    final Path identity = temp.resolve("implementation.txt");
    Files.writeString(identity, "implementation", StandardCharsets.UTF_8);
    final Path evidence = temp.resolve("unavailable-evidence");
    final Path plan = temp.resolve("unavailable-plan.json");
    Files.writeString(
        plan,
        tiePlan(source, identity, evidence)
            .replace("[0.000003, 0.00001]", "[1.7976931348623157E308]"),
        StandardCharsets.UTF_8);
    final Path registration = temp.resolve("unavailable-registration.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "prepare", plan.toString(), source.toString(), registration.toString()));
    final Path result = evidence.resolve("diagnostics.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "diagnose", registration.toString(), source.toString(), result.toString()));
    final JsonNode report = JSON.readTree(Files.readAllBytes(result));
    assertEquals("incomplete", report.get("diagnosticEvidence").asString());
    final JsonNode fold = report.get("folds").get(0);
    assertEquals("unevaluated", fold.get("diagnosticStatus").asString());
    assertFalse(fold.has("polls"));
    for (JsonNode search : fold.get("methods")) {
      assertEquals("failed", search.get("attempts").get(0).get("status").asString());
      assertFalse(search.get("numericallyAvailable").booleanValue());
    }
    assertTrue(report.get("reasons").toString().contains("diagnostics unevaluated"));
    assertFalse(report.get("releaseAuthorized").booleanValue());
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
            "diagnose", registration.toString(), source.toString(), result.toString()));

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
    assertTrue(
        java.util.Set.of(0.000003, 0.00001)
            .contains(
                provenance
                    .get("selectedParameters")
                    .get("eight")
                    .get("walkVariance")
                    .doubleValue()));
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

    final JsonNode outcomes = evidence.get("outcomes");
    assertEquals(2, outcomes.size());
    final JsonNode published = outcome(evidence, "eight");
    assertEquals("complete", published.get("status").asString());
    assertEquals(349, published.get("verifiedSeatTotal").intValue());
    assertEquals(64, published.get("verifiedDraws").intValue());
    assertEquals(published.get("seats").get("daySeed"), published.get("coalitions").get("daySeed"));
    assertEquals(2, published.get("probabilityRuns").size());
    assertEquals(20260908, published.get("probabilityRuns").get(0).get("seed").longValue());
    assertEquals(64, published.get("probabilityRuns").get(0).get("draws").intValue());
    assertTrue(published.get("probabilityRuns").get(0).has("maxMonteCarloStandardError"));
    assertEquals("unavailable", outcome(evidence, "fi").get("status").asString());

    final JsonNode sensitivities = published.get("sensitivity");
    assertEquals("centering", sensitivities.get(0).get("kind").asString());
    assertTrue(sensitivities.get(0).get("comparedDays").intValue() > 0);
    assertEquals(
        sensitivities.get(0).get("comparedOn"),
        sensitivities.get(0).get("probabilities").get("comparedOn"));
    assertTrue(
        sensitivities
            .valueStream()
            .anyMatch(entry -> entry.get("kind").asString().equals("leave_one_institute_out")));
    assertTrue(
        sensitivities
            .valueStream()
            .anyMatch(
                entry -> entry.get("needsDisclosure").booleanValue() && entry.has("disclosure")),
        sensitivities::toPrettyString);

    assertEquals("passed", check(evidence, "seat_totals").get("status").asString());
    assertEquals("passed", check(evidence, "joint_probability_coherence").get("status").asString());
    assertEquals(
        "failed",
        check(evidence, "probability_monte_carlo_standard_error").get("status").asString());
    assertEquals(
        "failed", check(evidence, "threshold_probability_precision").get("status").asString());
    assertEquals(
        "failed", check(evidence, "majority_probability_precision").get("status").asString());
    assertTrue(
        evidence.get("reasons").toString().contains("probability Monte Carlo standard error"));
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

  @Test
  void measuresSnapshotDriftAndResourcesThroughTheValidationEntryPoint() throws Exception {
    final Estimated run = estimate("measured");
    final Path result = run.evidenceDirectory().resolve("measurement.json");

    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "measure",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            result.toString()));

    final JsonNode evidence = JSON.readTree(Files.readAllBytes(result));
    assertEquals("blocked", evidence.get("status").asString());
    assertEquals("passed", check(evidence, "snapshot_drift").get("status").asString());
    final JsonNode perturbations = evidence.get("snapshotDrift").get("perturbations");
    assertEquals(4, perturbations.size());
    for (JsonNode perturbation : perturbations) {
      assertTrue(
          java.util.Set.of("addition_deletion", "correction")
              .contains(perturbation.get("kind").asString()));
      assertTrue(perturbation.get("rowNumber").intValue() > 0);
      assertFalse(perturbation.get("institute").asString().isBlank());
      assertFalse(perturbation.get("collectionFrom").asString().isBlank());
      assertFalse(perturbation.get("collectionTo").asString().isBlank());
      assertFalse(perturbation.get("maxShiftOn").asString().isBlank());
      assertFalse(perturbation.get("component").asString().isBlank());
      assertTrue(perturbation.get("comparedValues").longValue() > 0);
      assertTrue(perturbation.get("maxShiftPoints").doubleValue() >= 0);
    }
    assertEquals(
        "latest_eligible_poll_per_institute",
        evidence.get("snapshotDrift").get("rowSelection").asString());
    final JsonNode resources = evidence.get("resources");
    assertTrue(resources.get("runtimeMillis").longValue() > 0);
    assertTrue(resources.get("peakHeapBytes").longValue() > 0);
    assertEquals(10_000, resources.get("runtimeTargetMillis").longValue());
    assertFalse(resources.get("runtimeTargetBlocking").booleanValue());
    assertEquals(1_800_000, resources.get("hostPipelineRequirementMillis").longValue());
    assertEquals("unevaluated", resources.get("hostPipelineRequirementStatus").asString());
    assertTrue(resources.get("inputPolls").intValue() > 0);
    assertTrue(resources.get("estimatedDays").intValue() > 0);
    assertEquals(64, resources.get("finalDraws").intValue());
    assertFalse(resources.get("measurementBoundary").asString().isBlank());
  }

  @Test
  void retainsAndComparesSyntheticDrawsWithoutReplacingEitherRun() throws Exception {
    final Estimated run = estimate("architecture");
    final Path first = run.evidenceDirectory().resolve("reproduction-amd64.json");
    final Path second = run.evidenceDirectory().resolve("reproduction-arm64.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "reproduce",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            first.toString()));
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "reproduce",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            second.toString()));
    final JsonNode firstRun = JSON.readTree(Files.readAllBytes(first));
    final ObjectNode secondRun = (ObjectNode) JSON.readTree(Files.readAllBytes(second));
    assertEquals("complete", firstRun.get("status").asString());
    assertEquals(1, firstRun.get("periods").size());
    assertEquals(64 * 9, firstRun.get("artifact").get("values").intValue());
    assertTrue(
        Files.isRegularFile(
            run.evidenceDirectory().resolve(firstRun.get("artifact").get("path").asString())));

    secondRun.put("architecture", "arm64");
    secondRun.put("platform", "linux/arm64");
    for (JsonNode period : secondRun.get("periods"))
      ((ObjectNode) period.get("reproduction")).put("osArch", "arm64");
    Files.writeString(second, JSON.writeValueAsString(secondRun), StandardCharsets.UTF_8);
    final Path compared = run.evidenceDirectory().resolve("cross-architecture.json");
    assertEquals(
        DevelopmentValidation.SUCCESS,
        DevelopmentValidation.run(
            "compare",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            first.toString(),
            second.toString(),
            compared.toString()));
    final JsonNode comparison = JSON.readTree(Files.readAllBytes(compared));
    assertEquals(
        "passed", check(comparison, "cross_architecture_reproduction").get("status").asString());
    assertEquals(64 * 9, comparison.get("comparedValues").intValue());
    assertEquals(0, comparison.get("maxAbsoluteDifferencePoints").doubleValue());
    assertEquals(2, comparison.get("runs").size());

    final Path secondArtifact =
        run.evidenceDirectory().resolve(secondRun.get("artifact").get("path").asString());
    final byte[] shiftedDraws = Files.readAllBytes(secondArtifact);
    final java.nio.ByteBuffer shifted = java.nio.ByteBuffer.wrap(shiftedDraws);
    shifted.putDouble(0, shifted.getDouble(0) + 1e-12);
    Files.write(secondArtifact, shiftedDraws);
    ((ObjectNode) secondRun.get("artifact")).put("sha256", DevelopmentGates.sha256(secondArtifact));
    Files.writeString(second, JSON.writeValueAsString(secondRun), StandardCharsets.UTF_8);
    final Path failed = run.evidenceDirectory().resolve("cross-architecture-failed.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "compare",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            first.toString(),
            second.toString(),
            failed.toString()));
    assertEquals(
        "failed",
        check(JSON.readTree(Files.readAllBytes(failed)), "cross_architecture_reproduction")
            .get("status")
            .asString());

    final Path missingResult = run.evidenceDirectory().resolve("missing-architecture.json");
    assertEquals(
        DevelopmentValidation.BLOCKED,
        DevelopmentValidation.run(
            "compare",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            first.toString(),
            run.evidenceDirectory().resolve("missing.json").toString(),
            missingResult.toString()));
    assertEquals(
        "unevaluated",
        check(JSON.readTree(Files.readAllBytes(missingResult)), "cross_architecture_reproduction")
            .get("status")
            .asString());

    final ObjectNode mixed = secondRun.deepCopy();
    mixed.put("sourceSha256", "0".repeat(64));
    final Path mixedRun = run.evidenceDirectory().resolve("mixed.json");
    Files.writeString(mixedRun, JSON.writeValueAsString(mixed), StandardCharsets.UTF_8);
    final Path rejected = run.evidenceDirectory().resolve("mixed-result.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "compare",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            first.toString(),
            mixedRun.toString(),
            rejected.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(rejected)).get("reasons").toString().contains("source"));

    final ObjectNode mixedEnvironment = secondRun.deepCopy();
    mixedEnvironment.put("javaRuntime", "foreign-runtime");
    final Path mixedEnvironmentRun = run.evidenceDirectory().resolve("mixed-environment.json");
    Files.writeString(
        mixedEnvironmentRun, JSON.writeValueAsString(mixedEnvironment), StandardCharsets.UTF_8);
    final Path environmentRejected =
        run.evidenceDirectory().resolve("mixed-environment-result.json");
    assertEquals(
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "compare",
            run.registration().toString(),
            run.source().toString(),
            run.tuning().toString(),
            first.toString(),
            mixedEnvironmentRun.toString(),
            environmentRejected.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(environmentRejected))
            .get("reasons")
            .toString()
            .contains("runtime"));
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

  private static JsonNode outcome(JsonNode evidence, String periodId) {
    return evidence
        .get("outcomes")
        .valueStream()
        .filter(entry -> entry.get("periodId").asString().equals(periodId))
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
            outcomeRow("2014-01-10", "2014-01-01", "2014-01-09", "NA", "Ipsos")
                + biasedRow("2014-01-16", "2014-01-10", "2014-01-14", "NA")
                + outcomeRow("2014-04-12", "2014-04-09", "2014-04-11", "1", "Ipsos")
                + biasedRow("2014-05-10", "2014-05-01", "2014-05-05", "1")
                + outcomeRow("2014-05-16", "2014-04-20", "2014-04-30", "1", "Ipsos")
                + biasedRow("2014-06-01", "2014-05-20", "2014-05-30", "1")));
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
    return row(published, from, to, fi, "Ipsos");
  }

  private static String row(String published, String from, String to, String fi, String institute) {
    return "2014-01,"
        + institute
        + ",20,5,8,5,30,8,5,17,"
        + fi
        + ",10,1000,"
        + published
        + ","
        + institute
        + ","
        + from
        + ","
        + to
        + ",FALSE\n";
  }

  private static String biasedRow(String published, String from, String to, String fi) {
    return outcomeRow(published, from, to, fi, "Novus")
        .replace("20,4,8,5,30,8,5,17", "5,4,8,5,45,8,5,17");
  }

  private static String outcomeRow(
      String published, String from, String to, String fi, String institute) {
    return row(published, from, to, fi, institute)
        .replace("20,5,8,5,30,8,5,17", "20,4,8,5,30,8,5,17")
        .replace(",10,1000,", ",11,1000,");
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
          "outcomes": {
            "maxMonteCarloStandardError": 0.005,
            "maxThresholdProbabilitySpread": 0.0,
            "maxMajorityProbabilitySpread": 0.0,
            "sensitivityDisclosurePoints": 10.0,
            "allocationRules": [
              {
                "electionYear": 2014,
                "seats": 349,
                "thresholdPercent": 4.0,
                "thresholdInclusive": true,
                "firstDivisor": 1.4,
                "subsequentDivisorFormula": "2 * seats_already_allocated + 1",
                "tieOrder": ["S", "M", "SD", "V", "C", "KD", "L", "MP", "FI"],
                "otherReceivesSeats": false,
                "constituencyExceptionsIncluded": false,
                "officialTieRule": "lottery",
                "sourceUrl": "https://www.val.se/"
              }
            ]
          },
          "measurements": {
            "snapshot": {
              "rowSelection": "latest_eligible_poll_per_institute",
              "correctionFrom": "OTHER",
              "correctionTo": "M",
              "correctionPoints": 0.1,
              "maxShiftPoints": 100.0
            },
            "reproduction": {
              "architectures": ["amd64", "arm64"],
              "maxDifferencePoints": 2e-14
            },
            "resources": {
              "runtimeTargetMillis": 10000,
              "runtimeTargetBlocking": false,
              "hostPipelineRequirementMillis": 1800000,
              "measurementBoundary": "coverage through corrected history, joint uncertainty, repeated-seed probabilities and comparable remainder"
            }
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
