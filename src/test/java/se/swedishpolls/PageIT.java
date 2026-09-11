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
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;
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
        PublicationStore store) {
      return new Publisher(dataSource, db, ingest, queries, store, TestPublication.released(DRAWS));
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
  @InjectWireMock private WireMockServer wireMock;

  @BeforeEach
  void publishOnce() {
    if (publicationId == null) {
      TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
      final Publisher.Attempt attempt = publisher.publish();
      assertEquals(Publisher.Outcome.PUBLISHED, attempt.outcome(), attempt.detail());
      publicationId = attempt.publicationId();
    }
  }

  @Test
  void everyApprovedRouteIsServedInBothLanguagesWithItsOwnLanguageAttribute() {
    for (final SiteRoutes.Family family : SiteRoutes.Family.values()) {
      for (final String language : Translations.LANGUAGES) {
        final String parameter = family == SiteRoutes.Family.PARTY ? "socialdemokraterna" : null;
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

  @Test
  void theApprovedRoutesAreTheOnlyOnesServed() {
    for (final String path : List.of("/en/parties", "/seats", "/sv", "/mandat/2026")) {
      assertEquals(404, get(path).statusCode(), path);
    }
  }
}
