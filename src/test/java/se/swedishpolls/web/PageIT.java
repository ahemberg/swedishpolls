package se.swedishpolls.web;

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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import se.swedishpolls.estimation.Coalitions;
import se.swedishpolls.publication.ModelFreeze;
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
  @Autowired private Flyway flyway;
  @InjectWireMock private WireMockServer wireMock;

  @BeforeEach
  void publishOnce() {
    if (publicationId == null) {
      flyway.clean();
      flyway.migrate();
      TestPublication.serve(wireMock, TestPublication.polls("2014-01-01"));
      final Publisher.Attempt attempt = publisher.publish();
      assertEquals(PublicationOutcome.PUBLISHED, attempt.outcome(), attempt.detail());
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
        Map.of(
                "/mandat",
                "seats",
                "/regeringsunderlag",
                "coalitions",
                "/institut",
                "pollsters",
                "/metod",
                "method")
            .entrySet()) {
      final String body = get(page.getKey()).body();
      assertFalse(
          body.contains("<meta name=\"description\" content=\"" + overview),
          page.getKey() + " repeats the overview description");
      assertTrue(
          body.contains("<meta name=\"description\" content=\"" + own(text, page.getValue())),
          page.getKey() + " describes itself");
    }
  }

  /**
   * The start of a page's own description, up to its first placeholder. A description with no
   * placeholder, like the method page's, is used whole.
   */
  private static String own(SiteText text, String family) {
    final String template = text.text("head.description." + family);
    final int placeholder = template.indexOf('{');
    if (placeholder < 0) {
      return SiteHtml.escape(template);
    }
    return SiteHtml.escape(template.substring(0, placeholder));
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
                "/mandat",
                List.of("seats"),
                "/regeringsunderlag",
                List.of("coalitions", "pairwise"),
                "/institut",
                List.of("institutes", "heat-grid", "heat-table"),
                "/metod",
                List.of("coverage"))
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
  void theSeatsCoalitionsAndPollstersPagesPinTheSamePublicationForEveryDependentLink() {
    for (final Map.Entry<String, List<String>> path :
        Map.of(
                "/mandat",
                List.of("seats", "coalitions"),
                "/regeringsunderlag",
                List.of("seats", "coalitions"),
                "/institut",
                List.of("institutes"))
            .entrySet()) {
      final String page = get(path.getKey()).body();
      final JsonNode resolved = bootstrap(page);
      assertEquals(publicationId, resolved.get("api").get("publication").asString(), path.getKey());
      for (final String surface : path.getValue()) {
        assertTrue(
            page.contains(
                "/api/v1/" + surface + "?publication=" + publicationId + "&amp;language=sv"),
            path.getKey() + " does not offer " + surface + " pinned to its own publication");
      }
    }
  }

  // The poll table: archived source observations, filtered, paged and downloadable as they stand.

  @Test
  void thePollsPageListsArchivedObservationsBeforeAnyScriptRuns() {
    final String page = get("/matningar").body();
    final JsonNode table = bootstrap(page).get("pollTable");
    final SiteText text = SiteText.of(Translations.SWEDISH);
    assertTrue(
        page.contains("<div class=\"scroll\">\n<table class=\"polls\">"),
        "the poll table is not inside a scroll container");
    assertTrue(page.contains(SiteHtml.escape(text.text("head.title.polls"))), "the heading");
    assertTrue(page.contains(SiteHtml.escape(text.text("polls.lead"))), "the lead");
    assertTrue(table.get("total").asInt() > PollQuery.DEFAULT_PAGE_SIZE, "more than one page");
    assertEquals(PollQuery.DEFAULT_PAGE_SIZE, table.get("polls").size());
    final String rendered = table(page, "polls");
    boolean methodEvidence = false;
    boolean denominatorNote = false;
    for (final JsonNode poll : table.get("polls")) {
      assertTrue(
          rendered.contains(SiteHtml.escape(poll.get("institute").asString())),
          poll.get("pollId").asString());
      if (!poll.get("methodEvidence").isNull()) {
        methodEvidence = true;
        assertTrue(
            rendered.contains("href=\"" + SiteHtml.escape(poll.get("methodEvidence").asString())),
            "method evidence");
      }
      if (!poll.get("denominatorNote").isNull()) {
        denominatorNote = true;
        assertTrue(
            rendered.contains("title=\"" + SiteHtml.escape(poll.get("denominatorNote").asString())),
            "sample denominator");
      }
    }
    assertTrue(methodEvidence, "the fixture includes method provenance");
    assertTrue(denominatorNote, "the fixture includes denominator provenance");
  }

  /**
   * The table shows the source's own digits. Rounding them to the estimate's display precision
   * would publish a number no institute ever reported.
   */
  @Test
  void aReportedShareKeepsTheSourcePrecisionAndAnAbsentOneStaysMissing() {
    final String page = get("/en/polls").body();
    final String rendered = table(page, "polls");
    final JsonNode table = bootstrap(page).get("pollTable");
    int missing = 0;
    for (final JsonNode poll : table.get("polls")) {
      for (final JsonNode column : table.get("columns")) {
        final JsonNode share = poll.get("shares").get(column.asString());
        if (share.isNull()) {
          missing++;
          continue;
        }
        final String display = poll.get("displayShares").get(column.asString()).asString();
        assertEquals(share.decimalValue().toPlainString(), display, poll.get("pollId").asString());
        assertTrue(
            rendered.contains(">" + display),
            poll.get("pollId").asString() + " " + column.asString());
      }
    }
    if (missing > 0) {
      assertTrue(
          rendered.contains(
              SiteHtml.escape(SiteText.of(Translations.ENGLISH).text("polls.missing"))),
          "a share the source never reported reads as missing, not as zero");
    }
  }

  /**
   * The download is the table's own rows. Anything else would hand a reader a file that does not
   * match what they were looking at, which is the whole point of pinning the publication.
   */
  @Test
  void theDownloadSelectsExactlyTheRowsTheFilteredTableCounted() {
    final String query = "?institute=Novus&from=2015-01-01&to=2019-12-31";
    final String page = get("/matningar" + query).body();
    final JsonNode table = bootstrap(page).get("pollTable");
    assertTrue(table.get("total").asInt() > 0, "the fixture has Novus polls in this range");
    final String csv = table.get("csv").asString();
    assertTrue(csv.contains("publication=" + publicationId), csv);
    assertTrue(csv.contains("institute=Novus"), csv);
    assertTrue(csv.contains("from=2015-01-01") && csv.contains("to=2019-12-31"), csv);

    final HttpResponse<String> download = get(csv);
    assertEquals(200, download.statusCode(), csv);
    final long rows = download.body().lines().filter(line -> !line.isBlank()).count() - 1;
    assertEquals(table.get("total").asInt(), rows, "the CSV holds every counted row, once");
    assertTrue(page.contains(SiteHtml.escape(csv + "&language=sv")), "the page offers that file");
  }

  @Test
  void aPartyFilterNarrowsTheTableColumnsAndTheDownloadTogether() {
    final String page = get("/en/polls?party=S").body();
    final JsonNode table = bootstrap(page).get("pollTable");
    assertEquals(List.of("S"), columns(table));
    final String rendered = table(page, "polls");
    assertFalse(rendered.contains("\">SD</th>"), "a narrowed table has no other party column");
    final String csv = table.get("csv").asString();
    assertTrue(csv.contains("party=S"), csv);
    final String header = get(csv).body().lines().findFirst().orElseThrow();
    assertTrue(header.contains(",S,"), header);
    assertFalse(header.contains(",SD,"), header);
  }

  @Test
  void anEmptyResultSaysSoRatherThanRenderingAnEmptyTable() {
    final String page = get("/matningar?from=1900-01-01&to=1900-12-31").body();
    final SiteText text = SiteText.of(Translations.SWEDISH);
    assertEquals(0, bootstrap(page).get("pollTable").get("total").asInt());
    assertTrue(page.contains(SiteHtml.escape(text.text("polls.empty"))), "the empty notice");
    assertFalse(page.contains("<table class=\"polls\">"), "no header row over nothing");
  }

  /**
   * A reader following a stale or hand-edited link is better served by the table than by an error,
   * so the page names the filter it could not read and shows the ones it could.
   */
  @Test
  void anInvalidFilterIsNamedAndIgnoredRatherThanFailingTheRequest() {
    final HttpResponse<String> response = get("/matningar?from=yesterday&institute=Novus");
    assertEquals(200, response.statusCode());
    final JsonNode table = bootstrap(response.body()).get("pollTable");
    assertEquals(1, table.get("invalid").size());
    assertEquals("from", table.get("invalid").get(0).get("name").asString());
    assertEquals("not_a_date", table.get("invalid").get(0).get("reason").asString());
    assertTrue(table.get("filters").get("from").isNull(), "the unreadable bound is not guessed");
    assertEquals("Novus", table.get("filters").get("institute").get(0).asString());
    assertTrue(
        response.body().contains("role=\"alert\""),
        "the rejected filter is announced, not swallowed");
  }

  @Test
  void anUnknownCoveragePeriodIsRejectedWithoutSilentlySelectingAnother() {
    final JsonNode table = bootstrap(get("/matningar?coveragePeriod=1066").body()).get("pollTable");
    assertEquals("coveragePeriod", table.get("invalid").get(0).get("name").asString());
    assertTrue(table.get("filters").get("coveragePeriod").isNull());
  }

  @Test
  void pagingKeepsTheFilterAndStaysOnTheSamePublication() {
    final String first = get("/matningar?institute=Novus").body();
    final JsonNode table = bootstrap(first).get("pollTable");
    assertTrue(table.get("pages").asInt() > 1, "the fixture pages Novus polls");
    assertTrue(
        first.contains(
            "href=\"/matningar?publication=" + publicationId + "&amp;institute=Novus&amp;page=2\""),
        "the next link keeps the resolved publication");

    final String second = get("/matningar?institute=Novus&page=2").body();
    final JsonNode paged = bootstrap(second).get("pollTable");
    assertEquals(2, paged.get("page").asInt());
    assertEquals(table.get("total").asInt(), paged.get("total").asInt());
    assertTrue(
        paged.get("csv").asString().startsWith("/api/v1/polls.csv?publication=" + publicationId),
        paged.get("csv").asString());
    assertNotEquals(
        table.get("polls").get(0).get("pollId").asString(),
        paged.get("polls").get(0).get("pollId").asString());
    assertTrue(
        second.contains(
            "href=\"/matningar?publication=" + publicationId + "&amp;institute=Novus&amp;page=1\""),
        "and back on the same publication");
  }

  /**
   * A page number past the end lands on the last page rather than on an empty table, so the caption
   * keeps saying which page is showing and "nothing matches" keeps meaning the filter matched
   * nothing. The API answers the same number with the requested page beside an empty result; the
   * difference is documented in docs/api-contract.md.
   */
  @Test
  void aPageNumberPastTheEndLandsOnTheLastPage() {
    final JsonNode table = bootstrap(get("/matningar?page=999999").body()).get("pollTable");
    assertEquals(table.get("pages").asInt(), table.get("page").asInt());
    assertTrue(table.get("polls").size() > 0, "the last page still shows rows");
  }

  @Test
  void aPermanentPollsLinkCarriesItsPublicationThroughTheFormAndThePaging() {
    final String page = get("/matningar?publication=" + publicationId + "&institute=Novus").body();
    assertTrue(
        page.contains("<input type=\"hidden\" name=\"publication\" value=\"" + publicationId),
        "the filter form keeps the pin");
    assertTrue(
        page.contains("href=\"/matningar?publication=" + publicationId + "&amp;institute=Novus"),
        "and so does the paging");
  }

  /**
   * FI is a real archived observation in every period and a modeled component in none of the
   * fixture's. The table has to show the number and say what it is, rather than dropping it or
   * letting it read as a published estimate.
   */
  @Test
  void anObservationOutsideTheModeledRosterIsMarkedRatherThanPresentedAsAnEstimate() {
    final String page = get("/matningar?party=FI&to=2018-12-31").body();
    final JsonNode table = bootstrap(page).get("pollTable");
    final SiteText text = SiteText.of(Translations.SWEDISH);
    boolean reported = false;
    for (final JsonNode poll : table.get("polls")) {
      if (poll.get("shares").get("FI").isNull()) {
        continue;
      }
      reported = true;
    }
    assertTrue(reported, "the fixture reports FI shares");
    assertTrue(table(page, "polls").contains(text.text("polls.unmodeledMark")), "the marker");
    assertTrue(page.contains(SiteHtml.escape(text.text("polls.unmodeled"))), "and its footnote");
  }

  @Test
  void excludedPollsAreShownOnlyWhenAskedForAndSayWhyTheyWereExcluded() {
    final JsonNode included = bootstrap(get("/matningar").body()).get("pollTable");
    for (final JsonNode poll : included.get("polls")) {
      assertTrue(poll.get("eligible").asBoolean(), poll.get("pollId").asString());
    }
    final String page = get("/matningar?includeExcluded=true").body();
    final JsonNode all = bootstrap(page).get("pollTable");
    assertTrue(all.get("total").asInt() > included.get("total").asInt(), "more rows, not fewer");
    assertTrue(
        page.contains(
            SiteHtml.escape(SiteText.of(Translations.SWEDISH).text("polls.column.eligibility"))),
        "the eligibility column appears only when excluded rows can be in the table");
  }

  @Test
  void theEnglishPollsPageTranslatesItsControlsAndDescribesItself() {
    final String page = get("/en/polls").body();
    final SiteText english = SiteText.of(Translations.ENGLISH);
    assertTrue(page.contains("<html lang=\"en\">"));
    assertTrue(page.contains("<form class=\"filters\" method=\"get\" action=\"/en/polls\">"));
    for (final String key :
        List.of(
            "polls.filters",
            "polls.filter.from",
            "polls.filter.institute",
            "polls.filter.coveragePeriod",
            "polls.filter.includeExcluded",
            "polls.filter.apply")) {
      assertTrue(page.contains(SiteHtml.escape(english.text(key))), key);
    }
    final String own = english.text("head.description.polls");
    assertTrue(
        page.contains(
            "<meta name=\"description\" content=\""
                + SiteHtml.escape(own.substring(0, own.indexOf('{')))),
        "the polls page describes itself");
    assertTrue(page.contains("hreflang=\"sv\" href=\"" + ORIGIN + "/matningar\">"));
  }

  @Test
  void thePollsPageOffersTheFilterOptionsItsOwnPublicationCarries() {
    final JsonNode options = bootstrap(get("/matningar").body()).get("pollTable").get("options");
    assertFalse(options.get("institutes").isEmpty(), "institutes to filter by");
    assertFalse(options.get("coveragePeriods").isEmpty(), "coverage periods to filter by");
    assertEquals(PollQuery.COMPONENTS.size(), options.get("parties").size());
    final String page = get("/matningar").body();
    for (final JsonNode institute : options.get("institutes")) {
      assertTrue(
          page.contains("<option value=\"" + SiteHtml.escape(institute.asString()) + "\""),
          institute.asString());
    }
  }

  private static List<String> columns(JsonNode table) {
    final List<String> columns = new java.util.ArrayList<>();
    for (final JsonNode column : table.get("columns")) {
      columns.add(column.asString());
    }
    return columns;
  }

  // The pollsters page: institutes, their eras and their house effects per cycle.

  @Test
  void thePollstersPageNamesItsInstitutesTheirErasAndTheirFootprint() {
    final String page = get("/institut").body();
    final JsonNode pollsters = bootstrap(page).get("data").get("pollsters");
    final SiteText text = SiteText.of(Translations.SWEDISH);
    assertTrue(page.contains("<table class=\"institutes\">"), "the metadata table");
    assertTrue(page.contains(SiteHtml.escape(text.text("pollsters.metadata"))), "the section");
    assertTrue(page.contains(SiteHtml.escape(text.text("pollsters.erasNote"))), "the era note");
    assertTrue(page.contains(SiteHtml.escape(text.text("pollsters.lead"))), "the lead");
    final String rendered = table(page, "institutes");
    assertTrue(pollsters.get("institutes").size() > 0, "the fixture has institutes");
    for (final JsonNode institute : pollsters.get("institutes")) {
      final String name = institute.get("institute").asString();
      assertTrue(rendered.contains(SiteHtml.escape(name)), name);
      assertTrue(
          rendered.contains(">" + institute.get("polls").asInt() + "<"), name + " poll count");
      for (final JsonNode era : institute.get("methodEras")) {
        if (!era.get("evidence").isNull()) {
          assertTrue(
              rendered.contains("href=\"" + SiteHtml.escape(era.get("evidence").asString())),
              name + " era evidence");
        }
      }
    }
  }

  @Test
  void thePollstersPageRendersAHeatTablePerCycleBesideItsTableAlternative() {
    final String page = get("/institut").body();
    final JsonNode pollsters = bootstrap(page).get("data").get("pollsters");
    final SiteText text = SiteText.of(Translations.SWEDISH);
    assertTrue(pollsters.get("matrices").size() > 0, "the fixture has fitted cycles");
    assertEquals(
        pollsters.get("matrices").size(),
        occurrences(page, "<table class=\"heat-grid\">"),
        "one heat table per cycle");
    assertEquals(
        pollsters.get("matrices").size(),
        occurrences(page, "<table class=\"heat-table\">"),
        "one table alternative per cycle");
    int cells = 0;
    for (final JsonNode matrix : pollsters.get("matrices")) {
      final String caption =
          SiteHtml.escape(
              SiteText.fill(
                  text.text("pollsters.gridCaption"), "cycle", matrix.get("cycle").asString()));
      assertTrue(page.contains(caption), matrix.get("cycle").asString());
      for (final JsonNode row : matrix.get("rows")) {
        for (final JsonNode cell : row.get("cells")) {
          cells++;
          assertEquals(
              SiteBootstrap.heat(cell.get("mean").asDouble()),
              cell.get("heat").asString(),
              "the colour step follows the effect's size");
          final String language = Translations.SWEDISH;
          final String markup =
              "<td class=\"num heat "
                  + SiteHtml.escape(cell.get("heat").asString())
                  + "\" title=\""
                  + SiteHtml.escape(
                      SiteText.fill(
                          SiteText.fill(
                              text.text("party.effectRange"),
                              "lower",
                              SiteFormat.decimal(cell.get("lower").asDouble(), language)),
                          "upper",
                          SiteFormat.decimal(cell.get("upper").asDouble(), language)))
                  + "\">"
                  + SiteHtml.escape(SiteFormat.decimal(cell.get("mean").asDouble(), language))
                  + "</td>";
          assertTrue(page.contains(markup), markup);
        }
      }
    }
    assertEquals(
        cells,
        occurrences(page, "<td class=\"num heat "),
        "one heat cell per effect, and the table alternative carries every one");
  }

  @Test
  void thePollstersPageSaysWhatItsEffectsAreMeasuredAgainst() {
    final String page = get("/institut").body();
    final JsonNode pollsters = bootstrap(page).get("data").get("pollsters");
    assertTrue(page.contains(SiteHtml.escape(pollsters.get("reference").asString())));
    assertTrue(
        page.contains(SiteHtml.escape(SiteText.of(Translations.SWEDISH).text("notForecast"))),
        "the single header statement");
  }

  @Test
  void theEnglishPollstersPageRendersTheSameTablesInEnglish() {
    final String page = get("/en/pollsters").body();
    final SiteText english = SiteText.of(Translations.ENGLISH);
    assertTrue(page.contains("<html lang=\"en\">"));
    assertTrue(page.contains("<table class=\"institutes\">"));
    assertTrue(page.contains(SiteHtml.escape(english.text("pollsters.metadata"))));
    final String own = english.text("head.description.pollsters");
    assertTrue(
        page.contains(
            "<meta name=\"description\" content=\""
                + SiteHtml.escape(own.substring(0, own.indexOf('{')))),
        "the pollsters page describes itself");
    assertTrue(page.contains("hreflang=\"sv\" href=\"" + ORIGIN + "/institut\">"));
  }

  // The method page: the written explanation, the recorded verdict and the frozen values.

  @Test
  void theMethodPageExplainsDataCoverageModelValidationAndSeats() {
    final String page = get("/metod").body();
    final SiteText text = SiteText.of(Translations.SWEDISH);
    final Translations words = Translations.of(Translations.SWEDISH);
    for (final String key :
        List.of(
            "method.lead",
            "method.data.title",
            "method.data.history",
            "method.data.provenance",
            "method.data.eligibility",
            "method.data.eras",
            "method.coverage.title",
            "method.coverage.otherNote",
            "method.coverage.fiNote",
            "method.coverage.boundaryNote",
            "method.model.title",
            "method.model.observations",
            "method.model.estimand",
            "method.model.house",
            "method.model.overdispersion",
            "method.model.hyper",
            "method.model.draws",
            "method.validation.title",
            "method.validation.gateScore",
            "method.validation.gateCoverage",
            "method.validation.gateMisfit",
            "method.validation.sensitivity",
            "method.reproduction.title",
            "method.reproduction.inputs",
            "method.seats.title",
            "seats.threshold",
            "seats.pointVersusMean",
            "notForecast")) {
      assertTrue(page.contains(SiteHtml.escape(text.text(key))), key);
    }
    assertTrue(page.contains(SiteHtml.escape(words.text("seats.note"))), "the seat limitations");
    assertTrue(page.contains(SiteHtml.escape(words.text("seats.tie"))), "deterministic ties");
  }

  @Test
  void theMethodPagePresentsTheRecordedVerdictOfTheFreezeItRunsUnder() {
    final String page = get("/metod").body();
    final ModelFreeze freeze = ModelFreeze.load();
    final JsonNode verdict = bootstrap(page).get("data").get("method").get("verdict");
    assertEquals(freeze.releaseStatus(), verdict.get("status").asString());
    assertEquals(freeze.released(), verdict.get("released").asBoolean());
    final List<String> gates = new java.util.ArrayList<>();
    for (final JsonNode gate : verdict.get("failedGates")) {
      gates.add(gate.asString());
    }
    assertEquals(freeze.failedBlockingGates(), gates);
    final SiteText text = SiteText.of(Translations.SWEDISH);
    final String key =
        freeze.released()
            ? "method.validation.verdictReleased"
            : "method.validation.verdictBlocked";
    assertTrue(page.contains(SiteHtml.escape(text.text(key))), key);
  }

  @Test
  void theMethodPageCarriesTheFrozenReproductionValues() {
    final String page = get("/en/method").body();
    final ModelFreeze freeze = ModelFreeze.load();
    final JsonNode method = bootstrap(page).get("data").get("method");
    assertEquals(freeze.uncertainty().seed(), method.get("draws").get("seed").asLong());
    assertEquals(freeze.uncertainty().draws(), method.get("draws").get("count").asInt());
    assertEquals(freeze.resolution().decimals(), method.get("draws").get("decimals").asInt());
    assertEquals(
        freeze.maxDriftPoints(), method.get("draws").get("maxDriftPoints").asDouble(), 0.0);
    assertEquals(freeze.estimatorVersion(), method.get("estimator").get("version").asString());
    assertTrue(page.contains("Seed: " + freeze.uncertainty().seed()), "the seed, spelled out");
    assertTrue(page.contains("Joint draws: " + freeze.uncertainty().draws()), "the draw count");
  }

  @Test
  void theMethodPageListsCoveragePeriodsWithTheirRostersAndTheirOwnerDecisions() {
    final String page = get("/metod").body();
    final JsonNode latest = bootstrap(page).get("data").get("latest");
    final Translations labels = Translations.of(Translations.SWEDISH);
    assertTrue(latest.get("coveragePeriods").size() > 0, "the fixture has coverage periods");
    final String rendered = table(page, "coverage");
    for (final JsonNode period : latest.get("coveragePeriods")) {
      final String id = period.get("id").asString();
      assertTrue(rendered.contains(SiteHtml.escape(id)), id);
      for (final JsonNode component : period.get("roster")) {
        assertTrue(
            rendered.contains(SiteHtml.escape(labels.component(component.asString()))),
            id + " " + component.asString());
      }
      if (period.get("otherMembers").size() > 0) {
        assertTrue(
            rendered.contains(SiteHtml.escape(labels.component("FI"))),
            id + " names what OTHER includes");
      }
      final JsonNode decision = period.get("decision");
      if (!decision.isNull()) {
        assertTrue(
            rendered.contains("href=\"" + SiteHtml.escape(decision.asString())),
            id + " links its owner decision");
      }
    }
  }

  @Test
  void theEnglishMethodPageRendersItsOwnWordingAndDescribesItself() {
    final String page = get("/en/method").body();
    final SiteText english = SiteText.of(Translations.ENGLISH);
    assertTrue(page.contains("<html lang=\"en\">"));
    assertTrue(page.contains(SiteHtml.escape(english.text("method.lead"))));
    assertFalse(
        page.contains(SiteHtml.escape(SiteText.of(Translations.SWEDISH).text("method.lead"))));
    final String own = english.text("head.description.method");
    assertTrue(
        page.contains("<meta name=\"description\" content=\"" + SiteHtml.escape(own)),
        "the method page describes itself");
    assertTrue(page.contains("hreflang=\"sv\" href=\"" + ORIGIN + "/metod\">"));
  }

  @Test
  void thePollstersAndMethodPagesReuseTheOverviewCard() {
    final JsonNode assets = bootstrap(get("/institut").body()).get("publication").get("assets");
    final String card = assets.get(ShareImages.OVERVIEW).get("sv").asString();
    assertTrue(
        get("/institut").body().contains("<meta property=\"og:image\" content=\"" + ORIGIN + card),
        "the pollsters page shares the overview card");
    assertTrue(
        get("/metod").body().contains("<meta property=\"og:image\" content=\"" + ORIGIN + card),
        "the method page shares the overview card");
  }

  @Test
  void theApprovedRoutesAreTheOnlyOnesServed() {
    for (final String path : List.of("/en/parties", "/seats", "/sv", "/mandat/2026")) {
      assertEquals(404, get(path).statusCode(), path);
    }
  }
}
