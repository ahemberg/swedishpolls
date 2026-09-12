package se.swedishpolls.estimation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.swedishpolls.model.NationalAllocationRule;

/**
 * The national seat approximation: 349 seats distributed over the parties that reach the national
 * threshold, under the rules of the election being approximated. It reads national shares only, so
 * it omits every constituency rule, and its ties are broken by a fixed party order where the
 * official process draws lots.
 */
public final class NationalSeats {
  /** The reason a component of the tie order has no seat estimate in a period. */
  public static final String NO_VALIDATED_PERIOD = "no_validated_coverage_period";

  /** What this approximation is, stated wherever its numbers are published. */
  public static final String APPROXIMATION_NOTE =
      "A national seat approximation from national shares. It omits constituency rules, including"
          + " the 12% constituency exception, the split into 310 fixed and 39 adjustment seats and"
          + " the return of excess fixed seats, and it is not an official allocation.";

  /** What the deterministic tie order is, stated wherever a tie decided a seat. */
  public static final String TIE_NOTE =
      "An exact quotient tie is resolved by the fixed party order recorded with the rule, which"
          + " makes a run reproducible. The official rule draws lots, so this is an approximation of"
          + " that rule and not the legal tie procedure.";

  /**
   * One allocation. {@code qualified} lists the parties the threshold admitted in tie order and
   * {@code excluded} the components it kept out, so OTHER is visibly never a party here.
   */
  public record Allocation(
      Map<String, Integer> seats,
      List<String> qualified,
      List<String> excluded,
      int tieBrokenSeats) {
    public Allocation {
      seats = ordered(seats);
      qualified = List.copyOf(qualified);
      excluded = List.copyOf(excluded);
    }

    @Override
    public Map<String, Integer> seats() {
      return ordered(seats);
    }

    @Override
    public List<String> qualified() {
      return List.copyOf(qualified);
    }

    @Override
    public List<String> excluded() {
      return List.copyOf(excluded);
    }

    public int total() {
      return seats.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int of(String component) {
      return seats.getOrDefault(component, 0);
    }

    public int of(List<String> components) {
      return components.stream().mapToInt(this::of).sum();
    }
  }

  private static Map<String, Integer> ordered(Map<String, Integer> seats) {
    return Collections.unmodifiableMap(new LinkedHashMap<>(seats));
  }

  private NationalSeats() {}

  /** Whether an unrounded share reaches the national threshold. Exactly 4% qualifies. */
  public static boolean qualifies(double share, NationalAllocationRule rules) {
    if (!Double.isFinite(share) || share < 0) {
      throw new IllegalArgumentException("Inadmissible national support " + share);
    }
    return rules.thresholdInclusive()
        ? share >= rules.thresholdPercent()
        : share > rules.thresholdPercent();
  }

  /**
   * The modified Sainte-Lague allocation of one composition. Shares are compared unrounded, so a
   * displayed rounding never decides eligibility, and a component outside the tie order, OTHER
   * above all, is never allocated a seat.
   */
  public static Allocation allocate(Map<String, Double> shares, NationalAllocationRule rules) {
    final List<String> excluded = new ArrayList<>();
    final Map<String, Double> qualified = new LinkedHashMap<>();
    for (final String component : rules.tieOrder()) {
      final Double share = shares.get(component);
      if (share == null) {
        continue;
      }
      if (qualifies(share, rules)) {
        qualified.put(component, share);
      } else {
        excluded.add(component);
      }
    }
    for (final String component : shares.keySet()) {
      if (!rules.tieOrder().contains(component)) {
        excluded.add(component);
      }
    }
    final Distributed distributed = distribute(qualified, rules.seats(), rules.firstDivisor());
    return new Allocation(
        distributed.seats(),
        List.copyOf(qualified.keySet()),
        excluded,
        distributed.tieBrokenSeats());
  }

  /** A distribution and how many of its seats the tie order rather than a quotient decided. */
  record Distributed(Map<String, Integer> seats, int tieBrokenSeats) {}

  /**
   * The seat counts alone, over components already admitted by the threshold and already in tie
   * order. The first quotient uses the era's first divisor and every later one {@code 2 * held +
   * 1}; on an exact tie the earlier component of the iteration order wins.
   */
  static Distributed distribute(Map<String, Double> qualified, int seats, double firstDivisor) {
    if (qualified.isEmpty()) {
      throw new IllegalArgumentException("No party reaches the national threshold");
    }
    final String[] components = qualified.keySet().toArray(String[]::new);
    final double[] shares = new double[components.length];
    final int[] held = new int[components.length];
    for (int index = 0; index < components.length; index++) {
      shares[index] = qualified.get(components[index]);
    }
    int tieBroken = 0;
    // ponytail: O(parties * seats) is bounded by 9 * 349; use a heap only if the rule grows.
    for (int allocated = 0; allocated < seats; allocated++) {
      int winner = 0;
      double best = -1;
      boolean tied = false;
      for (int index = 0; index < components.length; index++) {
        final double quotient =
            shares[index] / (held[index] == 0 ? firstDivisor : 2.0 * held[index] + 1);
        if (quotient > best) {
          best = quotient;
          winner = index;
          tied = false;
        } else if (Double.compare(quotient, best) == 0) {
          // An exact tie is the case the fixed party order exists for, so it is counted rather
          // than tolerated: the earlier component already holds the seat.
          tied = true;
        }
      }
      if (tied) {
        tieBroken++;
      }
      held[winner]++;
    }
    final Map<String, Integer> result = new LinkedHashMap<>();
    for (int index = 0; index < components.length; index++) {
      result.put(components[index], held[index]);
    }
    return new Distributed(result, tieBroken);
  }

  /**
   * Every joint draw allocated on its own, one row per draw and one column per component of the
   * period's roster. Seat quantities are summarized from these rows, so a coalition's seats are
   * summed inside a draw and never assembled from separate marginal summaries.
   */
  public static final class SeatDraws {
    private final String periodId;
    private final LocalDate date;
    private final List<String> components;
    private final NationalAllocationRule rules;
    private final long daySeed;
    private final int[][] seats;
    private final double[] meanShares;
    private final int[] cleared;

    private SeatDraws(
        String periodId,
        LocalDate date,
        List<String> components,
        NationalAllocationRule rules,
        long daySeed,
        int[][] seats,
        double[] meanShares,
        int[] cleared) {
      this.periodId = periodId;
      this.date = date;
      this.components = List.copyOf(components);
      this.rules = rules;
      this.daySeed = daySeed;
      this.seats = seats;
      this.meanShares = meanShares;
      this.cleared = cleared;
    }

    public String periodId() {
      return periodId;
    }

    public LocalDate date() {
      return date;
    }

    public List<String> components() {
      return List.copyOf(components);
    }

    public NationalAllocationRule rules() {
      return rules;
    }

    /** The seed of the day's draw stream, which is what a rerun repeats. */
    public long daySeed() {
      return daySeed;
    }

    public int count() {
      return seats.length;
    }

    public int seats(int draw, String component) {
      final int column = components.indexOf(component);
      return column < 0 ? 0 : seats[draw][column];
    }

    /** The published point estimate of a component: the mean of its transformed draws. */
    public double meanSupport(String component) {
      final int column = components.indexOf(component);
      return column < 0 ? 0 : meanShares[column];
    }

    public Map<String, Double> meanSupport() {
      final Map<String, Double> shares = new LinkedHashMap<>();
      for (int column = 0; column < components.size(); column++) {
        shares.put(components.get(column), meanShares[column]);
      }
      return Collections.unmodifiableMap(shares);
    }

    /**
     * The chance of reaching the threshold nationally, counted over the same draws the allocation
     * reads. It is national eligibility, not the full legal chance of entering the Riksdag.
     */
    public double thresholdProbability(String component) {
      final int column = components.indexOf(component);
      return column < 0 ? 0 : (double) cleared[column] / seats.length;
    }

    /** One draw's seats for a set of components, summed inside that draw. */
    public int[] totals(List<String> members) {
      final int[] columns = members.stream().mapToInt(components::indexOf).toArray();
      final int[] totals = new int[seats.length];
      for (int draw = 0; draw < seats.length; draw++) {
        int total = 0;
        for (final int column : columns) {
          if (column >= 0) {
            total += seats[draw][column];
          }
        }
        totals[draw] = total;
      }
      return totals;
    }
  }

  /**
   * Allocates every retained joint draw. The seat total is checked on each draw, so a draw that
   * misses it stops the run rather than being summarized.
   */
  public static SeatDraws allocateDraws(
      JointUncertainty.Draws draws, NationalAllocationRule rules) {
    final double[][] shares = new double[draws.count()][draws.components().size()];
    for (int draw = 0; draw < shares.length; draw++) {
      for (int column = 0; column < shares[draw].length; column++) {
        shares[draw][column] = draws.shares().get(draw, column);
      }
    }
    return allocateDraws(
        draws.periodId(), draws.date(), draws.components(), shares, draws.daySeed(), rules);
  }

  /**
   * The same allocation over transformed draws a caller already holds, which is what a sensitivity
   * rerun has: it refits and redraws the same day under one changed assumption.
   */
  public static SeatDraws allocateDraws(
      String periodId,
      LocalDate date,
      List<String> componentNames,
      double[][] transformed,
      long daySeed,
      NationalAllocationRule rules) {
    final List<String> components = List.copyOf(componentNames);
    final int[][] seats = new int[transformed.length][components.size()];
    final double[] means = new double[components.size()];
    final int[] cleared = new int[components.size()];
    for (int draw = 0; draw < transformed.length; draw++) {
      final Map<String, Double> shares = new LinkedHashMap<>();
      for (int column = 0; column < components.size(); column++) {
        final double share = transformed[draw][column];
        shares.put(components.get(column), share);
        means[column] += share;
        if (rules.tieOrder().contains(components.get(column)) && qualifies(share, rules)) {
          cleared[column]++;
        }
      }
      final Allocation allocation = allocate(shares, rules);
      if (allocation.total() != rules.seats()) {
        throw new IllegalStateException("A drawn allocation does not total " + rules.seats());
      }
      for (int column = 0; column < components.size(); column++) {
        seats[draw][column] = allocation.of(components.get(column));
      }
    }
    for (int column = 0; column < means.length; column++) {
      means[column] /= transformed.length;
    }
    return new SeatDraws(periodId, date, components, rules, daySeed, seats, means, cleared);
  }

  /**
   * One party's seats. The integer {@code pointSeats} allocation of the posterior mean support is
   * what a 349-dot hemicycle draws; {@code meanSeats} averages the drawn allocations and is
   * generally fractional. The two answer different questions and are never interchanged.
   */
  public record PartySeats(
      String component,
      double meanSupport,
      int pointSeats,
      double meanSeats,
      int lowerSeats,
      int upperSeats,
      double thresholdProbability) {}

  /** A component of the tie order with no estimate in this period, and the reason there is none. */
  public record Unavailable(String component, String reason) {}

  /** One period's final-day seat summary. */
  public record Summary(
      String periodId,
      LocalDate date,
      NationalAllocationRule rules,
      double intervalLevel,
      int draws,
      long daySeed,
      List<PartySeats> parties,
      List<String> excludedFromAllocation,
      List<Unavailable> unavailable,
      int tieBrokenPointSeats,
      String note,
      String tieNote) {
    public Summary {
      parties = List.copyOf(parties);
      excludedFromAllocation = List.copyOf(excludedFromAllocation);
      unavailable = List.copyOf(unavailable);
    }

    @Override
    public List<PartySeats> parties() {
      return List.copyOf(parties);
    }

    @Override
    public List<String> excludedFromAllocation() {
      return List.copyOf(excludedFromAllocation);
    }

    @Override
    public List<Unavailable> unavailable() {
      return List.copyOf(unavailable);
    }

    public int totalPointSeats() {
      return parties.stream().mapToInt(PartySeats::pointSeats).sum();
    }

    public double totalMeanSeats() {
      return parties.stream().mapToDouble(PartySeats::meanSeats).sum();
    }
  }

  /**
   * Summarizes one period's final day. The point allocation reads the posterior mean support once;
   * the mean seats, the interval and the threshold probability read the drawn allocations. A party
   * can therefore hold seats in the point allocation while its threshold probability stays well
   * below one: clearing 4% and holding a seat in this one allocation are different events.
   */
  public static Summary summarize(SeatDraws seatDraws, double intervalLevel) {
    if (!Double.isFinite(intervalLevel) || intervalLevel <= 0 || intervalLevel >= 1) {
      throw new IllegalArgumentException("Inadmissible interval level " + intervalLevel);
    }
    final NationalAllocationRule rules = seatDraws.rules();
    final Allocation point = allocate(seatDraws.meanSupport(), rules);
    final List<PartySeats> parties = new ArrayList<>();
    final List<Unavailable> unavailable = new ArrayList<>();
    for (final String component : rules.tieOrder()) {
      if (!seatDraws.components().contains(component)) {
        unavailable.add(new Unavailable(component, NO_VALIDATED_PERIOD));
        continue;
      }
      final int[] drawn = seatDraws.totals(List.of(component));
      long total = 0;
      for (final int value : drawn) {
        total += value;
      }
      parties.add(
          new PartySeats(
              component,
              seatDraws.meanSupport(component),
              point.of(component),
              (double) total / drawn.length,
              seatQuantile(drawn, (1 - intervalLevel) / 2, false),
              seatQuantile(drawn, (1 + intervalLevel) / 2, true),
              seatDraws.thresholdProbability(component)));
    }
    final List<String> excluded =
        seatDraws.components().stream().filter(name -> !rules.tieOrder().contains(name)).toList();
    return new Summary(
        seatDraws.periodId(),
        seatDraws.date(),
        rules,
        intervalLevel,
        seatDraws.count(),
        seatDraws.daySeed(),
        parties,
        excluded,
        unavailable,
        point.tieBrokenSeats(),
        APPROXIMATION_NOTE,
        TIE_NOTE);
  }

  /**
   * A seat count is a whole number, so an interval endpoint is an order statistic rather than a
   * value interpolated between two of them. Both endpoints round outward, which keeps the stated
   * interval from being narrower than the draws support.
   */
  static int seatQuantile(int[] seats, double probability, boolean upward) {
    final int[] sorted = seats.clone();
    Arrays.sort(sorted);
    final double position = (sorted.length - 1) * probability;
    final int index =
        upward
            ? (int) Math.min(sorted.length - 1.0, Math.ceil(position))
            : (int) Math.max(0, Math.floor(position));
    return sorted[index];
  }

  /**
   * The share of draws at or above a boundary. The 4% threshold and the 175-seat line include it.
   */
  public static double probabilityAtOrAbove(double[] values, double boundary) {
    if (values.length == 0 || !Double.isFinite(boundary)) {
      throw new IllegalArgumentException("A probability needs finite draws and a boundary");
    }
    long count = 0;
    for (final double value : values) {
      if (!Double.isFinite(value)) {
        throw new IllegalArgumentException("A probability draw is not finite");
      }
      if (value >= boundary) {
        count++;
      }
    }
    return (double) count / values.length;
  }

  /** The same count over drawn seat totals, which is what a majority line is read against. */
  public static double probabilityAtOrAbove(int[] values, int boundary) {
    if (values.length == 0) {
      throw new IllegalArgumentException("A probability needs draws");
    }
    long count = 0;
    for (final int value : values) {
      if (value >= boundary) {
        count++;
      }
    }
    return (double) count / values.length;
  }

  /**
   * A published probability, as whole percent. A finite number of draws cannot establish that an
   * event is impossible or certain, so anything rounding to 0% reads {@code <1%} and anything
   * rounding to 100% reads {@code >99%}. Neither 0% nor 100% is ever published.
   */
  public static String percent(double probability) {
    if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
      throw new IllegalArgumentException("Inadmissible probability " + probability);
    }
    final long rounded = Math.round(probability * 100);
    if (rounded <= 0) {
      return "<1%";
    }
    if (rounded >= 100) {
      return ">99%";
    }
    return rounded + "%";
  }
}
