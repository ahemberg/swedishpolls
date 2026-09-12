package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.testsupport.PollCsvFixtures;

class DevelopmentTuningTest {
  private static final LocalDate START = LocalDate.of(2014, 1, 1);
  private static final List<LocalDate> ELECTIONS =
      List.of(LocalDate.of(2014, 9, 14), LocalDate.of(2018, 9, 9));
  private static final DevelopmentTuning.Fold FOLD =
      new DevelopmentTuning.Fold(LocalDate.of(2014, 6, 30), LocalDate.of(2014, 8, 4));
  private static final DevelopmentTuning.Grid GRID =
      new DevelopmentTuning.Grid(List.of(1e-5, 1e-4, 1e-3), List.of(0.05, 0.2), List.of(1.0, 2.0));

  @TempDir Path temp;

  /**
   * One eligible poll, published on its collection end unless the caller overrides the publication
   * date.
   */
  private static String row(String institute, int from, int to, String m, String publication) {
    return "2014-01,"
        + institute
        + ","
        + m
        + ",5,8,5,25,8,5,12,1,10,1000,"
        + (publication == null ? START.plusDays(to).toString() : publication)
        + ","
        + institute
        + ","
        + START.plusDays(from)
        + ","
        + START.plusDays(to)
        + ",FALSE\n";
  }

  private static String row(String institute, int from, int to, String m) {
    return row(institute, from, to, m, null);
  }

  private static Roster.CoveragePeriod period(boolean fi) {
    return new Roster.CoveragePeriod(
        "test",
        START,
        START.plusDays(3650),
        fi ? Stream.concat(PollCsv.PARTIES.stream(), Stream.of("FI")).toList() : PollCsv.PARTIES,
        fi,
        false,
        "https://example.invalid");
  }

  private static List<PollCsv.Poll> polls(String rows) {
    return PollCsv.parse(PollCsvFixtures.csv(rows));
  }

