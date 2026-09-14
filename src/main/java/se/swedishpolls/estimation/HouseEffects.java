package se.swedishpolls.estimation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;

/**
 * Publishes a fitted cycle's house effects as percentage points. The state carries them in ilr
 * coordinates, where a deviation has no unit a reader can use, so each effect is closed against the
 * cycle's own opinion and reported as the difference it makes to every component's share.
 */
public final class HouseEffects {
  private HouseEffects() {}

  private static final RandomGeneratorFactory<RandomGenerator> RANDOM_FACTORY =
      RandomGeneratorFactory.of("Random");

  /**
   * What every effect of one fitted cycle is measured against: the basis and components it is
   * closed through, the cycle's own opinion and the shares that opinion makes, and the draw rules
   * the interval is read from.
   */
  private static final class Against {
    // Model coordinates, not a record: these arrays are read in place by every effect of the
    // cycle, and a record component may not be an array.
    final List<String> components;
    final double[][] basis;
    final String periodId;
    final double[] opinion;
    final double[] reference;
    final double houseScale;
    final double intervalLevel;
    final int draws;
    final long seed;

    Against(
        List<String> components,
        double[][] basis,
        String periodId,
        double[] opinion,
        double[] reference,
        double houseScale,
        double intervalLevel,
        int draws,
        long seed) {
      this.components = components;
      this.basis = basis;
      this.periodId = periodId;
      this.opinion = opinion;
      this.reference = reference;
      this.houseScale = houseScale;
      this.intervalLevel = intervalLevel;
      this.draws = draws;
      this.seed = seed;
    }
  }

  /** One institute's deviation from the cycle ensemble, in points of the component's share. */
  public record Effect(
      String electionCycle,
      String effect,
      String component,
      double mean,
      double lower,
      double upper,
      boolean shrunk) {}

  /** Fits one coverage period and returns its latest run's house effects. */
  public static List<Effect> estimate(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      CoverageValidation.Rules coverage,
      double intervalLevel,
      int draws,
      long seed) {
    return estimate(
        EstimateHistory.fitted(period, polls, elections, parameters, coverage),
        period.id(),
        elections,
        intervalLevel,
        draws,
        seed);
  }

  /** The same effects over a fit the publication already took. */
  public static List<Effect> estimate(
      EstimateHistory.Fitted fitted,
      String periodId,
      List<LocalDate> elections,
      double intervalLevel,
      int draws,
      long seed) {
    return of(fitted.spans().getLast().fit(), periodId, elections, intervalLevel, draws, seed);
  }

  /** Every effect of one fitted run, ordered by cycle, then effect, then component. */
  static List<Effect> of(
      DailyStateSpace.Fit fit,
      String periodId,
      List<LocalDate> elections,
      double intervalLevel,
      int draws,
      long seed) {
    final double[][] basis = PollObservations.transposedBasis(fit.batch());
    final List<String> components = fit.batch().components();
    final int dimension = components.size() - 1;
    final List<Effect> effects = new ArrayList<>();
    for (final DailyStateSpace.Cycle cycle : fit.cycles()) {
      final double[] opinion =
          Arrays.copyOf(closest(fit.days(), cycle.end()).smoothedMean().toArray(), dimension);
      final double[] reference = new double[components.size()];
      PollObservations.close(basis, opinion, reference, periodId);
      final Against against =
          new Against(
              components,
              basis,
              periodId,
              opinion,
              reference,
              fit.parameters().houseScale(),
              intervalLevel,
              draws,
              seed);
      final String label = cycleLabel(cycle, elections);
      final double[] stacked = cycle.smoothedMean().toArray();
      for (int index = 0; index < cycle.effects().size(); index++) {
        effects.addAll(
            effect(
                cycle.effects().get(index),
                label,
                Arrays.copyOfRange(stacked, index * dimension, (index + 1) * dimension),
                block(cycle.smoothedCovariance(), index, dimension),
                against));
      }
    }
    return List.copyOf(effects);
  }

