package se.swedishpolls.estimation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import se.swedishpolls.source.Roster;

/** Pointwise support summaries of the 255 nonempty eight-party subsets, in mask order. */
public final class CoalitionHistory {
  public static final List<String> ROSTER = Roster.COALITION_PARTIES;

  private CoalitionHistory() {}

  public record Summary(Double mean, Double lower, Double upper, String availability) {
    public Summary(double mean, double lower, double upper) {
      this(mean, lower, upper, "available");
    }
  }

  public record Day(LocalDate date, String periodId, String fitId, List<Summary> subsets) {
    public Day {
      subsets = List.copyOf(subsets);
    }
  }

  public record Estimated(
      EstimateHistory.Estimated history, List<Day> days, JointUncertainty.Draws finalDraws) {
    public Estimated {
      days = List.copyOf(days);
    }
  }

  private record Summarized(
      Day coalition, EstimateHistory.Day parties, JointUncertainty.Draws finalDraws) {}

  /** Streams days through the existing parallel pool; retains only summaries and final draws. */
  public static Estimated estimate(
      EstimateHistory.Fitted fitted,
      Roster.CoveragePeriod period,
      CoverageValidation.Rules coverage,
      JointUncertainty.Rules rules) {
    final List<EstimateHistory.Segment> segments = new ArrayList<>();
    final List<Day> days = new ArrayList<>();
    JointUncertainty.Draws last = null;
    for (final EstimateHistory.Span span : fitted.spans()) {
      final double[][] basis = PollObservations.transposedBasis(span.batch());
      final String fitId = period.id() + ":" + span.fit().days().getFirst().date();
      final LocalDate finalDate = span.fit().days().getLast().date();
      final List<Summarized> summarized =
          span.fit().days().parallelStream()
              .map(
                  day -> {
                    final double[][] shares =
                        JointUncertainty.transformed(span.batch(), basis, period.id(), day, rules);
                    final JointUncertainty.Draws retained =
                        JointUncertainty.retained(span.batch(), period.id(), day, shares, rules);
                    return new Summarized(
                        new Day(day.date(), period.id(), fitId, aggregate(retained)),
                        EstimateHistory.published(
                            JointUncertainty.summarize(
                                span.batch(), basis, period.id(), day, shares, rules)),
                        day.date().equals(finalDate) ? retained : null);
                  })
              .toList();
      days.addAll(summarized.stream().map(Summarized::coalition).toList());
      final List<EstimateHistory.Day> partyDays =
          summarized.stream().map(Summarized::parties).toList();
      last = summarized.getLast().finalDraws();
      segments.add(
          new EstimateHistory.Segment(
              period.id(), partyDays.getFirst().date(), partyDays.getLast().date(), partyDays));
    }
    if (last == null) {
      throw new IllegalArgumentException("No supported joint fit");
    }
    return new Estimated(
        new EstimateHistory.Estimated(
            period.id(),
            fitted.support(),
            segments,
            EstimateHistory.boundaries(period, fitted, coverage)),
        days,
        last);
  }

  /** Sums each joint draw before taking the shared linear-interpolation quantiles. */
  public static List<Summary> aggregate(JointUncertainty.Draws draws) {
    if (draws.count() == 0 || draws.shares().getNumCols() != draws.components().size()) {
      throw new IllegalArgumentException("A joint ensemble must contain complete draws");
    }
    final int[] columns = new int[ROSTER.size()];
    for (int party = 0; party < columns.length; party++) {
      columns[party] = draws.components().indexOf(ROSTER.get(party));
    }
    final List<Summary> summaries = new ArrayList<>();
    final double[] values = new double[draws.count()];
    for (int mask = 1; mask < 256; mask++) {
      boolean missing = false;
      for (int party = 0; party < columns.length; party++) {
        if ((mask & (1 << party)) != 0 && columns[party] < 0) missing = true;
      }
      if (missing) {
        summaries.add(new Summary(null, null, null, "party_unavailable"));
        continue;
      }
      double total = 0;
      for (int draw = 0; draw < draws.count(); draw++) {
        double sum = 0;
        for (int party = 0; party < columns.length; party++) {
          if ((mask & (1 << party)) != 0) {
            final double value = draws.shares().get(draw, columns[party]);
            if (!Double.isFinite(value) || value < 0 || value > 100) {
              throw new IllegalArgumentException("Invalid joint share");
            }
            sum += value;
          }
        }
        values[draw] = sum;
        total += sum;
      }
      Arrays.sort(values);
      summaries.add(
          new Summary(
              total / values.length,
              JointUncertainty.quantile(values, (1 - 0.95) / 2),
              JointUncertainty.quantile(values, (1 + 0.95) / 2)));
    }
    return List.copyOf(summaries);
  }
}
