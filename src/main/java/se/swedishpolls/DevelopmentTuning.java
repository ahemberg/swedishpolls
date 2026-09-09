package se.swedishpolls;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plug-in marginal-likelihood tuning of the three approved parameters inside the frozen development
 * folds.
 */
public final class DevelopmentTuning {
  private DevelopmentTuning() {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The frozen candidate grid. Each axis is finite, strictly ascending and admissible for a fit.
   */
  public record Grid(
      List<Double> walkVariances, List<Double> houseScales, List<Double> covarianceMultipliers) {
    public Grid {
      walkVariances = axis(walkVariances, "walkVariance", true);
      houseScales = axis(houseScales, "houseScale", false);
      covarianceMultipliers = axis(covarianceMultipliers, "covarianceMultiplier", false);
    }

    /**
     * Ascending walk variance, then house scale, then multiplier, so an exact tie keeps the first
     * point.
     */
    public List<DailyStateSpace.Parameters> points() {
      var points = new ArrayList<DailyStateSpace.Parameters>();
      for (double walk : walkVariances)
        for (double house : houseScales)
          for (double multiplier : covarianceMultipliers)
            points.add(new DailyStateSpace.Parameters(walk, house, multiplier));
      return List.copyOf(points);
    }
  }

  private static List<Double> axis(List<Double> values, String name, boolean zeroAllowed) {
    if (values == null || values.isEmpty())
      throw new IllegalArgumentException("Empty grid axis " + name);
    for (int i = 0; i < values.size(); i++) {
      double value = values.get(i);
      if (!Double.isFinite(value) || value < 0 || (value == 0 && !zeroAllowed))
        throw new IllegalArgumentException("Inadmissible " + name + " grid value " + value);
      if (i > 0 && value <= values.get(i - 1))
        throw new IllegalArgumentException("Grid axis " + name + " must be strictly ascending");
    }
    return List.copyOf(values);
  }

  /** One frozen publication-time development fold. Its scoring window belongs to checkpoint 9. */
  public record Fold(LocalDate cutoff, LocalDate scoreThrough) {
    public Fold {
      if (cutoff == null || scoreThrough == null || !scoreThrough.isAfter(cutoff))
        throw new IllegalArgumentException("A fold scores after its cutoff");
    }
  }

  public record Protocol(String version, List<Fold> folds, Grid grid) {
    public Protocol {
      folds = List.copyOf(folds);
    }
  }

  /**
   * One resolved fold fit for one coverage period. `gridBoundaries` names every axis whose optimum
   * sits at an end of the frozen grid, which the protocol requires a documented expansion to
   * resolve.
   */
  public record Resolved(
      String periodId,
      Fold fold,
      DailyStateSpace.Parameters parameters,
      double logLikelihood,
      int trainingPolls,
      int observations,
      int exclusions,
      Map<String, Integer> exclusionReasons,
      List<String> gridBoundaries) {
    public Resolved {
      exclusionReasons = Map.copyOf(exclusionReasons);
      gridBoundaries = List.copyOf(gridBoundaries);
    }

    public boolean onGridBoundary() {
      return !gridBoundaries.isEmpty();
    }
  }

  /**
   * A fold that produced no resolved parameters, from an empty training set or a failed fit. Never
   * dropped.
   */
  public record Unresolved(String periodId, Fold fold, String reason) {}

  /**
   * The aggregate development gate. It blocks until an owner records a decision on every reason
   * listed.
   */
  public record Gate(boolean blocked, List<String> reasons) {
    public Gate {
      reasons = List.copyOf(reasons);
    }
  }

  public record Tuning(
      String protocolVersion,
      Grid grid,
      Gate gate,
      List<Resolved> resolved,
      List<Unresolved> unresolved) {
    public Tuning {
      resolved = List.copyOf(resolved);
      unresolved = List.copyOf(unresolved);
    }
  }

  public static Protocol protocol(Path file) {
    try {
      var root = JSON.readTree(Files.readAllBytes(file));
      var folds = new ArrayList<Fold>();
      for (var fold : required(root, "development_folds", file))
        folds.add(
            new Fold(
                LocalDate.parse(required(fold, "cutoff", file).asString()),
                LocalDate.parse(required(fold, "score_through", file).asString())));
      var grid = required(root, "tuning_grid", file);
      return new Protocol(
          required(root, "version", file).asString(),
          folds,
          new Grid(
              values(grid, "walk_variance", file),
              values(grid, "house_scale", file),
              values(grid, "covariance_multiplier", file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode required(JsonNode parent, String field, Path file) {
    var value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull())
      throw new IllegalArgumentException(
          "Incomplete validation protocol in " + file + ": missing " + field);
    return value;
  }

  private static List<Double> values(JsonNode grid, String axis, Path file) {
    var values = new ArrayList<Double>();
    for (var value : required(grid, axis, file)) values.add(value.doubleValue());
    return values;
  }

  /**
   * The polls a fold may train on: eligible, with a known publication date on or before the cutoff.
   * Eligibility already rejects publication before fieldwork end, so the fieldwork end lies on or
   * before the cutoff too.
   */
  public static List<PollCsv.Poll> training(List<PollCsv.Poll> polls, Fold fold) {
    return polls.stream()
        .filter(PollCsv.Poll::publicationTimeEligible)
        .filter(poll -> !poll.publicationDate().isAfter(fold.cutoff()))
        .toList();
  }

  /**
   * Resolves the grid point with the highest plug-in marginal likelihood on one fold's training
   * data. Every point must fit finitely: a failed or nonfinite fit stops the fold instead of being
   * dropped from the grid.
   */
  public static Resolved tune(
      Roster.CoveragePeriod period,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      Fold fold,
      Grid grid) {
    var training = training(polls, fold);
    var batch = PollObservations.prepare(period, training);
    if (batch.observations().isEmpty())
      throw new IllegalArgumentException("Empty fold " + fold.cutoff() + " for " + period.id());
    return resolve(period.id(), batch, elections, fold, grid, training.size());
  }

  /**
   * Tunes every coverage period over every fold of one protocol. A fold that cannot resolve is
   * listed rather than dropped, and never stops the remaining folds; boundary optima and unresolved
   * folds block the gate.
   */
  public static Tuning tuneAll(
      List<Roster.CoveragePeriod> periods,
      List<PollCsv.Poll> polls,
      List<LocalDate> elections,
      Protocol protocol) {
    var resolved = new ArrayList<Resolved>();
    var unresolved = new ArrayList<Unresolved>();
    for (var period : periods)
      for (var fold : protocol.folds()) {
        var training = training(polls, fold);
        var batch = PollObservations.prepare(period, training);
        if (batch.observations().isEmpty()) {
          unresolved.add(
              new Unresolved(period.id(), fold, "no eligible training observation in the period"));
          continue;
        }
        try {
          resolved.add(
              resolve(period.id(), batch, elections, fold, protocol.grid(), training.size()));
        } catch (RuntimeException e) {
          unresolved.add(new Unresolved(period.id(), fold, e.getMessage()));
        }
      }
    return new Tuning(
        protocol.version(), protocol.grid(), gate(resolved, unresolved), resolved, unresolved);
  }

  /**
   * The protocol blocks the aggregate gate on every boundary optimum and every fold that did not
   * resolve.
   */
  private static Gate gate(List<Resolved> resolved, List<Unresolved> unresolved) {
    var reasons = new ArrayList<String>();
    long boundaries = resolved.stream().filter(Resolved::onGridBoundary).count();
    if (boundaries > 0)
      reasons.add(
          boundaries
              + " of "
              + resolved.size()
              + " resolved folds sit on a grid boundary, which needs a documented grid expansion and a repeat"
              + " of every affected development check, or the gate fails");
    if (!unresolved.isEmpty())
      reasons.add(
          unresolved.size()
              + " folds did not resolve and need a reviewed reason and a revised development protocol");
    return new Gate(!reasons.isEmpty(), reasons);
  }

  private static Resolved resolve(
      String periodId,
      PollObservations.Batch batch,
      List<LocalDate> elections,
      Fold fold,
      Grid grid,
      int trainingPolls) {
    var points = grid.points();
    var likelihoods =
        points.parallelStream()
            .mapToDouble(
                point -> {
                  double likelihood;
                  try {
                    likelihood = DailyStateSpace.logLikelihood(batch, elections, point);
                  } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                        "Failed fit at " + point + " for " + periodId + " fold " + fold.cutoff(),
                        e);
                  }
                  if (!Double.isFinite(likelihood))
                    throw new IllegalArgumentException(
                        "Nonfinite marginal likelihood at "
                            + point
                            + " for "
                            + periodId
                            + " fold "
                            + fold.cutoff());
                  return likelihood;
                })
            .toArray();
    int best = 0;
    for (int i = 1; i < likelihoods.length; i++) if (likelihoods[i] > likelihoods[best]) best = i;
    var resolved = points.get(best);
    // The likelihood path skips the daily joint factorization, so the stored point is confirmed by
    // a full fit.
    double retained = DailyStateSpace.fit(batch, elections, resolved).logLikelihood();
    if (Double.compare(retained, likelihoods[best]) != 0)
      throw new IllegalArgumentException(
          "Retained fit disagrees with the tuning likelihood at "
              + resolved
              + " for "
              + periodId
              + " fold "
              + fold.cutoff());
    var reasons = new java.util.TreeMap<String, Integer>();
    for (var exclusion : batch.exclusions())
      for (var reason : exclusion.reasons()) reasons.merge(reason, 1, Integer::sum);
    var boundaries = new ArrayList<String>();
    boundary(
        boundaries,
        "walkVariance",
        grid.walkVariances().indexOf(resolved.walkVariance()),
        grid.walkVariances().size());
    boundary(
        boundaries,
        "houseScale",
        grid.houseScales().indexOf(resolved.houseScale()),
        grid.houseScales().size());
    boundary(
        boundaries,
        "covarianceMultiplier",
        grid.covarianceMultipliers().indexOf(resolved.covarianceMultiplier()),
        grid.covarianceMultipliers().size());
    return new Resolved(
        periodId,
        fold,
        resolved,
        likelihoods[best],
        trainingPolls,
        batch.observations().size(),
        batch.exclusions().size(),
        reasons,
        boundaries);
  }

  private static void boundary(List<String> boundaries, String name, int index, int size) {
    if (index == 0) boundaries.add(name + ":lower");
    if (index == size - 1) boundaries.add(name + ":upper");
  }

  public static String report(Tuning tuning) {
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tuning);
  }

  /** Reads back a stored run of {@link #report}. */
  public static Tuning tuning(Path file) {
    try {
      return JSON.readValue(Files.readAllBytes(file), Tuning.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The point each period resolved on its last cutoff, which trains on the most data. These are
   * development parameters carrying the run's gate, never release values.
   */
  public static Map<String, DailyStateSpace.Parameters> latestParameters(Tuning tuning) {
    var latest = new java.util.LinkedHashMap<String, Resolved>();
    for (var resolved : tuning.resolved())
      latest.merge(
          resolved.periodId(),
          resolved,
          (kept, candidate) ->
              candidate.fold().cutoff().isAfter(kept.fold().cutoff()) ? candidate : kept);
    var parameters = new java.util.LinkedHashMap<String, DailyStateSpace.Parameters>();
    latest.forEach((periodId, resolved) -> parameters.put(periodId, resolved.parameters()));
    return Map.copyOf(parameters);
  }
}
