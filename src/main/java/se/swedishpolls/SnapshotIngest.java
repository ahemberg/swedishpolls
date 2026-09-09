package se.swedishpolls;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "polls.ingest.enabled", havingValue = "true", matchIfMissing = true)
public class SnapshotIngest {
  public enum Result {
    CHANGED,
    UNCHANGED,
    BUSY
  }

  public record Snapshot(long id, String sha256) {}

  private final JdbcClient db;
  private final TransactionTemplate transaction;
  private final String sourceUrl;
  private final JsonMapper json = JsonMapper.builder().build();

  public SnapshotIngest(
      JdbcClient db,
      PlatformTransactionManager transactions,
      @Value(
              "${polls.source-url:https://raw.githubusercontent.com/MansMeg/SwedishPolls/master/Data/Polls.csv}")
          String sourceUrl) {
    this.db = db;
    this.transaction = new TransactionTemplate(transactions);
    this.sourceUrl = URI.create(sourceUrl).toString();
  }

  @Scheduled(fixedRate = 30 * 60 * 1000)
  public void scheduledCheck() {
    try {
      check();
    } catch (RuntimeException e) {
      LoggerFactory.getLogger(SnapshotIngest.class)
          .error("Poll source check failed; active snapshot retained", e);
    }
  }

  /** One transaction prevents partial archives or overlapping workers from changing membership. */
  public Result check() {
    return transaction.execute(
        status -> {
          if (!db.sql("SELECT pg_try_advisory_xact_lock(1717001)").query(Boolean.class).single())
            return Result.BUSY;
          db.sql("INSERT INTO poll_source(source_url) VALUES (?) ON CONFLICT DO NOTHING")
              .param(sourceUrl)
              .update();
          var source =
              db.sql("SELECT etag, last_modified FROM poll_source WHERE source_url = ?")
                  .param(sourceUrl)
                  .query()
                  .singleRow();
          var previous = activeSnapshot();
          var request =
              HttpRequest.newBuilder(URI.create(sourceUrl))
                  .timeout(Duration.ofSeconds(30))
                  .header("Accept", "text/csv");
          if (previous.isPresent()) {
            if (source.get("etag") instanceof String etag) request.header("If-None-Match", etag);
            if (source.get("last_modified") instanceof String modified)
              request.header("If-Modified-Since", modified);
          }
          var response = fetch(request.build());
          if (response.statusCode() == 304) {
            if (previous.isEmpty())
              throw new IllegalStateException("304 without an archived snapshot");
            updateSource(
                previous.get().id(),
                response.headers().firstValue("ETag").orElse((String) source.get("etag")),
                response
                    .headers()
                    .firstValue("Last-Modified")
                    .orElse((String) source.get("last_modified")));
            return Result.UNCHANGED;
          }
          if (response.statusCode() != 200
              || response.headers().firstValue("Content-Range").isPresent())
            throw new IllegalStateException(
                "Expected a complete source response, got HTTP " + response.statusCode());
          var bytes = response.body();
          var hash = sha256(bytes);
          var existing =
              db.sql("SELECT id FROM poll_snapshot WHERE source_url = ? AND sha256 = ?")
                  .params(sourceUrl, hash)
                  .query(Long.class)
                  .optional();
          long id;
          if (existing.isPresent()) {
            id = existing.get();
          } else {
            var polls = PollCsv.parse(bytes);
            id =
                db.sql(
                        "INSERT INTO poll_snapshot(source_url, sha256, raw_csv, parser_version) VALUES (?, ?, ?, 1) RETURNING id")
                    .params(sourceUrl, hash, bytes)
                    .query(Long.class)
                    .single();
            for (var poll : polls) {
              db.sql(
                      "INSERT INTO snapshot_poll(snapshot_id, row_number, poll) VALUES (?, ?, ?::jsonb)")
                  .params(id, poll.rowNumber(), json.writeValueAsString(poll))
                  .update();
            }
          }
          updateSource(
              id,
              response.headers().firstValue("ETag").orElse(null),
              response.headers().firstValue("Last-Modified").orElse(null));
          return previous.isPresent() && previous.get().id() == id
              ? Result.UNCHANGED
              : Result.CHANGED;
        });
  }

  private void updateSource(long id, String etag, String modified) {
    db.sql(
            "UPDATE poll_source SET active_snapshot_id = ?, etag = ?, last_modified = ?, last_successful_check_at = now() WHERE source_url = ?")
        .params(id, etag, modified, sourceUrl)
        .update();
  }

  public Optional<Snapshot> activeSnapshot() {
    return db.sql(
            "SELECT s.id, s.sha256 FROM poll_snapshot s JOIN poll_source p ON p.active_snapshot_id = s.id WHERE p.source_url = ?")
        .param(sourceUrl)
        .query((rs, row) -> new Snapshot(rs.getLong("id"), rs.getString("sha256")))
        .optional();
  }

  public Snapshot snapshot(long id) {
    return db.sql("SELECT id, sha256 FROM poll_snapshot WHERE id = ? AND source_url = ?")
        .params(id, sourceUrl)
        .query((rs, row) -> new Snapshot(rs.getLong("id"), rs.getString("sha256")))
        .single();
  }

  /** Reads the archived source bytes on demand, so a snapshot never carries the whole payload. */
  public byte[] rawCsv(long snapshotId) {
    return db.sql("SELECT raw_csv FROM poll_snapshot WHERE id = ? AND source_url = ?")
        .params(snapshotId, sourceUrl)
        .query(byte[].class)
        .single();
  }

  public List<PollCsv.Poll> polls(long snapshotId) {
    return db.sql(
            "SELECT p.poll::text FROM snapshot_poll p JOIN poll_snapshot s ON s.id = p.snapshot_id WHERE s.id = ? AND s.source_url = ? ORDER BY row_number")
        .params(snapshotId, sourceUrl)
        .query((rs, row) -> json.readValue(rs.getString(1), PollCsv.Poll.class))
        .list();
  }

  private static HttpResponse<byte[]> fetch(HttpRequest request) {
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      return client.send(
          request,
          info ->
              HttpResponse.BodySubscribers.limiting(
                  HttpResponse.BodySubscribers.ofByteArray(), 16 * 1024 * 1024));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Source fetch interrupted", e);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Source fetch failed", e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
