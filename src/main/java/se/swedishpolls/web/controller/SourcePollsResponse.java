package se.swedishpolls.web.controller;

import java.util.List;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.web.PollFilters;

/** A filtered page of source observations and the snapshot every related request must name. */
record SourcePollsResponse(
    Snapshot snapshot,
    PollsResponse.Filters filters,
    int total,
    int page,
    int pageSize,
    String csv,
    List<String> institutes,
    List<PollFilters.Invalid> invalid,
    List<PollsResponse.Poll> polls) {
  static SourcePollsResponse from(
      Snapshot snapshot,
      PollQuery.Result result,
      PollQuery.Filters filters,
      List<PollFilters.Invalid> invalid,
      List<String> institutes) {
    final int pages =
        Math.max(1, (int) ((result.total() + (long) result.pageSize() - 1) / result.pageSize()));
    final int page = Math.min(result.page1(), pages);
    final int from = (int) Math.min((long) (page - 1) * result.pageSize(), result.total());
    final int to = (int) Math.min((long) from + result.pageSize(), result.total());
    return new SourcePollsResponse(
        snapshot,
        new PollsResponse.Filters(
            filters.from(),
            filters.to(),
            filters.institutes(),
            List.of(),
            filters.includeExcluded(),
            null),
        result.total(),
        page,
        result.pageSize(),
        PollFilters.sourceCsvLink(snapshot.id(), filters),
        List.copyOf(institutes),
        List.copyOf(invalid),
        result.matching().subList(from, to).stream()
            .map(row -> PollsResponse.Poll.from(row, filters))
            .toList());
  }
}
