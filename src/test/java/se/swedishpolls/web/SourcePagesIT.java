package se.swedishpolls.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import se.swedishpolls.publication.PublicationOutcome;
import se.swedishpolls.publication.service.Publisher;
import se.swedishpolls.publication.service.TestPublication;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.service.SnapshotIngest;
import se.swedishpolls.testsupport.TestDatabase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Source poll pages served while the production release gate blocks estimate publication. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "logging.level.WireMock=warn",
      "polls.ingest.enabled=false",
      "publication.enabled=false",
      "polls.source-url=${wiremock.server.baseUrl}/polls.csv",
      "spring.docker.compose.enabled=false",
      "spring.flyway.clean-disabled=false",
      "site.origin=https://example.test"
    })
@Import({TestDatabase.Configuration.class, SourcePagesIT.Fixture.class})
@EnableWireMock
class SourcePagesIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static Path root;

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixture {
    @Bean
    PlatformTransactionManager fixtureTransactions(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }
  }

  @DynamicPropertySource
  static void volume(DynamicPropertyRegistry registry) throws IOException {
    root = Files.createTempDirectory("swedishpolls-source-pages");
    registry.add("publication.root", () -> root.toString());
  }

  @LocalServerPort private int port;
  @Autowired private Publisher publisher;
  @Autowired private SnapshotIngest ingest;
  @Autowired private Flyway flyway;
  @InjectWireMock private WireMockServer wireMock;

  @BeforeEach
  void reset() {
    flyway.clean();
    flyway.migrate();
    wireMock.resetAll();
  }

  @Test
  void blockedPublicationStillServesCollectedPollsInBothLanguagesAndBasicHtml() throws Exception {
    TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
    final Publisher.Attempt attempt = publisher.publish();
    assertEquals(PublicationOutcome.BLOCKED, attempt.outcome(), attempt.detail());

    for (final String path : java.util.List.of("/", "/en", "/matningar", "/en/polls")) {
      final HttpResponse<String> response = get(path);
      assertEquals(200, response.statusCode(), path);
      final JsonNode bootstrap = bootstrap(response.body());
      assertTrue(bootstrap.path("source").path("snapshotId").asLong() > 0, path);
      assertTrue(response.body().contains("<table"), path);
      assertFalse(response.body().contains("estimates_unavailable"), path);
    }

    assertEquals(503, get("/api/v1/polls").statusCode(), "the frozen publication API is unchanged");
  }

  @Test
  void sourceFiltersCsvExclusionsAndEmptyMatchesUseTheSameSnapshot() throws Exception {
    TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
    assertEquals(PublicationOutcome.BLOCKED, publisher.publish().outcome());

    final String query = "?from=2025-01-01&to=2025-12-31&institute=Novus&includeExcluded=true";
    final JsonNode page = bootstrap(get("/en/polls" + query).body());
    final JsonNode table = page.path("pollTable");
    final long snapshot = page.path("source").path("snapshotId").asLong();
    assertTrue(table.path("total").asInt() > 0);
    assertEquals(PollQuery.COMPONENTS.size(), table.path("columns").size());
    for (int index = 0; index < PollQuery.COMPONENTS.size(); index++) {
      assertEquals(PollQuery.COMPONENTS.get(index), table.path("columns").get(index).asString());
    }
    for (final JsonNode poll : table.path("polls")) {
      assertEquals("Novus", poll.path("institute").asString());
      assertFalse(
          LocalDate.parse(poll.path("collectionTo").asString()).isBefore(LocalDate.of(2025, 1, 1)));
      assertFalse(
          LocalDate.parse(poll.path("collectionFrom").asString())
              .isAfter(LocalDate.of(2025, 12, 31)));
    }

    final String csvPath = table.path("csv").asString();
    assertTrue(csvPath.startsWith("/source/polls.csv?snapshot=" + snapshot));
    final HttpResponse<String> csv = get(csvPath);
    assertEquals(200, csv.statusCode());
    assertEquals(table.path("total").asInt() + 1, csv.body().lines().count());
    assertTrue(
        csv.body().lines().findFirst().orElseThrow().contains("Other parties, including FI"));

    final JsonNode all = bootstrap(get("/en/polls").body()).path("pollTable");
    assertTrue(all.path("total").asInt() > all.path("polls").size());
    assertEquals(
        all.path("total").asInt() + 1, get(all.path("csv").asString()).body().lines().count());

    final int eligible =
        bootstrap(get("/matningar").body()).path("pollTable").path("total").asInt();
    final int archived =
        bootstrap(get("/matningar?includeExcluded=true").body())
            .path("pollTable")
            .path("total")
            .asInt();
    assertTrue(archived > eligible);

    final String empty = get("/en/polls?from=1900-01-01&to=1900-12-31").body();
    assertTrue(empty.contains("No matching polls"));
    assertTrue(empty.contains("Clear the filter"));
    assertFalse(empty.contains("<table class=\"polls\">"));

    final String invalid = get("/en/polls?from=yesterday&institute=Novus&party=S").body();
    assertTrue(invalid.contains("role=\"alert\""));
    final JsonNode invalidTable = bootstrap(invalid).path("pollTable");
    assertEquals("from", invalidTable.path("invalid").get(0).path("name").asString());
    assertEquals("party", invalidTable.path("invalid").get(1).path("name").asString());
    assertEquals(PollQuery.COMPONENTS.size(), invalidTable.path("columns").size());
  }

  @Test
  void theSourceChartDrawsIntervalMarkersOverActualFieldworkWithoutAnEstimate() throws Exception {
    TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
    assertEquals(PublicationOutcome.BLOCKED, publisher.publish().outcome());

    final JsonNode home = bootstrap(get("/").body());
    final JsonNode chart = home.path("sourceChart");
    assertEquals(home.path("source").path("snapshotId"), chart.path("snapshotId"));
    assertEquals("oneYear", chart.path("defaultRange").asString());
    assertEquals(
        java.util.List.of("oneYear", "fourYears", "all"),
        java.util.stream.StreamSupport.stream(chart.path("ranges").spliterator(), false)
            .map(range -> range.path("id").asString())
            .toList());
    assertEquals("oneYear", chart.path("range").path("id").asString());
    final LocalDate from = LocalDate.parse(chart.path("range").path("from").asString());
    final LocalDate to = LocalDate.parse(chart.path("range").path("to").asString());
    assertEquals(to.minusYears(1), from, "the chart opens on the last year");
    assertEquals(PollQuery.COMPONENTS, componentsOf(chart), "every reported party, FI included");

    assertFalse(chart.path("observations").isEmpty());
    assertTrue(
        chart.path("observations").size() > home.path("sourcePolls").path("polls").size(),
        "the chart draws every overlapping poll, not the table page");
    boolean missingShare = false;
    for (final JsonNode observation : chart.path("observations")) {
      final LocalDate start = LocalDate.parse(observation.path("from").asString());
      final LocalDate end = LocalDate.parse(observation.path("to").asString());
      assertFalse(end.isBefore(from), "a marker outside the window is not drawn");
      assertFalse(start.isAfter(to), "a marker outside the window is not drawn");
      assertTrue(observation.path("approximatePeriod").isBoolean());
      assertFalse(observation.has("other"), "the comparable remainder is not a party");
      assertEquals(PollQuery.COMPONENTS.size(), observation.path("shares").size());
      missingShare = missingShare || observation.path("shares").path("FI").isNull();
    }
    assertTrue(missingShare, "an unreported share stays absent rather than becoming a zero");
  }

  @Test
  void aChartWindowIsFetchedAgainstTheTablesOwnSnapshotAndFilters() throws Exception {
    TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
    assertEquals(PublicationOutcome.BLOCKED, publisher.publish().outcome());

    final JsonNode page = bootstrap(get("/en/polls?institute=Novus&includeExcluded=true").body());
    final long snapshot = page.path("source").path("snapshotId").asLong();
    final JsonNode chart = page.path("sourceChart");
    assertEquals("institute=Novus", chart.path("query").asString());
    assertEquals(
        PollQuery.COMPONENTS, componentsOf(chart), "a table filter is not a chart control");
    for (final JsonNode observation : chart.path("observations")) {
      assertEquals("Novus", observation.path("institute").asString());
    }

    final HttpResponse<String> fetched =
        get("/source/chart?snapshot=" + snapshot + "&range=all&institute=Novus");
    assertEquals(200, fetched.statusCode());
    final JsonNode all = JSON.readTree(fetched.body());
    assertEquals("all", all.path("range").path("id").asString());
    assertTrue(all.path("observations").size() > chart.path("observations").size());

    assertEquals(
        404, get("/source/chart?snapshot=" + snapshot + "&range=sinceElection").statusCode());
    assertEquals(404, get("/source/chart?snapshot=999999999").statusCode());
    assertEquals(400, get("/source/chart?from=yesterday").statusCode());
  }

  private static java.util.List<String> componentsOf(JsonNode chart) {
    return java.util.stream.StreamSupport.stream(chart.path("components").spliterator(), false)
        .map(JsonNode::asString)
        .toList();
  }

  @Test
  void aCorrectionCannotChangeAnExistingVisitButARefreshSelectsTheActiveSnapshot()
      throws Exception {
    TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
    assertEquals(PublicationOutcome.BLOCKED, publisher.publish().outcome());
    final String first = get("/en/polls?institute=Novus").body();
    final JsonNode firstPage = bootstrap(first);
    final long pinned = firstPage.path("source").path("snapshotId").asLong();
    final JsonNode firstTable = firstPage.path("pollTable");
    final String firstCsv = get(firstTable.path("csv").asString()).body();
    assertTrue(first.contains("<input type=\"hidden\" name=\"snapshot\" value=\"" + pinned));

    TestPublication.serve(wireMock, TestPublication.polls("2023-01-01"));
    assertEquals(PublicationOutcome.BLOCKED, publisher.publish().outcome());
    final JsonNode refreshed = bootstrap(get("/en/polls?institute=Novus").body());
    assertTrue(refreshed.path("source").path("snapshotId").asLong() > pinned);

    final JsonNode retained =
        bootstrap(get("/en/polls?snapshot=" + pinned + "&institute=Novus").body());
    assertEquals(firstTable.path("total"), retained.path("pollTable").path("total"));
    assertEquals(firstTable.path("polls"), retained.path("pollTable").path("polls"));
    assertEquals(firstCsv, get(retained.path("pollTable").path("csv").asString()).body());
    assertEquals(404, get("/en/polls?snapshot=999999999").statusCode());
    assertEquals(404, get("/en/polls?snapshot=").statusCode());
  }

  @Test
  void anEmptyArchiveOffersRetryWithoutFetchingAndModelPagesRedirectByLanguage() throws Exception {
    final HttpResponse<String> empty = get("/en");
    assertEquals(200, empty.statusCode());
    assertTrue(empty.body().contains("No polls available"));
    assertTrue(empty.body().contains(">Retry</a>"));
    assertEquals(2, bootstrap(empty.body()).path("navigation").size());
    assertFalse(bootstrap(empty.body()).has("sourceChart"), "no retained polls, no chart");

    final HttpResponse<String> english = get("/en/seats");
    assertEquals(302, english.statusCode());
    assertEquals("/en", english.headers().firstValue("Location").orElseThrow());
    final HttpResponse<String> swedish = get("/metod");
    assertEquals(302, swedish.statusCode());
    assertEquals("/", swedish.headers().firstValue("Location").orElseThrow());
    assertTrue(wireMock.getAllServeEvents().isEmpty(), "ordinary reads never contact the source");
  }

  @Test
  void aFailedRefreshRetainsRowsAndTheirCaptureTime() throws Exception {
    TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
    assertEquals(PublicationOutcome.BLOCKED, publisher.publish().outcome());
    final JsonNode before = bootstrap(get("/").body());

    wireMock.resetAll();
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(TestPublication.SOURCE_PATH))
            .willReturn(aResponse().withStatus(500)));
    assertThrows(IllegalStateException.class, ingest::check);

    final JsonNode after = bootstrap(get("/").body());
    assertEquals(before.path("source"), after.path("source"));
    assertEquals(before.path("sourcePolls"), after.path("sourcePolls"));
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static JsonNode bootstrap(String page) {
    final String start = "<script type=\"application/json\" id=\"site-bootstrap\">";
    final int from = page.indexOf(start) + start.length();
    final int to = page.indexOf("</script>", from);
    return JSON.readTree(page.substring(from, to));
  }
}
