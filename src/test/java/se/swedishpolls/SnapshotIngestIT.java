package se.swedishpolls;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotIngestIT {
    private HttpServer server;
    private Flyway flyway;
    private SnapshotIngest ingest;
    private DriverManagerDataSource dataSource;
    private String url;
    private byte[] body = PollCsvTest.csv(PollCsvTest.ROW);
    private int status = 200;
    private String etag = "\"first\"";
    private String receivedEtag;
    private String receivedModified;

    @BeforeEach
    void start() throws Exception {
        var schema = "ingest_" + UUID.randomUUID().toString().replace("-", "");
        var databaseUrl = System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/swedishpolls");
        dataSource = new DriverManagerDataSource(databaseUrl + (databaseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv().getOrDefault("DATABASE_USER", "swedishpolls"), System.getenv("DATABASE_PASSWORD"));
        flyway = Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
        flyway.migrate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/polls.csv", exchange -> {
            receivedEtag = exchange.getRequestHeaders().getFirst("If-None-Match");
            receivedModified = exchange.getRequestHeaders().getFirst("If-Modified-Since");
            if (etag != null) exchange.getResponseHeaders().add("ETag", etag);
            exchange.getResponseHeaders().add("Last-Modified", "Mon, 07 Sep 2026 05:09:49 GMT");
            exchange.sendResponseHeaders(status, status == 304 ? -1 : body.length);
            if (status != 304) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/polls.csv";
        ingest = newIngest();
    }

    private SnapshotIngest newIngest() {
        return new SnapshotIngest(JdbcClient.create(dataSource), new DataSourceTransactionManager(dataSource), url);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (flyway != null) flyway.clean();
    }

    @Test
    void completeSnapshotsReplaceMembershipAndRemainReadableAfterRestart() {
        var original = PollCsvTest.ROW.replace("2020-01-20", "NA");
        body = PollCsvTest.csv(original + PollCsvTest.ROW.replace("Ipsos", "Novus"));
        assertEquals(SnapshotIngest.Result.CHANGED, ingest.check());
        var first = ingest.activeSnapshot().orElseThrow();
        assertArrayEquals(body, first.rawCsv());
        assertEquals(2, ingest.polls(first.id()).size());

        // A corrected share, changed sample-size key and deleted Novus row replace the whole active set.
        etag = "\"second\"";
        body = PollCsvTest.csv(original.replace("20.123", "20.124").replace(",1000,", ",1001,"));
        assertEquals(SnapshotIngest.Result.CHANGED, ingest.check());
        var second = ingest.activeSnapshot().orElseThrow();
        assertNotEquals(first.id(), second.id());
        assertEquals(1, ingest.polls(second.id()).size());
        assertEquals("1001", ingest.polls(second.id()).getFirst().raw().get("n"));
        assertEquals("20.124", ingest.polls(second.id()).getFirst().raw().get("M"));
        assertTrue(ingest.polls(second.id()).getFirst().eligible());
        assertFalse(ingest.polls(second.id()).getFirst().publicationTimeEligible());
        assertEquals(2, newIngest().polls(first.id()).size());
        assertArrayEquals(first.rawCsv(), newIngest().snapshot(first.id()).rawCsv());
        assertEquals(second.id(), newIngest().activeSnapshot().orElseThrow().id());
    }
    @Test
    void conditionalRequestsAndHashesAvoidReimportAndCanRestoreAnEarlierSnapshot() {
        assertEquals(SnapshotIngest.Result.CHANGED, ingest.check());
        assertNull(receivedEtag);
        var first = ingest.activeSnapshot().orElseThrow();
        status = 304;
        assertEquals(SnapshotIngest.Result.UNCHANGED, newIngest().check());
        assertEquals("\"first\"", receivedEtag);
        assertEquals("Mon, 07 Sep 2026 05:09:49 GMT", receivedModified);
        status = 200;
        etag = "\"new-validator-same-bytes\"";
        assertEquals(SnapshotIngest.Result.UNCHANGED, ingest.check());
        assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
        body = PollCsvTest.csv(PollCsvTest.ROW.replace("20.123", "20.125"));
        assertEquals(SnapshotIngest.Result.CHANGED, ingest.check());
        body = first.rawCsv();
        assertEquals(SnapshotIngest.Result.CHANGED, ingest.check());
        assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
        assertEquals(1, ingest.polls(first.id()).size());
    }

    @Test
    void failedOrPartialResponsesKeepTheLastSnapshotAndValidators() {
        status = 304;
        assertThrows(IllegalStateException.class, ingest::check);
        assertTrue(ingest.activeSnapshot().isEmpty());
        status = 200;
        ingest.check();
        var first = ingest.activeSnapshot().orElseThrow();
        etag = "\"bad\"";
        for (int code : new int[] { 500, 206, 404 }) {
            status = code;
            assertThrows(IllegalStateException.class, ingest::check);
            assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
        }
        status = 200;
        for (byte[] invalid : new byte[][] { new byte[0], PollCsvTest.HEADER.getBytes(StandardCharsets.UTF_8),
                PollCsvTest.csv("short,row\n"), new byte[] { (byte) 0xc3, (byte) 0x28 } }) {
            body = invalid;
            assertThrows(IllegalArgumentException.class, ingest::check);
            assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
            assertEquals("\"first\"", receivedEtag);
        }
        body = first.rawCsv();
        assertEquals(SnapshotIngest.Result.UNCHANGED, ingest.check());
    }

    @Test
    void aFailedRowWriteRollsBackTheArchiveAndPointerTogether() {
        ingest.check();
        var first = ingest.activeSnapshot().orElseThrow();
        var db = JdbcClient.create(dataSource);
        db.sql("ALTER TABLE snapshot_poll ADD CONSTRAINT simulated_disk_failure CHECK (poll->>'company' <> 'Broken')").update();
        body = PollCsvTest.csv(PollCsvTest.ROW + PollCsvTest.ROW.replace("Ipsos", "Broken"));
        assertThrows(org.springframework.dao.DataAccessException.class, ingest::check);
        assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
        db.sql("ALTER TABLE snapshot_poll DROP CONSTRAINT simulated_disk_failure").update();
        assertEquals(SnapshotIngest.Result.CHANGED, ingest.check());
        assertEquals(2, ingest.polls(ingest.activeSnapshot().orElseThrow().id()).size());
    }

    @Test
    void anotherWorkerCannotFetchWhileTheDatabaseLockIsHeld() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(ignored -> {
            JdbcClient.create(dataSource).sql("SELECT pg_advisory_xact_lock(1717001)").query().singleRow();
            try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertEquals(SnapshotIngest.Result.BUSY, executor.submit(() -> newIngest().check()).get());
            } catch (Exception e) { throw new AssertionError(e); }
        });
        assertNull(receivedEtag);
        assertTrue(ingest.activeSnapshot().isEmpty());
        assertEquals(SnapshotIngest.Result.CHANGED, ingest.check());
    }

    @Test
    void packagedApplicationSchedulesAnInitialSourceCheck() throws Exception {
        var log = Path.of("target", "scheduled-ingest-integration.log").toFile();
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", "target/swedishpolls-0.1.0.jar", "--server.port=0", "--polls.source-url=" + url)
                .redirectErrorStream(true).redirectOutput(log);
        builder.environment().put("DATABASE_URL", dataSource.getUrl());
        var process = builder.start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (ingest.activeSnapshot().isEmpty() && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(ingest.activeSnapshot().isPresent(), "Scheduled ingest did not run; see " + log);
            assertEquals(1, ingest.polls(ingest.activeSnapshot().orElseThrow().id()).size());
        } finally {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
    }
}
