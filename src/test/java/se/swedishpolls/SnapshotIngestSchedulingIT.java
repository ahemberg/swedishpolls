package se.swedishpolls;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "polls.ingest.enabled=true",
      "polls.ingest.interval=1h",
      "spring.docker.compose.enabled=false"
    })
@Import(TestDatabase.Configuration.class)
class SnapshotIngestSchedulingIT {
  private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

  static {
    SERVER.start();
    SERVER.stubFor(
        get(urlEqualTo("/polls.csv"))
            .willReturn(
                ok().withHeader("ETag", "\"scheduled\"")
                    .withBody(PollCsvTest.csv(PollCsvTest.ROW))));
  }

  @Autowired private SnapshotIngest ingest;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) {
    properties.add("polls.source-url", () -> SERVER.baseUrl() + "/polls.csv");
  }

  @AfterAll
  static void stop() {
    SERVER.stop();
  }

  @Test
  void springRunsTheScheduledSourceCheck() {
    await().atMost(Duration.ofSeconds(10)).until(() -> ingest.activeSnapshot().isPresent());
    SERVER.verify(moreThan(0), getRequestedFor(urlEqualTo("/polls.csv")));
  }
}
