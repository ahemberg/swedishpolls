package se.swedishpolls;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM;
import org.ejml.interfaces.decomposition.CholeskyDecomposition_F64;
import org.ejml.interfaces.linsol.LinearSolverDense;
import org.ejml.simple.SimpleMatrix;

/** Fixed-parameter ilr fit for one prepared coverage-period batch. No house effects yet. */
public final class DailyStateSpace {
    private DailyStateSpace() {}

    public record Parameters(double walkVariance, double covarianceMultiplier) {}
    public record Day(LocalDate date, SimpleMatrix filteredMean, SimpleMatrix filteredCovariance,
                      SimpleMatrix smoothedMean, SimpleMatrix smoothedCovariance) {}
    public record Fit(PollObservations.Batch batch, Parameters parameters, List<Day> days, double logLikelihood) {
        public Fit { days = List.copyOf(days); }
    }

    /** Returns internal daily states from the period start through the last observation midpoint. */
    public static Fit fit(PollObservations.Batch batch, Parameters parameters) {
        if (!Double.isFinite(parameters.walkVariance()) || parameters.walkVariance() < 0
                || !Double.isFinite(parameters.covarianceMultiplier()) || parameters.covarianceMultiplier() <= 0)
            throw new IllegalArgumentException("Walk variance must be finite and nonnegative; covariance multiplier finite and positive");
        if (batch.observations().isEmpty()) throw new IllegalArgumentException("No observations to fit");
        int dimension = batch.components().size() - 1;
        for (var observation : batch.observations()) {
            if (observation.midpoint().isBefore(batch.period().effectiveFrom())
                    || batch.period().effectiveTo() != null && observation.midpoint().isAfter(batch.period().effectiveTo()))
                throw new IllegalArgumentException("Observation outside coverage period");
            if (observation.ilr().getNumRows() != dimension || observation.ilr().getNumCols() != 1
                    || observation.ilr().hasUncountable() || observation.covariance().getNumRows() != dimension
                    || observation.covariance().getNumCols() != dimension)
                throw new IllegalArgumentException("Invalid observation dimensions or values");
            factor(observation.covariance());
        }
        var identity = SimpleMatrix.identity(dimension);
        var mean = new SimpleMatrix(dimension, 1);
        var covariance = identity.scale(4);
        var walk = identity.scale(parameters.walkVariance());
        var observations = batch.observations().stream().sorted(Comparator
                .comparing(PollObservations.Observation::midpoint)
                .thenComparingInt(o -> o.poll().rowNumber())).toList();
        var days = new ArrayList<Day>();
        double logLikelihood = 0;
        int index = 0;
        for (var date = batch.period().effectiveFrom(); !date.isAfter(observations.getLast().midpoint()); date = date.plusDays(1)) {
            if (!days.isEmpty()) covariance = covariance.plus(walk);
            while (index < observations.size() && observations.get(index).midpoint().equals(date)) {
                var observation = observations.get(index++);
                var noise = observation.covariance().scale(parameters.covarianceMultiplier());
                var innovation = observation.ilr().minus(mean);
                var system = factor(covariance.plus(noise));
                var gain = system.solve(covariance).transpose();
                logLikelihood -= 0.5 * (dimension * Math.log(2 * Math.PI) + system.logDeterminant()
                        + innovation.dot(system.solve(innovation)));
                mean = mean.plus(gain.mult(innovation));
                var remaining = identity.minus(gain);
                // Joseph form avoids subtracting two nearly equal covariances at the diffuse first update.
                covariance = symmetric(remaining.mult(covariance).mult(remaining.transpose())
                        .plus(gain.mult(noise).mult(gain.transpose())));
            }
            if (mean.hasUncountable() || !Double.isFinite(logLikelihood))
                throw new IllegalArgumentException("Nonfinite filtered state or likelihood");
            factor(covariance);
            days.add(new Day(date, mean.copy(), covariance.copy(), mean.copy(), covariance.copy()));
        }
        for (int t = days.size() - 2; t >= 0; t--) {
            var day = days.get(t);
            var next = days.get(t + 1);
            var predicted = day.filteredCovariance().plus(walk);
            var gain = factor(predicted).solve(day.filteredCovariance()).transpose();
            var smoothedMean = day.filteredMean().plus(gain.mult(next.smoothedMean().minus(day.filteredMean())));
            var smoothedCovariance = symmetric(day.filteredCovariance().plus(gain
                    .mult(next.smoothedCovariance().minus(predicted)).mult(gain.transpose())));
            if (smoothedMean.hasUncountable()) throw new IllegalArgumentException("Nonfinite smoothed state");
            factor(smoothedCovariance);
            days.set(t, new Day(day.date(), day.filteredMean(), day.filteredCovariance(), smoothedMean, smoothedCovariance));
        }
        return new Fit(batch, parameters, days, logLikelihood);
    }

    private static SimpleMatrix symmetric(SimpleMatrix matrix) {
        return matrix.plus(matrix.transpose()).scale(0.5);
    }

    private record Factor(LinearSolverDense<DMatrixRMaj> solver, double logDeterminant) {
        SimpleMatrix solve(SimpleMatrix right) {
            var result = new SimpleMatrix(right.getNumRows(), right.getNumCols());
            solver.solve(right.getDDRM().copy(), result.getDDRM());
            return result;
        }
    }

    private static Factor factor(SimpleMatrix covariance) {
        if (covariance.hasUncountable() || covariance.minus(covariance.transpose()).elementMaxAbs()
                > 1e-12 * covariance.elementMaxAbs())
            throw new IllegalArgumentException("Covariance must be finite and symmetric");
        var solver = LinearSolverFactory_DDRM.symmPosDef(covariance.getNumRows());
        if (!solver.setA(covariance.getDDRM().copy()))
            throw new IllegalArgumentException("Covariance is not positive definite");
        CholeskyDecomposition_F64<DMatrixRMaj> decomposition = solver.getDecomposition();
        var lower = decomposition.getT(null);
        double logDeterminant = 0;
        for (int i = 0; i < lower.numRows; i++) logDeterminant += 2 * Math.log(lower.get(i, i));
        if (!Double.isFinite(logDeterminant)) throw new IllegalArgumentException("Nonfinite covariance factorization");
        return new Factor(solver, logDeterminant);
    }
}
