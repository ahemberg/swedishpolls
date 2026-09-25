package se.swedishpolls.publication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import se.swedishpolls.publication.PublicationOutcome;
import se.swedishpolls.publication.TestFreeze;
import se.swedishpolls.publication.repository.PublicationLock;
import se.swedishpolls.publication.repository.PublicationStore;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.source.service.NationalAllocationRuleService;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;
import se.swedishpolls.testsupport.TestDatabase;

@JdbcTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestDatabase.Configuration.class)
class PublisherPersistenceTest {
  private static final String PUBLICATION_ID = "pub_20260912T120000Z";

  @Autowired private JdbcClient db;
  @Autowired private PlatformTransactionManager transactions;

  @Test
  void anUnchangedSnapshotKeepsOneFreshPersistedPublication() {
    final long snapshotId = published();
    final PublicationStore store = new PublicationStore(db, transactions);
    final Publisher publisher = publisher(store, snapshotId, true);

    final Publisher.Attempt attempt = publisher.publish();

    assertEquals(PublicationOutcome.UNCHANGED, attempt.outcome());
    assertEquals(PUBLICATION_ID, attempt.publicationId());
    assertFalse(store.current().orElseThrow().stale());
    assertEquals(1, db.sql("SELECT count(*) FROM publication").query(Integer.class).single());
  }

  @Test
  void aBlockedReleaseLeavesNoPersistedPublication() {
    final PublicationStore store = new PublicationStore(db, transactions);
    final Publisher publisher = publisher(store, 42, false);

    final Publisher.Attempt attempt = publisher.publish();

    assertEquals(PublicationOutcome.BLOCKED, attempt.outcome());
    assertTrue(store.current().isEmpty());
    assertEquals(0, db.sql("SELECT count(*) FROM publication").query(Integer.class).single());
  }

  private Publisher publisher(PublicationStore store, long snapshotId, boolean released) {
    final PublicationLock lock = mock(PublicationLock.class);
    when(lock.whileHeld(any()))
        .thenAnswer(
            invocation -> {
              final Supplier<?> work = invocation.getArgument(0);
              return Optional.of(work.get());
            });
    final SnapshotIngest ingest = mock(SnapshotIngest.class);
    when(ingest.check()).thenReturn(SnapshotIngest.Result.UNCHANGED);
    when(ingest.activeSnapshot())
        .thenReturn(Optional.of(new Snapshot(snapshotId, "sha", Instant.EPOCH)));
    return new Publisher(
        lock,
        ingest,
        mock(PollQueryService.class),
        mock(ElectionReferenceService.class),
        mock(NationalAllocationRuleService.class),
        store,
        released ? TestFreeze.released(2) : TestFreeze.blocked(2));
  }

  private long published() {
    final long snapshotId =
        db.sql(
                """
                INSERT INTO poll_snapshot(source_url, sha256, raw_csv, parser_version)
                VALUES ('https://example.test/polls.csv', repeat('0', 64), ''::bytea, 1)
                RETURNING id
                """)
            .query(Long.class)
            .single();
    db.sql(
            """
            INSERT INTO model_run(id, snapshot_id, protocol_version, seed, draws, parameters,
                code_version, estimator_version, runtime, numerical_library)
            VALUES ('run_000001', ?, '1', 1, 2, '{}'::jsonb, 'test', 'test', 'test', 'test')
            """)
        .param(snapshotId)
        .update();
    db.sql(
            """
            INSERT INTO publication(id, run_id, snapshot_id, last_fieldwork_date,
                source_checked_at, published_at, history, headline_period,
                approximated_election, state)
            VALUES (?, 'run_000001', ?, '2026-09-10', now(), now(), 'corrected',
                'eight_party_2010', 2026, 'published')
            """)
        .params(PUBLICATION_ID, snapshotId)
        .update();
    db.sql(
            """
            INSERT INTO current_publication(singleton, publication_id, stale, stale_since)
            VALUES (true, ?, false, NULL)
            """)
        .param(PUBLICATION_ID)
        .update();
    return snapshotId;
  }
}
