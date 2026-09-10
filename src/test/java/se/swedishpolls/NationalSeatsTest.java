package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NationalSeatsTest {
  private static final List<String> TIE_ORDER =
      List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI");
  private static final List<String> COMPONENTS =
      List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER");

  @Test
  void exactlyFourPercentQualifiesAndAnUnroundedShareBelowItDoesNot() {
    final NationalSeats.Rules rules = rules(2026, 1.2);
    assertFalse(NationalSeats.qualifies(3.9999, rules));
    assertTrue(NationalSeats.qualifies(4.0, rules));
    assertTrue(NationalSeats.qualifies(Math.nextUp(4.0), rules));
    // Rounding 3.95 for display would show 4.0; eligibility reads the unrounded share.
    assertFalse(NationalSeats.qualifies(3.95, rules));

    final NationalSeats.Allocation allocation =
        NationalSeats.allocate(shares(30, 30, 12, 10, 6, 4, 3.95, 4.05), rules);
    assertEquals(List.of("S", "M", "SD", "V", "C", "KD", "MP"), allocation.qualified());
    assertEquals(0, allocation.of("L"));
    assertTrue(allocation.of("MP") > 0);
    assertEquals(349, allocation.total());
  }

  @Test
  void otherIsNeverAllocatedSeatsAndIsNamedAsExcluded() {
    final NationalSeats.Allocation allocation =
        NationalSeats.allocate(shares(30, 25, 20, 5, 5, 5, 5, 4), rules(2026, 1.2));
    assertFalse(allocation.seats().containsKey("OTHER"));
    assertTrue(allocation.excluded().contains("OTHER"));
    assertEquals(349, allocation.total());
  }

  @Test
  void eachElectionAllocatesUnderItsOwnEraDivisorAndBothErasTotal349() {
    final Map<String, Double> official = official2014();
    final Map<String, Integer> era = NationalSeats.allocate(official, rules(2014, 1.4)).seats();
    final Map<String, Integer> later = NationalSeats.allocate(official, rules(2018, 1.2)).seats();
    assertEquals(349, era.values().stream().mapToInt(Integer::intValue).sum());
    assertEquals(349, later.values().stream().mapToInt(Integer::intValue).sum());
    assertEquals(
        Map.of("S", 112, "M", 85, "SD", 47, "V", 21, "C", 22, "KD", 17, "L", 20, "MP", 25), era);
    // Over a 349-seat board the two divisors agree here: the first divisor only ranks a party's
    // first seat, and every party above 4% nationally wins well over one. The reduced divisor is
    // a constituency-sized effect, which is exactly what the national approximation omits.
    assertEquals(era, later);
  }

  @Test
  void theEraDivisorDecidesSeatsOnAConstituencySizedBoard() {
    final Map<String, Double> shares = Map.of("S", 40.0, "M", 30.0, "SD", 20.0, "V", 10.0);
    assertEquals(
        Map.of("S", 3, "M", 2, "SD", 1, "V", 0),
        NationalSeats.allocate(shares, seats(2014, 1.4, 6)).seats());
    assertEquals(
        Map.of("S", 2, "M", 2, "SD", 1, "V", 1),
        NationalSeats.allocate(shares, seats(2018, 1.2, 6)).seats());
  }

  @Test
  void anExactTieGoesToTheEarlierPartyOfTheFixedOrderAndIsCounted() {
    final NationalSeats.Rules order = small(List.of("A", "B"), 3);
    final NationalSeats.Allocation allocation =
        NationalSeats.allocate(Map.of("A", 50.0, "B", 50.0), order);
    assertEquals(Map.of("A", 2, "B", 1), allocation.seats());
    assertTrue(allocation.tieBrokenSeats() > 0, "A 50/50 split decides seats by the party order");

    final NationalSeats.Rules reversed = small(List.of("B", "A"), 3);
    assertEquals(
        Map.of("B", 2, "A", 1),
        NationalSeats.allocate(Map.of("A", 50.0, "B", 50.0), reversed).seats());
    assertEquals(
        0,
        NationalSeats.allocate(Map.of("A", 60.0, "B", 40.0), order).tieBrokenSeats(),
        "Strictly ordered quotients need no tie rule");
  }

  @Test
  void theFirstDivisorAppliesOnlyToAPartyWithNoSeatYet() {
    final NationalSeats.Rules modified = small(List.of("A", "B"), 2);
    assertEquals(
        Map.of("A", 2, "B", 0),
        NationalSeats.allocate(Map.of("A", 70.0, "B", 25.0), modified).seats());
    final NationalSeats.Rules unmodified =
        new NationalSeats.Rules(
            2026,
            2,
            4,
            true,
            1.0,
            "2 * seats_already_allocated + 1",
            List.of("A", "B"),
            false,
            false,
            "lottery",
            "https://www.val.se/");
    assertEquals(
        Map.of("A", 1, "B", 1),
        NationalSeats.allocate(Map.of("A", 70.0, "B", 25.0), unmodified).seats());
  }

  @Test
  void noQualifyingPartyStopsTheAllocationRatherThanReturningAnEmptyRiksdag() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NationalSeats.allocate(Map.of("S", 3.0, "M", 2.0), rules(2026, 1.2)));
    assertThrows(
        IllegalArgumentException.class,
        () -> NationalSeats.allocate(Map.of("S", Double.NaN), rules(2026, 1.2)));
  }

  @Test
  void pointSeatsComeFromTheMeanSupportAndPosteriorMeanSeatsFromTheDrawnAllocations() {
    // L averages exactly the threshold, but clears it in only half the draws.
    final NationalSeats.SeatDraws drawn =
        draws(
            rules(2026, 1.2),
            new double[][] {
              {30, 25, 20, 6, 5, 5, 4.5, 3.0, 1.5}, {30, 25, 20, 6, 5, 5, 3.5, 4.0, 1.5}
            });
    final NationalSeats.Summary summary = NationalSeats.summarize(drawn, 0.95);
    final NationalSeats.PartySeats liberals =
        summary.parties().stream()
            .filter(party -> party.component().equals("L"))
            .findFirst()
            .orElseThrow();
    assertEquals(4.0, liberals.meanSupport(), 1e-12);
    assertTrue(liberals.pointSeats() > 0, "The mean support reaches the inclusive threshold");
    assertEquals(0.5, liberals.thresholdProbability(), 1e-12);
    assertNotEquals(
        (double) liberals.pointSeats(),
        liberals.meanSeats(),
        "Point seats and posterior mean seats answer different questions");
    assertEquals(0, liberals.lowerSeats(), "Half the draws leave L out of the Riksdag");
    assertEquals(349, summary.totalPointSeats());
    assertEquals(349, Math.round(summary.totalMeanSeats()));
    assertEquals(List.of("OTHER"), summary.excludedFromAllocation());
  }

  @Test
  void aPartyOutsideTheValidatedRosterHasNoSeatEstimateRatherThanZero() {
    final NationalSeats.Summary summary =
        NationalSeats.summarize(
            draws(rules(2026, 1.2), new double[][] {{30, 25, 20, 6, 5, 5, 4.5, 3.0, 1.5}}), 0.95);
    assertEquals(
        List.of(new NationalSeats.Unavailable("FI", NationalSeats.NO_VALIDATED_PERIOD)),
        summary.unavailable());
    assertTrue(summary.parties().stream().noneMatch(party -> party.component().equals("FI")));
  }

  @Test
  void aSeatIntervalRoundsOutwardBecauseASeatCountIsAWholeNumber() {
    final int[] seats = {10, 11, 12, 13, 14};
    assertEquals(10, NationalSeats.seatQuantile(seats, 0.025, false));
    assertEquals(14, NationalSeats.seatQuantile(seats, 0.975, true));
    assertEquals(11, NationalSeats.seatQuantile(seats, 0.3, false));
    assertEquals(13, NationalSeats.seatQuantile(seats, 0.6, true));
  }

  @Test
  void theMajorityLineIsMoreThanHalfTheSeatsAndCountsAnExactTotal() {
    assertEquals(175, rules(2026, 1.2).majoritySeats());
    assertEquals(2.0 / 3, NationalSeats.probabilityAtOrAbove(new int[] {174, 175, 176}, 175));
    assertEquals(
        2.0 / 3,
        NationalSeats.probabilityAtOrAbove(
            new double[] {Math.nextDown(4.0), 4.0, Math.nextUp(4.0)}, 4.0));
  }

  @Test
  void publishedProbabilitiesAreWholePercentAndNeverZeroOrOneHundred() {
    assertEquals("<1%", NationalSeats.percent(0));
    assertEquals("<1%", NationalSeats.percent(0.0001));
    assertEquals("<1%", NationalSeats.percent(0.004));
    assertEquals("1%", NationalSeats.percent(0.005));
    assertEquals("42%", NationalSeats.percent(0.4213));
    assertEquals("99%", NationalSeats.percent(0.994));
    assertEquals(">99%", NationalSeats.percent(0.996));
    assertEquals(">99%", NationalSeats.percent(1));
    assertThrows(IllegalArgumentException.class, () -> NationalSeats.percent(1.5));
  }

  private static NationalSeats.SeatDraws draws(NationalSeats.Rules rules, double[][] transformed) {
    return NationalSeats.allocateDraws(
        "eight_party_2010", LocalDate.of(2021, 9, 20), COMPONENTS, transformed, 1L, rules);
  }

  private static Map<String, Double> shares(
      double s, double m, double sd, double v, double c, double kd, double l, double mp) {
    final Map<String, Double> shares = new LinkedHashMap<>();
    shares.put("S", s);
    shares.put("M", m);
    shares.put("SD", sd);
    shares.put("V", v);
    shares.put("C", c);
    shares.put("KD", kd);
    shares.put("L", l);
    shares.put("MP", mp);
    shares.put("OTHER", 100 - s - m - sd - v - c - kd - l - mp);
    return shares;
  }

  private static Map<String, Double> official2014() {
    final Map<String, Double> shares = new LinkedHashMap<>();
    final double valid = 6231573;
    shares.put("S", 100 * 1932711 / valid);
    shares.put("M", 100 * 1453517 / valid);
    shares.put("SD", 100 * 801178 / valid);
    shares.put("V", 100 * 356331 / valid);
    shares.put("C", 100 * 380937 / valid);
    shares.put("KD", 100 * 284806 / valid);
    shares.put("L", 100 * 337773 / valid);
    shares.put("MP", 100 * 429275 / valid);
    shares.put("FI", 100 * 194719 / valid);
    return shares;
  }

  static NationalSeats.Rules rules(int electionYear, double firstDivisor) {
    return seats(electionYear, firstDivisor, 349);
  }

  private static NationalSeats.Rules seats(int electionYear, double firstDivisor, int seats) {
    return new NationalSeats.Rules(
        electionYear,
        seats,
        4,
        true,
        firstDivisor,
        "2 * seats_already_allocated + 1",
        TIE_ORDER,
        false,
        false,
        "lottery",
        "https://www.val.se/");
  }

  private static NationalSeats.Rules small(List<String> tieOrder, int seats) {
    return new NationalSeats.Rules(
        2026,
        seats,
        4,
        true,
        1.2,
        "2 * seats_already_allocated + 1",
        tieOrder,
        false,
        false,
        "lottery",
        "https://www.val.se/");
  }
}
