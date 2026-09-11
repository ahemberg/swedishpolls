package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PollQueryTest {
  private static final long SNAPSHOT = 412;

  private static final List<Roster.CoveragePeriod> PERIODS =
      List.of(
          new Roster.CoveragePeriod(
              "eight_party_2010",
              LocalDate.of(2010, 1, 1),
              null,
              List.of("S", "M", "SD", "V", "C", "KD", "L", "MP"),
              false,
              true,
              "https://github.com/ahemberg/swedishpolls/issues/12"));

  private static List<PollCsv.Poll> polls;

  @BeforeAll
  static void readFixture() throws Exception {
    try (InputStream input = PollQueryTest.class.getResourceAsStream("/polls/audit.csv")) {
      polls = PollCsv.parse(input.readAllBytes());
    }
  }

  private static PollQuery.Result filter(PollQuery.Filters filters, int page, int pageSize) {
    return PollQuery.filter(SNAPSHOT, polls, PERIODS, filters, page, pageSize);
  }

  @Test
  void anInclusiveRangeKeepsEveryPollWhoseFieldworkOverlapsIt() {
    final PollQuery.Result result =
        filter(
            new PollQuery.Filters(
                LocalDate.of(2016, 6, 1),
                LocalDate.of(2016, 6, 30),
                List.of(),
                List.of(),
                null,
                false),
            1,
            PollQuery.DEFAULT_PAGE_SIZE);
    assertFalse(result.matching().isEmpty());
    for (final PollQuery.Row row : result.matching()) {
      assertFalse(row.poll().collectionTo().isBefore(LocalDate.of(2016, 6, 1)));
      assertFalse(row.poll().collectionFrom().isAfter(LocalDate.of(2016, 6, 30)));
      assertTrue(row.poll().eligible(), "An excluded row needs includeExcluded");
      assertEquals(SNAPSHOT + ":" + row.poll().rowNumber(), row.pollId());
    }
  }

  @Test
  void excludedRowsAppearOnlyOnRequestAndCarryTheirReasons() {
    final PollQuery.Filters visible = PollQuery.Filters.none();
    final PollQuery.Filters everything =
        new PollQuery.Filters(null, null, List.of(), List.of(), null, true);
    final int eligible = filter(visible, 1, 1).total();
    final PollQuery.Result all = filter(everything, 1, 1);
    assertTrue(all.total() > eligible, "The snapshot archives excluded rows too");
    assertTrue(
        all.matching().stream()
            .filter(row -> !row.poll().eligible())
            .allMatch(row -> !row.poll().exclusionReasons().isEmpty()));
  }

  @Test
  void anInstituteFilterAndPagingCutTheSameOrderedRows() {
    final PollQuery.Filters filters =
        new PollQuery.Filters(null, null, List.of("Novus"), List.of(), null, false);
    final PollQuery.Result whole = filter(filters, 1, PollQuery.MAX_PAGE_SIZE);
    assertTrue(whole.matching().stream().allMatch(row -> "Novus".equals(row.poll().institute())));
    final PollQuery.Result second = filter(filters, 2, 10);
    assertEquals(whole.total(), second.total());
    assertEquals(whole.matching().subList(10, 20), second.page());
    for (int index = 1; index < whole.matching().size(); index++) {
      assertFalse(
          whole
              .matching()
              .get(index - 1)
              .poll()
              .collectionTo()
              .isBefore(whole.matching().get(index).poll().collectionTo()),
          "The newest fieldwork is first");
    }
  }

  @Test
  void aPageBeyondTheLastRowIsEmptyRatherThanAnError() {
    final PollQuery.Result result = filter(PollQuery.Filters.none(), 10_000, 50);
    assertTrue(result.page().isEmpty());
    assertEquals(result.total(), result.matching().size());
  }

  @Test
  void theDownloadCarriesEveryMatchingRowAtSourcePrecision() {
    final PollQuery.Filters filters =
        new PollQuery.Filters(
            LocalDate.of(2016, 6, 1), LocalDate.of(2016, 6, 30), List.of(), List.of(), null, false);
    final PollQuery.Result result = filter(filters, 1, 1);
    final List<String> lines = List.of(PollQuery.csv(result, filters).split("\n", -1));
    assertEquals(String.join(",", PollQuery.CSV_HEADER), lines.getFirst());
    assertEquals(1, result.page().size(), "The table pages");
    assertEquals(result.total() + 2, lines.size(), "The download does not page");
    for (final PollQuery.Row row : result.matching()) {
      for (final String component : PollQuery.COMPONENTS) {
        final java.math.BigDecimal share = row.poll().shares().get(component);
        if (share != null) {
          assertTrue(
              lines.stream().anyMatch(line -> line.contains("," + share.toPlainString() + ",")),
              component + " lost its archived precision");
        }
      }
    }
  }

  @Test
  void aPartySelectionCutsTheSameColumnsFromTheTableAndTheDownload() {
    final PollQuery.Filters filters =
        new PollQuery.Filters(
            LocalDate.of(2016, 6, 1),
            LocalDate.of(2016, 6, 30),
            List.of(),
            List.of("S", "SD"),
            null,
            false);
    assertEquals(List.of("S", "SD"), filters.selectedComponents());
    final String header = PollQuery.csv(filter(filters, 1, 50), filters).split("\n", -1)[0];
    assertTrue(header.contains(",S,SD,"));
    assertFalse(header.contains(",M,"));
    assertFalse(header.contains(",FI,"));
  }

  @Test
  void aMissingShareStaysEmptyRatherThanBecomingZero() {
    final PollQuery.Filters filters =
        new PollQuery.Filters(null, null, List.of(), List.of(), null, true);
    final PollQuery.Result result = filter(filters, 1, 1);
    final PollQuery.Row missing =
        result.matching().stream()
            .filter(row -> row.poll().shares().get("FI") == null)
            .findFirst()
            .orElseThrow();
    final String csv = PollQuery.csv(result, filters);
    final String line =
        csv.lines()
            .filter(entry -> entry.startsWith(text(missing.poll().publicationDate())))
            .filter(entry -> entry.contains(missing.poll().institute()))
            .findFirst()
            .orElseThrow();
    assertTrue(line.contains(",,"), "An unreported share is empty, never filled");
  }

  @Test
  void institutesCarryTheirCompaniesMethodErasAndCollectionSpan() {
    final PollQuery.Institute inizio = PollQuery.institutes(polls).get("Inizio");
    assertNotNull(inizio);
    assertTrue(inizio.polls() > 0);
    assertTrue(inizio.companies().contains("Inizio"));
    assertTrue(inizio.methodEras().containsKey("inizio_continuation"));
    assertFalse(inizio.firstCollection().isAfter(inizio.lastCollection()));
  }

  private static String text(LocalDate date) {
    return date == null ? "" : date.toString();
  }
}
