package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import se.swedishpolls.testsupport.TestDatabase;
import se.swedishpolls.web.controller.ApiErrors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Before the first publication exists there is nothing to serve, and the API says exactly that. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestDatabase.Configuration.class)
class ApiUnavailableIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @DynamicPropertySource
  static void volume(DynamicPropertyRegistry registry) throws Exception {
    final Path root = Files.createTempDirectory("swedishpolls-unavailable");
    registry.add("publication.root", () -> root.toString());
    registry.add("polls.ingest.enabled", () -> "false");
    registry.add("publication.enabled", () -> "false");
    registry.add("spring.flyway.clean-disabled", () -> "false");
  }

  @org.springframework.boot.test.web.server.LocalServerPort private int port;
  @Autowired private Flyway flyway;

  @BeforeEach
  void emptyDatabase() {
    flyway.clean();
    flyway.migrate();
  }

  @Test
  void everySurfaceThatNeedsAPublicationReturnsExplicitUnavailability() throws Exception {
    for (final String path :
        List.of(
            "/api/v1/publication",
            "/api/v1/estimates/latest",
            "/api/v1/estimates/history",
            "/api/v1/polls",
            "/api/v1/polls.csv",
            "/api/v1/institutes",
            "/api/v1/elections",
            "/api/v1/seats",
            "/api/v1/coalitions")) {
      final HttpResponse<String> response = get(path);
      assertEquals(503, response.statusCode(), path);
      final JsonNode body = JSON.readTree(response.body());
      assertEquals(ApiErrors.ESTIMATES_UNAVAILABLE, body.get("code").asString(), path);
      assertFalse(body.has("components"), path + " must carry no estimate fields");
      assertFalse(body.has("parties"), path);
      assertTrue(body.has("sourceCheckedAt"), path);
    }
  }

  @Test
  void aPermanentLinkStillFailsAsUnknownRatherThanAsUnavailable() throws Exception {
    final HttpResponse<String> response = get("/api/v1/publications/pub_00000000T000000Z");
    assertEquals(404, response.statusCode());
    assertEquals(
        ApiErrors.UNKNOWN_PUBLICATION, JSON.readTree(response.body()).get("code").asString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    try (final HttpClient client = HttpClient.newHttpClient()) {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }
}
