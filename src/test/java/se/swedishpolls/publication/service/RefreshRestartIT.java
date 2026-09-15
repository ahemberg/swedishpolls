package se.swedishpolls.publication.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import se.swedishpolls.Application;
import se.swedishpolls.source.service.SnapshotIngest;
import se.swedishpolls.testsupport.PollCsvFixtures;
import se.swedishpolls.testsupport.TestDatabase;

class RefreshRestartIT {
  private static final String SCHEMA = "refresh_restart";

  @Test
  void aBlockedReleaseCollectsOnFreshStartupButRetainedDataWaitsAfterRestart() {
    final DriverManagerDataSource dataSource = TestDatabase.dataSource(SCHEMA);
    final Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).cleanDisabled(false).load();
    flyway.clean();
    flyway.migrate();
    final WireMockServer wireMock = new WireMockServer(wireMockConfig().dynamicPort());
    wireMock.start();
    wireMock.stubFor(
        get(urlEqualTo("/polls.csv"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("ETag", "\"restart\"")
                    .withBody(PollCsvFixtures.csv(PollCsvFixtures.ROW))));

    try {
      try (final ConfigurableApplicationContext first = start(dataSource, wireMock)) {
        assertTrue(first.getBean(SnapshotIngest.class).activeSnapshot().isPresent());
        assertEquals(
            "blocked",
            first
                .getBean(JdbcClient.class)
                .sql("SELECT outcome FROM publication_attempt")
                .query(String.class)
                .single());
      }
      wireMock.resetRequests();

      try (final ConfigurableApplicationContext second = start(dataSource, wireMock)) {
        assertTrue(second.getBean(SnapshotIngest.class).activeSnapshot().isPresent());
        wireMock.verify(0, getRequestedFor(urlEqualTo("/polls.csv")));
      }
    } finally {
      wireMock.stop();
      flyway.clean();
    }
  }

  private static ConfigurableApplicationContext start(
      DriverManagerDataSource dataSource, WireMockServer wireMock) {
    return new SpringApplicationBuilder(Application.class)
        .web(WebApplicationType.NONE)
        .run(
            "--logging.level.root=warn",
            "--polls.ingest.enabled=true",
            "--publication.enabled=true",
            "--polls.source-url=" + wireMock.baseUrl() + "/polls.csv",
            "--spring.docker.compose.enabled=false",
            "--spring.datasource.url=" + dataSource.getUrl(),
            "--spring.datasource.username=" + dataSource.getUsername(),
            "--spring.datasource.password=" + dataSource.getPassword(),
            "--spring.flyway.default-schema=" + SCHEMA);
  }
}
