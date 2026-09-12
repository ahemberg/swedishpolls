package se.swedishpolls.estimation;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.ejml.simple.SimpleMatrix;
import se.swedishpolls.source.PollCsv;

/**
 * The forward filter that scores held-out polls, where a poll observes the arithmetic average of
 * the daily latent ilr states over an inclusive window. The registered midpoint candidate is this
 * same filter with every window collapsed to the poll's own midpoint, so the candidate and the
 * ilr-window reference differ in that convention and nothing else.
 *
 * <p>A window is carried exactly, by holding the running sum of the days it covers in the state
 * beside the opinion and the house effects. Averaging inverse-transformed daily shares, or moving
 * the observation to one representative day, would both change the model rather than the
 * convention.
 */
public final class WindowFilter {
  private WindowFilter() {}

  /** The days one poll's observation averages over. */
  public record Window(LocalDate from, LocalDate to) {
    public Window {
      if (from == null || to == null || to.isBefore(from))
        throw new IllegalArgumentException("A window ends on or after it starts");
    }

    public int days() {
      return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    public boolean overlaps(Window other) {
      return !from.isAfter(other.to()) && !other.from().isAfter(to);
    }
  }

  /** How a poll observes the daily states. */
  public enum Convention {
    MIDPOINT,
    FIELDWORK;

    public Window window(PollObservations.Observation observation) {
      return this == MIDPOINT
          ? new Window(observation.midpoint(), observation.midpoint())
          : new Window(observation.poll().collectionFrom(), observation.poll().collectionTo());
    }
  }

  /**
   * One held-out poll's predictive distribution of its own transformed composition, conditioned on
   * the whole training set and never on the poll itself. The covariance carries the state, the
   * house effect and the poll's own sampling noise at the fitted multiplier.
   */
  public record Prediction(
      PollCsv.Poll poll,
      Window window,
      LocalDate predictedOn,
      boolean priorEffect,
      ModelValues mean,
      ModelValues covariance) {}

  /** One fold's filtered training likelihood and the predictions it implies. */
  public record Scored(double logLikelihood, int observations, List<Prediction> predictions) {
    public Scored {
      predictions = List.copyOf(predictions);
    }

    @Override
    public List<Prediction> predictions() {
      return List.copyOf(predictions);
    }
  }

  /** The plug-in marginal likelihood of the training observations under one convention. */
  public static double logLikelihood(
      PollObservations.Batch training,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      Convention convention) {
    return run(training, List.of(), elections, parameters, convention).logLikelihood();
  }

  /**
   * Filters the training observations and predicts every held-out poll without updating the fit
   * from it. A held-out window that closes before the last training observation keeps its running
   * sum in the state until that observation is filtered, so every prediction conditions on the
   * whole training set rather than on the part of it that happens to precede the poll.
   */
  public static Scored score(
      PollObservations.Batch training,
      List<PollObservations.Observation> heldOut,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      Convention convention) {
    return run(training, heldOut, elections, parameters, convention);
  }

  /** One poll's running sum while its window is open, and the cycle its house effect belongs to. */
  private static final class Pending {
    private final PollObservations.Observation observation;
    private final Window window;
    private final LocalDate resolveOn;
    private final boolean heldOut;
    private final int cycle;
    private Block sum;

    private Pending(
        PollObservations.Observation observation,
        Window window,
        LocalDate resolveOn,
        boolean heldOut,
        int cycle) {
      this.observation = observation;
      this.window = window;
      this.resolveOn = resolveOn;
      this.heldOut = heldOut;
      this.cycle = cycle;
    }
  }

  /** One election cycle's house effects, and the last day a poll of that cycle resolves on. */
  private record Cycle(LocalDate start, List<String> effects, LocalDate lastNeeded) {
    private Cycle {
      effects = List.copyOf(effects);
    }
  }

