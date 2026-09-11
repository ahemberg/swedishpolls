package se.swedishpolls;

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
import javax.sql.DataSource;
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
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;
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
  @Autowired private DataSource dataSource;
  @Autowired private Flyway flyway;
  @Autowired private SnapshotIngest ingest;
  @Autowired private PollQueryService queries;
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
    return publisher(store, TestPublication.released(DRAWS));
  }

  private Publisher publisher(PublicationStore target, ModelFreeze freeze) {
    return new Publisher(dataSource, db, ingest, queries, target, freeze);
  }

  @Test
  void aChangedSnapshotBecomesOnePublicationWithEveryDocumentAndEveryCard() {
    final Publisher.Attempt attempt = publisher().publish();
    assertEquals(Publisher.Outcome.PUBLISHED, attempt.outcome(), attempt.detail());

    final PublicationStore.Current current = store.current().orElseThrow();
    assertEquals(attempt.publicationId(), current.publicationId());
    assertFalse(current.stale());
    assertNull(current.staleSince());

    final PublicationStore.Header header = store.header(current.publicationId()).orElseThrow();
    assertEquals(PublicationStore.PUBLISHED, header.state());
    assertEquals("corrected", header.history());
    assertEquals(PERIOD, header.headlinePeriod());
    assertTrue(header.approximatedElection() >= header.lastFieldworkDate().getYear());
    // The three times are distinct quantities, and none of them stands in for the others.
    assertFalse(header.publishedAt().isBefore(header.sourceCheckedAt()));
    assertTrue(header.lastFieldworkDate().isBefore(LocalDate.now(ZoneOffset.UTC)));

    for (final String language : Translations.LANGUAGES) {
      for (final String surface :
          List.of(
              PublicationDocuments.HISTORY_SURFACE,
              PublicationDocuments.INSTITUTES_SURFACE,
              PublicationDocuments.latestSurface(PERIOD),
              PublicationDocuments.electionsSurface(PERIOD))) {
        assertTrue(
            store.document(current.publicationId(), surface, language).isPresent(),
            surface + "/" + language);
      }
    }

    final List<PublicationStore.Asset> assets = store.assets(current.publicationId());
    assertEquals(ShareImages.KINDS.size() * Translations.LANGUAGES.size(), assets.size());
    for (final PublicationStore.Asset asset : assets) {
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

  @Test
  void anUnchangedSnapshotIsNotAFailureAndKeepsThePublicationFresh() {
    final Publisher publisher = publisher();
    final Publisher.Attempt first = publisher.publish();
    assertEquals(Publisher.Outcome.PUBLISHED, first.outcome(), first.detail());
    final Publisher.Attempt second = publisher.publish();
    assertEquals(Publisher.Outcome.UNCHANGED, second.outcome());
    assertEquals(first.publicationId(), second.publicationId());
    assertFalse(store.current().orElseThrow().stale());
    assertEquals(1, published());
  }

  @Test
  void aBlockedReleaseVerdictPublishesNothingAndSaysWhy() {
    final Publisher.Attempt attempt = publisher(store, TestPublication.blocked(DRAWS)).publish();
    assertEquals(Publisher.Outcome.BLOCKED, attempt.outcome());
    assertNull(attempt.publicationId());
    assertTrue(attempt.detail().contains("development_gates"));
    assertTrue(store.current().isEmpty());
    assertEquals(0, db.sql("SELECT count(*) FROM publication").query(Integer.class).single());
  }

  @Test
  void aFailedUpdateExposesNoPartialPublicationAndKeepsThePriorOneWithStaleness() {
    final Publisher.Attempt first = publisher().publish();
    assertEquals(Publisher.Outcome.PUBLISHED, first.outcome(), first.detail());
    final List<PublicationStore.Asset> published = store.assets(first.publicationId());

    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt failed =
        publisher(new FailingStore(db, transactions, root), TestPublication.released(DRAWS))
            .publish();
    assertEquals(Publisher.Outcome.FAILED, failed.outcome());

    final PublicationStore.Current current = store.current().orElseThrow();
    assertEquals(first.publicationId(), current.publicationId(), "The prior one stays current");
    assertTrue(current.stale());
    assertNotNull(current.staleSince());
    assertEquals(published, store.assets(first.publicationId()));
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
    assertEquals(Publisher.Outcome.PUBLISHED, first.outcome(), first.detail());
    final byte[] card =
        store.bytes(
            store.latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv").orElseThrow());
    final String document =
        store
            .document(first.publicationId(), PublicationDocuments.latestSurface(PERIOD), "sv")
            .orElseThrow();

    TestPublication.serve(wireMock, TestPublication.polls("2019-06-01"));
    final Publisher.Attempt second = publisher.publish();
    assertEquals(Publisher.Outcome.PUBLISHED, second.outcome(), second.detail());
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

  @Test
  void aRendererChangeAddsAnAssetVersionBesideTheOneAlreadyPublished() {
    final Publisher.Attempt first = publisher().publish();
    assertEquals(Publisher.Outcome.PUBLISHED, first.outcome(), first.detail());
    final PublicationStore.Asset version1 =
        store.latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv").orElseThrow();
    final byte[] original = store.bytes(version1);

    assertEquals(2, store.nextAssetVersion(first.publicationId(), ShareImages.OVERVIEW, "sv"));
    final byte[] rerendered = new byte[original.length + 1];
    System.arraycopy(original, 0, rerendered, 0, original.length);
    final PublicationStore.Asset version2 =
        new PublicationStore.Asset(
            first.publicationId(),
            ShareImages.OVERVIEW,
            "sv",
            2,
            "2",
            ShareImages.MEDIA_TYPE,
            rerendered.length,
            PublicationStore.sha256(rerendered));
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
  void theRegisteredCenteringAlternativeIsRefitAndDiscloseItsMovementBesideTheNumbers() {
    ingest.check();
    final long snapshotId = ingest.activeSnapshot().orElseThrow().id();
    final ModelFreeze freeze = TestPublication.released(DRAWS);
    final PublicationRun.Results results =
        PublicationRun.run(
            db, freeze, snapshotId, "sha", ingest.polls(snapshotId), queries.periods());

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
    final NationalSeats.Rules rules = results.allocationRule();
    final NationalSeats.SeatDraws drawn =
        NationalSeats.allocateDraws(results.headline().draws(), rules);
    final String expected =
        PublicationDocuments.sensitivityNote(
            List.of(
                SeatOutcomes.sensitivity(
                    centering.kind(),
                    centering.label(),
                    SeatOutcomes.headline(drawn),
                    SeatOutcomes.headline(NationalSeats.allocateDraws(centering.draws(), rules)))),
            Translations.of(Translations.ENGLISH));
    for (final tools.jackson.databind.node.ObjectNode document :
        List.of(
            PublicationDocuments.seats(
                identity, results, drawn, freeze, Translations.of(Translations.ENGLISH)),
            PublicationDocuments.coalitions(
                identity, results, drawn, freeze, Translations.of(Translations.ENGLISH)))) {
      if (expected == null) {
        assertFalse(
            document.has("sensitivity"), "Nothing moved far enough to disclose beside the numbers");
      } else {
        assertEquals(expected, document.get("sensitivity").asString());
      }
    }
  }

  private int published() {
    return db.sql("SELECT count(*) FROM publication WHERE state = 'published'")
        .query(Integer.class)
        .single();
  }

  /** A store that stages nothing, standing in for a failed image write or an interrupted move. */
  private static final class FailingStore extends PublicationStore {
    FailingStore(JdbcClient db, PlatformTransactionManager transactions, Path root) {
      super(db, transactions, root.toString());
    }

    @Override
    public List<PublicationStore.Asset> stage(
        String publicationId, List<PublicationStore.Asset> assets, List<byte[]> bytes) {
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
