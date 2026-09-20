package se.swedishpolls.estimation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resource limit of the accepted {@code synthetic-recovery-v2} protocol: what the sustained
 * preflight window implies about the formal run, and whether the registered host can carry each
 * stage of it in the budget remaining when that stage starts.
 *
 * <p>The projection is deliberately arithmetic on measurements rather than a policy. It never
 * lowers the repetition count, shortens a window, omits a grid point or changes precision: a stage
 * that does not project within its remaining budget is deferred unmeasured, and the run stops.
 *
 * <p>v1 charged the slowest measured dataset of a cold single-worker burst against the whole cap
 * before anything ran. v2 charges the slowest <em>sustained</em> rate of a window in which every
 * worker was busy, and charges it stage by stage.
 */
final class SyntheticBudget {
  private SyntheticBudget() {}

  /** The registered 24-hour wall-clock cap, the projection margin and the log reserve. */
  static final long CAP_NANOS = 24L * 3600 * 1_000_000_000L;

  static final double MARGIN = 1.25;

  static final long LOG_RESERVE_BYTES = 1L << 30;

  /** The smallest consecutive run of completed datasets a sustained rate may be read from. */
  static final int SUB_WINDOW_DATASETS = 20;

  /** The stages of one convention, in the order their cost is charged. */
  private static final List<String> STAGES =
      List.of(SyntheticRecovery.KNOWN_STAGE, SyntheticRecovery.ESTIMATED_STAGE);

  /**
   * One completed preflight dataset. Generation is charged to the known stage; the 180 training
   * grid evaluations are charged to the estimated stage, and each stage carries its own evidence
   * writing and hashing and its own reproduction.
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

  /**
   * One consecutive sub-window of the measured window: the datasets that completed inside it and
   * the wall-clock time the host took over them, with every worker busy throughout.
   */
  record SubWindow(String convention, long elapsedNanos, List<Measured> measured) {
    SubWindow {
      measured = List.copyOf(measured);
    }

    @Override
    public List<Measured> measured() {
      return List.copyOf(measured);
    }

    int datasets() {
      return measured.size();
    }

    /** Wall-clock seconds per dataset: the rate the host actually sustained over this window. */
    double perDatasetNanos() {
      return elapsedNanos / (double) measured.size();
    }

    /**
     * What one stage of this sub-window sustained. The window's wall clock is the only measurement
     * of the host under load; the per-stage timings within the datasets say how it divided.
     */
    double stageNanos(String stage) {
      long total = 0;
      long charged = 0;
      for (Measured dataset : measured) {
        for (String candidate : STAGES) total += dataset.stageNanos(candidate);
        charged += dataset.stageNanos(stage);
      }
      return total == 0 ? 0 : perDatasetNanos() * (charged / (double) total);
    }
  }

  /** The slowest sustained rate and the largest retained output of one convention and stage. */
  record Stage(String convention, String stage, long sustainedNanos, long largestBytes) {}

  /** One stage's projection against the budget that was left when its turn came. */
  record StageBudget(
      String convention,
      String stage,
      long perDatasetNanos,
      long projectedNanos,
      long remainingNanos,
      boolean fits,
      String source) {}

  /** What the sustained window projects for the registered run, and what the host would hold. */
  record Budget(
      List<Stage> stages,
      int datasets,
      long perRepetitionNanos,
      long projectedNanos,
      long preflightNanos,
      long totalNanos,
      long capNanos,
      boolean withinCap,
      StageBudget firstStage,
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

    /** The projection of one convention and stage, as the sustained window measured it. */
    Stage stage(String convention, String stage) {
      for (Stage candidate : stages)
        if (candidate.convention().equals(convention) && candidate.stage().equals(stage))
          return candidate;
      throw new IllegalArgumentException("No projection for " + convention + "/" + stage);
    }
  }

