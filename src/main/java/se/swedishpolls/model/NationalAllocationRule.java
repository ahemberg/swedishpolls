package se.swedishpolls.model;

import java.util.List;

/** One election's approved national allocation rule. */
public record NationalAllocationRule(
    int electionYear,
    int seats,
    double thresholdPercent,
    boolean thresholdInclusive,
    double firstDivisor,
    String subsequentDivisorFormula,
    List<String> tieOrder,
    boolean otherReceivesSeats,
    boolean constituencyExceptionsIncluded,
    String officialTieRule,
    String sourceUrl) {
  public NationalAllocationRule {
    tieOrder = List.copyOf(tieOrder);
    if (seats < 1
        || !Double.isFinite(thresholdPercent)
        || thresholdPercent < 0
        || !Double.isFinite(firstDivisor)
        || firstDivisor <= 0
        || tieOrder.isEmpty()
        || tieOrder.stream().distinct().count() != tieOrder.size()
        || otherReceivesSeats
        || constituencyExceptionsIncluded) {
      throw new IllegalArgumentException("Inadmissible national allocation rules");
    }
  }

  @Override
  public List<String> tieOrder() {
    return List.copyOf(tieOrder);
  }

  /** The majority line: more than half of the seats, which is 175 of 349. */
  public int majoritySeats() {
    return seats / 2 + 1;
  }
}
