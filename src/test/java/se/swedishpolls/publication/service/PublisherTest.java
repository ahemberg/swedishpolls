package se.swedishpolls.publication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.swedishpolls.publication.CurrentPublication;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.PublicationOutcome;
import se.swedishpolls.publication.TestFreeze;
import se.swedishpolls.publication.repository.PublicationLock;
import se.swedishpolls.publication.repository.PublicationStore;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.source.service.NationalAllocationRuleService;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;

class PublisherTest {
  private static final Instant CHECKED_AT = Instant.parse("2026-09-12T12:00:00Z");
  private static final Snapshot SNAPSHOT = new Snapshot(42, "sha", CHECKED_AT);
  private static final PublicationHeader HEADER =
      new PublicationHeader(
          "pub_20260912T120000Z",
          "run-1",
          SNAPSHOT.id(),
          LocalDate.of(2026, 9, 10),
          CHECKED_AT,
          CHECKED_AT,
          "corrected",
          "eight_party_2010",
          2026,
          "published");

  private final PublicationLock lock = mock(PublicationLock.class);
  private final SnapshotIngest ingest = mock(SnapshotIngest.class);
  private final PollQueryService queries = mock(PollQueryService.class);
  private final ElectionReferenceService electionReferences = mock(ElectionReferenceService.class);
  private final NationalAllocationRuleService allocationRules =
      mock(NationalAllocationRuleService.class);
  private final PublicationStore store = mock(PublicationStore.class);

  @BeforeEach
  void holdLockAndFindSnapshot() {
    when(lock.whileHeld(any()))
        .thenAnswer(
            invocation -> {
              final Supplier<?> work = invocation.getArgument(0);
              return Optional.of(work.get());
            });
    when(ingest.check()).thenReturn(SnapshotIngest.Result.UNCHANGED);
    when(ingest.activeSnapshot()).thenReturn(Optional.of(SNAPSHOT));
    when(store.lastSuccessfulCheck()).thenReturn(Optional.of(CHECKED_AT));
  }

  @Test
  void anUnchangedSnapshotKeepsTheCurrentPublicationFresh() {
    when(store.current())
        .thenReturn(Optional.of(new CurrentPublication(HEADER.publicationId(), false, null)));
    when(store.header(HEADER.publicationId())).thenReturn(Optional.of(HEADER));
    final Publisher publisher = publisher(true);

    final Publisher.Attempt attempt = publisher.publish();

    assertEquals(PublicationOutcome.UNCHANGED, attempt.outcome());
    assertEquals(HEADER.publicationId(), attempt.publicationId());
    verify(store).recordAttempt(PublicationOutcome.UNCHANGED, CHECKED_AT, null, null);
    verify(store, never()).markStale(any());
    verify(store, never())
        .recordCandidate(any(), any(), anyLong(), any(), any(), any(), any(), anyInt());
  }

  @Test
  void aBlockedReleasePublishesNothingAndSaysWhy() {
    when(store.current()).thenReturn(Optional.empty());
    final Publisher publisher = publisher(false);

    final Publisher.Attempt attempt = publisher.publish();

    assertEquals(PublicationOutcome.BLOCKED, attempt.outcome());
    assertNull(attempt.publicationId());
    assertTrue(attempt.detail().contains("development_gates"));
    verify(store).recordAttempt(PublicationOutcome.BLOCKED, CHECKED_AT, null, attempt.detail());
    verify(store, never())
        .recordCandidate(any(), any(), anyLong(), any(), any(), any(), any(), anyInt());
  }

  private Publisher publisher(boolean released) {
    return new Publisher(
        lock,
        ingest,
        queries,
        electionReferences,
        allocationRules,
        store,
        released ? TestFreeze.released(2) : TestFreeze.blocked(2));
  }
}
