package se.swedishpolls.source.service;

import java.util.List;
import org.springframework.stereotype.Component;
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

  public PollQueryService(SnapshotRepository snapshots, CoveragePeriodRepository periods) {
    this.snapshots = snapshots;
    this.periods = periods;
  }

  /** One poll request against the pinned snapshot, paged like the table shows it. */
  public PollQuery.Result query(
      long snapshotId, PollQuery.Filters filters, int page, int pageSize) {
    return query(snapshotId, periods.periods(), filters, page, pageSize);
  }

  /** One poll request classified by the coverage periods stored with its publication. */
  public PollQuery.Result query(
      long snapshotId,
      List<Roster.CoveragePeriod> coverage,
      PollQuery.Filters filters,
      int page,
      int pageSize) {
    return PollQuery.filter(
        snapshotId, snapshots.polls(snapshotId), coverage, filters, page, pageSize);
  }

  /** Every coverage period, for requests that name one. */
  public List<Roster.CoveragePeriod> periods() {
    return periods.periods();
  }
}
