package se.swedishpolls;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The single publication worker. One advisory lock keeps two workers from publishing the same
 * snapshot, a changed complete snapshot becomes a private candidate, and the candidate becomes
 * current only after every document and every image byte is written and read back. A failed update
 * leaves the previous publication exactly as it was, with a staleness notice.
 */
@Component
public class Publisher {
  /** Distinct from the ingest lock: a publication may run while a source check is not running. */
  public static final long ADVISORY_LOCK = 1_717_002L;

  /** What one attempt did. The stored value is the lower-case name. */
  public enum Outcome {
    PUBLISHED,
    UNCHANGED,
    BLOCKED,
    FAILED,
    BUSY;

    public String stored() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private static final Logger LOG = LoggerFactory.getLogger(Publisher.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter PUBLICATION_ID =
      DateTimeFormatter.ofPattern("'pub_'yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  /** What one attempt did, and why it did not publish when it did not. */
  public record Attempt(Outcome outcome, String publicationId, String detail) {}

  /** A candidate that never became current, with the check or write that stopped it. */
  public static final class PublicationFailure extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    PublicationFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final DataSource dataSource;
  private final JdbcClient db;
  private final SnapshotIngest ingest;
  private final PublicationStore store;
  private final ModelFreeze freeze;

  @org.springframework.beans.factory.annotation.Autowired
  public Publisher(
      DataSource dataSource, JdbcClient db, SnapshotIngest ingest, PublicationStore store) {
    this(dataSource, db, ingest, store, ModelFreeze.load());
  }

  Publisher(
      DataSource dataSource,
      JdbcClient db,
      SnapshotIngest ingest,
      PublicationStore store,
      ModelFreeze freeze) {
    this.dataSource = dataSource;
    this.db = db;
    this.ingest = ingest;
    this.store = store;
    this.freeze = freeze;
  }

  public ModelFreeze freeze() {
    return freeze;
  }

  /** One publication attempt, holding the worker lock for its whole run. */
  public Attempt publish() {
    try (Connection connection = dataSource.getConnection()) {
      if (!lock(connection)) {
        return new Attempt(Outcome.BUSY, null, "another worker holds the publication lock");
      }
      try {
        return attempt();
      } finally {
        unlock(connection);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("The publication lock is unavailable", e);
    }
  }

  private Attempt attempt() {
    final SnapshotIngest.Result check = ingest.check();
    if (check == SnapshotIngest.Result.BUSY) {
      return new Attempt(Outcome.BUSY, null, "a source check is running");
    }
    final Instant sourceCheckedAt = lastSuccessfulCheck();
    final Optional<SnapshotIngest.Snapshot> active = ingest.activeSnapshot();
    if (active.isEmpty()) {
      return fail(sourceCheckedAt, "no archived source snapshot");
    }
    final Optional<PublicationStore.Current> current = store.current();
    if (current.isPresent()) {
      final PublicationStore.Header header =
          store.header(current.get().publicationId()).orElseThrow();
      if (header.snapshotId() == active.get().id()) {
        // Unchanged input is not a failure. The current publication stays current and fresh.
        store.recordAttempt(Outcome.UNCHANGED, sourceCheckedAt, null, null);
        return new Attempt(Outcome.UNCHANGED, header.publicationId(), "the snapshot is unchanged");
      }
    }
    if (!freeze.released()) {
      final String detail =
          "release "
              + freeze.releaseStatus()
              + "; blocking gates "
              + String.join(", ", freeze.failedBlockingGates());
      store.recordAttempt(Outcome.BLOCKED, sourceCheckedAt, null, detail);
      return new Attempt(Outcome.BLOCKED, null, detail);
    }
    try {
      return run(active.get(), sourceCheckedAt);
    } catch (RuntimeException e) {
      LOG.error("Publication failed; the previous publication is retained", e);
      return fail(sourceCheckedAt, causes(e));
    }
  }

  /** The whole cause chain, so the recorded reason names the check or write that stopped it. */
  private static String causes(Throwable thrown) {
    final StringBuilder chain = new StringBuilder();
    Throwable cause = thrown;
    // A bounded walk: a self-referential cause chain must not turn a failure into a hang.
    for (int depth = 0; cause != null && depth < 8; depth++) {
      if (!chain.isEmpty()) {
        chain.append(": ");
      }
      chain.append(cause.getClass().getSimpleName()).append(' ').append(cause.getMessage());
      cause = cause.getCause();
    }
    return chain.toString();
  }

  private Attempt fail(Instant sourceCheckedAt, String failure) {
    store.recordAttempt(Outcome.FAILED, sourceCheckedAt, null, failure);
    store.markStale(Instant.now());
    return new Attempt(Outcome.FAILED, null, failure);
  }

  private Attempt run(SnapshotIngest.Snapshot snapshot, Instant sourceCheckedAt) {
    ShareImages.checkFonts();
    final List<PollCsv.Poll> polls = ingest.polls(snapshot.id());
    final PublicationRun.Results results =
        PublicationRun.run(db, freeze, snapshot.id(), snapshot.sha256(), polls);
    checkInvariants(results);
    checkReproduction(results, polls);
    checkDrift(results);

    final Instant publishedAt = Instant.now();
    final String publicationId = PUBLICATION_ID.format(publishedAt);
    final String runId = nextRunId();
    String candidate = null;
    try {
      store.recordRun(runId, snapshot.id(), freeze, parameters(results), codeVersion(), runtime());
      store.recordCandidate(
          publicationId,
          runId,
          snapshot.id(),
          results.lastFieldworkDate(),
          sourceCheckedAt,
          publishedAt);
      candidate = publicationId;
      final PublicationDocuments.Identity identity =
          new PublicationDocuments.Identity(publicationId, runId, snapshot.id());
      for (final PublicationDocuments.Document document :
          PublicationDocuments.all(identity, results, freeze)) {
        store.recordDocument(
            publicationId,
            document.surface(),
            document.language(),
            JSON.writeValueAsString(document.body()));
      }
      final List<PublicationStore.Asset> assets = new ArrayList<>();
      final List<byte[]> bytes = new ArrayList<>();
      for (final ShareImages.Card card : ShareImages.cards(results, freeze)) {
        final byte[] png = ShareImages.render(card, Translations.of(card.language()));
        bytes.add(png);
        assets.add(
            new PublicationStore.Asset(
                publicationId,
                card.kind(),
                card.language(),
                1,
                ShareImages.RENDERER_VERSION,
                ShareImages.MEDIA_TYPE,
                png.length,
                PublicationStore.sha256(png)));
      }
      store.stage(publicationId, assets, bytes);
      store.promote(publicationId, assets);
      store.recordAttempt(Outcome.PUBLISHED, sourceCheckedAt, publicationId, null);
      return new Attempt(Outcome.PUBLISHED, publicationId, null);
    } catch (RuntimeException e) {
      if (candidate != null) {
        store.abandon(candidate);
      }
      throw new PublicationFailure("Candidate " + publicationId + " was not published", e);
    }
  }

  // Publication-time checks. A failure here keeps the previous publication.

  private void checkInvariants(PublicationRun.Results results) {
    for (final PublicationRun.Period period : results.periods()) {
      for (final EstimateHistory.Segment segment : period.history().segments()) {
        for (final EstimateHistory.Day day : segment.days()) {
          double total = 0;
          for (final EstimateHistory.Estimate estimate : day.components().values()) {
            if (!Double.isFinite(estimate.mean())) {
              throw new IllegalStateException(
                  "Non-finite estimate on " + day.date() + " in " + period.period().id());
            }
            total += estimate.mean();
          }
          if (Math.abs(total - 100) > 1e-6) {
            throw new IllegalStateException(
                "The composition of " + day.date() + " sums to " + total + ", not 100");
          }
        }
      }
      for (final NationalSeats.Rules rules : results.allocationRules()) {
        final NationalSeats.Summary seats =
            NationalSeats.summarize(
                NationalSeats.allocateDraws(period.draws(), rules), results.intervalLevel());
        if (seats.totalPointSeats() != rules.seats()) {
          throw new IllegalStateException(
              "The "
                  + rules.electionYear()
                  + " allocation of "
                  + period.period().id()
                  + " assigns "
                  + seats.totalPointSeats()
                  + " seats, not "
                  + rules.seats());
        }
      }
    }
  }

  /** The retained draws must come back unchanged at the same seed, from the archived input. */
  private void checkReproduction(PublicationRun.Results results, List<PollCsv.Poll> polls) {
    final List<LocalDate> elections =
        new ElectionReferences(db)
            .all().stream().map(ElectionReferences.Election::electionDate).toList();
    final CoverageValidation.Rules coverage = freeze.coverageThrough(results.lastFieldworkDate());
    for (final PublicationRun.Period period : results.periods()) {
      final JointUncertainty.Draws repeated =
          JointUncertainty.finalDay(
                  period.period(),
                  polls,
                  elections,
                  freeze.period(period.period().id()).parameters(),
                  coverage,
                  freeze.uncertainty())
              .draws();
      final JointUncertainty.Reproduced reproduced =
          JointUncertainty.reproduced(period.draws(), repeated);
      if (!reproduced.exact()) {
        throw new IllegalStateException(
            "Seeded reproduction of "
                + period.period().id()
                + " differs by "
                + reproduced.maxAbsoluteDifference());
      }
    }
  }

  /** A published share that jumps further than the registered tolerance blocks the update. */
  private void checkDrift(PublicationRun.Results results) {
    final Optional<PublicationStore.Current> current = store.current();
    if (current.isEmpty()) {
      return;
    }
    final Optional<String> previous =
        store.document(
            current.get().publicationId(),
            PublicationDocuments.latestSurface(results.headline().period().id()),
            Translations.ENGLISH);
    if (previous.isEmpty()) {
      return;
    }
    final EstimateHistory.Day day = PublicationDocuments.lastDay(results.headline().history());
    for (final tools.jackson.databind.JsonNode component :
        JSON.readTree(previous.get()).get("components")) {
      final EstimateHistory.Estimate estimate =
          day.components().get(component.get("component").asString());
      if (estimate == null) {
        continue;
      }
      final double moved = Math.abs(estimate.mean() - component.get("mean").doubleValue());
      if (moved > freeze.maxDriftPoints()) {
        throw new IllegalStateException(
            component.get("component").asString()
                + " moved "
                + moved
                + " points against the previous publication");
      }
    }
  }

  // Run identity.

  private String nextRunId() {
    final int next = db.sql("SELECT count(*) FROM model_run").query(Integer.class).single() + 1;
    return String.format(Locale.ROOT, "run_%06d", next);
  }

  private String parameters(PublicationRun.Results results) {
    final ObjectNode node = JSON.createObjectNode();
    node.put("developmentProtocolVersion", freeze.developmentProtocolVersion());
    node.put("releaseProtocolVersion", freeze.releaseProtocolVersion());
    node.put("intervalLevel", results.intervalLevel());
    node.put("publicationDecimals", freeze.resolution().decimals());
    final ObjectNode periods = node.putObject("periods");
    for (final PublicationRun.Period period : results.periods()) {
      final DailyStateSpace.Parameters parameters =
          freeze.period(period.period().id()).parameters();
      final ObjectNode entry = periods.putObject(period.period().id());
      entry.put("walkVariance", parameters.walkVariance());
      entry.put("houseScale", parameters.houseScale());
      entry.put("covarianceMultiplier", parameters.covarianceMultiplier());
    }
    return JSON.writeValueAsString(node);
  }

  private Instant lastSuccessfulCheck() {
    return db.sql("SELECT max(last_successful_check_at) FROM poll_source")
        .query(java.sql.Timestamp.class)
        .optional()
        .map(java.sql.Timestamp::toInstant)
        .orElseGet(Instant::now);
  }

  /** The build this publication came from, so an archived result names its own executable. */
  static String codeVersion() {
    final Properties git = new Properties();
    try (InputStream input = Publisher.class.getResourceAsStream("/git.properties")) {
      if (input != null) {
        git.load(input);
      }
    } catch (IOException e) {
      LOG.warn("Build metadata is unreadable", e);
    }
    final String version =
        Optional.ofNullable(Publisher.class.getPackage().getImplementationVersion())
            .orElse(git.getProperty("git.build.version", "0.0.0"));
    return version + "+" + git.getProperty("git.commit.id.abbrev", "unknown");
  }

  static String runtime() {
    return System.getProperty("java.vm.name")
        + " "
        + System.getProperty("java.vm.version")
        + " on "
        + System.getProperty("os.name")
        + " "
        + System.getProperty("os.arch");
  }

  private static boolean lock(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        java.sql.ResultSet result =
            statement.executeQuery("SELECT pg_try_advisory_lock(" + ADVISORY_LOCK + ")")) {
      return result.next() && result.getBoolean(1);
    }
  }

  private static void unlock(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SELECT pg_advisory_unlock(" + ADVISORY_LOCK + ")");
    }
  }
}