  @Test
  void gridAxesMustBeAscendingAndAdmissibleAndExpandInTieBreakingOrder() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DevelopmentTuning.Grid(List.of(), List.of(0.05), List.of(1.0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DevelopmentTuning.Grid(List.of(1e-3, 1e-4), List.of(0.05), List.of(1.0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DevelopmentTuning.Grid(List.of(1e-4, 1e-4), List.of(0.05), List.of(1.0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DevelopmentTuning.Grid(List.of(1e-4), List.of(-0.05), List.of(1.0)));
    // A static opinion state is an admissible walk variance; a zero house scale or multiplier is
    // not.
    assertDoesNotThrow(
        () -> new DevelopmentTuning.Grid(List.of(0.0, 1e-4), List.of(0.05), List.of(1.0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DevelopmentTuning.Grid(List.of(1e-4), List.of(0.0), List.of(1.0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DevelopmentTuning.Grid(List.of(1e-4), List.of(0.05), List.of(0.0)));

    final java.util.List<se.swedishpolls.estimation.DailyStateSpace.Parameters> points =
        GRID.points();
    assertEquals(12, points.size());
    assertEquals(new DailyStateSpace.Parameters(1e-5, 0.05, 1.0), points.getFirst());
    assertEquals(new DailyStateSpace.Parameters(1e-5, 0.05, 2.0), points.get(1));
    assertEquals(new DailyStateSpace.Parameters(1e-5, 0.2, 1.0), points.get(2));
    assertEquals(new DailyStateSpace.Parameters(1e-3, 0.2, 2.0), points.getLast());
  }

  @Test
  void trainingKeepsOnlyEligiblePollsPublishedByTheCutoff() {
    final java.lang.String rows =
        row("Novus", 0, 2, "20")
            + row("Sifo", 200, 202, "21") // published after the cutoff
            + row("Ipsos", 3, 5, "22", "NA") // unknown publication date
            + row(
                "Demoskop",
                6,
                8,
                "22",
                START.plusDays(200).toString()) // late publication of early fieldwork
            + row("SVT", 9, 11, "22") // exit poll, ineligible
            + row("Novus", 12, 14, "150"); // invalid share, ineligible
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> training =
        DevelopmentTuning.training(polls(rows), FOLD);
    assertEquals(List.of("Novus"), training.stream().map(PollCsv.Poll::institute).toList());
    assertEquals(List.of(1), training.stream().map(PollCsv.Poll::rowNumber).toList());
    assertTrue(
        training.stream()
            .allMatch(
                poll ->
                    !poll.publicationDate().isAfter(FOLD.cutoff())
                        && !poll.collectionTo().isAfter(FOLD.cutoff())));
  }

  @Test
  void tuningResolvesTheGridMaximumAndCountsItsTrainingInputsForBothRosters() {
    final java.lang.StringBuilder rows = new StringBuilder();
    for (int week = 0; week < 24; week++)
      rows.append(row("Sifo", week * 7, week * 7 + 1, week % 2 == 0 ? "24" : "20"))
          .append(row("Novus", week * 7 + 2, week * 7 + 3, "18"));
    // Eligible and published in time, but its fieldwork starts before the coverage period.
    rows.append(row("Sifo", -12, -10, "22"));
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls = polls(rows.toString());
    for (boolean fi : List.of(false, true)) {
      final se.swedishpolls.estimation.DevelopmentTuning.Resolved resolved =
          DevelopmentTuning.tune(period(fi), polls, ELECTIONS, FOLD, GRID);
      final se.swedishpolls.estimation.PollObservations.Batch batch =
          PollObservations.prepare(period(fi), DevelopmentTuning.training(polls, FOLD));
      assertEquals("test", resolved.periodId());
      assertEquals(FOLD, resolved.fold());
      assertEquals(49, resolved.trainingPolls());
      assertEquals(48, resolved.observations());
      assertEquals(1, resolved.exclusions());
      assertEquals(Map.of("outside_coverage_period:test", 1), resolved.exclusionReasons());
      // The resolved point is the grid maximum of the same plug-in marginal likelihood, recomputed
      // directly.
      double best = Double.NEGATIVE_INFINITY;
      for (se.swedishpolls.estimation.DailyStateSpace.Parameters point : GRID.points())
        best = Math.max(best, DailyStateSpace.logLikelihood(batch, ELECTIONS, point));
      assertEquals(best, resolved.logLikelihood(), 0);
      assertEquals(best, DailyStateSpace.logLikelihood(batch, ELECTIONS, resolved.parameters()), 0);
    }
  }

  @Test
  void gridBoundariesAreRecordedOnEveryAxisWhoseOptimumSitsAtAnEnd() {
    final java.lang.StringBuilder rows = new StringBuilder();
    for (int week = 0; week < 20; week++)
      rows.append(row("Sifo", week * 7, week * 7 + 1, "24"))
          .append(row("Novus", week * 7 + 2, week * 7 + 3, "18"));
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls = polls(rows.toString());
    // A single-valued axis is a fixed parameter, so its optimum is at both ends of that axis.
    final se.swedishpolls.estimation.DevelopmentTuning.Grid fixed =
        new DevelopmentTuning.Grid(List.of(1e-4), List.of(0.05), List.of(1.5));
    assertEquals(
        List.of(
            "walkVariance:lower",
            "walkVariance:upper",
            "houseScale:lower",
            "houseScale:upper",
            "covarianceMultiplier:lower",
            "covarianceMultiplier:upper"),
        DevelopmentTuning.tune(period(false), polls, ELECTIONS, FOLD, fixed).gridBoundaries());

    // Two steadily disagreeing institutes need a house scale above 1e-9, so that axis hits its
    // upper end.
    final se.swedishpolls.estimation.DevelopmentTuning.Grid narrow =
        new DevelopmentTuning.Grid(List.of(1e-9, 1e-8), List.of(1e-9, 1e-8), List.of(1.0, 1e6));
    final se.swedishpolls.estimation.DevelopmentTuning.Resolved resolved =
        DevelopmentTuning.tune(period(false), polls, ELECTIONS, FOLD, narrow);
    assertTrue(resolved.onGridBoundary());
    assertTrue(resolved.gridBoundaries().contains("houseScale:upper"), resolved::toString);
  }

  @Test
  void anExactTieKeepsTheSmallestParametersInGridOrder() {
    // A single poll on the period start day never applies the daily walk, so every walk variance
    // ties exactly.
    final se.swedishpolls.estimation.DevelopmentTuning.Resolved resolved =
        DevelopmentTuning.tune(
            period(false), polls(row("Novus", 0, 0, "20")), ELECTIONS, FOLD, GRID);
    assertEquals(1e-5, resolved.parameters().walkVariance());
    assertEquals(GRID.walkVariances().getFirst(), resolved.parameters().walkVariance());
  }

  @Test
  void tuneAllListsUnresolvedFoldsAndBlocksTheGateWithoutLosingTheOtherFolds() {
    final java.lang.StringBuilder rows = new StringBuilder();
    for (int week = 0; week < 24; week++)
      rows.append(row("Sifo", week * 7, week * 7 + 1, week % 2 == 0 ? "24" : "20"))
          .append(row("Novus", week * 7 + 2, week * 7 + 3, "18"));
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls = polls(rows.toString());
    final se.swedishpolls.estimation.DevelopmentTuning.Fold late =
        new DevelopmentTuning.Fold(LocalDate.of(2013, 12, 31), LocalDate.of(2014, 2, 4));
    final se.swedishpolls.estimation.DevelopmentTuning.Protocol protocol =
        new DevelopmentTuning.Protocol("test", List.of(late, FOLD), GRID);

    final se.swedishpolls.estimation.DevelopmentTuning.Tuning tuning =
        DevelopmentTuning.tuneAll(List.of(period(false), period(true)), polls, ELECTIONS, protocol);

    // The empty fold is listed for both rosters and the two usable folds still resolve.
    assertEquals(2, tuning.resolved().size());
    assertEquals(2, tuning.unresolved().size());
    assertTrue(
        tuning.unresolved().stream()
            .allMatch(
                u ->
                    u.fold().equals(late)
                        && u.reason().contains("no eligible training observation")));
    assertTrue(tuning.gate().blocked());
    assertTrue(
        tuning.gate().reasons().stream().anyMatch(reason -> reason.contains("did not resolve")),
        () -> tuning.gate().reasons().toString());

    // A failed fit is listed with its reason too, rather than stopping the periods and folds after
    // it.
    final se.swedishpolls.estimation.DevelopmentTuning.Grid overflowing =
        new DevelopmentTuning.Grid(List.of(Double.MAX_VALUE), List.of(0.05), List.of(1.0));
    final se.swedishpolls.estimation.DevelopmentTuning.Tuning failing =
        DevelopmentTuning.tuneAll(
            List.of(period(false)),
            polls,
            ELECTIONS,
            new DevelopmentTuning.Protocol("test", List.of(FOLD), overflowing));
    assertEquals(List.of(), failing.resolved());
    assertEquals(1, failing.unresolved().size());
    assertTrue(
        failing.unresolved().getFirst().reason().contains("Failed fit"),
        () -> failing.unresolved().getFirst().reason());
    assertTrue(failing.gate().blocked());
  }

  @Test
  void emptyFoldsAndFailedFitsStopTuningInsteadOfBeingDroppedFromTheGrid() {
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        polls(row("Novus", 30, 32, "20"));
    final se.swedishpolls.estimation.DevelopmentTuning.Fold early =
        new DevelopmentTuning.Fold(LocalDate.of(2013, 12, 31), LocalDate.of(2014, 2, 4));
    final java.lang.IllegalArgumentException empty =
        assertThrows(
            IllegalArgumentException.class,
            () -> DevelopmentTuning.tune(period(false), polls, ELECTIONS, early, GRID));
    assertTrue(empty.getMessage().contains("Empty fold"), empty::getMessage);

    // The walk overflows the opinion covariance before the poll, which the fit rejects rather than
    // clipping.
    final se.swedishpolls.estimation.DevelopmentTuning.Grid overflowing =
        new DevelopmentTuning.Grid(List.of(1e-4, Double.MAX_VALUE), List.of(0.05), List.of(1.0));
    final java.lang.IllegalArgumentException failed =
        assertThrows(
            IllegalArgumentException.class,
            () -> DevelopmentTuning.tune(period(false), polls, ELECTIONS, FOLD, overflowing));
    assertTrue(failed.getMessage().contains("Failed fit"), failed::getMessage);
  }

  @Test
  void theRegisteredProtocolFreezesTheFoldsAndTheCandidateGrid() {
    final se.swedishpolls.estimation.DevelopmentTuning.Protocol protocol =
        DevelopmentTuning.protocol(Path.of("docs", "validation", "protocol.json"));
    assertEquals("v1-development-1", protocol.version());
    assertEquals(48, protocol.folds().size());
    assertEquals(
        new DevelopmentTuning.Fold(LocalDate.of(2014, 1, 15), LocalDate.of(2014, 2, 19)),
        protocol.folds().getFirst());
    assertEquals(LocalDate.of(2021, 10, 5), protocol.folds().getLast().cutoff());
    assertTrue(
        protocol.folds().stream()
            .allMatch(fold -> fold.scoreThrough().equals(fold.cutoff().plusDays(35))),
        "Folds score 35 days ahead");
    assertEquals(List.of(1e-5, 3e-5, 1e-4, 3e-4, 1e-3), protocol.grid().walkVariances());
    assertEquals(List.of(0.02, 0.05, 0.1, 0.2), protocol.grid().houseScales());
    assertEquals(List.of(1.0, 1.5, 2.0, 3.0), protocol.grid().covarianceMultipliers());
    assertEquals(80, protocol.grid().points().size());
  }

  @Test
  void storedEvidenceReadsBackAndCarriesEachPeriodsLastCutoffPoint() {
    final se.swedishpolls.estimation.DevelopmentTuning.Tuning tuning =
        DevelopmentTuning.tuning(Path.of("docs", "validation", "tuning.json"));
    assertEquals("v1-development-1", tuning.protocolVersion());
    assertTrue(tuning.gate().blocked());
    assertFalse(tuning.resolved().isEmpty());

    final java.util.Map<java.lang.String, se.swedishpolls.estimation.DailyStateSpace.Parameters>
        parameters = DevelopmentTuning.latestParameters(tuning);

    assertEquals(
        tuning.resolved().stream()
            .map(DevelopmentTuning.Resolved::periodId)
            .distinct()
            .sorted()
            .toList(),
        parameters.keySet().stream().sorted().toList());
    for (java.util.Map.Entry<
            java.lang.String, se.swedishpolls.estimation.DailyStateSpace.Parameters>
        entry : parameters.entrySet()) {
      final se.swedishpolls.estimation.DevelopmentTuning.Resolved latest =
          tuning.resolved().stream()
              .filter(resolved -> resolved.periodId().equals(entry.getKey()))
              .max(java.util.Comparator.comparing(resolved -> resolved.fold().cutoff()))
              .orElseThrow();
      assertEquals(latest.parameters(), entry.getValue());
      assertTrue(tuning.grid().walkVariances().contains(entry.getValue().walkVariance()));
    }
  }

  @Test
  void anIncompleteProtocolNamesTheMissingField() throws Exception {
    final java.nio.file.Path file = temp.resolve("protocol.json");
    Files.writeString(file, "{\"version\":\"test\",\"development_folds\":[],\"tuning_grid\":{}}");

    final java.lang.IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> DevelopmentTuning.protocol(file));

    assertTrue(error.getMessage().contains("walk_variance"), error::getMessage);
  }
}