  private static Scored run(
      PollObservations.Batch training,
      List<PollObservations.Observation> heldOut,
      List<LocalDate> elections,
      DailyStateSpace.Parameters parameters,
      Convention convention) {
    DailyStateSpace.checkParameters(parameters);
    if (training.observations().isEmpty())
      throw new IllegalArgumentException("No observations to fit");
    final int dimension = training.components().size() - 1;
    final java.time.LocalDate start = training.period().effectiveFrom();
    java.time.LocalDate lastTraining = start;
    for (se.swedishpolls.estimation.PollObservations.Observation observation :
        training.observations()) {
      final se.swedishpolls.estimation.WindowFilter.Window window = convention.window(observation);
      if (window.from().isBefore(start))
        throw new IllegalArgumentException("An observation window starts before the period");
      if (window.to().isAfter(lastTraining)) lastTraining = window.to();
    }
    java.time.LocalDate lastDay = lastTraining;
    for (se.swedishpolls.estimation.PollObservations.Observation observation : heldOut) {
      final se.swedishpolls.estimation.WindowFilter.Window window = convention.window(observation);
      if (window.from().isBefore(start))
        throw new IllegalArgumentException("A held-out window starts before the period");
      if (window.to().isAfter(lastDay)) lastDay = window.to();
    }
    final java.util.List<java.time.LocalDate> starts = cycleStarts(elections, start, lastDay);
    final java.util.ArrayList<se.swedishpolls.estimation.WindowFilter.Pending> pending =
        new ArrayList<Pending>();
    for (se.swedishpolls.estimation.PollObservations.Observation observation :
        training.observations()) {
      final se.swedishpolls.estimation.WindowFilter.Window window = convention.window(observation);
      pending.add(new Pending(observation, window, window.to(), false, cycle(starts, window.to())));
    }
    for (se.swedishpolls.estimation.PollObservations.Observation observation : heldOut) {
      final se.swedishpolls.estimation.WindowFilter.Window window = convention.window(observation);
      pending.add(
          new Pending(
              observation,
              window,
              window.to().isAfter(lastTraining) ? window.to() : lastTraining,
              true,
              cycle(starts, window.to())));
    }
    final java.util.List<se.swedishpolls.estimation.WindowFilter.Cycle> cycles =
        cycles(starts, pending);
    // A training observation resolves when its own window closes; a held-out one only once the
    // last training observation is filtered, so no prediction misses part of the training set.
    final java.util.ArrayList<se.swedishpolls.estimation.WindowFilter.Pending> resolving =
        new ArrayList<>(pending);
    resolving.sort(
        Comparator.comparing((Pending slot) -> slot.resolveOn)
            .thenComparing(slot -> slot.heldOut)
            .thenComparingInt(slot -> slot.observation.poll().rowNumber()));
    final java.util.ArrayList<se.swedishpolls.estimation.WindowFilter.Pending> opening =
        new ArrayList<>(pending);
    opening.sort(
        Comparator.comparing((Pending slot) -> slot.window.from())
            .thenComparingInt(slot -> slot.observation.poll().rowNumber()));

    final se.swedishpolls.estimation.WindowFilter.Filter filter = new Filter(dimension, parameters);
    final java.util.ArrayList<
            java.util.Map<java.lang.String, se.swedishpolls.estimation.WindowFilter.Block>>
        effects = new ArrayList<Map<String, Block>>();
    final java.util.ArrayList<se.swedishpolls.estimation.WindowFilter.Pending> open =
        new ArrayList<Pending>();
    final java.util.ArrayList<se.swedishpolls.estimation.WindowFilter.Prediction> predictions =
        new ArrayList<Prediction>();
    int cycle = -1;
    int opened = 0;
    int resolved = 0;
    for (java.time.LocalDate date = start; !date.isAfter(lastDay); date = date.plusDays(1)) {
      if (!date.equals(start)) filter.walk();
      if (cycle + 1 < cycles.size() && cycles.get(cycle + 1).start().equals(date)) {
        // A finished cycle's effects leave the state only once every poll measured in it has
        // resolved, so a held-out poll that closes before an election still reads its own house.
        for (int past = 0; past <= cycle; past++)
          if (!effects.get(past).isEmpty() && date.isAfter(cycles.get(past).lastNeeded())) {
            filter.release(List.copyOf(effects.get(past).values()));
            effects.set(past, Map.of());
          }
        cycle++;
        final java.util.LinkedHashMap<
                java.lang.String, se.swedishpolls.estimation.WindowFilter.Block>
            claimed = new LinkedHashMap<String, Block>();
        for (java.lang.String effect : cycles.get(cycle).effects())
          claimed.put(effect, filter.claim(parameters.houseScale() * parameters.houseScale()));
        effects.add(claimed);
      }
      while (opened < opening.size() && opening.get(opened).window.from().equals(date)) {
        final se.swedishpolls.estimation.WindowFilter.Pending slot = opening.get(opened++);
        slot.sum = filter.claim(0);
        open.add(slot);
      }
      for (se.swedishpolls.estimation.WindowFilter.Pending slot : open)
        if (!slot.window.from().isAfter(date) && !slot.window.to().isBefore(date))
          filter.accumulate(slot.sum);
      while (resolved < resolving.size() && resolving.get(resolved).resolveOn.equals(date)) {
        final se.swedishpolls.estimation.WindowFilter.Pending slot = resolving.get(resolved++);
        final se.swedishpolls.estimation.WindowFilter.Block effect =
            effects.get(slot.cycle).get(effectIdentity(slot.observation));
        if (slot.heldOut) predictions.add(filter.predict(slot, effect));
        else filter.update(slot, effect);
        open.remove(slot);
        filter.release(List.of(slot.sum));
      }
      filter.check(date);
    }
    return new Scored(filter.logLikelihood(), training.observations().size(), predictions);
  }

