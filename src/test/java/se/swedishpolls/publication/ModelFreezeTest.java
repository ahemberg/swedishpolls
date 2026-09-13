package se.swedishpolls.publication;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.swedishpolls.estimation.CoverageValidation;
import se.swedishpolls.estimation.ReleaseAudit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The shipped frozen model must stay the development evidence, not a second opinion of it. */
class ModelFreezeTest {
  @Test
  void coalitionPrecisionMatchesItsSeparateRegistration() throws Exception {
    final JsonNode registered = read(Path.of("docs/validation/coalition-history-protocol.json"));
    final se.swedishpolls.estimation.CoalitionPrecision.Rules shipped =
        ModelFreeze.load().coalitionPrecision();
    assertEquals(registered.get("draws").asInt(), shipped.draws());
    assertEquals(registered.get("referenceDraws").asInt(), shipped.referenceDraws());
    assertEquals(registered.get("referenceSeed").asLong(), shipped.referenceSeed());
    assertEquals(registered.get("maxMeanErrorPoints").asDouble(), shipped.maxMeanErrorPoints());
    assertEquals(
        registered.get("maxEndpointErrorPoints").asDouble(), shipped.maxEndpointErrorPoints());
    final List<Long> seeds = new ArrayList<>();
    for (final JsonNode seed : registered.get("seeds")) seeds.add(seed.longValue());
    assertEquals(seeds, shipped.seeds());
  }

  private static final Path PROTOCOL = Path.of("docs", "validation", "protocol.json");
  private static final Path COVERAGE = Path.of("docs", "validation", "coverage.json");
  private static final Path AUDIT = Path.of("docs", "validation", "release-audit.json");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static JsonNode read(Path file) throws Exception {
    return JSON.readTree(Files.readAllBytes(file));
  }

  @Test
  void theShippedFreezeCarriesTheRegisteredProtocolSeedDrawsAndResolution() throws Exception {
    final ModelFreeze freeze = ModelFreeze.load();
    final JsonNode protocol = read(PROTOCOL);
    assertEquals(protocol.get("version").asString(), freeze.developmentProtocolVersion());
    assertEquals(protocol.get("seed").longValue(), freeze.uncertainty().seed());
    assertEquals(protocol.get("final_draws").intValue(), freeze.uncertainty().draws());
    final List<Double> levels = new ArrayList<>();
    for (final JsonNode level : protocol.get("uncertainty").get("interval_levels")) {
      levels.add(level.doubleValue());
    }
    assertEquals(levels, freeze.uncertainty().intervalLevels());
    assertEquals(levels.getLast(), freeze.intervalLevel());
    assertEquals(
        protocol.get("publication").get("decimals").intValue(), freeze.resolution().decimals());
  }

  @Test
  void theShippedCoverageRulesAreTheRegisteredOnes() throws Exception {
    final CoverageValidation.Rules shipped = ModelFreeze.load().developmentCoverage();
    assertEquals(CoverageValidation.rules(PROTOCOL), shipped);
  }

  @Test
  void aPublicationReadsCorrectedHistoryThroughItsOwnLastFieldworkDate() {
    final ModelFreeze freeze = ModelFreeze.load();
    final java.time.LocalDate fieldwork = java.time.LocalDate.of(2026, 9, 5);
    final CoverageValidation.Rules publication = freeze.coverageThrough(fieldwork);
    assertEquals(fieldwork, publication.developmentThrough());
    assertEquals(freeze.developmentCoverage().minObservations(), publication.minObservations());
    assertEquals(freeze.developmentCoverage().minInstitutes(), publication.minInstitutes());
    assertEquals(
        freeze.developmentCoverage().maxInternalGapDays(), publication.maxInternalGapDays());
    assertTrue(
        freeze.developmentCoverage().developmentThrough().isBefore(fieldwork),
        "Development evidence stops where the registered protocol says it stops");
  }

  @Test
  void everyValidatedPeriodCarriesTheParametersTheCoverageEvidenceResolved() throws Exception {
    final ModelFreeze freeze = ModelFreeze.load();
    for (final JsonNode period : read(COVERAGE).get("periods")) {
      final String id = period.get("periodId").asString();
      final ModelFreeze.Period shipped = freeze.period(id);
      assertEquals(period.get("supported").booleanValue(), shipped.supported(), id);
      final JsonNode parameters = period.get("parameters");
      assertEquals(
          parameters.get("walkVariance").doubleValue(), shipped.parameters().walkVariance());
      assertEquals(parameters.get("houseScale").doubleValue(), shipped.parameters().houseScale());
      assertEquals(
          parameters.get("covarianceMultiplier").doubleValue(),
          shipped.parameters().covarianceMultiplier());
    }
  }

  @Test
  void theReleaseVerdictTravelsWithTheFreezeAndCurrentlyBlocksPublication() throws Exception {
    final ModelFreeze freeze = ModelFreeze.load();
    final JsonNode audit = read(AUDIT);
    assertEquals(audit.get("frozen").get("version").asString(), freeze.releaseProtocolVersion());
    assertEquals(audit.get("verdict").get("status").asString(), freeze.releaseStatus());
    final List<String> failed = new ArrayList<>();
    for (final JsonNode gate : audit.get("verdict").get("gates")) {
      if (ReleaseAudit.BLOCKING.equals(gate.get("enforcement").asString())
          && !gate.get("passed").booleanValue()
          && gate.get("waiver").isNull()) {
        failed.add(gate.get("name").asString());
      }
    }
    assertEquals(failed, freeze.failedBlockingGates());
    assertFalse(
        freeze.released(),
        "The audited verdict blocks release, so a deployment publishes nothing until it changes");
  }

  @Test
  void anUnknownCoveragePeriodHasNoFrozenFitRatherThanADefaultOne() {
    assertThrows(IllegalArgumentException.class, () -> ModelFreeze.load().period("nonesuch"));
  }
}
