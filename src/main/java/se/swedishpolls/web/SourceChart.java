package se.swedishpolls.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import se.swedishpolls.source.PollQuery;

/**
 * The date axis a source chart draws on, derived from the retained observations alone.
 *
 * <p>Nothing here reads a publication. A source chart exists before the first estimate does, so its
 * extent is the span the archived interview periods actually cover rather than the days an estimate
 * happened to be sampled at. A marker is selected by overlap, the same rule the poll table and the
 * download filter with, so a period crossing the chosen boundary is drawn and clipped rather than
 * dropped.
 */
public final class SourceChart {
  private SourceChart() {}

  /** The range a chart opens on when a request names none. */
  public static final String DEFAULT_RANGE = "oneYear";

  private static final int LONG_YEARS = 4;

  /** One offered window of the date axis. Both bounds are inclusive interview dates. */
  public record Range(String id, LocalDate from, LocalDate to) {}

  /**
   * The span every dated observation falls inside, or empty when the archive carries none. A row
   * without interview dates has nowhere to sit on a date axis and is left off the chart.
   */
  public static Optional<Range> extent(List<PollQuery.Row> rows) {
    LocalDate first = null;
    LocalDate last = null;
    for (final PollQuery.Row row : rows) {
      final LocalDate from = row.poll().collectionFrom();
      final LocalDate to = row.poll().collectionTo();
      if (from == null || to == null) {
        continue;
      }
      first = first == null || from.isBefore(first) ? from : first;
      last = last == null || to.isAfter(last) ? to : last;
    }
    if (first == null) {
      return Optional.empty();
    }
    return Optional.of(new Range("all", first, last));
  }

  /**
   * The windows the chart offers, all ending at the newest interview date. A window reaching past
   * the start of the archive is clamped to it, so a short archive offers the same chips without
   * drawing empty years beside its own first poll.
   */
  public static List<Range> ranges(Range extent) {
    return List.of(
        new Range(DEFAULT_RANGE, start(extent, extent.to().minusYears(1)), extent.to()),
        new Range("fourYears", start(extent, extent.to().minusYears(LONG_YEARS)), extent.to()),
        extent);
  }

  /** The named window, the last year when the request names none, or empty when it is unknown. */
  public static Optional<Range> range(Range extent, String id) {
    final String wanted = id == null || id.isBlank() ? DEFAULT_RANGE : id;
    return ranges(extent).stream().filter(range -> range.id().equals(wanted)).findFirst();
  }

  /** Every dated observation whose interview period overlaps the window, clipping to nothing. */
  public static List<PollQuery.Row> within(List<PollQuery.Row> rows, Range range) {
    return rows.stream()
        .filter(row -> row.poll().collectionFrom() != null && row.poll().collectionTo() != null)
        .filter(row -> !row.poll().collectionTo().isBefore(range.from()))
        .filter(row -> !row.poll().collectionFrom().isAfter(range.to()))
        .toList();
  }

  private static LocalDate start(Range extent, LocalDate wanted) {
    return wanted.isBefore(extent.from()) ? extent.from() : wanted;
  }
}