  /** The house-effect identity of an observation's poll, as the fitted estimator names it. */
  private static String effectIdentity(PollObservations.Observation observation) {
    return DailyStateSpace.effectIdentity(observation.poll());
  }

  /** Cycles start at the coverage-period start and at every election day the run covers. */
  private static List<LocalDate> cycleStarts(
      List<LocalDate> elections, LocalDate start, LocalDate lastDay) {
    final java.util.ArrayList<java.time.LocalDate> starts = new ArrayList<>(List.of(start));
    LocalDate previous = null;
    for (java.time.LocalDate election : elections) {
      if (election == null || (previous != null && !election.isAfter(previous)))
        throw new IllegalArgumentException("Election dates must be distinct and ascending");
      previous = election;
      if (election.isAfter(start) && !election.isAfter(lastDay)) starts.add(election);
    }
    return List.copyOf(starts);
  }

  private static int cycle(List<LocalDate> starts, LocalDate date) {
    int cycle = 0;
    while (cycle + 1 < starts.size() && !starts.get(cycle + 1).isAfter(date)) cycle++;
    return cycle;
  }

  /**
   * A cycle's effects come from the training observations measured inside it; a held-out poll from
   * an institute with none draws its effect from the prior instead. The cycle also records the last
   * day one of its polls resolves on, which is when its effects may leave the state.
   */
  private static List<Cycle> cycles(List<LocalDate> starts, List<Pending> pending) {
    final java.util.ArrayList<se.swedishpolls.estimation.WindowFilter.Cycle> cycles =
        new ArrayList<Cycle>();
    for (int index = 0; index < starts.size(); index++) {
      final java.util.TreeSet<java.lang.String> effects = new TreeSet<String>();
      java.time.LocalDate lastNeeded = starts.get(index);
      for (se.swedishpolls.estimation.WindowFilter.Pending slot : pending)
        if (slot.cycle == index) {
          if (!slot.heldOut) effects.add(effectIdentity(slot.observation));
          if (slot.resolveOn.isAfter(lastNeeded)) lastNeeded = slot.resolveOn;
        }
      cycles.add(new Cycle(starts.get(index), List.copyOf(effects), lastNeeded));
    }
    return List.copyOf(cycles);
  }

  /** One block of the joint state: a house effect, or one open window's running sum. */
  private static final class Block {
    private int offset;

    private Block(int offset) {
      this.offset = offset;
    }
  }

  /**
   * The joint state: the opinion coordinates, one block per live house effect and one per open
   * window. Every step below is a sparse linear map applied in place, so a day costs the blocks it
   * touches rather than the whole covariance.
   */
  private static final class Filter {
    private final int dimension;
    private final DailyStateSpace.Parameters parameters;
    private final List<Block> blocks = new ArrayList<>();
    private int size;
    private double[] mean;
    private double[][] covariance;
    private double logLikelihood;

    private Filter(int dimension, DailyStateSpace.Parameters parameters) {
      this.dimension = dimension;
      this.parameters = parameters;
      this.size = dimension;
      this.mean = new double[dimension];
      this.covariance = new double[dimension][dimension];
      // The opinion state starts diffuse and independent of the first poll and of any election.
      for (int i = 0; i < dimension; i++) covariance[i][i] = 4;
    }

    private double logLikelihood() {
      return logLikelihood;
    }

    /** One day of the random walk on the opinion coordinates. */
    private void walk() {
      for (int i = 0; i < dimension; i++) covariance[i][i] += parameters.walkVariance();
    }

    /** Appends an independent block with the given prior variance, and returns it. */
    private Block claim(double variance) {
      final int resized = size + dimension;
      final double[] grownMean = new double[resized];
      final double[][] grownCovariance = new double[resized][resized];
      System.arraycopy(mean, 0, grownMean, 0, size);
      for (int r = 0; r < size; r++)
        System.arraycopy(covariance[r], 0, grownCovariance[r], 0, size);
      for (int i = size; i < resized; i++) grownCovariance[i][i] = variance;
      final se.swedishpolls.estimation.WindowFilter.Block block = new Block(size);
      blocks.add(block);
      size = resized;
      mean = grownMean;
      covariance = grownCovariance;
      return block;
    }