  private static List<Effect> effect(
      String name, String electionCycle, double[] mean, double[][] covariance, Against against) {
    final List<String> components = against.components;
    final double[][] basis = against.basis;
    final String periodId = against.periodId;
    final double[] opinion = against.opinion;
    final double[] reference = against.reference;
    final int draws = against.draws;
    final double[] point = new double[components.size()];
    PollObservations.close(basis, sum(opinion, mean), point, periodId);
    final double[][] factor = WindowFilter.factor(covariance);
    final RandomGenerator random =
        RANDOM_FACTORY.create(effectSeed(periodId, electionCycle, name, against.seed));
    final double[][] sampled = new double[components.size()][draws];
    final double[] normal = new double[mean.length];
    final double[] state = new double[mean.length];
    final double[] shares = new double[components.size()];
    for (int draw = 0; draw < draws; draw++) {
      for (int i = 0; i < normal.length; i++) {
        normal[i] = random.nextGaussian();
      }
      for (int i = 0; i < state.length; i++) {
        double value = mean[i];
        for (int j = 0; j <= i; j++) {
          value += factor[i][j] * normal[j];
        }
        state[i] = value;
      }
      PollObservations.close(basis, sum(opinion, state), shares, periodId);
      for (int component = 0; component < shares.length; component++) {
        sampled[component][draw] = shares[component] - reference[component];
      }
    }
    // The flag says the model applied its zero-centred prior to this effect, which a strictly
    // positive house scale is. It is not a claim about how much evidence the institute has.
    final boolean shrunk = against.houseScale > 0;
    final List<Effect> effects = new ArrayList<>(components.size());
    for (int component = 0; component < components.size(); component++) {
      final double[] column = sampled[component];
      Arrays.sort(column);
      effects.add(
          new Effect(
              electionCycle,
              name,
              components.get(component),
              point[component] - reference[component],
              JointUncertainty.quantile(column, (1 - against.intervalLevel) / 2),
              JointUncertainty.quantile(column, (1 + against.intervalLevel) / 2),
              shrunk));
    }
    return effects;
  }

  private static double[] sum(double[] left, double[] right) {
    final double[] total = new double[left.length];
    for (int i = 0; i < left.length; i++) {
      total[i] = left[i] + right[i];
    }
    return total;
  }

  private static double[][] block(ModelValues covariance, int effect, int dimension) {
    final double[][] block = new double[dimension][dimension];
    for (int row = 0; row < dimension; row++) {
      for (int column = 0; column < dimension; column++) {
        block[row][column] = covariance.get(effect * dimension + row, effect * dimension + column);
      }
    }
    return block;
  }

  private static DailyStateSpace.Day closest(List<DailyStateSpace.Day> days, LocalDate date) {
    return days.stream()
        .filter(day -> !day.date().isAfter(date))
        .max(Comparator.comparing(DailyStateSpace.Day::date))
        .orElse(days.getLast());
  }

  private static String cycleLabel(DailyStateSpace.Cycle cycle, List<LocalDate> elections) {
    final LocalDate opened =
        elections.stream()
            .filter(election -> !election.isAfter(cycle.start()))
            .max(LocalDate::compareTo)
            .orElse(cycle.start());
    final LocalDate closed =
        elections.stream()
            .filter(election -> election.isAfter(cycle.start()))
            .min(LocalDate::compareTo)
            .orElse(cycle.end());
    return opened.getYear() + "-" + closed.getYear();
  }

  private static long effectSeed(String periodId, String electionCycle, String name, long seed) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (periodId + "|" + electionCycle + "|" + name + "|" + seed)
                      .getBytes(StandardCharsets.UTF_8));
      long value = 0;
      for (int index = 0; index < Long.BYTES; index++) {
        value = (value << 8) | (digest[index] & 0xFF);
      }
      return value;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }
}
