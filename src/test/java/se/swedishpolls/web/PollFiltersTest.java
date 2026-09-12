package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollQuery;

/**
 * The one filter parser the poll table, the poll page and the download share. Two parsers would
 * drift, and a table that showed different rows from the CSV beside it would be the first symptom.
 */
class PollFiltersTest {
  private static final String PERIOD = "2022-2026";

  private static PollFilters.Parsed parse(
      String from, String to, String institute, String party, String period, String excluded) {
    return PollFilters.parse(from, to, institute, party, period, excluded, PERIOD::equals);
  }

  @Test
  void anEmptyQueryDeclaresNoFilterAtAll() {
    final PollFilters.Parsed parsed = parse("", "", "", "", "", "");
    assertEquals(List.of(), parsed.invalid());
    assertEquals(PollQuery.Filters.none(), parsed.filters());
  }

  @Test
  void listsAreSplitOnCommasAndTrimmed() {
    final PollFilters.Parsed parsed = parse(null, null, " Novus , Ipsos ", "S, M", null, null);
    assertEquals(List.of("Novus", "Ipsos"), parsed.filters().institutes());
    assertEquals(List.of("S", "M"), parsed.filters().parties());
  }

  @Test
  void aDateBoundIsInclusiveAndBothBoundsAreRead() {
    final PollFilters.Parsed parsed = parse("2024-01-01", "2024-12-31", null, null, null, null);
    assertEquals(LocalDate.of(2024, 1, 1), parsed.filters().from());
    assertEquals(LocalDate.of(2024, 12, 31), parsed.filters().to());
    assertEquals(List.of(), parsed.invalid());
  }

  @Test
  void everyRejectedParameterIsNamedRatherThanOnlyTheFirst() {
    final PollFilters.Parsed parsed =
        parse("yesterday", "2024-13-40", null, "XX", "1066-1067", "perhaps");
    assertEquals(
        List.of("coveragePeriod", "from", "to", "party", "includeExcluded"),
        parsed.invalid().stream().map(PollFilters.Invalid::name).toList(),
        parsed.invalid().toString());
  }

  @Test
  void aRangeThatRunsBackwardsIsRejectedOnItsUpperBound() {
    final PollFilters.Parsed parsed = parse("2024-12-31", "2024-01-01", null, null, null, null);
    assertEquals(1, parsed.invalid().size());
    assertEquals("to", parsed.invalid().getFirst().name());
    assertEquals("before_from", parsed.invalid().getFirst().reason());
    assertNull(parsed.filters().to());
  }

  @Test
  void aRejectedParameterLeavesItsFilterUnappliedRatherThanGuessed() {
    final PollFilters.Parsed parsed = parse("yesterday", null, "Novus", "S,XX", null, null);
    assertNull(parsed.filters().from());
    assertEquals(List.of("Novus"), parsed.filters().institutes());
    assertEquals(List.of("S"), parsed.filters().parties());
  }

  @Test
  void theDownloadCarriesEveryDeclaredFilterSoItCannotSelectOtherRows() {
    final PollQuery.Filters filters =
        new PollQuery.Filters(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 12, 31),
            List.of("Novus", "Ipsos"),
            List.of("S"),
            PERIOD,
            true);
    assertEquals(
        "/api/v1/polls.csv?publication=pub_1&from=2024-01-01&to=2024-12-31"
            + "&institute=Novus,Ipsos&party=S&coveragePeriod=2022-2026&includeExcluded=true",
        PollFilters.csvLink("pub_1", filters));
  }

  @Test
  void anUnfilteredDownloadNamesOnlyItsPublication() {
    assertEquals(
        "/api/v1/polls.csv?publication=pub_1",
        PollFilters.csvLink("pub_1", PollQuery.Filters.none()));
  }
}
