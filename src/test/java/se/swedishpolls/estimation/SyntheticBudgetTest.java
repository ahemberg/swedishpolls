package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The resource arithmetic of the accepted {@code synthetic-recovery-v2} protocol, specified
 * independently of the preflight that measures it: the slowest sustained sub-window of each
 * convention and stage, the budget each stage is gated against when its turn comes, and the disk
 * the retained output needs.
 *
 * <p>Nothing here reads a coverage figure. Timing informs what runs; coverage never does.
 */
class SyntheticBudgetTest {
  private static final long MILLISECOND = 1_000_000L;
  private static final long GIBIBYTE = 1L << 30;
  private static final long DAY = 24L * 3600 * 1_000_000_000L;

  @Test
  void chargesEachStageTheSlowestSustainedSubWindowRatherThanTheWindowMean() {
    // Two sub-windows of one convention: a fast start and a slower remainder. Averaging the two
    // would be a weaker margin than v1 had, so the slower one carries the charge.
    final SyntheticBudget.Budget budget =
        SyntheticBudget.project(
            List.of(
                window(
                    "midpoint", 40, measured("midpoint", 3, 1, 0), measured("midpoint", 3, 1, 0)),
                window(
                    "midpoint", 80, measured("midpoint", 3, 1, 0), measured("midpoint", 3, 1, 0)),
                window(
                    "ilr_window",
                    100,
                    measured("ilr_window", 1, 1, 0),
                    measured("ilr_window", 1, 1, 0))),
            10,
            0,
            Long.MAX_VALUE);

    assertEquals(
        List.of("midpoint/known", "midpoint/estimated", "ilr_window/known", "ilr_window/estimated"),
        budget.stages().stream().map(stage -> stage.convention() + "/" + stage.stage()).toList());
    // The slower sub-window sustained 80 ms per dataset, split 3:1 by what the stages cost in it.
    assertEquals(60 * MILLISECOND, budget.stage("midpoint", "known").sustainedNanos());
    assertEquals(20 * MILLISECOND, budget.stage("midpoint", "estimated").sustainedNanos());
    assertEquals(50 * MILLISECOND, budget.stage("ilr_window", "known").sustainedNanos());
    assertEquals(50 * MILLISECOND, budget.stage("ilr_window", "estimated").sustainedNanos());
    assertEquals(180 * MILLISECOND, budget.perRepetitionNanos());
  }

  @Test
  void projectsTheWholeRunWithTheRegisteredMarginAndTheElapsedPreflight() {
    final SyntheticBudget.Budget budget =
        SyntheticBudget.project(
            List.of(window("midpoint", 8, measured("midpoint", 1, 1, 0))),
            1000,
            7 * MILLISECOND,
            0);

    assertEquals(8 * MILLISECOND, budget.perRepetitionNanos());
    assertEquals(1000 * 8 * MILLISECOND * 5 / 4, budget.projectedNanos());
    assertEquals(7 * MILLISECOND, budget.preflightNanos());
    assertEquals(budget.projectedNanos() + 7 * MILLISECOND, budget.totalNanos());
    assertEquals(DAY, budget.capNanos());
    assertTrue(budget.withinCap());
  }

  @Test
  void refusesAPreflightOnlyWhenTheFirstStageDoesNotFitOrTheDiskDoesNot() {
    // Twenty seconds of known stage over 10,000 repetitions is 69 hours once the margin applies,
    // so the very first stage cannot start and the preflight is infeasible.
    final SyntheticBudget.Budget refused =
        SyntheticBudget.project(
            List.of(window("midpoint", 20_000, measured("midpoint", 1, 0, 0))),
            10000,
            60 * 1000 * MILLISECOND,
            Long.MAX_VALUE);

    assertFalse(refused.firstStage().fits());
    assertFalse(refused.feasible());
    assertEquals(
        List.of("the first stage does not project within the cap less the elapsed preflight"),
        refused.reasons());

    // A run whose first stage fits but whose total does not is feasible here: the stages that the
    // elapsed run can no longer afford are deferred when their turn comes, not refused now.
    final SyntheticBudget.Budget deferrable =
        SyntheticBudget.project(
            List.of(
                window("midpoint", 4_000, measured("midpoint", 1, 3, 0)),
                window("ilr_window", 4_000, measured("ilr_window", 1, 3, 0))),
            10000,
            0,
            Long.MAX_VALUE);

    assertTrue(deferrable.firstStage().fits());
    assertFalse(deferrable.withinCap());
    assertTrue(deferrable.feasible());
    assertEquals(List.of(), deferrable.reasons());
  }

