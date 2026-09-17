package se.swedishpolls.estimation;

import java.util.ArrayList;
import java.util.List;

/**
 * The training-only parameter search of the accepted {@code synthetic-recovery-v1} protocol.
 *
 * <p>The 180 registered points are evaluated in ascending walk, house and multiplier order on the
 * training observations alone, so no scoring observation can reach a selection. Every attempt is
 * retained with its status, an exact maximum tie keeps the first point, and a valid selection on a
 * grid endpoint stays in the result with its endpoints named. A failed or nonfinite likelihood
 * stops the search at that point rather than dropping it from the grid.
 */
final class SyntheticSearch {
  private SyntheticSearch() {}

  /** The registered grid, reusing the frozen axis validation of the development tuner. */
  static final DevelopmentTuning.Grid GRID =
      new DevelopmentTuning.Grid(
          List.of(0.000003, 0.00001, 0.00003, 0.0001, 0.0003, 0.001),
          List.of(0.01, 0.02, 0.05, 0.1, 0.2),
          List.of(0.5, 0.75, 1.0, 1.5, 2.0, 3.0));

  static final int POINTS = 180;

  static final String EVALUATED = "evaluated";
  static final String FAILED = "failed";

  /** One attempted grid point: what it was fitted at and what the fit returned. */
  record Attempt(
      int index,
      double walkVariance,
      double houseScale,
      double covarianceMultiplier,
      double logLikelihood,
      String status,
      String message) {}

  /**
   * One training-only search: every attempt it made, the point it selected and the grid endpoints
   * that point sits on. A search stopped by a numerical failure selects nothing.
   */
  record Selection(
      List<Attempt> attempts,
      int selectedIndex,
      DailyStateSpace.Parameters parameters,
      double logLikelihood,
      List<String> endpoints) {
    Selection {
      attempts = List.copyOf(attempts);
      endpoints = List.copyOf(endpoints);
    }

    @Override
    public List<Attempt> attempts() {
      return List.copyOf(attempts);
    }

    @Override
    public List<String> endpoints() {
      return List.copyOf(endpoints);
    }

    boolean failed() {
      return selectedIndex < 0;
    }

    /** The attempt that stopped the search, or null when every point was evaluated. */
    Attempt failure() {
      if (!failed()) return null;
      return attempts.get(attempts.size() - 1);
    }
  }

  /**
   * Maximizes the training marginal likelihood of one convention over the registered grid. The
   * scoring observations are not supplied, so they cannot take part in the selection.
   */
  static Selection select(PollObservations.Batch training, WindowFilter.Convention convention) {
    final List<DailyStateSpace.Parameters> points = GRID.points();
    final List<Attempt> attempts = new ArrayList<>();
    int selected = -1;
    double best = Double.NEGATIVE_INFINITY;
    for (int index = 0; index < points.size(); index++) {
      final DailyStateSpace.Parameters point = points.get(index);
      double likelihood = Double.NaN;
      String status = EVALUATED;
      String message = null;
      try {
        likelihood = WindowFilter.logLikelihood(training, List.of(), point, convention);
        if (!Double.isFinite(likelihood)) {
          status = FAILED;
          message = "Nonfinite training marginal likelihood";
        }
      } catch (RuntimeException e) {
        status = FAILED;
        message = e.getMessage() == null ? e.toString() : e.getMessage();
      }
      attempts.add(
          new Attempt(
              index,
              point.walkVariance(),
              point.houseScale(),
              point.covarianceMultiplier(),
              likelihood,
              status,
              message));
      // A failed point stops the search: it is never skipped, and the grid is never expanded.
      if (status.equals(FAILED)) return new Selection(attempts, -1, null, Double.NaN, List.of());
      // Strictly greater, so an exact maximum tie keeps the first point of the registered order.
      if (likelihood > best) {
        best = likelihood;
        selected = index;
      }
    }
    final DailyStateSpace.Parameters parameters = points.get(selected);
    return new Selection(attempts, selected, parameters, best, endpoints(parameters));
  }

  /** The axes whose selected value sits at an end of the frozen grid. */
  private static List<String> endpoints(DailyStateSpace.Parameters parameters) {
    final List<String> endpoints = new ArrayList<>();
    endpoint(endpoints, "walkVariance", GRID.walkVariances(), parameters.walkVariance());
    endpoint(endpoints, "houseScale", GRID.houseScales(), parameters.houseScale());
    endpoint(
        endpoints,
        "covarianceMultiplier",
        GRID.covarianceMultipliers(),
        parameters.covarianceMultiplier());
    return endpoints;
  }

  private static void endpoint(List<String> endpoints, String axis, List<Double> values, double v) {
    final int index = values.indexOf(v);
    if (index == 0) endpoints.add(axis + ":lower");
    if (index == values.size() - 1) endpoints.add(axis + ":upper");
  }
}
