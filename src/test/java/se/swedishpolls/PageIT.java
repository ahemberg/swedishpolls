package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import se.swedishpolls.estimation.Coalitions;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.source.service.NationalAllocationRuleService;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;
import se.swedishpolls.testsupport.TestDatabase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public pages, served from one published publication: both languages, the basic HTML a reader
 * gets before any script runs, and the metadata a shared link carries.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "logging.level.WireMock=warn",
      "polls.ingest.enabled=false",
      "publication.enabled=false",
      "polls.source-url=${wiremock.server.baseUrl}/polls.csv",
      "spring.docker.compose.enabled=false",
      "spring.flyway.clean-disabled=false",
      "site.origin=https://example.test",
      "site.name=Test poll of polls"
    })
@Import({TestDatabase.Configuration.class, PageIT.Fixture.class})
@EnableWireMock
class PageIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int DRAWS = 200;
  private static final String ORIGIN = "https://example.test";

  private static Path root;
  private static String publicationId;

  /** Only the freeze is replaced: the source is served over HTTP like the production one. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Fixture {
    @Bean
    @Primary
    Publisher publisher(
        DataSource dataSource,
        JdbcClient db,
        SnapshotIngest ingest,
        PollQueryService queries,
        ElectionReferenceService electionReferences,
        NationalAllocationRuleService allocationRules,
        PublicationStore store) {
      return new Publisher(
          dataSource,
          db,
          ingest,
          queries,
          electionReferences,
          allocationRules,
          store,
          TestPublication.released(DRAWS));
    }

    @Bean
    PlatformTransactionManager fixtureTransactions(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  @DynamicPropertySource
  static void volume(DynamicPropertyRegistry registry) throws IOException {
    root = Files.createTempDirectory("swedishpolls-pages");
    registry.add("publication.root", () -> root.toString());
  }

  @LocalServerPort private int port;
  @Autowired private Publisher publisher;
  @Autowired private org.flywaydb.core.Flyway flyway;
  @InjectWireMock private WireMockServer wireMock;

  @BeforeEach
  void publishOnce() {
    if (publicationId == null) {
      flyway.clean();
      flyway.migrate();
      TestPublication.serve(wireMock, TestPublication.polls("2014-01-01"));
      final Publisher.Attempt attempt = publisher.publish();
      assertEquals(Publisher.Outcome.PUBLISHED, attempt.outcome(), attempt.detail());
      publicationId = attempt.publicationId();
    }
  }

  @Test
  void everyApprovedRouteIsServedInBothLanguagesWithItsOwnLanguageAttribute() {
    for (final SiteRoutes.Family family : SiteRoutes.Family.values()) {
      for (final String language : Translations.LANGUAGES) {
        final String parameter = family == SiteRoutes.Family.PARTY ? "S" : null;
        final String path = SiteRoutes.path(family, language, parameter);
        final HttpResponse<String> response = get(path);
        assertEquals(200, response.statusCode(), path);
        assertTrue(response.body().contains("<html lang=\"" + language + "\">"), path);
        assertTrue(
            response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"), path);
      }
    }
  }

  @Test
  void aCurrentPartyPageCarriesOnePartyThroughHtmlChartsEffectsAndDownloads() {
    final String page = get("/en/party/social-democrats").body();
    final JsonNode resolved = bootstrap(page);
    final JsonNode party = resolved.get("data").get("party");
    assertEquals("S", resolved.get("route").get("parameter").asString());
    assertEquals("S", party.get("component").asString());
    assertFalse(party.get("historicalOnly").asBoolean());
    assertFalse(party.get("estimate").get("mean").isNull());
    assertFalse(party.get("thresholdProbability").isNull());
    assertTrue(
        party.get("observations").findValues("modeled").stream().anyMatch(JsonNode::asBoolean));
    assertTrue(page.contains("<table class=\"party-estimate\">"));
    assertTrue(page.contains("Social Democrats"));
    assertTrue(page.contains("relative to the ensemble"));
    assertTrue(
        page.contains(
            "/api/v1/polls.csv?publication=" + publicationId + "&amp;language=en&amp;party=S"));
    assertTrue(
        page.contains("/api/v1/institutes?publication=" + publicationId + "&amp;language=en"));
    assertTrue(
        page.contains("/api/v1/elections?publication=" + publicationId + "&amp;language=en"));
    assertEquals("/parti/socialdemokraterna", resolved.get("alternates").get("sv").asString());
  }

  @Test
  void fiStaysDiscoverableAsDatedSourceHistoryWithoutInventingACurrentEstimate() {
    final String page = get("/parti/feministiskt-initiativ").body();
    final JsonNode resolved = bootstrap(page);
    final JsonNode party = resolved.get("data").get("party");
    assertEquals("FI", party.get("component").asString());
    assertTrue(party.get("historicalOnly").asBoolean());
    assertTrue(party.get("estimate").get("mean").isNull());
    assertTrue(party.get("thresholdProbability").isNull());
    assertTrue(party.get("pointSeats").isNull());
    assertEquals("2022-08-29", resolved.get("headlineDate").asString());
    assertTrue(party.get("observations").size() > 300);
    for (final JsonNode observation : party.get("observations")) {
      assertFalse(observation.get("share").isNull());
      assertFalse(observation.get("modeled").asBoolean());
    }
    assertTrue(page.contains("Ingen aktuell enskild skattning finns för Feministiskt initiativ"));
    assertFalse(page.contains(">0,0 %<"));
    assertTrue(page.contains("2022"));
    assertTrue(page.contains("/assets/" + publicationId + "/party-fi-sv-1.png"));
    assertTrue(page.contains("hreflang=\"en\" href=\"/en/party/feminist-initiative\""));
  }

  @Test
  void anUnknownPartySlugReturnsNotFound() {
    assertEquals(404, get("/parti/okant").statusCode());
  }

  @Test
  void theOverviewCarriesItsHeadlineDateAndResultsTableBeforeAnyScriptRuns() {
    final String page = get("/").body();
    final JsonNode resolved = bootstrap(page);
    final JsonNode latest = resolved.get("data").get("latest");
    final SiteText text = SiteText.of(Translations.SWEDISH);
    assertEquals(
        latest.get("lastFieldworkDate").asString(),
        resolved.get("headlineDate").asString(),
        "The headline is dated at the estimate's own last day, not at the snapshot's");
    assertTrue(page.contains(SiteHtml.escape(text.text("headline"))), "headline");
    assertTrue(
        page.contains(
            SiteHtml.escape(
                SiteFormat.date(
                    java.time.LocalDate.parse(latest.get("lastFieldworkDate").asString()),
                    Translations.SWEDISH))),
        "fieldwork date");
    assertTrue(page.contains("<table class=\"estimates\">"), "results table");
    for (final JsonNode component : latest.get("components")) {
      final String label =
          Translations.of(Translations.SWEDISH).component(component.get("component").asString());
      assertTrue(page.contains(SiteHtml.escape(label)), label);
    }
    assertTrue(page.contains(SiteHtml.escape(text.text("notForecast"))), "the one header line");
    assertTrue(page.contains(SiteHtml.escape(text.text("about.title"))), "the method footer");
  }

  @Test
  void aPublishedProbabilityNeverReadsAsImpossibleOrCertain() {
    final String page = get("/en").body();
    final int table = page.indexOf("<table class=\"blocs\">");
    final String blocs = page.substring(table, page.indexOf("</table>", table));
    assertFalse(blocs.contains(">0%<"), "A coalition that can win does not read as 0%");
    assertFalse(blocs.contains(">100%<"), "A coalition that can lose does not read as 100%");
  }

  @Test
  void theEnglishOverviewSaysTheSameThingInEnglish() {
    final String page = get("/en").body();
    final SiteText english = SiteText.of(Translations.ENGLISH);
    assertTrue(page.contains(SiteHtml.escape(english.text("headline"))));
    assertFalse(page.contains(SiteHtml.escape(SiteText.of(Translations.SWEDISH).text("headline"))));
    assertTrue(
        page.contains(SiteHtml.escape(Translations.of(Translations.ENGLISH).component("S"))));
  }

  @Test
  void canonicalAndAlternateLinksComeFromTheConfiguredOriginRatherThanTheHost() {
    final String page = get("/mandat").body();
    assertTrue(page.contains("<link rel=\"canonical\" href=\"" + ORIGIN + "/mandat\">"), page);
    assertTrue(page.contains("hreflang=\"sv\" href=\"" + ORIGIN + "/mandat\">"));
    assertTrue(page.contains("hreflang=\"en\" href=\"" + ORIGIN + "/en/seats\">"));
    assertTrue(page.contains("hreflang=\"x-default\" href=\"" + ORIGIN + "/mandat\">"));
    assertFalse(
        page.contains("127.0.0.1"),
        "The request arrived on 127.0.0.1; no public URL may be built from that host");
  }

  @Test
  void aSharedLinkCarriesOpenGraphAndXMetadataWithImageAlternativeText() {
    final String page = get("/en").body();
    assertTrue(page.contains("<meta property=\"og:type\" content=\"website\">"));
    assertTrue(page.contains("<meta property=\"og:url\" content=\"" + ORIGIN + "/en\">"));
    assertTrue(page.contains("<meta property=\"og:site_name\" content=\"Test poll of polls\">"));
    assertTrue(page.contains("<meta property=\"og:locale\" content=\"en_GB\">"));
    assertTrue(page.contains("<meta property=\"og:image\" content=\"" + ORIGIN + "/assets/"));
    assertTrue(page.contains("<meta property=\"og:image:alt\" content=\""));
    assertTrue(page.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"));
  }

  @Test
  void theLanguageOfThePathDecidesThePageWhateverTheBrowserAsksFor() {
    final HttpResponse<String> swedishPath =
        response(request("/").header("Accept-Language", "en-GB,en;q=0.9"));
    assertEquals(200, swedishPath.statusCode());
    assertTrue(swedishPath.body().contains("<html lang=\"sv\">"));

    final HttpResponse<String> englishPath =
        response(request("/en").header("Accept-Language", "sv-SE,sv;q=0.9"));
    assertTrue(englishPath.body().contains("<html lang=\"en\">"));
  }

  @Test
  void everyPageNamesOneResolvedPublicationAndPinsItsDownloadsAndImagesToIt() {
    final String page = get("/").body();
    final JsonNode resolved = bootstrap(page);
    assertEquals(publicationId, resolved.get("publication").get("publicationId").asString());
    assertEquals(publicationId, resolved.get("api").get("publication").asString());
    assertTrue(
        page.contains("/api/v1/polls.csv?publication=" + publicationId + "&amp;language=sv"));
    assertTrue(page.contains("/api/v1/seats?publication=" + publicationId + "&amp;language=sv"));
    assertTrue(
        resolved
            .get("publication")
            .get("assets")
            .get(ShareImages.OVERVIEW)
            .get("sv")
            .asString()
            .startsWith("/assets/" + publicationId));
  }

  @Test
  void aPermanentLinkServesThatPublicationAndCachesForever() {
    final HttpResponse<String> permanent = get("/?publication=" + publicationId);
    assertEquals(200, permanent.statusCode());
    assertTrue(header(permanent, "Cache-Control").contains("immutable"));
    assertTrue(header(permanent, "Cache-Control").contains("max-age=31536000"));
    assertEquals(
        publicationId,
        bootstrap(permanent.body()).get("publication").get("publicationId").asString());

    final HttpResponse<String> current = get("/");
    assertTrue(header(current, "Cache-Control").contains("max-age=300"));
    assertFalse(header(current, "Cache-Control").contains("immutable"));
  }

  @Test
  void anUnknownPublicationIsRefusedRatherThanFallingBackToTheCurrentOne() {
    assertEquals(404, get("/?publication=pub_00000000T000000Z").statusCode());
  }

  @Test
  void aPageRevalidatesOnItsOwnContentEtag() {
    final HttpResponse<String> first = get("/");
    final String etag = header(first, "ETag");
    assertFalse(etag.isBlank());
    assertNotEquals(etag, header(get("/en"), "ETag"), "A language is its own representation");
    assertEquals(304, send(request("/").header("If-None-Match", etag)).statusCode());
  }

  @Test
  void theNavigationAndLanguageSwitchLinkToTranslatedPathsOnly() {
    final String page = get("/en").body();
    for (final SiteRoutes.Family family : SiteRoutes.NAVIGATION) {
      final String english = SiteRoutes.path(family, Translations.ENGLISH, null);
      assertTrue(page.contains("href=\"" + english + "\""), english);
    }
    assertTrue(page.contains("hreflang=\"sv\" href=\"/\""), "the switch maps the equivalent path");
  }

  @Test
  void theBootstrapIsEscapedSoItCannotCloseItsOwnScriptElement() {
    final String page = get("/").body();
    final int start = page.indexOf("id=\"" + SiteHtml.BOOTSTRAP + "\">");
    final int end = page.indexOf("</script>", start);
    assertTrue(start > 0 && end > start);
    assertFalse(page.substring(start, end).contains("<"), "no raw < inside the bootstrap");
  }

  @Test
  void thePageMountsTheScriptOverItsOwnMarkupRatherThanBesideIt() {
    final String page = get("/").body();
    assertEquals(1, occurrences(page, "id=\"" + SiteHtml.MOUNT + "\""));
    assertEquals(1, occurrences(page, "<main id=\"main\">"));
    assertEquals(
        1,
        occurrences(page, "<table class=\"estimates\">"),
        "one results table, not one per rendering");
  }

  /**
   * One rendered table, so an assertion reads the markup rather than the bootstrap JSON below it.
   * Every label the page writes is also in that JSON, which would otherwise satisfy a whole-page
   * {@code contains} without a single row being rendered.
   */
  private static String table(String page, String name) {
    final String open = "<table class=\"" + name + "\">";
    final int start = page.indexOf(open);
    assertTrue(start >= 0, "the page has no " + name + " table");
    return page.substring(start, page.indexOf("</table>", start));
  }

  private static int occurrences(String page, String needle) {
    int count = 0;
    int at = page.indexOf(needle);
    while (at >= 0) {
      count++;
      at = page.indexOf(needle, at + needle.length());
    }
    return count;
  }

  private static JsonNode bootstrap(String page) {
    final String open = "id=\"" + SiteHtml.BOOTSTRAP + "\">";
    final int start = page.indexOf(open) + open.length();
    final int end = page.indexOf("</script>", start);
    return JSON.readTree(page.substring(start, end));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
  }

  private HttpResponse<String> get(String path) {
    return send(request(path));
  }

  private HttpResponse<String> response(HttpRequest.Builder request) {
    return send(request);
  }

  private HttpResponse<String> send(HttpRequest.Builder request) {
    try (final HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String header(HttpResponse<?> response, String name) {
    return response.headers().firstValue(name).orElse("");
  }

  // The seats page: the integer hemicycle, and the posterior summaries kept separate from it.

  @Test
  void theSeatsPageCarriesItsIntegerAllocationBeforeAnyScriptRuns() {
    final String page = get("/mandat").body();
    final JsonNode seats = bootstrap(page).get("data").get("seats");
    final SiteText text = SiteText.of(Translations.SWEDISH);
    assertTrue(page.contains("<table class=\"seats\">"), "the seat table");
    assertTrue(page.contains(SiteHtml.escape(text.text("seats.title"))), "the seats heading");
    for (final JsonNode party : seats.get("parties")) {
      final String label =
          Translations.of(Translations.SWEDISH).component(party.get("component").asString());
      assertTrue(page.contains(SiteHtml.escape(label)), label);
    }
  }

  /**
   * The hemicycle is the integer allocation, which is why the seat column has to sum to the full
   * chamber. Rounding each posterior mean instead is what this forbids: those round independently
   * and need not sum to 349 at all.
   */
  @Test
  void theHemicycleSeatsAreAllocatedRatherThanRoundedFromPosteriorMeans() {
    final JsonNode seats = bootstrap(get("/mandat").body()).get("data").get("seats");
    int allocated = 0;
    for (final JsonNode party : seats.get("parties")) {
      assertFalse(party.get("pointSeats").isNull(), party.get("component").asString());
      allocated += party.get("pointSeats").asInt();
    }
    assertEquals(
        seats.get("totalSeats").asInt(),
        allocated,
        "Integer point seats fill the chamber exactly; rounded posterior means need not");
  }

  @Test
  void theSeatsPageSeparatesPointSeatsFromPosteriorMeansAndIntervals() {
    final String page = get("/en/seats").body();
    final SiteText text = SiteText.of(Translations.ENGLISH);
    for (final String column :
        List.of("seats.column.point", "seats.column.mean", "seats.column.interval")) {
      assertTrue(page.contains(SiteHtml.escape(text.text(column))), column);
    }
    assertTrue(
        page.contains(SiteHtml.escape(text.text("blocs.majority"))),
        "the 175-seat majority marker");
  }

  @Test
  void theSeatsPageExplainsTheApproximationItPublishes() {
    final String page = get("/mandat").body();
    final Translations swedish = Translations.of(Translations.SWEDISH);
    final SiteText text = SiteText.of(Translations.SWEDISH);
    assertTrue(page.contains(SiteHtml.escape(swedish.text("seats.note"))), "omitted exceptions");
    assertTrue(page.contains(SiteHtml.escape(swedish.text("seats.tie"))), "deterministic ties");
    assertTrue(page.contains(SiteHtml.escape(text.text("seats.era"))), "election-era allocation");
    assertTrue(page.contains(SiteHtml.escape(text.text("seats.threshold"))), "the 4% threshold");
    assertTrue(page.contains(SiteHtml.escape(text.text("seats.other"))), "OTHER is not allocated");
  }

  // The coalitions page: all ten memberships, and every pair of them.

  @Test
  void theCoalitionsPageListsAllTenApprovedMembershipsWithTheirParties() {
    final String page = get("/regeringsunderlag").body();
    final Translations labels = Translations.of(Translations.SWEDISH);
    assertTrue(page.contains("<table class=\"coalitions\">"), "the catalogue table");
    // Scoped to the rendered table: every label is also in the bootstrap JSON, so asserting on
    // the whole document would pass without rendering a single row.
    final String catalogue = table(page, "coalitions");
    for (final Coalitions.Preset preset : Coalitions.PRESETS) {
      assertTrue(catalogue.contains(SiteHtml.escape(labels.coalition(preset.id()))), preset.id());
      for (final String party : preset.parties()) {
        assertTrue(
            catalogue.contains(SiteHtml.escape(labels.component(party))),
            preset.id() + " " + party);
      }
    }
    assertEquals(
        Coalitions.PRESETS.size(),
        bootstrap(page).get("data").get("coalitions").get("coalitions").size());
  }

  /**
   * Both presets count four parties and differ in exactly one of them. A page that collapsed them
   * would read as nine memberships, so the distinction is asserted on the membership, not the name.
   */
  @Test
  void theOppositionPresetStaysDistinctFromCLMPSBecauseOneCountsVAndTheOtherL() {
    final List<String> opposition = Coalitions.preset("opposition").parties();
    final List<String> clmps = Coalitions.preset("c_l_mp_s").parties();
    assertTrue(opposition.contains("V") && !opposition.contains("L"));
    assertTrue(clmps.contains("L") && !clmps.contains("V"));

    final String catalogue = table(get("/regeringsunderlag").body(), "coalitions");
    final Translations labels = Translations.of(Translations.SWEDISH);
    assertNotEquals(labels.coalition("opposition"), labels.coalition("c_l_mp_s"));
    assertTrue(catalogue.contains(SiteHtml.escape(labels.coalition("opposition"))));
    assertTrue(catalogue.contains(SiteHtml.escape(labels.coalition("c_l_mp_s"))));
  }

  @Test
  void theCoalitionsPageComparesEveryPairFromTheSameDraws() {
    final String page = get("/regeringsunderlag").body();
    final JsonNode comparison = bootstrap(page).get("data").get("coalitions").get("comparison");
    final int presets = Coalitions.PRESETS.size();
    assertEquals(presets * (presets - 1) / 2, comparison.get("pairs").size(), "every pair");
    assertEquals(Coalitions.TIE_OUTCOME, comparison.get("tie").asString());
    assertTrue(page.contains("<table class=\"pairwise\">"), "the comparison table");
    assertEquals(
        comparison.get("pairs").size(),
        occurrences(page, "<tr class=\"pair\">"),
        "one rendered row per pair");
    assertTrue(
        page.contains(SiteHtml.escape(SiteText.of(Translations.SWEDISH).text("pairwise.tied"))),
        "a tie is neither side winning");
  }

  @Test
  void theCoalitionsPageStatesThatALabelIsNotAnEndorsement() {
    final String page = get("/en/coalitions").body();
    assertTrue(
        page.contains(
            SiteHtml.escape(Translations.of(Translations.ENGLISH).text("coalitions.note"))));
  }

  @Test
  void aCoalitionProbabilityReadsAsWholePercentAndNeverAsCertainty() {
    final String page = get("/en/coalitions").body();
    final String catalogue = table(page, "coalitions");
    assertFalse(catalogue.contains(">0%<"), "a coalition that can win does not read as 0%");
    assertFalse(catalogue.contains(">100%<"), "a coalition that can lose does not read as 100%");
    for (final JsonNode coalition :
        bootstrap(page).get("data").get("coalitions").get("coalitions")) {
      final String rendered =
          SiteFormat.probability(
              coalition.get("majorityProbability").asDouble(), Translations.ENGLISH);
      assertTrue(catalogue.contains(SiteHtml.escape(rendered)), coalition.get("id").asString());
    }
  }

  // Both pages are shareable in their own right.

  @Test
  void theSeatsAndCoalitionsPagesShareTheirOwnCardRatherThanTheOverviewCard() {
    final JsonNode assets = bootstrap(get("/mandat").body()).get("publication").get("assets");
    final String seatsCard = assets.get(ShareImages.SEATS).get("sv").asString();
    final String coalitionsCard = assets.get(ShareImages.COALITIONS).get("sv").asString();
    assertNotEquals(seatsCard, coalitionsCard);

    assertTrue(
        get("/mandat")
            .body()
            .contains("<meta property=\"og:image\" content=\"" + ORIGIN + seatsCard),
        "the seats page shares the seats card");
    assertTrue(
        get("/regeringsunderlag")
            .body()
            .contains("<meta property=\"og:image\" content=\"" + ORIGIN + coalitionsCard),
        "the coalitions page shares the coalitions card");
  }

  @Test
  void eachPageDescribesItselfRatherThanRepeatingTheOverviewDescription() {
    final SiteText text = SiteText.of(Translations.SWEDISH);
    final String template = text.text("head.description.overview");
    final String overview = SiteHtml.escape(template.substring(0, template.indexOf('{')));
    for (final Map.Entry<String, String> page :
        Map.of("/mandat", "seats", "/regeringsunderlag", "coalitions").entrySet()) {
      final String body = get(page.getKey()).body();
      assertFalse(
          body.contains("<meta name=\"description\" content=\"" + overview),
          page.getKey() + " repeats the overview description");
      final String own = text.text("head.description." + page.getValue());
      assertTrue(
          body.contains(
              "<meta name=\"description\" content=\""
                  + SiteHtml.escape(own.substring(0, own.indexOf('{')))),
          page.getKey() + " describes itself");
    }
  }

  /**
   * At 390px these tables are wider than the screen. The approved design scrolls a wide table
   * inside its own container rather than letting the page scroll sideways, and that has to hold
   * before the script runs too: the container is markup, not something React adds later.
   */
  @Test
  void aWideTableScrollsInsideItsOwnContainerRatherThanWideningThePage() {
    for (final Map.Entry<String, List<String>> page :
        Map.of(
                "/mandat", List.of("seats"),
                "/regeringsunderlag", List.of("coalitions", "pairwise"))
            .entrySet()) {
      final String body = get(page.getKey()).body();
      for (final String name : page.getValue()) {
        assertTrue(
            body.contains("<div class=\"scroll\">\n<table class=\"" + name + "\">"),
            page.getKey() + " " + name + " is not inside a scroll container");
      }
    }
  }

  @Test
  void theSeatsAndCoalitionsPagesPinTheSamePublicationForEveryDependentLink() {
    for (final String path : List.of("/mandat", "/regeringsunderlag")) {
      final String page = get(path).body();
      final JsonNode resolved = bootstrap(page);
      assertEquals(publicationId, resolved.get("api").get("publication").asString(), path);
      for (final String surface : List.of("seats", "coalitions")) {
        assertTrue(
            page.contains(
                "/api/v1/" + surface + "?publication=" + publicationId + "&amp;language=sv"),
            path + " does not offer " + surface + " pinned to its own publication");
      }
    }
  }

  @Test
  void theApprovedRoutesAreTheOnlyOnesServed() {
    for (final String path : List.of("/en/parties", "/seats", "/sv", "/mandat/2026")) {
      assertEquals(404, get(path).statusCode(), path);
    }
  }
}