    /** Drops resolved blocks and closes the gaps they leave. */
    private void release(List<Block> released) {
      for (se.swedishpolls.estimation.WindowFilter.Block block : released) {
        final int resized = size - dimension;
        final double[] shrunkMean = new double[resized];
        final double[][] shrunkCovariance = new double[resized][resized];
        for (int r = 0; r < resized; r++) {
          final int source = r < block.offset ? r : r + dimension;
          shrunkMean[r] = mean[source];
          for (int c = 0; c < resized; c++)
            shrunkCovariance[r][c] = covariance[source][c < block.offset ? c : c + dimension];
        }
        size = resized;
        mean = shrunkMean;
        covariance = shrunkCovariance;
        blocks.remove(block);
        for (se.swedishpolls.estimation.WindowFilter.Block other : blocks)
          if (other.offset > block.offset) other.offset -= dimension;
      }
    }

    /** Adds today's opinion state into one open window's running sum. */
    private void accumulate(Block sum) {
      for (int i = 0; i < dimension; i++) {
        mean[sum.offset + i] += mean[i];
        for (int c = 0; c < size; c++) covariance[sum.offset + i][c] += covariance[i][c];
      }
      for (int i = 0; i < dimension; i++)
        for (int r = 0; r < size; r++) covariance[r][sum.offset + i] += covariance[r][i];
    }

    /** The predicted mean of one window's average observation, before its own sampling noise. */
    private double[] predictedMean(Pending slot, Block effect) {
      final double days = slot.window.days();
      final double[] predicted = new double[dimension];
      for (int i = 0; i < dimension; i++)
        predicted[i] =
            mean[slot.sum.offset + i] / days + (effect == null ? 0 : mean[effect.offset + i]);
      return predicted;
    }

    /** The covariance of the state with that observation, {@code P H'}. */
    private double[][] cross(Pending slot, Block effect) {
      final double days = slot.window.days();
      final double[][] cross = new double[size][dimension];
      for (int r = 0; r < size; r++)
        for (int i = 0; i < dimension; i++)
          cross[r][i] =
              covariance[r][slot.sum.offset + i] / days
                  + (effect == null ? 0 : covariance[r][effect.offset + i]);
      return cross;
    }

    /**
     * The predictive covariance of the observation itself, including its sampling noise. An
     * institute with no effect in its own cycle contributes the effect prior, which is independent
     * of the state and adds only its own variance.
     */
    private double[][] system(Pending slot, Block effect, double[][] cross) {
      final double days = slot.window.days();
      final double[][] system = new double[dimension][dimension];
      for (int i = 0; i < dimension; i++)
        for (int j = 0; j < dimension; j++)
          system[i][j] =
              cross[slot.sum.offset + i][j] / days
                  + (effect == null
                      ? (i == j ? parameters.houseScale() * parameters.houseScale() : 0)
                      : cross[effect.offset + i][j])
                  + parameters.covarianceMultiplier() * slot.observation.covariance().get(i, j);
      return symmetric(system);
    }

    private Prediction predict(Pending slot, Block effect) {
      final double[][] system = system(slot, effect, cross(slot, effect));
      factor(system);
      return new Prediction(
          slot.observation.poll(),
          slot.window,
          slot.resolveOn,
          effect == null,
          ModelValues.copyOf(new SimpleMatrix(matrix(predictedMean(slot, effect)))),
          ModelValues.copyOf(new SimpleMatrix(system)));
    }

    /** The Kalman update of one closed window. */
    private void update(Pending slot, Block effect) {
      if (effect == null)
        throw new IllegalStateException("A training observation always carries its own effect");
      final double[][] cross = cross(slot, effect);
      final double[][] factor = factor(system(slot, effect, cross));
      final double[] predicted = predictedMean(slot, effect);
      final double[] innovation = new double[dimension];
      for (int i = 0; i < dimension; i++)
        innovation[i] = slot.observation.ilr().get(i) - predicted[i];
      final double[] solved = solve(factor, innovation);
      double quadratic = 0;
      for (int i = 0; i < dimension; i++) quadratic += innovation[i] * solved[i];
      logLikelihood -=
          0.5 * (dimension * Math.log(2 * Math.PI) + logDeterminant(factor) + quadratic);
      final double[][] gain = new double[size][dimension];
      for (int r = 0; r < size; r++) gain[r] = solve(factor, cross[r]);
      for (int r = 0; r < size; r++)
        for (int i = 0; i < dimension; i++) mean[r] += gain[r][i] * innovation[i];
      joseph(slot, effect, gain, cross);
    }

