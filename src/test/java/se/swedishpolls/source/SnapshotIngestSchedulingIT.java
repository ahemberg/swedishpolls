package se.swedishpolls.source;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import se.swedishpolls.TestDatabase;
import se.swedishpolls.source.service.SnapshotIngest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "logging.level.WireMock=warn",
      "polls.ingest.enabled=true",
      "polls.ingest.interval=1h",
      "polls.source-url=${wiremock.server.baseUrl}/polls.csv",
      "spring.docker.compose.enabled=false"
    })
@Import(TestDatabase.Configuration.class)
@EnableWireMock(@ConfigureWireMock(filesUnderClasspath = "scheduling-wiremock"))
class SnapshotIngestSchedulingIT {
  @Autowired private SnapshotIngest ingest;

  @Test
  void springRunsTheScheduledSourceCheck() {
    await().atMost(Duration.ofSeconds(10)).until(() -> ingest.activeSnapshot().isPresent());
  }
}
