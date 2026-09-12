package se.swedishpolls.source.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.source.repository.SnapshotRepository;

class PollQueryServiceTest {
  /**
   * A pinned snapshot is immutable, so requests against one snapshot parse it once: a page that
   * loads the table and then the download pays the repository read once. A request pinned to
   * another publication still reads that publication's own snapshot.
   */
  @Test
  void parsesOneSnapshotOnceWhileOlderPublicationsKeepTheirOwn() {
    final AtomicInteger reads = new AtomicInteger();
    final SnapshotRepository snapshots =
        new SnapshotRepository(null, "http://source.csv") {
          @Override
          public List<PollCsv.Poll> polls(long snapshotId) {
            reads.incrementAndGet();
            return List.of();
          }
        };
    final CoveragePeriodRepository periods =
        new CoveragePeriodRepository(null) {
          @Override
          public List<Roster.CoveragePeriod> periods() {
            return List.of();
          }
        };
    final PollQueryService service = new PollQueryService(snapshots, periods);
    final PollQuery.Filters none = PollQuery.Filters.none();

    service.query(7, none, 1, PollQuery.DEFAULT_PAGE_SIZE);
    service.query(7, none, 2, PollQuery.DEFAULT_PAGE_SIZE);
    assertEquals(1, reads.get(), "A second request against one snapshot parses no rows again");

    service.query(8, none, 1, PollQuery.DEFAULT_PAGE_SIZE);
    assertEquals(
        2,
        reads.get(),
        "A request pinned to an older publication reads that publication's own snapshot");
    service.query(8, none, 1, PollQuery.DEFAULT_PAGE_SIZE);
    assertEquals(2, reads.get());
  }

  /**
   * Retention is bounded: keeping every snapshot the server ever served would accumulate parsed
   * rows without end. The cache keeps the most recently requested snapshots, and a snapshot pushed
   * out is read again.
   */
  @Test
  void reReadsASnapshotPushedOutOfTheBoundCache() {
    final AtomicInteger reads = new AtomicInteger();
    final SnapshotRepository snapshots =
        new SnapshotRepository(null, "http://source.csv") {
          @Override
          public List<PollCsv.Poll> polls(long snapshotId) {
            reads.incrementAndGet();
            return List.of();
          }
        };
    final CoveragePeriodRepository periods =
        new CoveragePeriodRepository(null) {
          @Override
          public List<Roster.CoveragePeriod> periods() {
            return List.of();
          }
        };
    final PollQueryService service = new PollQueryService(snapshots, periods);
    final PollQuery.Filters none = PollQuery.Filters.none();
    final int bound = PollQueryService.CACHED_SNAPSHOTS;

    for (long snapshotId = 1; snapshotId <= bound; snapshotId++) {
      service.query(snapshotId, none, 1, PollQuery.DEFAULT_PAGE_SIZE);
    }
    assertEquals(bound, reads.get());
    service.query(1, none, 1, PollQuery.DEFAULT_PAGE_SIZE);
    assertEquals(bound, reads.get(), "A snapshot still cached is read once");

    for (long snapshotId = bound + 1; snapshotId <= 2 * bound; snapshotId++) {
      service.query(snapshotId, none, 1, PollQuery.DEFAULT_PAGE_SIZE);
    }
    assertEquals(2 * bound, reads.get());
    service.query(1, none, 1, PollQuery.DEFAULT_PAGE_SIZE);
    assertEquals(2 * bound + 1, reads.get(), "A snapshot pushed out of the cache is read again");
  }
}
