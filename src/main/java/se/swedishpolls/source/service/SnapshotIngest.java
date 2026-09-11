package se.swedishpolls.source.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.source.repository.SnapshotRepository;

/**
 * One source check: fetch the source with conditional requests, archive a changed complete snapshot
 * with its parsed polls, and activate it in the same transaction. A failed check leaves the active
 * snapshot exactly as it was.
 */
@Component
public class SnapshotIngest {
  public enum Result {
    CHANGED,
    UNCHANGED,
    BUSY
  }

  @HttpExchange(accept = "text/csv")
  interface PollSourceClient {
    @GetExchange
    ResponseEntity<byte[]> fetch(
        @RequestHeader(name = "If-None-Match", required = false) String etag,
        @RequestHeader(name = "If-Modified-Since", required = false) String modified);
  }

  private final SnapshotRepository snapshots;
  private final PollSourceClient pollSource;
  private final TransactionTemplate transaction;

  public SnapshotIngest(
      SnapshotRepository snapshots,
      PollSourceClient pollSource,
      PlatformTransactionManager transactions) {
    this.snapshots = snapshots;
    this.pollSource = pollSource;
    this.transaction = new TransactionTemplate(transactions);
  }

  /** One transaction prevents partial archives or overlapping workers from changing membership. */
  public Result check() {
    return transaction.execute(
        status -> {
          if (!snapshots.tryAdvisoryLock()) return Result.BUSY;
          final Map<String, @org.jspecify.annotations.Nullable Object> source =
              snapshots.registerSource();
          final Optional<Snapshot> previous = activeSnapshot();
          final ResponseEntity<byte[]> response =
              fetch(
                  previous.isPresent() ? (String) source.get("etag") : null,
                  previous.isPresent() ? (String) source.get("last_modified") : null);
          if (response.getStatusCode().value() == 304) {
            if (previous.isEmpty())
              throw new IllegalStateException("304 without an archived snapshot");
            snapshots.activate(
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
          final String hash = sha256(bytes);
          final Optional<Long> existing = snapshots.snapshotId(hash);
          final long id;
          if (existing.isPresent()) {
            id = existing.get();
          } else {
            id = snapshots.archive(hash, bytes, PollCsv.parse(bytes));
          }
          snapshots.activate(
              id,
              response.getHeaders().getFirst("ETag"),
              response.getHeaders().getFirst("Last-Modified"));
          return previous.isPresent() && previous.get().id() == id
              ? Result.UNCHANGED
              : Result.CHANGED;
        });
  }

  public Optional<Snapshot> activeSnapshot() {
    return snapshots.activeSnapshot();
  }

  public Snapshot snapshot(long id) {
    return snapshots.snapshot(id);
  }

  /** Reads the archived source bytes on demand, so a snapshot never carries the whole payload. */
  public byte[] rawCsv(long snapshotId) {
    return snapshots.rawCsv(snapshotId);
  }

  public List<PollCsv.Poll> polls(long snapshotId) {
    return snapshots.polls(snapshotId);
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
