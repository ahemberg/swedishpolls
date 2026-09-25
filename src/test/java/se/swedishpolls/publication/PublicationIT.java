package se.swedishpolls.publication;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import se.swedishpolls.estimation.JointUncertainty;
import se.swedishpolls.estimation.NationalSeats;
import se.swedishpolls.estimation.SeatOutcomes;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.publication.repository.PublicationLock;
import se.swedishpolls.publication.repository.PublicationStore;
import se.swedishpolls.publication.service.Publisher;
import se.swedishpolls.publication.service.TestPublication;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.source.service.NationalAllocationRuleService;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;
import se.swedishpolls.testsupport.TestDatabase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The publication worker end to end: what it publishes, and what a failed update leaves behind. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "logging.level.WireMock=warn",
      "polls.ingest.enabled=false",
      "publication.enabled=false",
      "polls.source-url=${wiremock.server.baseUrl}/polls.csv",
      "spring.docker.compose.enabled=false",
      "spring.flyway.clean-disabled=false"
    })
@Import(TestDatabase.Configuration.class)
@EnableWireMock
class PublicationIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int DRAWS = 200;
  private static final String PERIOD = "eight_party_2010";

  @Autowired private JdbcClient db;
  @Autowired private PublicationLock lock;
  @Autowired private Flyway flyway;
  @Autowired private SnapshotIngest ingest;
  @Autowired private PollQueryService queries;
  @Autowired private ElectionReferenceService electionReferences;
  @Autowired private NationalAllocationRuleService allocationRules;
  @Autowired private PublicationStore store;
  @Autowired private PlatformTransactionManager transactions;
  @InjectWireMock private WireMockServer wireMock;

  @BeforeEach
  void reset() {
    wireMock.resetAll();
    flyway.clean();
    flyway.migrate();
    TestPublication.serve(wireMock, TestPublication.polls(TestPublication.FROM));
  }

  private Publisher publisher() {
    return released(store);
  }

  private Publisher released(PublicationStore target) {
    return TestPublication.released(
        lock, ingest, queries, electionReferences, allocationRules, target, DRAWS);
  }

  @org.springframework.beans.factory.annotation.Autowired
  private se.swedishpolls.publication.service.Publications publications;

  @Test
  void migrationDropsThePublicationAssetTable() {
    assertEquals(
        0,
        db.sql(
                """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_name = 'publication_asset'
                """)
            .query(Integer.class)
            .single());
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "coalition.benchmark",
      matches = "true")
  void benchmarkTheFullPipelineOnTheSelectedHost() throws IOException {
    // The complete archived source, without trimming its history or relaxing numerical gates.
    final byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/polls/audit.csv"));
    TestPublication.serve(wireMock, bytes);
    final long start = System.nanoTime();
    final Publisher.Attempt attempt =
        TestPublication.benchmark(lock, ingest, queries, electionReferences, allocationRules, store)
            .publish();
    final double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
    final tools.jackson.databind.node.ObjectNode evidence = JSON.createObjectNode();
    evidence.put("protocolVersion", "coalition-history-1");
    evidence.put("sourceSha256", Digest.sha256(bytes));
    evidence.put("pipelineSeconds", seconds);
    evidence.put("outcome", attempt.outcome().toString());
    evidence.put("detail", attempt.detail());
    evidence.put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
    evidence.put("javaRuntime", System.getProperty("java.runtime.version"));
    evidence.put(
        "releaseScope", "Isolated benchmark fixture only. Live frozen release remains blocked.");
    if (attempt.outcome() == PublicationOutcome.PUBLISHED) {
      final String artifact =
          store
              .document(attempt.publicationId(), CoalitionHistoryDocument.SURFACE, "sv")
              .orElseThrow();
      evidence.put(
          "artifactBytes", artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      evidence.put(
          "artifactSha256",
          Digest.sha256(artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      evidence.set("precision", JSON.readTree(artifact).get("precision"));
    }
    evidence.put(
        "processHighWaterMark",
        Files.readAllLines(Path.of("/proc/self/status")).stream()
            .filter(line -> line.startsWith("VmHWM:"))
            .findFirst()
            .orElseThrow());
    Files.writeString(
        Path.of("target/coalition-pipeline-benchmark.json"), evidence.toPrettyString());
    assertEquals(PublicationOutcome.PUBLISHED, attempt.outcome(), attempt.detail());
    assertTrue(seconds < 1800, evidence.toPrettyString());
  }

  @Test
  void aChangedSnapshotBecomesOnePublicationWithEveryDocument() {
    final Publisher.Attempt attempt = publisher().publish();
    assertEquals(PublicationOutcome.PUBLISHED, attempt.outcome(), attempt.detail());

    final CurrentPublication current = store.current().orElseThrow();
    assertEquals(attempt.publicationId(), current.publicationId());
    assertFalse(current.stale());
    assertNull(current.staleSince());

    final PublicationHeader header = store.header(current.publicationId()).orElseThrow();
    assertEquals(PublicationStore.PUBLISHED, header.state());
    assertEquals("corrected", header.history());
    final JsonNode metadata = publications.metadata(header);
    assertEquals(1, metadata.path("capabilities").path("customCoalitionHistory").asInt());
    final String artifact = publications.coalitionHistory(header).orElseThrow();
    assertEquals(
        Digest.sha256(artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        metadata.path("coalitionHistory").path("artifactSha256").asString());
    final JsonNode coalitionHistory = JSON.readTree(artifact);
    assertEquals(255, coalitionHistory.get("subsets").size());
    for (final JsonNode subset : coalitionHistory.get("subsets")) {
      for (final String column : List.of("mean", "lower", "upper", "availability")) {
        assertEquals(coalitionHistory.get("dates").size(), subset.get(column).size());
      }
    }
    assertEquals(
        header.headlinePeriod(), coalitionHistory.get("coveragePeriodIds").get(0).asString());
    assertEquals(
        coalitionHistory.get("dates").get(coalitionHistory.get("dates").size() - 1),
        coalitionHistory.get("latest").get("date"));
    assertEquals(
        metadata.path("coalitionHistory").path("artifactSha256").asString(),
        db.sql(
                "SELECT sha256 FROM publication_document WHERE publication_id = ? AND surface = 'coalition-history' AND language = 'sv'")
            .param(header.publicationId())
            .query(String.class)
            .single());
    assertEquals(header.runId(), coalitionHistory.get("modelRunId").asString());
    assertEquals(header.publishedAt().toString(), coalitionHistory.get("publishedAt").asString());

    assertEquals(PERIOD, header.headlinePeriod());
    assertTrue(header.approximatedElection() >= header.lastFieldworkDate().getYear());
    // The three times are distinct quantities, and none of them stands in for the others.
    assertFalse(header.publishedAt().isBefore(header.sourceCheckedAt()));
    assertTrue(header.lastFieldworkDate().isBefore(LocalDate.now(ZoneOffset.UTC)));

    for (final String language : Translations.LANGUAGES) {
      for (final String surface :
          List.of(
              PublicationDocuments.HISTORY_SURFACE,
              "coalition-history",
              PublicationDocuments.INSTITUTES_SURFACE,
              PublicationDocuments.latestSurface(PERIOD),
              PublicationDocuments.electionsSurface(PERIOD))) {
        assertTrue(
            store.document(current.publicationId(), surface, language).isPresent(),
            surface + "/" + language);
      }
    }

    final JsonNode latest =
        JSON.readTree(
            store
                .document(
                    current.publicationId(),
                    PublicationDocuments.latestSurface(PERIOD),
                    Translations.ENGLISH)
                .orElseThrow());
    assertEquals(
        current.publicationId(), latest.get("publication").get("publicationId").asString());
    assertEquals(header.runId(), latest.get("publication").get("runId").asString());
    assertEquals(header.snapshotId(), latest.get("publication").get("snapshotId").longValue());
    double total = 0;
    for (final JsonNode component : latest.get("components")) {
      total += component.get("mean").doubleValue();
    }
    assertEquals(100.0, total, 0.2, "The published composition closes");
    // FI has no validated coverage period, so it is null with a reason and never zero.
    assertTrue(latest.get("unavailable").get("FI").get("mean").isNull());
    assertEquals(
        PublicationDocuments.NO_VALIDATED_PERIOD,
        latest.get("unavailable").get("FI").get("reason").asString());
  }

  @Test
  void promotionRollsBackWhenTheCurrentPointerCannotMove() {
    final Publisher.Attempt first = publisher().publish();
    assertEquals(PublicationOutcome.PUBLISHED, first.outcome(), first.detail());
    final PublicationHeader header = store.header(first.publicationId()).orElseThrow();
    final String candidate = "pub_20000101T000000Z";
    store.recordCandidate(
        candidate,
        header.runId(),
        header.snapshotId(),
        header.lastFieldworkDate(),
        header.sourceCheckedAt(),
        header.publishedAt(),
        header.headlinePeriod(),
        header.approximatedElection());
    db.sql(
            """
            CREATE FUNCTION reject_publication_pointer() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'injected pointer failure'; END
            $$
            """)
        .update();
    db.sql(
            """
            CREATE TRIGGER reject_publication_pointer
            BEFORE UPDATE ON current_publication
            FOR EACH ROW EXECUTE FUNCTION reject_publication_pointer()
            """)
        .update();

    assertThrows(RuntimeException.class, () -> store.promote(candidate));

    assertEquals(
        PublicationStore.CANDIDATE,
        db.sql("SELECT state FROM publication WHERE id = ?")
            .param(candidate)
            .query(String.class)
            .single());
    assertEquals(first.publicationId(), store.current().orElseThrow().publicationId());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(FailurePoint.class)
  void aFailedUpdateExposesNoPartialPublicationAndKeepsThePriorOneWithStaleness(
      FailurePoint failurePoint) {
    final Publisher.Attempt first = publisher().publish();
    assertEquals(PublicationOutcome.PUBLISHED, first.outcome(), first.detail());
    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt failed =
        released(new FailingStore(db, transactions, failurePoint)).publish();
    assertEquals(PublicationOutcome.FAILED, failed.outcome());

    final CurrentPublication current = store.current().orElseThrow();
    assertEquals(first.publicationId(), current.publicationId(), "The prior one stays current");
    assertTrue(current.stale());
    assertNotNull(current.staleSince());
    final PublicationStore restarted = new PublicationStore(db, transactions);
    assertEquals(
        store.document(first.publicationId(), CoalitionHistoryDocument.SURFACE, "sv"),
        restarted.document(first.publicationId(), CoalitionHistoryDocument.SURFACE, "sv"));
    assertTrue(restarted.coalitionHistoryMetadata(first.publicationId()).isPresent());
    assertEquals(
        0,
        db.sql("SELECT count(*) FROM publication WHERE state = 'candidate'")
            .query(Integer.class)
            .single(),
        "A candidate is abandoned rather than left half visible");
    assertEquals(1, published());
    assertTrue(
        db.sql("SELECT failure FROM publication_attempt WHERE outcome = 'failed'")
            .query(String.class)
            .single()
            .contains("injected"));
  }

  @Test
  void anEarlierPublicationSurvivesTheNextOneAndItsPermanentLinkNeverFallsBack() {
    final Publisher publisher = publisher();
    final Publisher.Attempt first = publisher.publish();
    assertEquals(PublicationOutcome.PUBLISHED, first.outcome(), first.detail());
    final String document =
        store
            .document(first.publicationId(), PublicationDocuments.latestSurface(PERIOD), "sv")
            .orElseThrow();

    final PublicationHeader pinned = store.header(first.publicationId()).orElseThrow();
    final String pairedBefore = publications.coalitionHistory(pinned).orElseThrow();
    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt second = publisher.publish();
    assertEquals(
        pairedBefore,
        publications.coalitionHistory(pinned).orElseThrow(),
        "Requests retain the page's resolved publication while current changes");
    assertEquals(PublicationOutcome.PUBLISHED, second.outcome(), second.detail());
    assertNotEquals(first.publicationId(), second.publicationId());
    assertEquals(second.publicationId(), store.current().orElseThrow().publicationId());

    assertEquals(
        document,
        store
            .document(first.publicationId(), PublicationDocuments.latestSurface(PERIOD), "sv")
            .orElseThrow());

    // A restart reads the same rows; nothing is held in memory.
    final PublicationStore restarted = new PublicationStore(db, transactions);
    assertTrue(restarted.header(first.publicationId()).isPresent());
    assertTrue(restarted.header("pub_00000000T000000Z").isEmpty());
    assertEquals(second.publicationId(), restarted.current().orElseThrow().publicationId());
  }

  /**
   * Movement against the previous publication is not a check. Weeks of new fieldwork can move a
   * share as far as the polls do, and an otherwise valid candidate publishes on its own evidence.
   */
  @Test
  void aHeadlineThatMovesFarAgainstThePreviousPublicationStillPublishes() {
    final Publisher publisher = publisher();
    final Publisher.Attempt first = publisher.publish();
    assertEquals(PublicationOutcome.PUBLISHED, first.outcome(), first.detail());

    TestPublication.serve(
        wireMock, TestPublication.moved(TestPublication.polls(TestPublication.FROM), 15.0));
    final Publisher.Attempt second = publisher.publish();

    assertEquals(PublicationOutcome.PUBLISHED, second.outcome(), second.detail());
    assertTrue(
        movement(first.publicationId(), second.publicationId(), "S") > 10.0,
        "The fixture has to move further than the removed bound for this to mean anything");
    assertEquals(second.publicationId(), store.current().orElseThrow().publicationId());
    assertFalse(store.current().orElseThrow().stale());
  }

  /** How far one component's published share moved between two publications, in points. */
  private double movement(String before, String after, String component) {
    return Math.abs(mean(after, component) - mean(before, component));
  }

  private double mean(String publicationId, String component) {
    final JsonNode document =
        JSON.readTree(
            store
                .document(publicationId, PublicationDocuments.latestSurface(PERIOD), "en")
                .orElseThrow());
    for (final JsonNode entry : document.get("components")) {
      if (component.equals(entry.get("component").asString())) {
        return entry.get("mean").doubleValue();
      }
    }
    throw new AssertionError("No published share for " + component);
  }

  /**
   * What the poll page and its download promise: a publication pins a snapshot, so a correction
   * that publishes while a reader is filtering cannot change the rows they are counting or the file
   * they are about to save.
   */
  @Test
  void aCorrectedSnapshotLeavesAPinnedPollQueryAndItsCsvExactlyAsTheyWere() {
    final Publisher publisher = publisher();
    final Publisher.Attempt first = publisher.publish();
    assertEquals(PublicationOutcome.PUBLISHED, first.outcome(), first.detail());
    final long snapshot = store.header(first.publicationId()).orElseThrow().snapshotId();
    final PollQuery.Filters filters =
        new PollQuery.Filters(null, null, List.of("Novus"), List.of("S"), null, false);
    final PollQuery.Result before =
        queries.query(snapshot, queries.periods(), filters, 1, PollQuery.DEFAULT_PAGE_SIZE);
    final String csv = PollQuery.csv(before, filters);
    assertTrue(before.total() > 0, "the fixture has Novus polls to pin");

    final PublicationHeader pinned = store.header(first.publicationId()).orElseThrow();
    final String pairedBefore = publications.coalitionHistory(pinned).orElseThrow();
    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt second = publisher.publish();
    assertEquals(
        pairedBefore,
        publications.coalitionHistory(pinned).orElseThrow(),
        "Requests retain the page's resolved publication while current changes");
    assertEquals(PublicationOutcome.PUBLISHED, second.outcome(), second.detail());
    final long corrected = store.header(second.publicationId()).orElseThrow().snapshotId();
    assertNotEquals(snapshot, corrected, "the correction is a different snapshot");

    final PollQuery.Result after =
        queries.query(snapshot, queries.periods(), filters, 1, PollQuery.DEFAULT_PAGE_SIZE);
    assertEquals(before.total(), after.total());
    assertEquals(
        before.matching().stream().map(PollQuery.Row::pollId).toList(),
        after.matching().stream().map(PollQuery.Row::pollId).toList(),
        "The pinned snapshot selects the same rows, in the same order");
    assertEquals(csv, PollQuery.csv(after, filters), "and the download is byte for byte the same");
    assertNotEquals(
        before.total(),
        queries
            .query(corrected, queries.periods(), filters, 1, PollQuery.DEFAULT_PAGE_SIZE)
            .total(),
        "The correction really did change what an unpinned query would have counted");
  }

  @Test
  void theRegisteredCenteringAlternativeIsRefitAndItsMovementSitsBesideTheNumbers() {
    ingest.check();
    final long snapshotId = ingest.activeSnapshot().orElseThrow().id();
    final ModelFreeze freeze = TestFreeze.released(DRAWS);
    final List<se.swedishpolls.source.PollCsv.Poll> polls = ingest.polls(snapshotId);
    final PublicationRun.Results results =
        PublicationRun.run(
            freeze,
            snapshotId,
            "sha",
            polls,
            queries.periods(),
            electionReferences.all(),
            allocationRules.forDate(PublicationRun.lastFieldworkDate(polls)),
            allocationRules.all());

    final PublicationRun.Alternative centering =
        results.alternatives().stream()
            .filter(alternative -> SeatOutcomes.POLL_COUNT.equals(alternative.label()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("The publication ran no centering alternative"));
    final JointUncertainty.Draws published = results.headline().draws();
    assertEquals(published.periodId(), centering.draws().periodId());
    assertEquals(published.date(), centering.draws().date(), "Both fits read the same final day");
    assertEquals(published.components(), centering.draws().components());
    assertNotEquals(
        published.shares().get(0, 0),
        centering.draws().shares().get(0, 0),
        "A refit under the other centering is not the published run");

    final PublicationDocuments.Identity identity =
        new PublicationDocuments.Identity("pub", "run", snapshotId);
    final NationalAllocationRule rules = results.allocationRule();
    final NationalSeats.SeatDraws drawn =
        NationalSeats.allocateDraws(results.headline().draws(), rules);
    final Translations english = Translations.of(Translations.ENGLISH);
    final List<SeatOutcomes.Sensitivity> sensitivity =
        List.of(
            SeatOutcomes.sensitivity(
                centering.kind(),
                centering.label(),
                SeatOutcomes.headline(drawn),
                SeatOutcomes.headline(NationalSeats.allocateDraws(centering.draws(), rules))));
    // Each page discloses the probabilities it shows, so the two notes are not the same note.
    assertDisclosure(
        PublicationDocuments.seats(identity, results, drawn, freeze, english),
        PublicationDocuments.sensitivityNote(sensitivity, "threshold", english));
    assertDisclosure(
        PublicationDocuments.coalitions(identity, results, drawn, freeze, english),
        PublicationDocuments.sensitivityNote(sensitivity, "majority", english));
  }

  private static void assertDisclosure(
      tools.jackson.databind.node.ObjectNode document, String expected) {
    if (expected == null) {
      assertFalse(
          document.has("sensitivity"), "Nothing moved far enough to disclose beside the numbers");
    } else {
      assertEquals(expected, document.get("sensitivity").asString());
    }
  }

  private int published() {
    return db.sql("SELECT count(*) FROM publication WHERE state = 'published'")
        .query(Integer.class)
        .single();
  }

  /** A store that fails a document write or the final database promotion. */
  private static final class FailingStore extends PublicationStore {
    private final FailurePoint failurePoint;

    FailingStore(
        JdbcClient db, PlatformTransactionManager transactions, FailurePoint failurePoint) {
      super(db, transactions);
      this.failurePoint = failurePoint;
    }

    @Override
    public void recordDocument(String publicationId, String surface, String language, String body) {
      if (failurePoint == FailurePoint.DOCUMENT_WRITE
          && CoalitionHistoryDocument.SURFACE.equals(surface)) {
        throw new IllegalStateException("injected coalition artifact failure");
      }
      super.recordDocument(publicationId, surface, language, body);
    }

    @Override
    public void promote(String publicationId) {
      if (failurePoint == FailurePoint.PROMOTION) {
        throw new IllegalStateException("injected promotion failure");
      }
      super.promote(publicationId);
    }
  }

  enum FailurePoint {
    DOCUMENT_WRITE,
    PROMOTION
  }
}
