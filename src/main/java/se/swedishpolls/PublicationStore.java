package se.swedishpolls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and writes publications. A candidate is private until its assets are staged, verified and
 * moved into place and the current pointer is switched in one transaction; nothing here ever
 * rewrites a published document or a published image byte.
 */
@Component
public class PublicationStore {
  public static final String CANDIDATE = "candidate";
  public static final String PUBLISHED = "published";
  public static final String ABANDONED = "abandoned";

  /** The publication a request without a pin reads, and whether a failed update kept it. */
  public record Current(String publicationId, boolean stale, Instant staleSince) {}

  /** One publication's immutable header. */
  public record Header(
      String publicationId,
      String runId,
      long snapshotId,
      LocalDate lastFieldworkDate,
      Instant sourceCheckedAt,
      Instant publishedAt,
      String history,
      String state) {}

  /** The model run behind a publication. */
  public record Run(
      String runId,
      String protocolVersion,
      long seed,
      int draws,
      String parameters,
      String codeVersion,
      String estimatorVersion,
      String runtime,
      String numericalLibrary) {}

  /** The archived source snapshot a publication pinned. */
  public record Snapshot(long snapshotId, String sha256, String sourceUrl, Instant capturedAt) {}

  /** One immutable asset version. Its bytes live on the durable publication volume. */
  public record Asset(
      String publicationId,
      String kind,
      String language,
      int version,
      String rendererVersion,
      String mediaType,
      int byteCount,
      String sha256) {
    /** The permanent path of this version. A renderer change adds a version, never a rewrite. */
    public String path() {
      return "/assets/" + publicationId + "/" + kind + "-" + language + "-" + version + ".png";
    }
  }

  private final JdbcClient db;
  private final TransactionTemplate transaction;
  private final Path root;

  public PublicationStore(
      JdbcClient db,
      PlatformTransactionManager transactions,
      @Value("${publication.root:target/publications}") String root) {
    this.db = db;
    this.transaction = new TransactionTemplate(transactions);
    this.root = Path.of(root);
  }

  public Path root() {
    return root;
  }

  public Optional<Current> current() {
    return db.sql("SELECT publication_id, stale, stale_since FROM current_publication")
        .query(
            (rs, row) ->
                new Current(
                    rs.getString("publication_id"),
                    rs.getBoolean("stale"),
                    rs.getTimestamp("stale_since") == null
                        ? null
                        : rs.getTimestamp("stale_since").toInstant()))
        .optional();
  }

  public Optional<Header> header(String publicationId) {
    return db.sql(
            """
            SELECT id, run_id, snapshot_id, last_fieldwork_date, source_checked_at, published_at,
                   history, state
            FROM publication WHERE id = ? AND state = 'published'
            """)
        .param(publicationId)
        .query(
            (rs, row) ->
                new Header(
                    rs.getString("id"),
                    rs.getString("run_id"),
                    rs.getLong("snapshot_id"),
                    rs.getObject("last_fieldwork_date", LocalDate.class),
                    rs.getTimestamp("source_checked_at").toInstant(),
                    rs.getTimestamp("published_at").toInstant(),
                    rs.getString("history"),
                    rs.getString("state")))
        .optional();
  }

  public Run run(String runId) {
    return db.sql(
            """
            SELECT id, protocol_version, seed, draws, parameters::text AS parameters, code_version,
                   estimator_version, runtime, numerical_library
            FROM model_run WHERE id = ?
            """)
        .param(runId)
        .query(
            (rs, row) ->
                new Run(
                    rs.getString("id"),
                    rs.getString("protocol_version"),
                    rs.getLong("seed"),
                    rs.getInt("draws"),
                    rs.getString("parameters"),
                    rs.getString("code_version"),
                    rs.getString("estimator_version"),
                    rs.getString("runtime"),
                    rs.getString("numerical_library")))
        .single();
  }

  public Snapshot snapshot(long snapshotId) {
    return db.sql("SELECT id, sha256, source_url, captured_at FROM poll_snapshot WHERE id = ?")
        .param(snapshotId)
        .query(
            (rs, row) ->
                new Snapshot(
                    rs.getLong("id"),
                    rs.getString("sha256"),
                    rs.getString("source_url"),
                    rs.getTimestamp("captured_at").toInstant()))
        .single();
  }

