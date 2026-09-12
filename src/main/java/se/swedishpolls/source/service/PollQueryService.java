package se.swedishpolls.source.service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.source.repository.SnapshotRepository;

/**
 * Resolves poll requests against one pinned source snapshot and the coverage periods. The table and
 * the download share the filter and the row order, so a page and its CSV differ only in paging.
 */
@Component
public class PollQueryService {
  private final SnapshotRepository snapshots;
  private final CoveragePeriodRepository periods;

  /**
   * The pinned snapshot is immutable, so its parsed rows are cached for the life of whatever
   * publication pins it. A request pinned to an older publication still names that publication's
   * own snapshot id, and a cache miss re-reads it.
   */
  private final ConcurrentHashMap<Long, List<PollCsv.Poll>> parsedPolls = new ConcurrentHashMap<>();

  public PollQueryService(SnapshotRepository snapshots, CoveragePeriodRepository periods) {
    this.snapshots = snapshots;
    this.periods = periods;
  }

  /** One poll request against the pinned snapshot, paged like the table shows it. */
  public PollQuery.Result query(
      long snapshotId, PollQuery.Filters filters, int page, int pageSize) {
    final List<PollCsv.Poll> polls = parsedPolls.computeIfAbsent(snapshotId, snapshots::polls);
    return PollQuery.filter(snapshotId, polls, periods.periods(), filters, page, pageSize);
  }

  /** Every coverage period, for requests that name one. */
  public List<Roster.CoveragePeriod> periods() {
    return periods.periods();
  }

  /** Whether a request's coverage period names a real one. */
  public boolean knownPeriod(String coveragePeriod) {
    return periods.periods().stream().anyMatch(period -> period.id().equals(coveragePeriod));
  }
}