  /**
   * Projects the formal run from the sustained preflight window. Each convention and stage is
   * charged its slowest sub-window rate, the projection of a stage is {@code datasets x t x 1.25},
   * and the retained output has to fit twice over with a gibibyte left for logs.
   *
   * <p>Preflight feasibility is the storage rule and the first stage alone: a stage the elapsed run
   * no longer affords is a deferral when its turn comes, not an infeasible preflight.
   */
  static Budget project(
      List<SubWindow> windows, int datasets, long preflightNanos, long usableBytes) {
    if (windows.isEmpty()) throw new IllegalArgumentException("Preflight measured no sub-window");
    if (datasets < 1) throw new IllegalArgumentException("A projection needs at least one dataset");
    for (SubWindow window : windows)
      if (window.datasets() < 1)
        throw new IllegalArgumentException("A sustained rate needs a completed dataset");
    final Map<String, Stage> slowest = new LinkedHashMap<>();
    for (SubWindow window : windows)
      for (String stage : STAGES) {
        final String key = window.convention() + "/" + stage;
        final Stage previous = slowest.get(key);
        long largest = previous == null ? 0 : previous.largestBytes();
        for (Measured dataset : window.measured())
          largest = Math.max(largest, dataset.stageBytes(stage));
        slowest.put(
            key,
            new Stage(
                window.convention(),
                stage,
                Math.max(
                    previous == null ? 0 : previous.sustainedNanos(),
                    Math.round(window.stageNanos(stage))),
                largest));
      }
    final List<Stage> stages = List.copyOf(slowest.values());
    long perRepetition = 0;
    long perRepetitionBytes = 0;
    for (Stage stage : stages) {
      perRepetition += stage.sustainedNanos();
      perRepetitionBytes += stage.largestBytes();
    }
    final long projectedNanos = Math.round(datasets * (double) perRepetition * MARGIN);
    final long totalNanos = projectedNanos + preflightNanos;
    final long projectedBytes = datasets * perRepetitionBytes;
    final long requiredBytes = 2 * projectedBytes + LOG_RESERVE_BYTES;
    final boolean sufficientDisk = usableBytes >= requiredBytes;
    final Stage first = stages.get(0);
    final StageBudget firstStage =
        stageBudget(
            first.convention(),
            first.stage(),
            first.sustainedNanos(),
            datasets,
            CAP_NANOS - preflightNanos,
            "the sustained preflight window");
    final List<String> reasons = new ArrayList<>();
    if (!firstStage.fits())
      reasons.add("the first stage does not project within the cap less the elapsed preflight");
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
        totalNanos <= CAP_NANOS,
        firstStage,
        projectedBytes,
        requiredBytes,
        usableBytes,
        sufficientDisk,
        reasons);
  }

  /**
   * One stage against the budget remaining when it starts. The margin is the same 25% the whole-run
   * projection carried, and a stage that does not fit is deferred rather than shortened.
   */
  static StageBudget stageBudget(
      String convention,
      String stage,
      long perDatasetNanos,
      int datasets,
      long remainingNanos,
      String source) {
    final long projected = Math.round(datasets * (double) perDatasetNanos * MARGIN);
    return new StageBudget(
        convention,
        stage,
        perDatasetNanos,
        projected,
        remainingNanos,
        projected <= remainingNanos,
        source);
  }

  /**
   * The sustained rate an estimated stage is re-projected from: the control's own throughput over
   * its completed repetitions, scaled by what the preflight window measured the estimated stage to
   * cost relative to the known one.
   *
   * <p>Ten thousand completed repetitions measure the host far better than a preflight window can,
   * and by the time an estimated stage is due that measurement exists. Only the ratio survives from
   * preflight, because nothing else there ran under the load the controls just established.
   */
  static long reprojected(
      long knownSustainedNanos,
      long estimatedSustainedNanos,
      long controlElapsedNanos,
      int controlDatasets) {
    if (controlDatasets < 1)
      throw new IllegalArgumentException("A re-projection needs a completed control");
    if (knownSustainedNanos <= 0) return estimatedSustainedNanos;
    return Math.round(
        controlElapsedNanos
            / (double) controlDatasets
            * (estimatedSustainedNanos / (double) knownSustainedNanos));
  }
}
