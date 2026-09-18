package se.swedishpolls.estimation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resource limit of the accepted {@code synthetic-recovery-v1} protocol: what the timed
 * preflight datasets imply about the formal run, and whether the registered host can carry it.
 *
 * <p>The projection is deliberately arithmetic on measurements rather than a policy. It never
 * lowers the repetition count, shortens a window, omits a grid point or changes precision: an
 * infeasible projection is retained as a reason and stops the run.
 */
final class SyntheticBudget {
  private SyntheticBudget() {}

  /** The registered 24-hour wall-clock cap, the projection margin and the log reserve. */
  static final long CAP_NANOS = 24L * 3600 * 1_000_000_000L;

  static final double MARGIN = 1.25;

  static final long LOG_RESERVE_BYTES = 1L << 30;

  /** The stages of one convention, in the order their cost is charged. */
  private static final List<String> STAGES =
      List.of(SyntheticRecovery.KNOWN_STAGE, SyntheticRecovery.ESTIMATED_STAGE);

  /**
   * One timed preflight dataset. Generation is charged to the known stage; the 180 training-grid
   * evaluations are charged to the estimated stage, and each stage carries its own evidence writing
   * and hashing and its own reproduction.
   */
  record Measured(
      String convention,
      long generationNanos,
      long knownPredictionNanos,
      long knownEvidenceNanos,
      long knownReproductionNanos,
      long likelihoodNanos,
      long tunedPredictionNanos,
      long estimatedEvidenceNanos,
      long estimatedReproductionNanos,
      long knownBytes,
      long estimatedBytes) {

    long stageNanos(String stage) {
      return stage.equals(SyntheticRecovery.KNOWN_STAGE)
          ? generationNanos + knownPredictionNanos + knownEvidenceNanos + knownReproductionNanos
          : likelihoodNanos
              + tunedPredictionNanos
              + estimatedEvidenceNanos
              + estimatedReproductionNanos;
    }

    long stageBytes(String stage) {
      return stage.equals(SyntheticRecovery.KNOWN_STAGE) ? knownBytes : estimatedBytes;
    }
  }

  /** The slowest and largest timed dataset of one convention and stage. */
  record Stage(String convention, String stage, long slowestNanos, long largestBytes) {}

  /** What the measurements project for the registered run, and what the host would have to hold. */
  record Budget(
      List<Stage> stages,
      int datasets,
      long perRepetitionNanos,
      long projectedNanos,
      long preflightNanos,
      long totalNanos,
      long capNanos,
      boolean withinCap,
      long projectedBytes,
      long requiredBytes,
      long usableBytes,
      boolean sufficientDisk,
      List<String> reasons) {
    Budget {
      stages = List.copyOf(stages);
      reasons = List.copyOf(reasons);
    }

    @Override
    public List<Stage> stages() {
      return List.copyOf(stages);
    }

    @Override
    public List<String> reasons() {
      return List.copyOf(reasons);
    }

    boolean feasible() {
      return reasons.isEmpty();
    }
  }

  /**
   * Projects the formal run from the timed preflight datasets. {@code T} is the sum of each
   * convention's slowest measured known and estimated stage times, the projection is {@code
   * datasets x T x 1.25} plus the elapsed preflight, and the retained output has to fit twice over
   * with a gibibyte left for logs and temporary files.
   */
  static Budget project(
      List<Measured> measured, int datasets, long preflightNanos, long usableBytes) {
    if (measured.isEmpty())
      throw new IllegalArgumentException("Preflight measured no timed dataset");
    if (datasets < 1) throw new IllegalArgumentException("A projection needs at least one dataset");
    final Map<String, Stage> slowest = new LinkedHashMap<>();
    for (Measured dataset : measured)
      for (String stage : STAGES) {
        final String key = dataset.convention() + "/" + stage;
        final Stage previous = slowest.get(key);
        slowest.put(
            key,
            new Stage(
                dataset.convention(),
                stage,
                Math.max(previous == null ? 0 : previous.slowestNanos(), dataset.stageNanos(stage)),
                Math.max(
                    previous == null ? 0 : previous.largestBytes(), dataset.stageBytes(stage))));
      }
    final List<Stage> stages = List.copyOf(slowest.values());
    long perRepetition = 0;
    long perRepetitionBytes = 0;
    for (Stage stage : stages) {
      perRepetition += stage.slowestNanos();
      perRepetitionBytes += stage.largestBytes();
    }
    final long projectedNanos = Math.round(datasets * (double) perRepetition * MARGIN);
    final long totalNanos = projectedNanos + preflightNanos;
    final boolean withinCap = totalNanos <= CAP_NANOS;
    final long projectedBytes = datasets * perRepetitionBytes;
    final long requiredBytes = 2 * projectedBytes + LOG_RESERVE_BYTES;
    final boolean sufficientDisk = usableBytes >= requiredBytes;
    final List<String> reasons = new ArrayList<>();
    if (!withinCap) reasons.add("projected formal duration exceeds the registered 24-hour cap");
    if (!sufficientDisk)
      reasons.add("free disk is below twice the projected retained output plus 1 GiB");
    return new Budget(
        stages,
        datasets,
        perRepetition,
        projectedNanos,
        preflightNanos,
        totalNanos,
        CAP_NANOS,
        withinCap,
        projectedBytes,
        requiredBytes,
        usableBytes,
        sufficientDisk,
        reasons);
  }
}
