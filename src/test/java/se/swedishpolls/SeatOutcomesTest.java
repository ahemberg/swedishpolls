package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeatOutcomesTest {
  private static final List<String> COMPONENTS =
      List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER");

  @Test
  void headlineProbabilitiesCoverEveryModeledPartyAndEveryPreset() {
    final SeatOutcomes.Headline headline = SeatOutcomes.headline(draws(4.5));
    assertEquals(
        List.of("S", "M", "SD", "V", "C", "KD", "L", "MP"),
        List.copyOf(headline.thresholdProbabilities().keySet()));
    assertEquals(10, headline.majorityProbabilities().size());
    assertTrue(headline.majorityProbabilities().containsKey("tido"));
  }

  @Test
  void precisionReportsTheSpreadOfEveryHeadlineProbabilityAcrossSeeds() {
    final List<SeatOutcomes.Precision> precision =
        SeatOutcomes.precision(List.of(draws(4.5), draws(3.5)));
    final SeatOutcomes.Precision liberals =
        precision.stream()
            .filter(entry -> entry.quantity().equals("threshold:L"))
            .findFirst()
            .orElseThrow();
    assertEquals(0.0, liberals.minimum(), 1e-12);
    assertEquals(1.0, liberals.maximum(), 1e-12);
    assertEquals(1.0, liberals.spread(), 1e-12);
    assertEquals(2, liberals.seeds());
    assertEquals(1, liberals.drawsPerSeed());
    assertTrue(precision.stream().anyMatch(entry -> entry.quantity().equals("majority:tido")));
    assertThrows(IllegalArgumentException.class, () -> SeatOutcomes.precision(List.of(draws(4.5))));
  }

  @Test
  void aSensitivityDifferenceIsInPercentagePointsAndOverTenPointsIsDisclosed() {
    final SeatOutcomes.Headline published = SeatOutcomes.headline(draws(4.5));
    final SeatOutcomes.Sensitivity moved =
        SeatOutcomes.sensitivity(
            "centering", SeatOutcomes.POLL_COUNT, published, SeatOutcomes.headline(draws(3.5)));
    assertEquals(-100.0, moved.thresholdDifferencePoints().get("L"), 1e-12);
    assertEquals(100.0, moved.maxAbsoluteDifferencePoints(), 1e-12);
    assertEquals("threshold:L", moved.largestMovement());
    assertTrue(moved.needsDisclosure());
    assertEquals(
        List.of(
            "threshold:L moves 100 percentage points under "
                + SeatOutcomes.POLL_COUNT
                + ", which is disclosed beside the number."),
        SeatOutcomes.disclosures(List.of(moved)));

    final SeatOutcomes.Sensitivity steady =
        SeatOutcomes.sensitivity("centering", SeatOutcomes.POLL_COUNT, published, published);
    assertEquals(0.0, steady.maxAbsoluteDifferencePoints(), 1e-12);
    assertFalse(steady.needsDisclosure());
    assertEquals(List.of(), SeatOutcomes.disclosures(List.of(steady)));
  }

  @Test
  void theNationalApproximationReproducesTheOfficial2018And2022Allocations() {
    for (final int year : List.of(2018, 2022)) {
      final SeatOutcomes.OfficialComparison comparison =
          SeatOutcomes.compare(official(year), NationalSeatsTest.rules(year, 1.2));
      assertEquals(349, comparison.totalApproximated());
      assertEquals(349, comparison.totalOfficial());
      assertTrue(
          comparison.matchesOfficial(),
          year + " differs from the official allocation: " + comparison.differenceSeats());
      assertTrue(
          comparison.differenceSeats().values().stream().allMatch(difference -> difference == 0));
    }
  }

  @Test
  void theOlderElectionsDifferFromTheOfficialAllocationByTheConstituencyRulesItOmits() {
    final SeatOutcomes.OfficialComparison twentyTen =
        SeatOutcomes.compare(official(2010), NationalSeatsTest.rules(2010, 1.4));
    assertEquals(349, twentyTen.totalApproximated());
    assertFalse(twentyTen.matchesOfficial());
    assertEquals(3, twentyTen.maxAbsoluteDifference());
    assertEquals(-3, twentyTen.differenceSeats().get("S"));

    final SeatOutcomes.OfficialComparison twentyFourteen =
        SeatOutcomes.compare(official(2014), NationalSeatsTest.rules(2014, 1.4));
    assertEquals(2, twentyFourteen.maxAbsoluteDifference());
    assertEquals(-2, twentyFourteen.differenceSeats().get("SD"));
    // The differences cancel, because the approximation still hands out exactly 349 seats.
    assertEquals(
        0, twentyFourteen.differenceSeats().values().stream().mapToInt(Integer::intValue).sum());
  }

  @Test
  void anOfficialResultIsAllocatedUnderItsOwnElectionRule() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SeatOutcomes.compare(official(2014), NationalSeatsTest.rules(2018, 1.2)));
  }

  private static NationalSeats.SeatDraws draws(double liberals) {
    return NationalSeats.allocateDraws(
        "eight_party_2010",
        LocalDate.of(2021, 9, 20),
        COMPONENTS,
        new double[][] {{30.0, 25.0, 20.0, 6.0, 5.0, 5.0, liberals, 8.0 - liberals, 1.0}},
        1L,
        NationalSeatsTest.rules(2026, 1.2));
  }

  /** The official national outcomes stored in the election-reference migration. */
  private static SeatOutcomes.OfficialResult official(int year) {
    return switch (year) {
      case 2010 ->
          result(
              2010,
              LocalDate.of(2010, 9, 19),
              5960408,
              new long[] {1827497, 1791766, 339610, 334053, 390804, 333696, 420524, 437435, 24139},
              new int[] {112, 107, 20, 19, 23, 19, 24, 25, 0});
      case 2014 ->
          result(
              2014,
              LocalDate.of(2014, 9, 14),
              6231573,
              new long[] {1932711, 1453517, 801178, 356331, 380937, 284806, 337773, 429275, 194719},
              new int[] {113, 84, 49, 21, 22, 16, 19, 25, 0});
      case 2018 ->
          result(
              2018,
              LocalDate.of(2018, 9, 9),
              6476725,
              new long[] {1830386, 1284698, 1135627, 518454, 557500, 409478, 355546, 285899, 29665},
              new int[] {100, 70, 62, 28, 31, 22, 20, 16, 0});
      default ->
          result(
              2022,
              LocalDate.of(2022, 9, 11),
              6477970,
              new long[] {1964474, 1237428, 1330325, 437050, 434945, 345712, 298542, 329242, 3157},
              new int[] {107, 68, 73, 24, 24, 19, 16, 18, 0});
    };
  }

  private static SeatOutcomes.OfficialResult result(
      int year, LocalDate date, long validVotes, long[] votes, int[] officialSeats) {
    final List<String> components = List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI");
    final Map<String, Double> shares = new LinkedHashMap<>();
    final Map<String, Integer> seats = new LinkedHashMap<>();
    for (int index = 0; index < components.size(); index++) {
      shares.put(components.get(index), 100.0 * votes[index] / validVotes);
      seats.put(components.get(index), officialSeats[index]);
    }
    return new SeatOutcomes.OfficialResult(year, date, shares, seats);
  }
}
