package se.swedishpolls.source.repository;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Snapshot;
import tools.jackson.databind.json.JsonMapper;

/**
 * JDBC operations for the archived source: poll_source, poll_snapshot and snapshot_poll rows. The
 * source URL is the archived identity key of everything this class stores and reads.
 */
@Component
public class SnapshotRepository {
  private final JdbcClient db;
  private final String sourceUrl;
  private final JsonMapper json = JsonMapper.builder().build();

  public SnapshotRepository(JdbcClient db, @Value("${polls.source-url}") String sourceUrl) {
    this.db = db;
    this.sourceUrl = URI.create(sourceUrl).toString();
  }

  /** One transaction prevents partial archives or overlapping workers from changing membership. */
  public boolean tryAdvisoryLock() {
    return db.sql("SELECT pg_try_advisory_xact_lock(1717001)").query(Boolean.class).single();
  }

  /** Registers the source and returns its stored conditional request state. */
  public Map<String, Object> registerSource() {
    db.sql("INSERT INTO poll_source(source_url) VALUES (?) ON CONFLICT DO NOTHING")
        .param(sourceUrl)
        .update();
    return db.sql("SELECT etag, last_modified FROM poll_source WHERE source_url = ?")
        .param(sourceUrl)
        .query()
        .singleRow();
  }

  public Optional<Snapshot> activeSnapshot() {
    return db.sql(
            "SELECT s.id, s.sha256, s.captured_at FROM poll_snapshot s JOIN poll_source p ON p.active_snapshot_id = s.id WHERE p.source_url = ?")
        .param(sourceUrl)
        .query(
            (rs, row) ->
                new Snapshot(
                    rs.getLong("id"),
                    rs.getString("sha256"),
                    rs.getTimestamp("captured_at").toInstant()))
        .optional();
  }

  public Snapshot snapshot(long id) {
    return db.sql(
            "SELECT id, sha256, captured_at FROM poll_snapshot WHERE id = ? AND source_url = ?")
        .params(id, sourceUrl)
        .query(
            (rs, row) ->
                new Snapshot(
                    rs.getLong("id"),
                    rs.getString("sha256"),
                    rs.getTimestamp("captured_at").toInstant()))
        .single();
  }

  public Optional<Snapshot> findSnapshot(long id) {
    return db.sql(
            "SELECT id, sha256, captured_at FROM poll_snapshot WHERE id = ? AND source_url = ?")
        .params(id, sourceUrl)
        .query(
            (rs, row) ->
                new Snapshot(
                    rs.getLong("id"),
                    rs.getString("sha256"),
                    rs.getTimestamp("captured_at").toInstant()))
        .optional();
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

  public Optional<Long> snapshotId(String sha256) {
    return db.sql("SELECT id FROM poll_snapshot WHERE source_url = ? AND sha256 = ?")
        .params(sourceUrl, sha256)
        .query(Long.class)
        .optional();
  }

  /** Archives the snapshot and its parsed polls; the caller keeps the transaction boundary. */
  public long archive(String sha256, byte[] rawCsv, List<PollCsv.Poll> polls) {
    final long id =
        db.sql(
                "INSERT INTO poll_snapshot(source_url, sha256, raw_csv, parser_version) VALUES (?, ?, ?, 1) RETURNING id")
            .params(sourceUrl, sha256, rawCsv)
            .query(Long.class)
            .single();
    for (final PollCsv.Poll poll : polls) {
      db.sql("INSERT INTO snapshot_poll(snapshot_id, row_number, poll) VALUES (?, ?, ?::jsonb)")
          .params(id, poll.rowNumber(), json.writeValueAsString(poll))
          .update();
    }
    return id;
  }

  /** Points the source at the snapshot atomically; callers run this inside their transaction. */
  public void activate(long snapshotId, String etag, String modified) {
    db.sql(
            "UPDATE poll_source SET active_snapshot_id = ?, etag = ?, last_modified = ?, last_successful_check_at = now() WHERE source_url = ?")
        .params(snapshotId, etag, modified, sourceUrl)
        .update();
  }
}