  @Test
  void requiresTwiceTheProjectedRetainedOutputPlusAGibibyteOfLogSpace() {
    final List<SyntheticBudget.SubWindow> windows =
        List.of(
            window("midpoint", 8, measured("midpoint", 1, 1, 300), measured("midpoint", 1, 1, 500)),
            window("ilr_window", 8, measured("ilr_window", 1, 1, 200)));
    final long projected = 4 * (500L + 500 + 200 + 200);
    final SyntheticBudget.Budget feasible =
        SyntheticBudget.project(windows, 4, 0, 2 * projected + GIBIBYTE);

    assertEquals(projected, feasible.projectedBytes());
    assertEquals(2 * projected + GIBIBYTE, feasible.requiredBytes());
    assertTrue(feasible.sufficientDisk());
    assertTrue(feasible.feasible());

    final SyntheticBudget.Budget cramped =
        SyntheticBudget.project(windows, 4, 0, 2 * projected + GIBIBYTE - 1);
    assertFalse(cramped.sufficientDisk());
    assertFalse(cramped.feasible());
    assertEquals(
        List.of("free disk is below twice the projected retained output plus 1 GiB"),
        cramped.reasons());
  }

  @Test
  void gatesEachStageAgainstTheBudgetRemainingWhenItStarts() {
    final SyntheticBudget.StageBudget fits =
        SyntheticBudget.stageBudget("midpoint", "known", MILLISECOND, 10000, DAY, "preflight");
    assertEquals(10000 * MILLISECOND * 5 / 4, fits.projectedNanos());
    assertEquals(DAY, fits.remainingNanos());
    assertTrue(fits.fits());

    // The same stage against what is left after a control has already run the day down.
    final SyntheticBudget.StageBudget deferred =
        SyntheticBudget.stageBudget(
            "ilr_window", "estimated", MILLISECOND, 10000, 10 * MILLISECOND, "control throughput");
    assertFalse(deferred.fits());
    assertEquals("control throughput", deferred.source());

    // Exactly filling the remaining budget is within it.
    assertTrue(
        SyntheticBudget.stageBudget("midpoint", "known", 8, 1000, 1000 * 8 * 5 / 4, "preflight")
            .fits());
  }

  @Test
  void reprojectsAnEstimatedStageFromItsControlsCompletedThroughput() {
    // Preflight measured the estimated stage at four times the known one. The control then ran
    // 10,000 repetitions at 2 ms each, which measures the host far better than the window did.
    assertEquals(
        8 * MILLISECOND,
        SyntheticBudget.reprojected(MILLISECOND, 4 * MILLISECOND, 10000 * 2 * MILLISECOND, 10000));

    // With no known-stage rate to scale against, the preflight estimate stands unchanged.
    assertEquals(
        4 * MILLISECOND,
        SyntheticBudget.reprojected(0, 4 * MILLISECOND, 10000 * 2 * MILLISECOND, 10000));
    assertEquals(
        "A re-projection needs a completed control",
        assertThrows(
                IllegalArgumentException.class,
                () -> SyntheticBudget.reprojected(MILLISECOND, MILLISECOND, MILLISECOND, 0))
            .getMessage());
  }

  @Test
  void refusesToProjectFromMeasurementsTheProtocolDoesNotHave() {
    assertEquals(
        "Preflight measured no sub-window",
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
                        List.of(window("midpoint", 8, measured("midpoint", 1, 1, 0))),
                        0,
                        0,
                        Long.MAX_VALUE))
            .getMessage());
    assertEquals(
        "A sustained rate needs a completed dataset",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    SyntheticBudget.project(
                        List.of(new SyntheticBudget.SubWindow("midpoint", 8, List.of())),
                        10,
                        0,
                        Long.MAX_VALUE))
            .getMessage());
  }

  private static SyntheticBudget.SubWindow window(
      String convention, long elapsedMillis, SyntheticBudget.Measured... measured) {
    return new SyntheticBudget.SubWindow(
        convention, elapsedMillis * MILLISECOND * measured.length, List.of(measured));
  }

  /**
   * One measured dataset, charging {@code known} to the known stage and {@code estimated} to it.
   */
  private static SyntheticBudget.Measured measured(
      String convention, long known, long estimated, long bytes) {
    return new SyntheticBudget.Measured(
        convention, known * MILLISECOND, 0, 0, 0, estimated * MILLISECOND, 0, 0, 0, bytes, bytes);
  }
}
