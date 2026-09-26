package se.swedishpolls.publication.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import se.swedishpolls.estimation.CoverageValidation;
import se.swedishpolls.estimation.DailyStateSpace;
import se.swedishpolls.estimation.EstimateHistory;
import se.swedishpolls.estimation.JointUncertainty;
import se.swedishpolls.estimation.NationalSeats;
import se.swedishpolls.model.ElectionReference;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.publication.CurrentPublication;
import se.swedishpolls.publication.ModelFreeze;
import se.swedishpolls.publication.PublicationDocuments;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.PublicationOutcome;
import se.swedishpolls.publication.PublicationRun;
import se.swedishpolls.publication.repository.PublicationLock;
import se.swedishpolls.publication.repository.PublicationStore;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.source.service.NationalAllocationRuleService;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The single publication worker. One advisory lock keeps two workers from publishing the same
 * snapshot, a changed complete snapshot becomes a private candidate, and the candidate becomes
 * current only after every document is written. A failed update leaves the previous publication
 * exactly as it was, with a staleness notice.
 */
@Component
public class Publisher {
  private static final Logger LOG = LoggerFactory.getLogger(Publisher.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter PUBLICATION_ID =
      DateTimeFormatter.ofPattern("'pub_'yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  /** What one attempt did, and why it did not publish when it did not. */
  public record Attempt(PublicationOutcome outcome, String publicationId, String detail) {}

  /** A candidate that never became current, with the check or write that stopped it. */
  public static final class PublicationFailure extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    PublicationFailure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final PublicationLock lock;
  private final SnapshotIngest ingest;
  private final PollQueryService queries;
  private final ElectionReferenceService electionReferences;
  private final NationalAllocationRuleService allocationRules;
  private final PublicationStore store;
  private final ModelFreeze freeze;

  @Autowired
  public Publisher(
      PublicationLock lock,
      SnapshotIngest ingest,
      PollQueryService queries,
      ElectionReferenceService electionReferences,
      NationalAllocationRuleService allocationRules,
      PublicationStore store) {
    this(lock, ingest, queries, electionReferences, allocationRules, store, ModelFreeze.load());
  }

  Publisher(
      PublicationLock lock,
      SnapshotIngest ingest,
      PollQueryService queries,
      ElectionReferenceService electionReferences,
      NationalAllocationRuleService allocationRules,
      PublicationStore store,
      ModelFreeze freeze) {
    this.lock = lock;
    this.ingest = ingest;
    this.queries = queries;
    this.electionReferences = electionReferences;
    this.allocationRules = allocationRules;
    this.store = store;
    this.freeze = freeze;
  }

  public ModelFreeze freeze() {
    return freeze;
  }

  void refreshAtStartup(boolean publicationEnabled) {
    if (ingest.activeSnapshot().isEmpty()) {
      refresh(publicationEnabled);
    }
  }

  @Nullable Attempt refresh(boolean publicationEnabled) {
    if (!publicationEnabled) {
      ingest.check();
      return null;
    }
    return publish();
  }

  /** One publication attempt, holding the worker lock for its whole run. */
  public Attempt publish() {
    return lock.whileHeld(this::attempt)
        .orElseGet(
            () ->
                new Attempt(
                    PublicationOutcome.BUSY, null, "another worker holds the publication lock"));
  }

  private Attempt attempt() {
    final SnapshotIngest.Result check = ingest.check();
    if (check == SnapshotIngest.Result.BUSY) {
      return new Attempt(PublicationOutcome.BUSY, null, "a source check is running");
    }
    final Instant sourceCheckedAt = store.lastSuccessfulCheck().orElseGet(Instant::now);
    final Optional<Snapshot> active = ingest.activeSnapshot();
    if (active.isEmpty()) {
      return fail(sourceCheckedAt, "no archived source snapshot");
    }
    if (freeze.authorization() == ModelFreeze.Authorization.NONE) {
      final String detail =
          "publication authorization none; release "
              + freeze.releaseStatus()
              + "; blocking gates "
              + String.join(", ", freeze.failedBlockingGates())
              + "; trend gates "
              + String.join(", ", freeze.failedTrendGates());
      store.recordAttempt(PublicationOutcome.BLOCKED, sourceCheckedAt, null, detail);
      return new Attempt(PublicationOutcome.BLOCKED, null, detail);
    }
    final Optional<CurrentPublication> current = store.current();
    if (current.isPresent()) {
      final PublicationHeader header = store.header(current.get().publicationId()).orElseThrow();
      if (header.snapshotId() == active.get().id()) {
        // Unchanged input is not a failure. The current publication stays current and fresh.
        store.recordAttempt(PublicationOutcome.UNCHANGED, sourceCheckedAt, null, null);
        return new Attempt(
            PublicationOutcome.UNCHANGED, header.publicationId(), "the snapshot is unchanged");
      }
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
    store.recordAttempt(PublicationOutcome.FAILED, sourceCheckedAt, null, failure);
    store.markStale(Instant.now());
    return new Attempt(PublicationOutcome.FAILED, null, failure);
  }

  private Attempt run(Snapshot snapshot, Instant sourceCheckedAt) {
    final List<PollCsv.Poll> polls = ingest.polls(snapshot.id());
    final List<ElectionReference> elections = electionReferences.all();
    final LocalDate lastFieldworkDate = PublicationRun.lastFieldworkDate(polls);
    final PublicationRun.Results results =
        PublicationRun.run(
            freeze,
            snapshot.id(),
            snapshot.sha256(),
            polls,
            queries.periods(),
            elections,
            allocationRules.forDate(lastFieldworkDate),
            allocationRules.all());
    checkInvariants(results);
    checkReproduction(results, polls);

    final Instant publishedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    final String publicationId = PUBLICATION_ID.format(publishedAt);
    final String runId = store.nextRunId();
    String candidate = null;
    try {
      store.recordRun(runId, snapshot.id(), freeze, parameters(results), codeVersion(), runtime());
      store.recordCandidate(
          publicationId,
          runId,
          snapshot.id(),
          results.lastFieldworkDate(),
          sourceCheckedAt,
          publishedAt,
          results.headline().period().id(),
          results.allocationRule().electionYear());
      candidate = publicationId;
      final PublicationDocuments.Identity identity =
          new PublicationDocuments.Identity(publicationId, runId, snapshot.id());
      for (final PublicationDocuments.Document document :
          PublicationDocuments.all(identity, results, freeze, publishedAt)) {
        store.recordDocument(
            publicationId,
            document.surface(),
            document.language(),
            JSON.writeValueAsString(document.body()));
      }
      store.promote(publicationId);
      store.recordAttempt(PublicationOutcome.PUBLISHED, sourceCheckedAt, publicationId, null);
      return new Attempt(PublicationOutcome.PUBLISHED, publicationId, null);
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
      for (final NationalAllocationRule rules : results.allocationRules()) {
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
        results.elections().stream().map(ElectionReference::electionDate).toList();
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

  // Run identity.

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

  /** The build this publication came from, so an archived result names its own executable. */
  static String codeVersion() {
    final Properties git = new Properties();
    try (final InputStream input = Publisher.class.getResourceAsStream("/git.properties")) {
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
}
