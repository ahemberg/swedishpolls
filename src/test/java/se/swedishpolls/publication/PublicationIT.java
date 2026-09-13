package se.swedishpolls.publication;

import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

  private static Path root;

  @DynamicPropertySource
  static void volume(DynamicPropertyRegistry registry) throws IOException {
    root = Files.createTempDirectory("swedishpolls-publications");
    registry.add("publication.root", () -> root.toString());
  }

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
  void reset() throws IOException {
    wireMock.resetAll();
    flyway.clean();
    flyway.migrate();
    deleteRecursively(root);
    Files.createDirectories(root);
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
      evidence.put("images", store.assets(attempt.publicationId()).size());
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
  void aChangedSnapshotBecomesOnePublicationWithEveryDocumentAndEveryCard() {
    final Publisher.Attempt attempt = publisher().publish();
    assertEquals(PublicationOutcome.PUBLISHED, attempt.outcome(), attempt.detail());

    final CurrentPublication current = store.current().orElseThrow();
    assertEquals(attempt.publicationId(), current.publicationId());
    assertFalse(current.stale());
    assertNull(current.staleSince());

    final PublicationHeader header = store.header(current.publicationId()).orElseThrow();
    assertEquals(PublicationStore.PUBLISHED, header.state());
    assertEquals("corrected", header.history());
    final JsonNode metadata = publications.metadata(header, Translations.of("sv"));
    assertEquals(1, metadata.path("capabilities").path("customCoalitionHistory").asInt());
    final String artifact = publications.coalitionHistory(header).orElseThrow();
    assertEquals(
        Digest.sha256(artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        metadata.path("coalitionHistory").path("artifactSha256").asString());
    final JsonNode coalitionHistory = JSON.readTree(artifact);
    assertEquals(255, coalitionHistory.get("subsets").size());
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

    final List<PublicationAsset> assets = store.assets(current.publicationId());
    assertEquals(ShareImages.KINDS.size() * Translations.LANGUAGES.size(), assets.size());
    for (final PublicationAsset asset : assets) {
      assertEquals(1, asset.version());
      assertEquals(ShareImages.RENDERER_VERSION, asset.rendererVersion());
      assertEquals(ShareImages.MEDIA_TYPE, asset.mediaType());
      assertEquals(asset.byteCount(), store.bytes(asset).length);
    }
    assertFalse(
        Files.exists(root.resolve("staging").resolve(current.publicationId())),
        "A promoted candidate leaves no staging directory behind");

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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aFailedUpdateExposesNoPartialPublicationAndKeepsThePriorOneWithStaleness(
      boolean artifactWrite) {
    final Publisher.Attempt first = publisher().publish();
    assertEquals(PublicationOutcome.PUBLISHED, first.outcome(), first.detail());
    final List<PublicationAsset> published = store.assets(first.publicationId());

    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt failed =
        released(new FailingStore(db, transactions, root, artifactWrite)).publish();
    assertEquals(PublicationOutcome.FAILED, failed.outcome());

    final CurrentPublication current = store.current().orElseThrow();
    assertEquals(first.publicationId(), current.publicationId(), "The prior one stays current");
    assertTrue(current.stale());
    assertNotNull(current.staleSince());
    assertEquals(published, store.assets(first.publicationId()));
    final PublicationStore restarted = new PublicationStore(db, transactions, root.toString());
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
    final byte[] card =
        store.bytes(
            store.latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv").orElseThrow());
    final String document =
        store
            .document(first.publicationId(), PublicationDocuments.latestSurface(PERIOD), "sv")
            .orElseThrow();

    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt second = publisher.publish();
    assertEquals(PublicationOutcome.PUBLISHED, second.outcome(), second.detail());
    assertNotEquals(first.publicationId(), second.publicationId());
    assertEquals(second.publicationId(), store.current().orElseThrow().publicationId());

    assertArrayEquals(
        card,
        store.bytes(
            store.latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv").orElseThrow()),
        "Published bytes are never overwritten");
    assertEquals(
        document,
        store
            .document(first.publicationId(), PublicationDocuments.latestSurface(PERIOD), "sv")
            .orElseThrow());

    // A restart reads the same rows and the same volume; nothing is held in memory.
    final PublicationStore restarted = new PublicationStore(db, transactions, root.toString());
    assertTrue(restarted.header(first.publicationId()).isPresent());
    assertTrue(restarted.header("pub_00000000T000000Z").isEmpty());
    assertEquals(second.publicationId(), restarted.current().orElseThrow().publicationId());
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

    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt second = publisher.publish();
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
  void aRendererChangeAddsAnAssetVersionBesideTheOneAlreadyPublished() {
    final Publisher.Attempt first = publisher().publish();
    assertEquals(PublicationOutcome.PUBLISHED, first.outcome(), first.detail());
    final PublicationAsset version1 =
        store.latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv").orElseThrow();
    final byte[] original = store.bytes(version1);

    assertEquals(2, store.nextAssetVersion(first.publicationId(), ShareImages.OVERVIEW, "sv"));
    final byte[] rerendered = new byte[original.length + 1];
    System.arraycopy(original, 0, rerendered, 0, original.length);
    final PublicationAsset version2 =
        new PublicationAsset(
            first.publicationId(),
            ShareImages.OVERVIEW,
            "sv",
            2,
            "2",
            ShareImages.MEDIA_TYPE,
            rerendered.length,
            Digest.sha256(rerendered));
    store.promote(
        first.publicationId(),
        store.stage(first.publicationId(), List.of(version2), List.of(rerendered)));

    assertEquals(
        2,
        store
            .latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv")
            .orElseThrow()
            .version());
    assertArrayEquals(original, store.bytes(version1), "Version 1 keeps its own bytes");
    assertArrayEquals(rerendered, store.bytes(version2));
    assertNotEquals(version1.path(), version2.path());
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

  /** A store that stages nothing, standing in for a failed image write or an interrupted move. */
  private static final class FailingStore extends PublicationStore {
    private final boolean artifactWrite;

    FailingStore(
        JdbcClient db, PlatformTransactionManager transactions, Path root, boolean artifactWrite) {
      super(db, transactions, root.toString());
      this.artifactWrite = artifactWrite;
    }

    @Override
    public void recordDocument(String publicationId, String surface, String language, String body) {
      if (artifactWrite && CoalitionHistoryDocument.SURFACE.equals(surface)) {
        throw new IllegalStateException("injected coalition artifact failure");
      }
      super.recordDocument(publicationId, surface, language, body);
    }

    @Override
    public List<PublicationAsset> stage(
        String publicationId, List<PublicationAsset> assets, List<byte[]> bytes) {
      throw new IllegalStateException("injected staging failure");
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (final Stream<Path> walk = Files.walk(directory)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
  }
}
