package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;

class SourceChartTest {
  private static final long SNAPSHOT = 412;

  private static List<PollQuery.Row> rows;

  @BeforeAll
  static void readFixture() throws Exception {
    try (final InputStream input = SourceChartTest.class.getResourceAsStream("/polls/audit.csv")) {
      final List<PollCsv.Poll> polls = PollCsv.parse(input.readAllBytes());
      rows =
          PollQuery.filter(
                  SNAPSHOT,
                  polls,
                  List.<Roster.CoveragePeriod>of(),
                  PollQuery.Filters.none(),
                  1,
                  PollQuery.DEFAULT_PAGE_SIZE)
              .matching();
    }
  }

  @Test
  void theExtentSpansEveryDatedObservationRatherThanTheTablePage() {
    final SourceChart.Range extent = SourceChart.extent(rows).orElseThrow();
    assertEquals("all", extent.id());
    for (final PollQuery.Row row : rows) {
      if (row.poll().collectionFrom() == null) {
        continue;
      }
      assertFalse(row.poll().collectionFrom().isBefore(extent.from()));
      assertFalse(row.poll().collectionTo().isAfter(extent.to()));
    }
  }

  @Test
  void anArchiveWithoutDatedObservationsOffersNoChart() {
    assertEquals(Optional.empty(), SourceChart.extent(List.of()));
  }

  @Test
  void theOfferedRangesEndAtTheExtentAndStartNoEarlierThanIt() {
    final SourceChart.Range extent = SourceChart.extent(rows).orElseThrow();
    final List<SourceChart.Range> ranges = SourceChart.ranges(extent, null);
    assertEquals(
        List.of("oneYear", "fourYears", "all"),
        ranges.stream().map(SourceChart.Range::id).toList());
    for (final SourceChart.Range range : ranges) {
      assertEquals(extent.to(), range.to());
      assertFalse(range.from().isBefore(extent.from()));
    }
    assertEquals(extent.to().minusYears(1), ranges.getFirst().from());
    assertEquals(extent.from(), ranges.getLast().from());
  }

  @Test
  void aShortArchiveClampsEveryOfferedRangeToItsOwnStart() {
    final SourceChart.Range extent =
        new SourceChart.Range("all", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 5));
    for (final SourceChart.Range range : SourceChart.ranges(extent, null)) {
      assertEquals(extent.from(), range.from());
    }
  }

  @Test
  void theElectionWindowIsOfferedOnlyForAnElectionInsideTheArchive() {
    final SourceChart.Range extent = SourceChart.extent(rows).orElseThrow();
    final LocalDate election = LocalDate.of(2022, 9, 11);
    final List<SourceChart.Range> ranges = SourceChart.ranges(extent, election);
    assertEquals(
        List.of("oneYear", "sinceElection", "fourYears", "all"),
        ranges.stream().map(SourceChart.Range::id).toList());
    assertEquals(election, ranges.get(1).from());
    assertEquals(Integer.valueOf(2022), ranges.get(1).year());
    assertNull(ranges.getFirst().year(), "only the election window names a year");
    assertEquals(3, SourceChart.ranges(extent, extent.to().plusYears(1)).size());
  }

  @Test
  void theMostRecentElectionOnOrBeforeTheLastInterviewDayIsTheOneOffered() {
    final List<LocalDate> elections =
        List.of(LocalDate.of(2018, 9, 9), LocalDate.of(2022, 9, 11), LocalDate.of(2026, 9, 13));
    assertEquals(
        LocalDate.of(2022, 9, 11),
        SourceChart.lastElectionOnOrBefore(elections, LocalDate.of(2026, 9, 5)));
    assertNull(SourceChart.lastElectionOnOrBefore(elections, LocalDate.of(2010, 1, 1)));
    assertNull(SourceChart.lastElectionOnOrBefore(List.of(), LocalDate.of(2026, 9, 5)));
  }

  @Test
  void theDefaultRangeIsTheLastYearAndAnUnknownRangeIsNotOffered() {
    final SourceChart.Range extent = SourceChart.extent(rows).orElseThrow();
    assertEquals(
        SourceChart.DEFAULT_RANGE, SourceChart.range(extent, null, null).orElseThrow().id());
    assertEquals("fourYears", SourceChart.range(extent, null, "fourYears").orElseThrow().id());
    assertEquals(Optional.empty(), SourceChart.range(extent, null, "sinceElection"));
  }

  @Test
  void aRangeKeepsEveryObservationOverlappingIt() {
    final SourceChart.Range range =
        new SourceChart.Range("window", LocalDate.of(2016, 6, 10), LocalDate.of(2016, 6, 20));
    final List<PollQuery.Row> within = SourceChart.within(rows, range);
    assertFalse(within.isEmpty());
    for (final PollQuery.Row row : within) {
      assertFalse(row.poll().collectionTo().isBefore(range.from()));
      assertFalse(row.poll().collectionFrom().isAfter(range.to()));
    }
    assertEquals(
        rows.stream()
            .filter(row -> row.poll().collectionFrom() != null)
            .filter(row -> !row.poll().collectionTo().isBefore(range.from()))
            .filter(row -> !row.poll().collectionFrom().isAfter(range.to()))
            .count(),
        within.size());
  }
}
