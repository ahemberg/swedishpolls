package se.swedishpolls.source.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.source.repository.SnapshotRepository;

/**
 * Resolves poll requests against one pinned source snapshot and the coverage periods. The table and
 * the download share the filter and the row order, so a page and its CSV differ only in paging.
 */
@Component
public class PollQueryService {
  /** How many snapshots' parsed rows the cache keeps: the current pin and a few recent ones. */
  static final int CACHED_SNAPSHOTS = 8;

  private final SnapshotRepository snapshots;
  private final CoveragePeriodRepository periods;

  /**
   * The pinned snapshot is immutable, so its parsed rows are reused across requests. Retention is
   * bounded and least-recently-requested first: snapshot ids only grow and nothing evicts itself,
   * so an unbounded cache would accumulate every snapshot the server ever served. A request pinned
   * to a dropped snapshot re-reads its own snapshot, and a cold parse holds the cache lock while it
   * reads.
   */
  private final Map<Long, List<PollCsv.Poll>> parsedPolls =
      Collections.synchronizedMap(new BoundedPollCache());

  /**
   * An access-ordered poll-row cache that drops its eldest entry beyond {@link CACHED_SNAPSHOTS}.
   */
  private static final class BoundedPollCache extends LinkedHashMap<Long, List<PollCsv.Poll>> {
    private static final long serialVersionUID = 1L;

    private BoundedPollCache() {
      super(16, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Long, List<PollCsv.Poll>> eldest) {
      return size() > CACHED_SNAPSHOTS;
    }
  }

  public PollQueryService(SnapshotRepository snapshots, CoveragePeriodRepository periods) {
    this.snapshots = snapshots;
    this.periods = periods;
  }

  /**
   * One poll request against the pinned snapshot, classified by the coverage periods the caller
   * resolved. Passing the repository's current periods here would let a correction reclassify an
   * already-published page, so callers always pass the periods their publication carries.
   */
  public PollQuery.Result query(
      long snapshotId,
      List<Roster.CoveragePeriod> coverage,
      PollQuery.Filters filters,
      int page,
      int pageSize) {
    final List<PollCsv.Poll> polls = parsedPolls.computeIfAbsent(snapshotId, snapshots::polls);
    return PollQuery.filter(snapshotId, polls, coverage, filters, page, pageSize);
  }

  /** Every coverage period, for requests that name one. */
  public List<Roster.CoveragePeriod> periods() {
    return periods.periods();
  }

  public Optional<Snapshot> activeSnapshot() {
    return snapshots.activeSnapshot();
  }

  public Optional<Snapshot> snapshot(long id) {
    return snapshots.findSnapshot(id);
  }

  public List<String> institutes(long snapshotId) {
    final List<PollCsv.Poll> polls = parsedPolls.computeIfAbsent(snapshotId, snapshots::polls);
    return List.copyOf(PollQuery.institutes(polls).keySet());
  }
}
