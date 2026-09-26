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
import tools.jackson.databind.node.ObjectNode;

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
    assertEquals(
        ModelFreeze.Authorization.NONE,
        freeze.authorization(),
        "The audited verdict blocks release, so a deployment publishes nothing until it changes");
  }

  @Test
  void trendGateUsesOnlyTheRegisteredCentralEstimateChecks() throws Exception {
    final JsonNode registration = read(Path.of("docs/validation/release-protocol.json"));
    final JsonNode gates = read(AUDIT).get("verdict").get("gates");
    final List<String> failed = new ArrayList<>();
    final List<String> requiredNames = new ArrayList<>();
    for (final JsonNode required :
        registration.get("publication_gates").get("trend_publication").get("required")) {
      requiredNames.add(required.asString());
      boolean passed = false;
      for (final JsonNode gate : gates) {
        if (required.asString().equals(gate.get("name").asString())) {
          passed = gate.get("passed").booleanValue() && gate.get("waiver").isNull();
        }
      }
      if (!passed) failed.add(required.asString());
    }
    assertEquals(failed, ModelFreeze.load().failedTrendGates());
    assertEquals(
        List.of(
            "audit_composition_sum",
            "audit_seat_total",
            "audit_seeded_reproduction",
            "tolerance:cross_architecture_reproduction",
            "predictive_log_score_vs_recency:eight_party_2010",
            "predictive_log_score_vs_recency:fi_candidate_2014_2018"),
        requiredNames,
        "Party and institute coverage gates cannot enter trend authorization");
    assertEquals(ModelFreeze.Authorization.NONE, ModelFreeze.load().authorization());
  }

  @Test
  void authorizationFallsBackOnlyToAGateThatPassed() throws Exception {
    final ObjectNode freeze = (ObjectNode) shipped();
    freeze.put("authorizedLevel", "trend");
    assertEquals(ModelFreeze.Authorization.TREND, ModelFreeze.parse(freeze).authorization());

    ((ObjectNode) freeze.get("trendGate")).putArray("failedGates").add("audit_seat_total");
    assertEquals(ModelFreeze.Authorization.NONE, ModelFreeze.parse(freeze).authorization());

    freeze.put("authorizedLevel", "calibrated");
    assertEquals(ModelFreeze.Authorization.NONE, ModelFreeze.parse(freeze).authorization());
    ((ObjectNode) freeze.get("trendGate")).putArray("failedGates");
    assertEquals(ModelFreeze.Authorization.TREND, ModelFreeze.parse(freeze).authorization());
    ((ObjectNode) freeze.get("release")).put("status", "released").putArray("failedBlockingGates");
    assertEquals(ModelFreeze.Authorization.CALIBRATED, ModelFreeze.parse(freeze).authorization());
    ((ObjectNode) freeze.get("trendGate")).putArray("failedGates").add("audit_seat_total");
    assertEquals(ModelFreeze.Authorization.NONE, ModelFreeze.parse(freeze).authorization());
  }

  private static JsonNode shipped() throws Exception {
    try (final java.io.InputStream input =
        ModelFreeze.class.getResourceAsStream(ModelFreeze.RESOURCE)) {
      return JSON.readTree(input.readAllBytes());
    }
  }

  @Test
  void everyShippedSettingCarriesARegisteredSourceOrAWrittenDerivation() throws Exception {
    assertEquals(List.of(), FreezeRegistration.check(shipped()));
  }

  @Test
  void aShippedFieldWithoutARegisteredBasisFailsWhereverItIsAdded() throws Exception {
    final ObjectNode added = (ObjectNode) shipped();
    added.put("maxDriftPoints", 10.0);
    assertEquals(
        List.of("maxDriftPoints"),
        FreezeRegistration.check(added).stream().map(FreezeRegistration.Failure::path).toList());

    final ObjectNode nested = (ObjectNode) shipped();
    ((ObjectNode) nested.get("periods").get(0).get("parameters")).put("perInstituteVariance", 0.2);
    assertEquals(
        List.of("periods[]/parameters/perInstituteVariance"),
        FreezeRegistration.check(nested).stream().map(FreezeRegistration.Failure::path).toList());
  }

  @Test
  void aShippedSettingThatDisagreesWithItsRegisteredValueFails() throws Exception {
    final ObjectNode changed = (ObjectNode) shipped();
    changed.put("draws", 40000);
    ((ObjectNode) changed.get("periods").get(1).get("parameters")).put("houseScale", 0.2);

    assertEquals(
        List.of("draws", "periods[]/parameters/houseScale"),
        FreezeRegistration.check(changed).stream().map(FreezeRegistration.Failure::path).toList());
  }

  @Test
  void aRegisteredSettingThatStopsBeingShippedFails() throws Exception {
    final ObjectNode removed = (ObjectNode) shipped();
    removed.remove("seed");

    assertEquals(
        List.of("seed"),
        FreezeRegistration.check(removed).stream().map(FreezeRegistration.Failure::path).toList());
  }

  @Test
  void anUnknownCoveragePeriodHasNoFrozenFitRatherThanADefaultOne() {
    assertThrows(IllegalArgumentException.class, () -> ModelFreeze.load().period("nonesuch"));
  }
}
