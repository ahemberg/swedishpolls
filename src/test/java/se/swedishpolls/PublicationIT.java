package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The publication worker end to end: what it publishes, and what a failed update leaves behind. */
class PublicationIT {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int DRAWS = 200;

  private interface Work {
    void run(Fixture fixture) throws Exception;
  }

  /** One isolated schema, publication volume and worker. */
  private record Fixture(
      JdbcClient db,
      DataSource dataSource,
      PublicationStore store,
      SnapshotIngest ingest,
      TestPublication.FixedSource source,
      Path root) {
    Publisher publisher(ModelFreeze freeze) {
      return new Publisher(dataSource, db, ingest, store, freeze);
    }
  }

  @Test
  void aChangedSnapshotBecomesOnePublicationWithEveryDocumentAndEveryCard() throws Exception {
    withFixture(
        fixture -> {
          final Publisher.Attempt attempt =
              fixture.publisher(TestPublication.released(DRAWS)).publish();
          assertEquals(Publisher.Outcome.PUBLISHED, attempt.outcome(), attempt.detail());

          final PublicationStore.Current current = fixture.store().current().orElseThrow();
          assertEquals(attempt.publicationId(), current.publicationId());
          assertFalse(current.stale());
          assertNull(current.staleSince());

          final PublicationStore.Header header =
              fixture.store().header(current.publicationId()).orElseThrow();
          assertEquals(PublicationStore.PUBLISHED, header.state());
          assertEquals("corrected", header.history());
          // The three times are distinct quantities, and none of them is the others.
          assertFalse(header.publishedAt().isBefore(header.sourceCheckedAt()));
          assertTrue(
              header
                  .lastFieldworkDate()
                  .isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC)));

          for (final String language : Translations.LANGUAGES) {
            assertTrue(
                fixture
                    .store()
                    .document(
                        current.publicationId(), PublicationDocuments.HISTORY_SURFACE, language)
                    .isPresent());
            assertTrue(
                fixture
                    .store()
                    .document(
                        current.publicationId(), PublicationDocuments.INSTITUTES_SURFACE, language)
                    .isPresent());
            assertTrue(
                fixture
                    .store()
                    .document(
                        current.publicationId(),
                        PublicationDocuments.latestSurface("eight_party_2010"),
                        language)
                    .isPresent());
          }

          final List<PublicationStore.Asset> assets =
              fixture.store().assets(current.publicationId());
          assertEquals(ShareImages.KINDS.size() * Translations.LANGUAGES.size(), assets.size());
          for (final PublicationStore.Asset asset : assets) {
            assertEquals(1, asset.version());
            assertEquals(ShareImages.RENDERER_VERSION, asset.rendererVersion());
            assertEquals(ShareImages.MEDIA_TYPE, asset.mediaType());
            assertEquals(asset.byteCount(), fixture.store().bytes(asset).length);
          }
          assertFalse(
              Files.exists(fixture.root().resolve("staging").resolve(current.publicationId())),
              "A promoted candidate leaves no staging directory behind");

          final JsonNode latest =
              JSON.readTree(
                  fixture
                      .store()
                      .document(
                          current.publicationId(),
                          PublicationDocuments.latestSurface("eight_party_2010"),
                          Translations.ENGLISH)
                      .orElseThrow());
          assertEquals(
              current.publicationId(), latest.get("publication").get("publicationId").asString());
          assertEquals(header.runId(), latest.get("publication").get("runId").asString());
          assertEquals(
              header.snapshotId(), latest.get("publication").get("snapshotId").longValue());
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
        });
  }

  @Test
  void anUnchangedSnapshotIsNotAFailureAndKeepsThePublicationFresh() throws Exception {
    withFixture(
        fixture -> {
          final Publisher publisher = fixture.publisher(TestPublication.released(DRAWS));
          final Publisher.Attempt first = publisher.publish();
          assertEquals(Publisher.Outcome.PUBLISHED, first.outcome(), first.detail());
          final Publisher.Attempt second = publisher.publish();
          assertEquals(Publisher.Outcome.UNCHANGED, second.outcome());
          assertEquals(first.publicationId(), second.publicationId());
          assertFalse(fixture.store().current().orElseThrow().stale());
          assertEquals(
              1,
              fixture
                  .db()
                  .sql("SELECT count(*) FROM publication WHERE state = 'published'")
                  .query(Integer.class)
                  .single());
        });
  }

  @Test
  void aBlockedReleaseVerdictPublishesNothingAndSaysWhy() throws Exception {
    withFixture(
        fixture -> {
          final Publisher.Attempt attempt =
              fixture.publisher(TestPublication.blocked(DRAWS)).publish();
          assertEquals(Publisher.Outcome.BLOCKED, attempt.outcome());
          assertNull(attempt.publicationId());
          assertTrue(attempt.detail().contains("development_gates"));
          assertTrue(fixture.store().current().isEmpty());
          assertEquals(
              0,
              fixture.db().sql("SELECT count(*) FROM publication").query(Integer.class).single());
        });
  }

  @Test
  void aFailedUpdateExposesNoPartialPublicationAndKeepsThePriorOneWithStaleness() throws Exception {
    withFixture(
        fixture -> {
          final Publisher publisher = fixture.publisher(TestPublication.released(DRAWS));
          final Publisher.Attempt first = publisher.publish();
          assertEquals(Publisher.Outcome.PUBLISHED, first.outcome(), first.detail());
          final List<PublicationStore.Asset> published =
              fixture.store().assets(first.publicationId());

          fixture.source().change(TestPublication.polls("2019-06-01"));
          final Publisher failing =
              new Publisher(
                  fixture.dataSource(),
                  fixture.db(),
                  fixture.ingest(),
                  new FailingStore(fixture.db(), fixture.dataSource(), fixture.root()),
                  TestPublication.released(DRAWS));
          final Publisher.Attempt failed = failing.publish();
          assertEquals(Publisher.Outcome.FAILED, failed.outcome());

          final PublicationStore.Current current = fixture.store().current().orElseThrow();
          assertEquals(
              first.publicationId(), current.publicationId(), "The prior one stays current");
          assertTrue(current.stale());
          assertNotNull(current.staleSince());
          assertEquals(published, fixture.store().assets(first.publicationId()));
          assertEquals(
              0,
              fixture
                  .db()
                  .sql("SELECT count(*) FROM publication WHERE state = 'candidate'")
                  .query(Integer.class)
                  .single(),
              "A candidate is abandoned rather than left half visible");
          assertEquals(
              1,
              fixture
                  .db()
                  .sql("SELECT count(*) FROM publication WHERE state = 'published'")
                  .query(Integer.class)
                  .single());
          assertTrue(
              fixture
                  .db()
                  .sql("SELECT failure FROM publication_attempt WHERE outcome = 'failed'")
                  .query(String.class)
                  .single()
                  .contains("injected"));
        });
  }

  @Test
  void anEarlierPublicationSurvivesTheNextOneAndItsPermanentLinkNeverFallsBack() throws Exception {
    withFixture(
        fixture -> {
          final Publisher publisher = fixture.publisher(TestPublication.released(DRAWS));
          final Publisher.Attempt first = publisher.publish();
          assertEquals(Publisher.Outcome.PUBLISHED, first.outcome(), first.detail());
          final byte[] card =
              fixture
                  .store()
                  .bytes(
                      fixture
                          .store()
                          .latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv")
                          .orElseThrow());
          final String document =
              fixture
                  .store()
                  .document(
                      first.publicationId(),
                      PublicationDocuments.latestSurface("eight_party_2010"),
                      "sv")
                  .orElseThrow();

          fixture.source().change(TestPublication.polls("2019-06-01"));
          final Publisher.Attempt second = publisher.publish();
          assertEquals(Publisher.Outcome.PUBLISHED, second.outcome(), second.detail());
          assertNotEquals(first.publicationId(), second.publicationId());
          assertEquals(
              second.publicationId(), fixture.store().current().orElseThrow().publicationId());

          assertArrayEquals(
              card,
              fixture
                  .store()
                  .bytes(
                      fixture
                          .store()
                          .latestAsset(first.publicationId(), ShareImages.OVERVIEW, "sv")
                          .orElseThrow()),
              "Published bytes are never overwritten");
          assertEquals(
              document,
              fixture
                  .store()
                  .document(
                      first.publicationId(),
                      PublicationDocuments.latestSurface("eight_party_2010"),
                      "sv")
                  .orElseThrow());

          // A restart reads the same rows and the same volume; nothing is held in memory.
          final PublicationStore restarted =
              new PublicationStore(
                  fixture.db(),
                  new DataSourceTransactionManager(fixture.dataSource()),
                  fixture.root().toString());
          assertTrue(restarted.header(first.publicationId()).isPresent());
          assertTrue(restarted.header("pub_00000000T000000Z").isEmpty());
          assertEquals(second.publicationId(), restarted.current().orElseThrow().publicationId());
        });
  }

  /** A store that stages nothing, standing in for a failed image write or an interrupted move. */
  private static final class FailingStore extends PublicationStore {
    FailingStore(JdbcClient db, DataSource dataSource, Path root) {
      super(db, new DataSourceTransactionManager(dataSource), root.toString());
    }

    @Override
    public List<PublicationStore.Asset> stage(
        String publicationId, List<PublicationStore.Asset> assets, List<byte[]> bytes) {
      throw new IllegalStateException("injected staging failure");
    }
  }

  private void withFixture(Work work) throws Exception {
    final String schema = "publication_" + UUID.randomUUID().toString().replace("-", "");
    final DataSource dataSource = TestDatabase.dataSource(schema);
    final Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    final Path root = Files.createTempDirectory("swedishpolls-publications");
    try {
      flyway.migrate();
      final JdbcClient db = JdbcClient.create(dataSource);
      final DataSourceTransactionManager transactions =
          new DataSourceTransactionManager(dataSource);
      final TestPublication.FixedSource source =
          new TestPublication.FixedSource(TestPublication.polls(TestPublication.FROM));
      work.run(
          new Fixture(
              db,
              dataSource,
              new PublicationStore(db, transactions, root.toString()),
              new SnapshotIngest(db, source, transactions, TestPublication.SOURCE_URL),
              source,
              root));
    } finally {
      flyway.clean();
      deleteRecursively(root);
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
    }
  }
}
