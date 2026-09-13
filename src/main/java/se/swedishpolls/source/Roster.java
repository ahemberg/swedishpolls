package se.swedishpolls.source;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups an eligible poll into the modeled components of one coverage period. */
public final class Roster {
  /** Canonical order for coalition memberships and subset-mask bits. */
  public static final List<String> COALITION_PARTIES =
      List.of("S", "M", "SD", "V", "C", "KD", "L", "MP");

  private Roster() {}

  public record CoveragePeriod(
      String id,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      List<String> roster,
      boolean individualFi,
      boolean supportValidated,
      String decisionUrl) {
    public CoveragePeriod {
      roster = List.copyOf(roster);
    }

    /** A poll belongs to the period only when its whole collection window lies inside it. */
    public boolean covers(PollCsv.Poll poll) {
      return poll.collectionFrom() != null
          && poll.collectionTo() != null
          && !poll.collectionFrom().isBefore(effectiveFrom)
          && (effectiveTo == null || !poll.collectionTo().isAfter(effectiveTo));
    }
  }

  /**
   * Components are empty when a reason excludes the poll; the archived source observation is
   * unaffected.
   */
  public record Composition(
      String periodId, Map<String, BigDecimal> components, List<String> exclusionReasons) {
    public Composition {
      components = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(components));
      exclusionReasons = List.copyOf(exclusionReasons);
    }

    public boolean complete() {
      return exclusionReasons.isEmpty();
    }

    /** Support outside the fixed eight, including FI in every period. */
    public BigDecimal comparableRemainder() {
      return components.entrySet().stream()
          .filter(component -> !PollCsv.PARTIES.contains(component.getKey()))
          .map(Map.Entry::getValue)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  /**
   * A candidate segment never replaces the validated roster until the estimator ticket validates
   * its fits.
   */
  public static CoveragePeriod supportedPeriod(List<CoveragePeriod> periods, PollCsv.Poll poll) {
    return periods.stream()
        .filter(CoveragePeriod::supportValidated)
        .filter(period -> period.covers(poll))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No validated coverage period covers " + poll.collectionFrom()));
  }

  public static Composition compose(CoveragePeriod period, PollCsv.Poll poll) {
    final java.util.ArrayList<java.lang.String> reasons = new ArrayList<>(poll.exclusionReasons());
    if (!period.covers(poll)) reasons.add("outside_coverage_period:" + period.id());
    final java.math.BigDecimal fi = poll.shares().get("FI");
    if (period.individualFi() && fi == null) reasons.add("missing_share:FI");
    final java.math.BigDecimal residual =
        reasons.isEmpty() && period.individualFi() ? poll.remainder().subtract(fi) : null;
    if (residual != null && residual.signum() < 0) reasons.add("negative_residual");
    final java.util.LinkedHashMap<java.lang.String, java.math.BigDecimal> components =
        new LinkedHashMap<String, BigDecimal>();
    if (reasons.isEmpty()) {
      for (java.lang.String party : PollCsv.PARTIES)
        components.put(party, poll.shares().get(party));
      // The eight-party remainder already contains FI; adding FI again would double-count it.
      if (residual == null) components.put("OTHER", poll.remainder());
      else {
        components.put("FI", fi);
        components.put("RESIDUAL", residual);
      }
    }
    return new Composition(period.id(), components, reasons);
  }
}
