package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The resource arithmetic of the accepted {@code synthetic-recovery-v1} protocol, specified
 * independently of the preflight that measures it: the slowest measured stage of each convention,
 * the projected formal duration against the 24-hour cap and the disk the retained output needs.
 */
class SyntheticBudgetTest {
  private static final long MILLISECOND = 1_000_000L;
  private static final long GIBIBYTE = 1L << 30;

  @Test
  void sumsTheSlowestMeasuredKnownAndEstimatedStageOfEachConvention() {
    // Generation is charged to the known stage; each stage carries its own evidence work and
    // reproduction. The slowest timed dataset of a stage stands for it, per convention.
    final SyntheticBudget.Budget budget =
        SyntheticBudget.project(
            List.of(
                measured("midpoint", 1, 1, 1, 1, 10, 1, 1, 1, 100),
                measured("midpoint", 2, 2, 2, 2, 5, 1, 1, 1, 400),
                measured("midpoint", 1, 1, 1, 1, 6, 1, 1, 1, 200),
                measured("ilr_window", 3, 3, 3, 3, 20, 2, 2, 2, 300),
                measured("ilr_window", 1, 1, 1, 1, 30, 2, 2, 2, 600),
                measured("ilr_window", 1, 1, 1, 1, 10, 2, 2, 2, 100)),
            10,
            0,
            Long.MAX_VALUE);

    assertEquals(
        List.of("midpoint/known", "midpoint/estimated", "ilr_window/known", "ilr_window/estimated"),
        budget.stages().stream().map(stage -> stage.convention() + "/" + stage.stage()).toList());
    assertEquals(8 * MILLISECOND, budget.stages().get(0).slowestNanos());
    assertEquals(13 * MILLISECOND, budget.stages().get(1).slowestNanos());
    assertEquals(12 * MILLISECOND, budget.stages().get(2).slowestNanos());
    assertEquals(36 * MILLISECOND, budget.stages().get(3).slowestNanos());
    assertEquals(69 * MILLISECOND, budget.perRepetitionNanos());
  }

  @Test
  void projectsTheFormalDurationWithTheRegisteredMarginAndTheElapsedPreflight() {
    final SyntheticBudget.Budget budget =
        SyntheticBudget.project(
            List.of(measured("midpoint", 1, 1, 1, 1, 1, 1, 1, 1, 0)), 1000, 7 * MILLISECOND, 0);

    assertEquals(8 * MILLISECOND, budget.perRepetitionNanos());
    assertEquals(1000 * 8 * MILLISECOND * 5 / 4, budget.projectedNanos());
    assertEquals(7 * MILLISECOND, budget.preflightNanos());
    assertEquals(budget.projectedNanos() + 7 * MILLISECOND, budget.totalNanos());
    assertEquals(24 * 3600 * 1_000_000_000L, budget.capNanos());
    assertTrue(budget.withinCap());
  }

  @Test
  void retainsInfeasibleRuntimeInsteadOfShorteningTheRegisteredRun() {
    // Ten seconds per repetition over 10,000 repetitions is 34 hours once the margin is applied.
    final SyntheticBudget.Budget budget =
        SyntheticBudget.project(
            List.of(measured("midpoint", 10_000, 0, 0, 0, 0, 0, 0, 0, 0)),
            10000,
            60 * 1000 * MILLISECOND,
            Long.MAX_VALUE);

    assertFalse(budget.withinCap());
    assertFalse(budget.feasible());
    assertEquals(
        List.of("projected formal duration exceeds the registered 24-hour cap"), budget.reasons());
    assertEquals(10000, budget.datasets());
  }

  @Test
  void requiresTwiceTheProjectedRetainedOutputPlusAGibibyteOfLogSpace() {
    final List<SyntheticBudget.Measured> measured =
        List.of(
            measured("midpoint", 1, 0, 0, 0, 0, 0, 0, 0, 300),
            measured("midpoint", 1, 0, 0, 0, 0, 0, 0, 0, 500),
            measured("ilr_window", 1, 0, 0, 0, 0, 0, 0, 0, 200));
    final long projected = 4 * (500L + 500 + 200 + 200);
    final SyntheticBudget.Budget feasible =
        SyntheticBudget.project(measured, 4, 0, 2 * projected + GIBIBYTE);

    assertEquals(projected, feasible.projectedBytes());
    assertEquals(2 * projected + GIBIBYTE, feasible.requiredBytes());
    assertTrue(feasible.sufficientDisk());
    assertTrue(feasible.feasible());

    final SyntheticBudget.Budget short0 =
        SyntheticBudget.project(measured, 4, 0, 2 * projected + GIBIBYTE - 1);
    assertFalse(short0.sufficientDisk());
    assertFalse(short0.feasible());
    assertEquals(
        List.of("free disk is below twice the projected retained output plus 1 GiB"),
        short0.reasons());
  }

  @Test
  void refusesToProjectFromMeasurementsTheProtocolDoesNotHave() {
    assertEquals(
        "Preflight measured no timed dataset",
        assertThrows(
                IllegalArgumentException.class,
                () -> SyntheticBudget.project(List.of(), 10, 0, Long.MAX_VALUE))
            .getMessage());
    assertEquals(
        "A projection needs at least one dataset",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    SyntheticBudget.project(
                        List.of(measured("midpoint", 1, 1, 1, 1, 1, 1, 1, 1, 0)),
                        0,
                        0,
                        Long.MAX_VALUE))
            .getMessage());
  }

  private static SyntheticBudget.Measured measured(
      String convention,
      long generation,
      long knownPrediction,
      long knownEvidence,
      long knownReproduction,
      long likelihoods,
      long tunedPrediction,
      long estimatedEvidence,
      long estimatedReproduction,
      long bytes) {
    return new SyntheticBudget.Measured(
        convention,
        generation * MILLISECOND,
        knownPrediction * MILLISECOND,
        knownEvidence * MILLISECOND,
        knownReproduction * MILLISECOND,
        likelihoods * MILLISECOND,
        tunedPrediction * MILLISECOND,
        estimatedEvidence * MILLISECOND,
        estimatedReproduction * MILLISECOND,
        bytes,
        bytes);
  }
}