  public Optional<String> document(String publicationId, String surface, String language) {
    return db.sql(
            "SELECT body::text FROM publication_document WHERE publication_id = ? AND surface = ?"
                + " AND language = ?")
        .params(publicationId, surface, language)
        .query(String.class)
        .optional();
  }

  public List<Asset> assets(String publicationId) {
    return db.sql(
            """
            SELECT publication_id, kind, language, version, renderer_version, media_type,
                   byte_count, sha256
            FROM publication_asset WHERE publication_id = ?
            ORDER BY kind, language, version
            """)
        .param(publicationId)
        .query(
            (rs, row) ->
                new Asset(
                    rs.getString("publication_id"),
                    rs.getString("kind"),
                    rs.getString("language"),
                    rs.getInt("version"),
                    rs.getString("renderer_version"),
                    rs.getString("media_type"),
                    rs.getInt("byte_count"),
                    rs.getString("sha256")))
        .list();
  }

  public Optional<Asset> asset(String publicationId, String kind, String language, int version) {
    return assets(publicationId).stream()
        .filter(
            asset ->
                asset.kind().equals(kind)
                    && asset.language().equals(language)
                    && asset.version() == version)
        .findFirst();
  }

  /** The current version of one card: the highest version stored for it. */
  public Optional<Asset> latestAsset(String publicationId, String kind, String language) {
    return assets(publicationId).stream()
        .filter(asset -> asset.kind().equals(kind) && asset.language().equals(language))
        .max(Comparator.comparingInt(Asset::version));
  }

