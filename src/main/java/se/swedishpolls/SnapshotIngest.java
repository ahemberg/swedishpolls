package se.swedishpolls;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SnapshotIngest {
  public enum Result {
    CHANGED,
    UNCHANGED,
    BUSY
  }

  public record Snapshot(long id, String sha256) {}

  @HttpExchange(accept = "text/csv")
  interface PollSourceClient {
    @GetExchange
    ResponseEntity<byte[]> fetch(
        @RequestHeader(name = "If-None-Match", required = false) String etag,
        @RequestHeader(name = "If-Modified-Since", required = false) String modified);
  }

  private final JdbcClient db;
  private final PollSourceClient pollSource;
  private final TransactionTemplate transaction;
  private final String sourceUrl;
  private final JsonMapper json = JsonMapper.builder().build();

  public SnapshotIngest(
      JdbcClient db,
      PollSourceClient pollSource,
      PlatformTransactionManager transactions,
      @Value("${polls.source-url}") String sourceUrl) {
    this.db = db;
    this.pollSource = pollSource;
    this.transaction = new TransactionTemplate(transactions);
    this.sourceUrl = URI.create(sourceUrl).toString();
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
          final java.util.Map<java.lang.String, java.lang.@org.jspecify.annotations.Nullable Object>
              source =
                  db.sql("SELECT etag, last_modified FROM poll_source WHERE source_url = ?")
                      .param(sourceUrl)
                      .query()
                      .singleRow();
          final java.util.Optional<se.swedishpolls.SnapshotIngest.Snapshot> previous =
              activeSnapshot();
          final org.springframework.http.ResponseEntity<byte[]> response =
              fetch(
                  previous.isPresent() ? (String) source.get("etag") : null,
                  previous.isPresent() ? (String) source.get("last_modified") : null);
          if (response.getStatusCode().value() == 304) {
            if (previous.isEmpty())
              throw new IllegalStateException("304 without an archived snapshot");
            updateSource(
                previous.get().id(),
                Optional.ofNullable(response.getHeaders().getFirst("ETag"))
                    .orElse((String) source.get("etag")),
                Optional.ofNullable(response.getHeaders().getFirst("Last-Modified"))
                    .orElse((String) source.get("last_modified")));
            return Result.UNCHANGED;
          }
          if (response.getStatusCode().value() != 200
              || response.getHeaders().getFirst("Content-Range") != null)
            throw new IllegalStateException(
                "Expected a complete source response, got HTTP "
                    + response.getStatusCode().value());
          final byte[] bytes = Optional.ofNullable(response.getBody()).orElseGet(() -> new byte[0]);
          final java.lang.String hash = sha256(bytes);
          final java.util.Optional<java.lang.Long> existing =
              db.sql("SELECT id FROM poll_snapshot WHERE source_url = ? AND sha256 = ?")
                  .params(sourceUrl, hash)
                  .query(Long.class)
                  .optional();
          final long id;
          if (existing.isPresent()) {
            id = existing.get();
          } else {
            final java.util.List<se.swedishpolls.PollCsv.Poll> polls = PollCsv.parse(bytes);
            id =
                db.sql(
                        "INSERT INTO poll_snapshot(source_url, sha256, raw_csv, parser_version) VALUES (?, ?, ?, 1) RETURNING id")
                    .params(sourceUrl, hash, bytes)
                    .query(Long.class)
                    .single();
            for (se.swedishpolls.PollCsv.Poll poll : polls) {
              db.sql(
                      "INSERT INTO snapshot_poll(snapshot_id, row_number, poll) VALUES (?, ?, ?::jsonb)")
                  .params(id, poll.rowNumber(), json.writeValueAsString(poll))
                  .update();
            }
          }
          updateSource(
              id,
              response.getHeaders().getFirst("ETag"),
              response.getHeaders().getFirst("Last-Modified"));
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

  private ResponseEntity<byte[]> fetch(String etag, String modified) {
    try {
      return pollSource.fetch(etag, modified);
    } catch (RestClientException e) {
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
