package se.swedishpolls;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationIT {
    @Test
    void packagedApplicationServesBundledAssetsAndMigratesPostgres() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var log = Path.of("target", "application-integration.log").toFile();
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "target/swedishpolls-0.1.0.jar", "--server.port=" + port)
                .redirectErrorStream(true).redirectOutput(log).start();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            var root = URI.create("http://127.0.0.1:" + port);
            HttpResponse<String> response = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline && process.isAlive()) {
                try {
                    response = http.send(HttpRequest.newBuilder(root).timeout(Duration.ofSeconds(1)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) break;
                } catch (java.io.IOException ignored) {
                    // The packaged server has not bound its port yet.
                }
                Thread.sleep(100);
            }
            assertNotNull(response, "Server did not start; see " + log);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Skattningar är ännu inte tillgängliga."));
            var asset = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(response.body());
            assertTrue(asset.find(), "HTML must reference compiled JavaScript");
            var script = http.send(HttpRequest.newBuilder(root.resolve(asset.group(1))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("content-type").orElse("").contains("javascript"));
            assertTrue(script.body().contains("hydrateRoot"));

            var db = JdbcClient.create(new DriverManagerDataSource(
                    System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/swedishpolls"),
                    System.getenv().getOrDefault("DATABASE_USER", "swedishpolls"),
                    System.getenv("DATABASE_PASSWORD")));
            assertEquals("18.4", db.sql("SHOW server_version").query(String.class).single().split(" ")[0]);
            assertEquals(1, db.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
                    .query(Integer.class).single());
            assertEquals(0, db.sql("SELECT count(*) FROM validation_protocol").query(Integer.class).single());
        } finally {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        }
    }
}
