package se.swedishpolls.estimation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** The registered Monte Carlo error gate for every subset of a candidate's fitted histories. */
public final class CoalitionPrecision {
  private CoalitionPrecision() {}

  public record Rules(
      int draws,
      List<Long> seeds,
      int referenceDraws,
      long referenceSeed,
      double maxMeanErrorPoints,
      double maxEndpointErrorPoints) {
    public Rules {
      seeds = List.copyOf(seeds);
      if (draws < 1
          || referenceDraws <= draws
          || seeds.isEmpty()
          || seeds.contains(referenceSeed)
          || !Double.isFinite(maxMeanErrorPoints)
          || maxMeanErrorPoints < 0
          || !Double.isFinite(maxEndpointErrorPoints)
          || maxEndpointErrorPoints < 0) {
        throw new IllegalArgumentException("Invalid coalition precision registration");
      }
    }
  }

  public record Report(
      List<LocalDate> dates,
      int comparedSummaries,
      double maxMeanErrorPoints,
      double maxEndpointErrorPoints,
      boolean passed) {
    public Report {
      dates = List.copyOf(dates);
    }

    public void requirePassed() {
      if (!passed) {
        throw new IllegalStateException(
            "Coalition precision failed: mean error "
                + maxMeanErrorPoints
                + ", endpoint error "
                + maxEndpointErrorPoints
                + " percentage points");
      }
    }
  }

  public static Report evaluate(EstimateHistory.Fitted fitted, String periodId, Rules rules) {
    final List<LocalDate> dates = new ArrayList<>();
    double meanError = 0;
    double endpointError = 0;
    int compared = 0;
    for (final EstimateHistory.Span span : fitted.spans()) {
      final double[][] basis = PollObservations.transposedBasis(span.batch());
      final List<DailyStateSpace.Day> days = span.fit().days();
      final Comparator<DailyStateSpace.Day> covariance =
          Comparator.comparingDouble(day -> day.smoothedCovariance().normF());
      final TreeSet<Integer> selected =
          new TreeSet<>(
              List.of(
                  0,
                  days.size() / 2,
                  days.size() - 1,
                  days.indexOf(days.stream().min(covariance).orElseThrow()),
                  days.indexOf(days.stream().max(covariance).orElseThrow())));
      for (final int index : selected) {
        final DailyStateSpace.Day day = days.get(index);
        dates.add(day.date());
        final List<CoalitionHistory.Summary> reference =
            summaries(span, basis, periodId, day, rules.referenceDraws(), rules.referenceSeed());
        for (final long seed : rules.seeds()) {
          final List<CoalitionHistory.Summary> sample =
              summaries(span, basis, periodId, day, rules.draws(), seed);
          for (int mask = 0; mask < 255; mask++) {
            final CoalitionHistory.Summary actual = sample.get(mask);
            final CoalitionHistory.Summary expected = reference.get(mask);
            if (!actual.availability().equals(expected.availability())) {
              throw new IllegalStateException("Seed changed coalition availability");
            }
            if (actual.mean() == null) continue;
            meanError = Math.max(meanError, Math.abs(actual.mean() - expected.mean()));
            endpointError =
                Math.max(
                    endpointError,
                    Math.max(
                        Math.abs(actual.lower() - expected.lower()),
                        Math.abs(actual.upper() - expected.upper())));
            compared++;
          }
        }
      }
    }
    return new Report(
        dates,
        compared,
        meanError,
        endpointError,
        compared > 0
            && meanError <= rules.maxMeanErrorPoints()
            && endpointError <= rules.maxEndpointErrorPoints());
  }

  private static List<CoalitionHistory.Summary> summaries(
      EstimateHistory.Span span,
      double[][] basis,
      String periodId,
      DailyStateSpace.Day day,
      int count,
      long seed) {
    final JointUncertainty.Rules rules = new JointUncertainty.Rules(seed, count, List.of(0.95), 2);
    return CoalitionHistory.aggregate(
        JointUncertainty.retained(
            span.batch(),
            periodId,
            day,
            JointUncertainty.transformed(span.batch(), basis, periodId, day, rules),
            rules));
  }
}
