package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import se.swedishpolls.estimation.Coalitions;
import se.swedishpolls.publication.PublicationDocuments;
import se.swedishpolls.publication.PublicationOutcome;
import se.swedishpolls.publication.ShareImages;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.publication.repository.PublicationLock;
import se.swedishpolls.publication.repository.PublicationStore;
import se.swedishpolls.publication.service.Publisher;
import se.swedishpolls.publication.service.TestPublication;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.source.service.NationalAllocationRuleService;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;
import se.swedishpolls.testsupport.TestDatabase;
import se.swedishpolls.web.controller.ApiErrors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frozen v1 surface, served from one real published publication. These checks keep the
 * publication-to-HTTP wiring; request errors and cache policy live in {@code ApiV1ControllerTest}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "logging.level.WireMock=warn",
      "polls.ingest.enabled=false",
      "publication.enabled=false",
      "polls.source-url=${wiremock.server.baseUrl}/polls.csv",
      "spring.docker.compose.enabled=false",
      "spring.flyway.clean-disabled=false"
    })
@Import({TestDatabase.Configuration.class, ApiV1IT.Fixture.class})
@EnableWireMock
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class ApiV1IT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int DRAWS = 200;

  private static Path root;
  private static String publicationId;

  /** Only the freeze is replaced: the source is served over HTTP like the production one. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Fixture {
    @Bean
    @Primary
    Publisher publisher(
        PublicationLock lock,
        SnapshotIngest ingest,
        PollQueryService queries,
        ElectionReferenceService electionReferences,
        NationalAllocationRuleService allocationRules,
        PublicationStore store) {
      return TestPublication.released(
          lock, ingest, queries, electionReferences, allocationRules, store, DRAWS);
    }

    @Bean
    PlatformTransactionManager fixtureTransactions(javax.sql.DataSource dataSource) {
      return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    }
  }

  @DynamicPropertySource
  static void volume(DynamicPropertyRegistry registry) throws Exception {
    root = Files.createTempDirectory("swedishpolls-api");
    registry.add("publication.root", () -> root.toString());
  }

  @org.springframework.boot.test.web.server.LocalServerPort private int port;
  @Autowired private Publisher publisher;
  @Autowired private PublicationStore store;
  @Autowired private Flyway flyway;
  @InjectWireMock private WireMockServer wireMock;

  @BeforeEach
  void publishOnce() {
    if (publicationId == null) {
      flyway.clean();
      flyway.migrate();
      TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
      final Publisher.Attempt attempt = publisher.publish();
      assertEquals(PublicationOutcome.PUBLISHED, attempt.outcome(), attempt.detail());
      publicationId = attempt.publicationId();
    }
  }

  @Test
  void publicationMetadataNamesItsRunSnapshotTimesAndImmutableAssets() {
    final JsonNode body = json("/api/v1/publication");
    assertEquals(publicationId, body.get("publicationId").asString());
    assertFalse(body.get("stale").booleanValue());
    assertTrue(body.get("staleSince").isNull());
    assertEquals("/api/v1/publications/" + publicationId, body.get("permalink").asString());
    assertEquals("corrected", body.get("history").asString());
    assertNotEquals(body.get("publishedAt").asString(), body.get("lastFieldworkDate").asString());
    assertFalse(body.get("modelRun").get("runId").asString().isBlank());
    assertFalse(body.get("modelRun").get("codeVersion").asString().isBlank());
    assertFalse(body.get("modelRun").get("runtime").asString().isBlank());
    assertFalse(body.get("modelRun").get("numericalLibrary").asString().isBlank());
    assertTrue(body.get("modelRun").get("seed").longValue() > 0);
    assertTrue(body.get("snapshot").get("sha256").asString().matches("[0-9a-f]{64}"));
    for (final String kind : ShareImages.KINDS) {
      for (final String language : Translations.LANGUAGES) {
        assertTrue(
            body.get("assets")
                .get(kind)
                .get(language)
                .asString()
                .startsWith("/assets/" + publicationId));
      }
    }
  }

  @Test
  void contentEtagsSeparateQueriesSchemasAndTranslations() {
    final String swedish = etag("/api/v1/estimates/latest?language=sv");
    final String english = etag("/api/v1/estimates/latest?language=en");
    assertNotEquals(
        etag("/api/v1/polls?language=sv"),
        etag("/api/v1/polls?language=en"),
        "A translated poll table is its own representation");
    final String seats = etag("/api/v1/seats?language=sv");
    final String sampled = etag("/api/v1/estimates/history?language=sv&step=7");
    final String whole = etag("/api/v1/estimates/history?language=sv&step=1");
    assertNotEquals(swedish, english);
    assertNotEquals(swedish, seats);
    assertNotEquals(sampled, whole);

    final HttpResponse<String> revalidated =
        send("/api/v1/estimates/latest?language=sv", swedish, HttpResponse.BodyHandlers.ofString());
    assertEquals(304, revalidated.statusCode());
  }

  @Test
  void everyDependentResponseIdentifiesItsPublicationAndRun() {
    for (final String path :
        List.of(
            "/api/v1/estimates/latest",
            "/api/v1/estimates/history",
            "/api/v1/polls",
            "/api/v1/institutes",
            "/api/v1/elections",
            "/api/v1/seats",
            "/api/v1/coalitions")) {
      final JsonNode body = json(path);
      assertEquals(publicationId, body.get("publication").get("publicationId").asString(), path);
      assertFalse(body.get("publication").get("runId").asString().isBlank(), path);
      assertTrue(body.get("publication").get("snapshotId").longValue() > 0, path);
    }
  }

  @Test
  void historyIsColumnarInclusiveAndSampledWithoutLosingBoundaries() {
    final JsonNode whole = json("/api/v1/estimates/history");
    final int days = whole.get("dates").size();
    assertTrue(days > 1);
    for (final JsonNode stream : whole.get("series")) {
      assertEquals(days, stream.get("mean").size());
      assertEquals(days, stream.get("lower").size());
      assertEquals(days, stream.get("upper").size());
    }
    assertEquals(days, whole.get("coveragePeriodByDate").size());
    assertTrue(whole.get("range").get("inclusive").booleanValue());

    final String to = whole.get("dates").get(days - 1).asString();
    final JsonNode sampled = json("/api/v1/estimates/history?step=7&to=" + to);
    assertEquals(
        to,
        sampled.get("dates").get(sampled.get("dates").size() - 1).asString(),
        "The last requested supported date survives the step");
    assertEquals(to, sampled.get("range").get("requestedTo").asString());
    assertTrue(sampled.get("dates").size() < days);
  }

  @Test
  void unsupportedValuesAreNullAndNeverZero() {
    final JsonNode latest = json("/api/v1/estimates/latest");
    assertTrue(latest.get("unavailable").get("FI").get("mean").isNull());
    final JsonNode seats = json("/api/v1/seats");
    assertTrue(seats.get("unavailable").get("FI").get("pointSeats").isNull());
    assertEquals(
        PublicationDocuments.NO_VALIDATED_PERIOD,
        seats.get("unavailable").get("FI").get("reason").asString());
    for (final JsonNode series : json("/api/v1/estimates/history").get("series")) {
      if ("FI".equals(series.get("component").asString())) {
        assertTrue(series.get("mean").get(0).isNull());
      }
    }
  }

  @Test
  void seatsKeepPointAllocationApartFromPosteriorMeansAndProbabilities() {
    final JsonNode seats = json("/api/v1/seats");
    assertEquals(349, seats.get("totalSeats").intValue());
    assertEquals(List.of("OTHER"), List.of(seats.get("excludedFromAllocation").get(0).asString()));
    int total = 0;
    for (final JsonNode party : seats.get("parties")) {
      total += party.get("pointSeats").intValue();
      final double probability = party.get("thresholdProbability").doubleValue();
      assertTrue(probability >= 0 && probability <= 1);
      assertTrue(
          party.get("seatInterval").get(0).intValue() <= party.get("meanSeats").doubleValue());
      assertTrue(
          party.get("seatInterval").get(1).intValue() >= party.get("meanSeats").doubleValue());
    }
    assertEquals(349, total);
    assertEquals(1.2, seats.get("allocationRule").get("firstDivisor").doubleValue());
    assertFalse(seats.get("allocationRule").get("constituencyExceptionsIncluded").booleanValue());
    assertEquals(
        349, json("/api/v1/seats?election=2014").get("allocationRule").get("seats").intValue());
    assertEquals(
        1.4,
        json("/api/v1/seats?election=2014")
            .get("allocationRule")
            .get("firstDivisor")
            .doubleValue());
  }

  @Test
  void coalitionsCarryTheApprovedMembershipsAndTheMajorityLine() {
    final JsonNode coalitions = json("/api/v1/coalitions");
    assertEquals(175, coalitions.get("majoritySeats").intValue());
    assertEquals(Coalitions.PRESETS.size(), coalitions.get("coalitions").size());
    assertEquals(Coalitions.TIE_OUTCOME, coalitions.get("comparison").get("tie").asString());
    ApiContractTest.assertEveryUnorderedPair(coalitions.get("comparison").get("pairs"));
    assertEquals(1, json("/api/v1/coalitions?coalition=tido").get("coalitions").size());
    assertEquals(400, get("/api/v1/coalitions?coalition=nonesuch").statusCode());
  }

  @Test
  void theFilteredTableAndItsDownloadAgreeOnRowsAndPrecision() {
    final JsonNode page = json("/api/v1/polls?institute=Novus&pageSize=5");
    assertEquals(5, page.get("polls").size());
    assertTrue(page.get("total").intValue() >= 5);
    final HttpResponse<String> csv = get("/api/v1/polls.csv?institute=Novus");
    assertEquals(200, csv.statusCode());
    assertTrue(header(csv, "Content-Type").startsWith("text/csv"));
    final List<String> lines = csv.body().lines().toList();
    assertEquals(page.get("total").intValue() + 1, lines.size(), "The download does not page");
    final JsonNode first = page.get("polls").get(0);
    final JsonNode shares = first.get("shares");
    for (final String component : PollQuery.COMPONENTS) {
      if (!shares.get(component).isNull()) {
        assertTrue(
            lines.stream()
                .anyMatch(line -> line.contains("," + shares.get(component).asString() + ",")),
            component + " lost its archived precision in the download");
      }
    }
    assertTrue(first.get("eligible").booleanValue());
    assertTrue(
        page.get("csv").asString().startsWith("/api/v1/polls.csv?publication=" + publicationId));
  }

  @Test
  void aPinnedPublicationReadsItsOwnSnapshotAndAnUnknownOneNeverFallsBack() {
    final JsonNode pinned = json("/api/v1/polls?publication=" + publicationId + "&pageSize=1");
    assertEquals(
        store.header(publicationId).orElseThrow().snapshotId(),
        pinned.get("publication").get("snapshotId").longValue());
    assertTrue(
        pinned
            .get("polls")
            .get(0)
            .get("pollId")
            .asString()
            .startsWith(pinned.get("publication").get("snapshotId").longValue() + ":"));

    final HttpResponse<String> unknown =
        get("/api/v1/estimates/latest?publication=pub_00000000T000000Z");
    assertEquals(404, unknown.statusCode());
    assertEquals(ApiErrors.UNKNOWN_PUBLICATION, code(unknown));
  }

  @Test
  void versionedImageLinksAreImmutableAndDecodeAsCards() {
    final JsonNode assets = json("/api/v1/publication").get("assets");
    for (final String kind : ShareImages.KINDS) {
      for (final String language : Translations.LANGUAGES) {
        final String link = assets.get(kind).get(language).asString();
        final HttpResponse<byte[]> image = bytes(link);
        assertEquals(200, image.statusCode(), link);
        assertEquals("image/png", header(image, "Content-Type"));
        assertTrue(header(image, "Cache-Control").contains("immutable"));
        assertTrue(image.body().length > 1000);
      }
    }
    assertEquals(404, bytes("/assets/" + publicationId + "/overview-sv-9.png").statusCode());
  }

  @Test
  void aMultiRequestLoadPinnedToOnePublicationStaysOnThatPublication() {
    final String pinned = "publication=" + publicationId;
    for (final String path :
        List.of(
            "/api/v1/estimates/latest?" + pinned,
            "/api/v1/estimates/history?" + pinned,
            "/api/v1/polls?" + pinned,
            "/api/v1/seats?" + pinned,
            "/api/v1/coalitions?" + pinned)) {
      assertEquals(publicationId, json(path).get("publication").get("publicationId").asString());
    }
    final HttpResponse<String> csv = get("/api/v1/polls.csv?" + pinned);
    assertEquals(200, csv.statusCode());
    assertTrue(header(csv, "Cache-Control").contains("immutable"));
  }

  @Test
  void theComparableRemainderAndTheElectionGroupingHoldFiInEveryPeriod() {
    final JsonNode remainder = json("/api/v1/estimates/latest").get("comparableRemainder");
    assertTrue(remainder.get("lower").doubleValue() <= remainder.get("mean").doubleValue());
    assertTrue(remainder.get("mean").doubleValue() <= remainder.get("upper").doubleValue());
    assertFalse(remainder.get("definition").asString().isBlank());
    final JsonNode grouping =
        json("/api/v1/elections").get("elections").get(0).get("comparableGrouping");
    assertEquals("eight_party_2010", grouping.get("coveragePeriod").asString());
    assertEquals(
        List.of("FI", "RESIDUAL"),
        List.of(grouping.get("other").get(0).asString(), grouping.get("other").get(1).asString()),
        "FI sits inside the compared aggregate where the roster does not separate it");
  }

  /**
   * A page resolves its publication once and passes it back. A publication landing between two of
   * its requests changes what is current and nothing the page already pinned.
   */
  @Test
  @org.junit.jupiter.api.Order(Integer.MAX_VALUE)
  void aPinnedLoadInterruptedByANewPublicationKeepsItsOwnDataCsvAndImages() {
    final String pinned = publicationId;
    final String estimates = json("/api/v1/estimates/latest?publication=" + pinned).toString();
    final String csv = get("/api/v1/polls.csv?publication=" + pinned).body();
    final byte[] card = bytes("/assets/" + pinned + "/overview-sv-1.png").body();

    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt next = publisher.publish();
    assertEquals(PublicationOutcome.PUBLISHED, next.outcome(), next.detail());
    assertNotEquals(pinned, next.publicationId());
    assertEquals(next.publicationId(), json("/api/v1/publication").get("publicationId").asString());

    assertEquals(estimates, json("/api/v1/estimates/latest?publication=" + pinned).toString());
    assertEquals(csv, get("/api/v1/polls.csv?publication=" + pinned).body());
    assertArrayEquals(card, bytes("/assets/" + pinned + "/overview-sv-1.png").body());
    publicationId = next.publicationId();
  }

  @Test
  void institutesReportHouseEffectsAgainstTheirCycleEnsemble() {
    final JsonNode institutes = json("/api/v1/institutes");
    assertFalse(institutes.get("reference").asString().isBlank());
    assertFalse(institutes.get("institutes").isEmpty());
    final JsonNode first = institutes.get("institutes").get(0);
    assertFalse(first.get("institute").asString().isBlank());
    assertTrue(first.get("polls").intValue() > 0);
    final String cycle = institutes.get("electionCycle").asString();
    for (final JsonNode institute : institutes.get("institutes")) {
      for (final JsonNode effect : institute.get("houseEffects")) {
        assertEquals(cycle, effect.get("electionCycle").asString());
        assertTrue(effect.get("lower").doubleValue() <= effect.get("upper").doubleValue());
      }
    }
  }

  @Test
  void electionReferencesKeepTheirOwnGroupingAndIntegerCounts() {
    final JsonNode elections = json("/api/v1/elections");
    assertEquals(4, elections.get("elections").size());
    final JsonNode last = elections.get("elections").get(3);
    assertEquals(2022, last.get("electionYear").intValue());
    assertEquals(6477970, last.get("validVotes").intValue());
    assertEquals(107, last.get("results").get("S").get("officialSeats").intValue());
    assertEquals(3157, last.get("results").get("FI").get("votes").intValue());
    assertEquals(
        "eight_party_2010", last.get("comparableGrouping").get("coveragePeriod").asString());
  }

  private JsonNode json(String path) {
    final HttpResponse<String> response = get(path);
    assertEquals(200, response.statusCode(), path + " -> " + response.body());
    return JSON.readTree(response.body());
  }

  private HttpResponse<String> get(String path) {
    return send(path, null, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> bytes(String path) {
    return send(path, null, HttpResponse.BodyHandlers.ofByteArray());
  }

  private <T> HttpResponse<T> send(
      String path, String ifNoneMatch, HttpResponse.BodyHandler<T> handler) {
    final HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (ifNoneMatch != null) {
      request.header("If-None-Match", ifNoneMatch);
    }
    try (final HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request.build(), handler);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse("");
  }

  private String etag(String path) {
    return header(get(path), "ETag");
  }

  private static String code(HttpResponse<String> response) {
    return JSON.readTree(response.body()).get("code").asString();
  }
}
