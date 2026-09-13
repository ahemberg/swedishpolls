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
        DevelopmentValidation.REJECTED,
        DevelopmentValidation.run(
            "preflight", registration.toString(), source.toString(), result.toString()));
    assertTrue(
        JSON.readTree(Files.readAllBytes(result)).get("reasons").toString().contains("repository"));
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
          "periods": [
            {"id": "eight", "from": "2010-01-01", "to": null, "individualFi": false, "activeFrom": "2014-01-15", "activeThrough": "2014-05-15"},
            {"id": "fi", "from": "2014-04-09", "to": "2018-09-07", "individualFi": true, "activeFrom": "2014-05-15", "activeThrough": "2014-05-15"}
          ],
          "folds": [
            {"cutoff": "2014-01-15", "scoreThrough": "2014-02-19"},
            {"cutoff": "2014-05-15", "scoreThrough": "2014-06-19"},
            {"cutoff": "2018-10-21", "scoreThrough": "2018-11-25"}
          ],
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
}
