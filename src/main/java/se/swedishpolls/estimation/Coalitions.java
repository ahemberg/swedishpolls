package se.swedishpolls.estimation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.swedishpolls.model.NationalAllocationRule;

/**
 * The ten preset coalitions, summarized from the same joint draws as the seat allocation. A preset
 * names which parties are counted together and nothing else: it states membership and implies no
 * agreement between those parties to govern together.
 */
public final class Coalitions {
  private Coalitions() {}

  /** A preset's key and the parties it counts. */
  public record Preset(String id, List<String> parties) {
    public Preset {
      parties = List.copyOf(parties);
      if (id == null || id.isBlank() || parties.isEmpty()) {
        throw new IllegalArgumentException("A preset needs a key and at least one party");
      }
    }

    @Override
    public List<String> parties() {
      return List.copyOf(parties);
    }
  }

  /**
   * The approved catalogue, in the order of the handoff table. Opposition and c_l_mp_s are distinct
   * presets: the first counts V and the second counts L.
   */
  public static final List<Preset> PRESETS =
      List.of(
          new Preset("left", List.of("S", "V", "MP")),
          new Preset("right", List.of("M", "L", "C", "KD")),
          new Preset("tido", List.of("M", "L", "KD", "SD")),
          new Preset("opposition", List.of("S", "C", "V", "MP")),
          new Preset("s_c_mp", List.of("S", "C", "MP")),
          new Preset("s_m", List.of("S", "M")),
          new Preset("c_l_mp_s", List.of("C", "L", "MP", "S")),
          new Preset("m_kd_sd", List.of("M", "KD", "SD")),
          new Preset("s_sd", List.of("S", "SD")),
          new Preset("m_sd", List.of("M", "SD")));

  /** The presets the overview shows before a visitor chooses anything. */
  public static final List<String> OVERVIEW_DEFAULTS = List.of("tido", "opposition", "left", "s_m");

  /** What a coalition label means, stated wherever the catalogue is published. */
  public static final String MEMBERSHIP_NOTE =
      "A label states which parties are counted together and implies no agreement to govern"
          + " together. Probabilities come from the same joint draws as the seat allocation.";

  /** How an exact tie between two compared coalitions is counted. */
  public static final String TIE_OUTCOME = "neither_side_wins";

  /** One coalition's seats on the summarized day. */
  public record Seats(
      String id,
      List<String> parties,
      int pointSeats,
      double meanSeats,
      int lowerSeats,
      int upperSeats,
      double majorityProbability) {
    public Seats {
      parties = List.copyOf(parties);
    }

    @Override
    public List<String> parties() {
      return List.copyOf(parties);
    }
  }

  /**
   * One pair of the comparison view. The three probabilities are counted over the same draws and
   * sum to one, and an exact tie is counted in {@code tied} rather than credited to either side.
   */
  public record Comparison(
      String left, String right, double leftLeads, double rightLeads, double tied) {}

  /** One period's final-day coalition summary. */
  public record Result(
      String periodId,
      LocalDate date,
      int majoritySeats,
      double intervalLevel,
      int draws,
      long daySeed,
      List<String> overviewDefaults,
      List<Seats> coalitions,
      List<Comparison> comparison,
      String note,
      String tie) {
    public Result {
      overviewDefaults = List.copyOf(overviewDefaults);
      coalitions = List.copyOf(coalitions);
      comparison = List.copyOf(comparison);
    }

    @Override
    public List<String> overviewDefaults() {
      return List.copyOf(overviewDefaults);
    }

    @Override
    public List<Seats> coalitions() {
      return List.copyOf(coalitions);
    }

    @Override
    public List<Comparison> comparison() {
      return List.copyOf(comparison);
    }

    public Seats coalition(String id) {
      return coalitions.stream()
          .filter(seats -> seats.id().equals(id))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("No coalition " + id));
    }
  }

  /** A preset by key, so a caller never has to restate a membership. */
  public static Preset preset(String id) {
    return PRESETS.stream()
        .filter(preset -> preset.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No coalition preset " + id));
  }

  /**
   * Summarizes every preset over one day's drawn allocations. A coalition's seats are summed inside
   * each draw before anything is averaged or cut into quantiles, so the total carries how the
   * parties covary rather than the sum of separate marginal summaries.
   */
  public static Result summarize(NationalSeats.SeatDraws seatDraws, double intervalLevel) {
    if (!Double.isFinite(intervalLevel) || intervalLevel <= 0 || intervalLevel >= 1) {
      throw new IllegalArgumentException("Inadmissible interval level " + intervalLevel);
    }
    final NationalAllocationRule rules = seatDraws.rules();
    final NationalSeats.Allocation point = NationalSeats.allocate(seatDraws.meanSupport(), rules);
    final Map<String, int[]> drawn = new LinkedHashMap<>();
    final List<Seats> summaries = new ArrayList<>();
    for (final Preset preset : PRESETS) {
      final int[] totals = seatDraws.totals(preset.parties());
      drawn.put(preset.id(), totals);
      long total = 0;
      for (final int value : totals) {
        total += value;
      }
      summaries.add(
          new Seats(
              preset.id(),
              preset.parties(),
              point.of(preset.parties()),
              (double) total / totals.length,
              NationalSeats.seatQuantile(totals, (1 - intervalLevel) / 2, false),
              NationalSeats.seatQuantile(totals, (1 + intervalLevel) / 2, true),
              NationalSeats.probabilityAtOrAbove(totals, rules.majoritySeats())));
    }
    return new Result(
        seatDraws.periodId(),
        seatDraws.date(),
        rules.majoritySeats(),
        intervalLevel,
        seatDraws.count(),
        seatDraws.daySeed(),
        OVERVIEW_DEFAULTS,
        summaries,
        compare(drawn),
        MEMBERSHIP_NOTE,
        TIE_OUTCOME);
  }

  /**
   * Every pair of the catalogue, counted draw by draw. Both directions of a pair are on its row, so
   * the full view answers an ordered question without a second pass over the draws.
   */
  private static List<Comparison> compare(Map<String, int[]> drawn) {
    final List<String> ids = List.copyOf(drawn.keySet());
    final List<Comparison> pairs = new ArrayList<>();
    for (int left = 0; left < ids.size(); left++) {
      for (int right = left + 1; right < ids.size(); right++) {
        final int[] here = drawn.get(ids.get(left));
        final int[] there = drawn.get(ids.get(right));
        long leftLeads = 0;
        long rightLeads = 0;
        long tied = 0;
        for (int draw = 0; draw < here.length; draw++) {
          if (here[draw] > there[draw]) {
            leftLeads++;
          } else if (here[draw] < there[draw]) {
            rightLeads++;
          } else {
            tied++;
          }
        }
        pairs.add(
            new Comparison(
                ids.get(left),
                ids.get(right),
                (double) leftLeads / here.length,
                (double) rightLeads / here.length,
                (double) tied / here.length));
      }
    }
    return List.copyOf(pairs);
  }
}