  /** The stored bytes, verified against the digest recorded when they were staged. */
  public byte[] bytes(Asset asset) {
    try {
      final byte[] bytes = Files.readAllBytes(assetFile(asset));
      if (!sha256(bytes).equals(asset.sha256())) {
        throw new IllegalStateException("Stored asset " + asset.path() + " no longer matches");
      }
      return bytes;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Path assetFile(Asset asset) {
    return root.resolve("assets")
        .resolve(asset.publicationId())
        .resolve(asset.kind() + "-" + asset.language() + "-" + asset.version() + ".png");
  }

  private Path stagingDirectory(String publicationId) {
    return root.resolve("staging").resolve(publicationId);
  }

  /**
   * Writes the candidate's image bytes to a private staging directory and reads every one of them
   * back. An interrupted staging leaves that directory behind and no published byte anywhere.
   */
  public List<Asset> stage(String publicationId, List<Asset> assets, List<byte[]> bytes) {
    if (assets.size() != bytes.size()) {
      throw new IllegalArgumentException("Every staged asset needs its bytes");
    }
    try {
      final Path staging = stagingDirectory(publicationId);
      deleteRecursively(staging);
      Files.createDirectories(staging);
      for (int index = 0; index < assets.size(); index++) {
        final Asset asset = assets.get(index);
        final Path file = staging.resolve(fileName(asset));
        Files.write(file, bytes.get(index));
        final byte[] written = Files.readAllBytes(file);
        if (!sha256(written).equals(asset.sha256()) || written.length != asset.byteCount()) {
          throw new IllegalStateException("Staged asset " + asset.path() + " failed verification");
        }
      }
      return List.copyOf(assets);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String fileName(Asset asset) {
    return asset.kind() + "-" + asset.language() + "-" + asset.version() + ".png";
  }

  /**
   * Moves the verified staging directory into place and switches the current pointer in one
   * transaction. The pointer never moves before every byte it will serve is readable.
   */
  public void promote(String publicationId, List<Asset> assets) {
    try {
      final Path staging = stagingDirectory(publicationId);
      final Path target = root.resolve("assets").resolve(publicationId);
      Files.createDirectories(root.resolve("assets"));
      if (Files.exists(target)) {
        throw new IllegalStateException("Published assets already exist for " + publicationId);
      }
      Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
      for (final Asset asset : assets) {
        final byte[] written = Files.readAllBytes(assetFile(asset));
        if (!sha256(written).equals(asset.sha256())) {
          throw new IllegalStateException("Moved asset " + asset.path() + " failed verification");
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    transaction.executeWithoutResult(
        status -> {
          for (final Asset asset : assets) {
            db.sql(
                    """
                    INSERT INTO publication_asset(publication_id, kind, language, version,
                        renderer_version, media_type, byte_count, sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                .params(
                    asset.publicationId(),
                    asset.kind(),
                    asset.language(),
                    asset.version(),
                    asset.rendererVersion(),
                    asset.mediaType(),
                    asset.byteCount(),
                    asset.sha256())
                .update();
          }
          db.sql("UPDATE publication SET state = 'published' WHERE id = ?")
              .param(publicationId)
              .update();
          db.sql(
                  """
                  INSERT INTO current_publication(singleton, publication_id, stale, stale_since)
                  VALUES (true, ?, false, NULL)
                  ON CONFLICT (singleton)
                  DO UPDATE SET publication_id = EXCLUDED.publication_id, stale = false,
                                stale_since = NULL
                  """)
              .param(publicationId)
              .update();
        });
  }

  /** Discards a candidate that never became current. Its staged bytes go with it. */
  public void abandon(String publicationId) {
    try {
      deleteRecursively(stagingDirectory(publicationId));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    transaction.executeWithoutResult(
        status -> {
          db.sql("DELETE FROM publication_document WHERE publication_id = ?")
              .param(publicationId)
              .update();
          db.sql("UPDATE publication SET state = 'abandoned' WHERE id = ? AND state = 'candidate'")
              .param(publicationId)
              .update();
        });
  }

  /** Marks the kept publication stale after a failed update. Its own bytes do not change. */
  public void markStale(Instant since) {
    db.sql("UPDATE current_publication SET stale = true, stale_since = ? WHERE NOT stale")
        .param(java.sql.Timestamp.from(since))
        .update();
  }

  public void recordRun(
      String runId,
      long snapshotId,
      ModelFreeze freeze,
      String parametersJson,
      String codeVersion,
      String runtime) {
    db.sql(
            """
            INSERT INTO model_run(id, snapshot_id, protocol_version, seed, draws, parameters,
                code_version, estimator_version, runtime, numerical_library)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """)
        .params(
            runId,
            snapshotId,
            freeze.developmentProtocolVersion(),
            freeze.uncertainty().seed(),
            freeze.uncertainty().draws(),
            parametersJson,
            codeVersion,
            freeze.estimatorVersion(),
            runtime,
            freeze.numericalLibrary())
        .update();
  }

  public void recordCandidate(
      String publicationId,
      String runId,
      long snapshotId,
      LocalDate lastFieldworkDate,
      Instant sourceCheckedAt,
      Instant publishedAt) {
    db.sql(
            """
            INSERT INTO publication(id, run_id, snapshot_id, last_fieldwork_date,
                source_checked_at, published_at, history, state)
            VALUES (?, ?, ?, ?, ?, ?, 'corrected', 'candidate')
            """)
        .params(
            publicationId,
            runId,
            snapshotId,
            lastFieldworkDate,
            java.sql.Timestamp.from(sourceCheckedAt),
            java.sql.Timestamp.from(publishedAt))
        .update();
  }

  public void recordDocument(String publicationId, String surface, String language, String body) {
    db.sql(
            "INSERT INTO publication_document(publication_id, surface, language, body, sha256)"
                + " VALUES (?, ?, ?, ?::jsonb, ?)")
        .params(
            publicationId,
            surface,
            language,
            body,
            sha256(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .update();
  }

  public void recordAttempt(
      Publisher.Outcome outcome, Instant sourceCheckedAt, String publicationId, String failure) {
    db.sql(
            """
            INSERT INTO publication_attempt(finished_at, source_checked_at, outcome,
                publication_id, failure)
            VALUES (now(), ?, ?, ?, ?)
            """)
        .params(
            sourceCheckedAt == null ? null : java.sql.Timestamp.from(sourceCheckedAt),
            outcome.stored(),
            publicationId,
            failure)
        .update();
  }

  /** The snapshot the current publication pinned, so a poll request never reads a newer one. */
  public Optional<Long> pinnedSnapshot(String publicationId) {
    return db.sql("SELECT snapshot_id FROM publication WHERE id = ? AND state = 'published'")
        .param(publicationId)
        .query(Long.class)
        .optional();
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    final List<Path> paths = new ArrayList<>();
    try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
      walk.forEach(paths::add);
    }
    paths.sort(Comparator.reverseOrder());
    for (final Path path : paths) {
      Files.deleteIfExists(path);
    }
  }
}
