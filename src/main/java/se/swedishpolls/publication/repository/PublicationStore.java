package se.swedishpolls.publication.repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import se.swedishpolls.publication.CurrentPublication;
import se.swedishpolls.publication.Digest;
import se.swedishpolls.publication.ModelFreeze;
import se.swedishpolls.publication.ModelRun;
import se.swedishpolls.publication.PinnedSnapshot;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.PublicationOutcome;

/**
 * Reads and writes publications. A candidate is private until the current pointer is switched in
 * one transaction; nothing here ever rewrites a published document.
 */
@Component
public class PublicationStore {
  public static final String CANDIDATE = "candidate";
  public static final String PUBLISHED = "published";
  public static final String ABANDONED = "abandoned";

  private final JdbcClient db;
  private final TransactionTemplate transaction;

  public PublicationStore(JdbcClient db, PlatformTransactionManager transactions) {
    this.db = db;
    this.transaction = new TransactionTemplate(transactions);
  }

  public Optional<CurrentPublication> current() {
    return db.sql("SELECT publication_id, stale, stale_since FROM current_publication")
        .query(
            (rs, row) ->
                new CurrentPublication(
                    rs.getString("publication_id"),
                    rs.getBoolean("stale"),
                    rs.getTimestamp("stale_since") == null
                        ? null
                        : rs.getTimestamp("stale_since").toInstant()))
        .optional();
  }

  public Optional<PublicationHeader> header(String publicationId) {
    return db.sql(
            """
            SELECT id, run_id, snapshot_id, last_fieldwork_date, source_checked_at, published_at,
                   history, headline_period, approximated_election, state
            FROM publication WHERE id = ? AND state = 'published'
            """)
        .param(publicationId)
        .query(
            (rs, row) ->
                new PublicationHeader(
                    rs.getString("id"),
                    rs.getString("run_id"),
                    rs.getLong("snapshot_id"),
                    rs.getObject("last_fieldwork_date", LocalDate.class),
                    rs.getTimestamp("source_checked_at").toInstant(),
                    rs.getTimestamp("published_at").toInstant(),
                    rs.getString("history"),
                    rs.getString("headline_period"),
                    rs.getInt("approximated_election"),
                    rs.getString("state")))
        .optional();
  }

  public ModelRun run(String runId) {
    return db.sql(
            """
            SELECT id, protocol_version, seed, draws, parameters::text AS parameters, code_version,
                   estimator_version, runtime, numerical_library
            FROM model_run WHERE id = ?
            """)
        .param(runId)
        .query(
            (rs, row) ->
                new ModelRun(
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

  public PinnedSnapshot snapshot(long snapshotId) {
    return db.sql("SELECT id, sha256, source_url, captured_at FROM poll_snapshot WHERE id = ?")
        .param(snapshotId)
        .query(
            (rs, row) ->
                new PinnedSnapshot(
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

  /** Publishes the complete candidate and switches the current pointer in one transaction. */
  public void promote(String publicationId) {
    transaction.executeWithoutResult(
        status -> {
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

  /** Discards a candidate that never became current. */
  public void abandon(String publicationId) {
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
        .param(Timestamp.from(since))
        .update();
  }

  /**
   * The identifier the next model run is stored under: one past the rows already there, so an
   * archived result names its own place in the sequence.
   */
  public String nextRunId() {
    final int next = db.sql("SELECT count(*) FROM model_run").query(Integer.class).single() + 1;
    return String.format(Locale.ROOT, "run_%06d", next);
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
      Instant publishedAt,
      String headlinePeriod,
      int approximatedElection) {
    db.sql(
            """
            INSERT INTO publication(id, run_id, snapshot_id, last_fieldwork_date,
                source_checked_at, published_at, history, headline_period,
                approximated_election, state)
            VALUES (?, ?, ?, ?, ?, ?, 'corrected', ?, ?, 'candidate')
            """)
        .params(
            publicationId,
            runId,
            snapshotId,
            lastFieldworkDate,
            Timestamp.from(sourceCheckedAt),
            Timestamp.from(publishedAt),
            headlinePeriod,
            approximatedElection)
        .update();
  }

  /** Read the small capability manifest without transferring every subset to metadata callers. */
  public Optional<String> coalitionHistoryMetadata(String publicationId) {
    return db.sql(
            """
        SELECT jsonb_build_object(
          'schemaVersion', body->'schemaVersion', 'aggregationVersion', body->'aggregationVersion',
          'artifactSha256', sha256,
          'artifactBytes', octet_length(body::text),
          'interval', body->'interval', 'roster', body->'roster',
          'draws', body->'draws', 'seed', body->'seed')::text
        FROM publication_document
        WHERE publication_id = ? AND surface = 'coalition-history' AND language = 'sv'
        """)
        .param(publicationId)
        .query(String.class)
        .optional();
  }

  public void recordDocument(String publicationId, String surface, String language, String body) {
    db.sql(
            """
        WITH document AS (SELECT ?::jsonb AS body)
        INSERT INTO publication_document(publication_id, surface, language, body, sha256)
        SELECT ?, ?, ?, body,
          CASE WHEN ? = 'coalition-history'
            THEN encode(sha256(convert_to(body::text, 'UTF8')), 'hex')
            ELSE ? END
        FROM document
        """)
        .params(
            body,
            publicationId,
            surface,
            language,
            surface,
            Digest.sha256(body.getBytes(StandardCharsets.UTF_8)))
        .update();
  }

  public void recordAttempt(
      PublicationOutcome outcome, Instant sourceCheckedAt, String publicationId, String failure) {
    db.sql(
            """
            INSERT INTO publication_attempt(finished_at, source_checked_at, outcome,
                publication_id, failure)
            VALUES (now(), ?, ?, ?, ?)
            """)
        .params(
            sourceCheckedAt == null ? null : Timestamp.from(sourceCheckedAt),
            outcome.stored(),
            publicationId,
            failure)
        .update();
  }

  /** The coverage periods one publication rendered an estimate for. */
  public List<String> coveragePeriods(String publicationId) {
    return db
        .sql(
            "SELECT DISTINCT surface FROM publication_document WHERE publication_id = ?"
                + " AND surface LIKE 'estimates-latest:%' ORDER BY surface")
        .param(publicationId)
        .query(String.class)
        .list()
        .stream()
        .map(surface -> surface.substring(surface.indexOf(':') + 1))
        .toList();
  }

  /** When the source was last read successfully, whether or not a publication followed. */
  public Optional<Instant> lastSuccessfulCheck() {
    return db.sql("SELECT max(last_successful_check_at) FROM poll_source")
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant);
  }
}