    /**
     * {@code (I - K H) P (I - K H)' + K R K'}, evaluated through the sparse design so it costs the
     * observation's own coordinates rather than the whole state. The first update of a coverage
     * period subtracts a diffuse prior, where the shorter form loses the difference of two nearly
     * equal covariances.
     */
    private void joseph(Pending slot, Block effect, double[][] gain, double[][] cross) {
      final double days = slot.window.days();
      final double[][] reduced = new double[size][size];
      for (int r = 0; r < size; r++)
        for (int c = 0; c < size; c++) {
          double value = covariance[r][c];
          for (int i = 0; i < dimension; i++) value -= gain[r][i] * cross[c][i];
          reduced[r][c] = value;
        }
      final double[][] reducedCross = new double[size][dimension];
      final double[][] noise = new double[size][dimension];
      for (int r = 0; r < size; r++)
        for (int i = 0; i < dimension; i++) {
          reducedCross[r][i] =
              reduced[r][slot.sum.offset + i] / days + reduced[r][effect.offset + i];
          double value = 0;
          for (int j = 0; j < dimension; j++)
            value +=
                gain[r][j]
                    * parameters.covarianceMultiplier()
                    * slot.observation.covariance().get(j, i);
          noise[r][i] = value;
        }
      for (int r = 0; r < size; r++)
        for (int c = 0; c < size; c++) {
          double value = reduced[r][c];
          for (int i = 0; i < dimension; i++)
            value += gain[c][i] * noise[r][i] - reducedCross[r][i] * gain[c][i];
          covariance[r][c] = value;
        }
      covariance = symmetric(covariance);
    }

    private void check(LocalDate date) {
      for (int r = 0; r < size; r++)
        if (!Double.isFinite(mean[r]))
          throw new IllegalArgumentException("Nonfinite filtered state on " + date);
      if (!Double.isFinite(logLikelihood))
        throw new IllegalArgumentException("Nonfinite likelihood on " + date);
    }
  }

  private static double[][] symmetric(double[][] matrix) {
    final double[][] balanced = new double[matrix.length][matrix.length];
    for (int r = 0; r < matrix.length; r++)
      for (int c = 0; c < matrix.length; c++) balanced[r][c] = 0.5 * (matrix[r][c] + matrix[c][r]);
    return balanced;
  }

  private static double[][] matrix(double[] column) {
    final double[][] matrix = new double[column.length][1];
    for (int r = 0; r < column.length; r++) matrix[r][0] = column[r];
    return matrix;
  }

  /**
   * The lower Cholesky factor of a predictive covariance. A covariance that is not finite,
   * symmetric and positive definite stops the run; there is no jitter and no clipping.
   */
  static double[][] factor(double[][] symmetric) {
    final int size = symmetric.length;
    final double[][] lower = new double[size][size];
    for (int r = 0; r < size; r++)
      for (int c = 0; c <= r; c++) {
        double value = symmetric[r][c];
        if (!Double.isFinite(value))
          throw new IllegalArgumentException("A predictive covariance must be finite");
        for (int i = 0; i < c; i++) value -= lower[r][i] * lower[c][i];
        if (r != c) lower[r][c] = value / lower[c][c];
        else if (value > 0) lower[r][c] = Math.sqrt(value);
        else throw new IllegalArgumentException("A predictive covariance is not positive definite");
      }
    return lower;
  }

  static double logDeterminant(double[][] factor) {
    double logDeterminant = 0;
    for (int i = 0; i < factor.length; i++) logDeterminant += 2 * Math.log(factor[i][i]);
    if (!Double.isFinite(logDeterminant))
      throw new IllegalArgumentException("Nonfinite covariance factorization");
    return logDeterminant;
  }

  /** Solves {@code L L' x = right} by forward and back substitution. */
  static double[] solve(double[][] factor, double[] right) {
    final int size = factor.length;
    final double[] solved = right.clone();
    for (int r = 0; r < size; r++) {
      for (int c = 0; c < r; c++) solved[r] -= factor[r][c] * solved[c];
      solved[r] /= factor[r][r];
    }
    for (int r = size - 1; r >= 0; r--) {
      for (int c = r + 1; c < size; c++) solved[r] -= factor[c][r] * solved[c];
      solved[r] /= factor[r][r];
    }
    return solved;
  }
}
