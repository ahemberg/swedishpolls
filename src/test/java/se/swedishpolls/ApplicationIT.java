package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.testsupport.TestDatabase;
import se.swedishpolls.web.SiteHtml;
import se.swedishpolls.web.SiteText;

class ApplicationIT {
  @Test
  void packagedApplicationServesBundledAssetsAndMigratesPostgres() throws Exception {
    final int port;
    try (final java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    final java.io.File log = Path.of("target", "application-integration.log").toFile();
    final org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
        TestDatabase.dataSource();
    final org.flywaydb.core.Flyway flyway =
        org.flywaydb.core.Flyway.configure().cleanDisabled(false).dataSource(dataSource).load();
    flyway.clean();
    flyway.migrate();
    final java.lang.Process process =
        TestDatabase.configure(
                new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar",
                    "target/swedishpolls-0.1.0.jar",
                    "--server.port=" + port,
                    "--polls.ingest.enabled=false"),
                dataSource)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start();
    try (final java.net.http.HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      final java.net.URI root = URI.create("http://127.0.0.1:" + port);
      HttpResponse<String> response = null;
      final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline && process.isAlive()) {
        try {
          response =
              http.send(
                  HttpRequest.newBuilder(root).timeout(Duration.ofSeconds(1)).build(),
                  HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() == 200) break;
        } catch (java.io.IOException ignored) {
          // The packaged server has not bound its port yet.
        }
        Thread.sleep(100);
      }
      assertNotNull(response, "Server did not start; see " + log);
      assertEquals(200, response.statusCode());
      assertTrue(
          response.body().contains("<html lang=\"sv\">"),
          "The root serves the Swedish overview route");
      assertTrue(
          response
              .body()
              .contains(
                  SiteHtml.escape(SiteText.of(Translations.SWEDISH).text("unavailable.title"))),
          "No publication exists in this run, so the page says so rather than showing zeros");
      final java.util.regex.Matcher asset =
          Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(response.body());
      assertTrue(asset.find(), "HTML must reference compiled JavaScript");
      final java.net.http.HttpResponse<java.lang.String> script =
          http.send(
              HttpRequest.newBuilder(root.resolve(asset.group(1))).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, script.statusCode());
      assertTrue(script.headers().firstValue("content-type").orElse("").contains("javascript"));
      assertTrue(
          script.body().contains("createRoot"),
          "The script mounts over the server markup rather than hydrating it");
      assertTrue(script.body().contains(SiteHtml.MOUNT), "and it mounts on the server's element");

      final org.springframework.jdbc.core.simple.JdbcClient db = JdbcClient.create(dataSource);
      final java.lang.String serverVersion =
          db.sql("SHOW server_version").query(String.class).single();
      assertEquals("18.4", serverVersion.substring(0, serverVersion.indexOf(' ')));
      assertEquals(
          1,
          db.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
              .query(Integer.class)
              .single());
      assertEquals(
          0, db.sql("SELECT count(*) FROM validation_protocol").query(Integer.class).single());
    } finally {
      process.destroy();
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor();
      }
    }
  }
}
