package se.swedishpolls;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM;
import org.ejml.interfaces.decomposition.CholeskyDecomposition_F64;
import org.ejml.interfaces.linsol.LinearSolverDense;
import org.ejml.simple.SimpleMatrix;

/** Fixed-parameter ilr fit for one prepared coverage-period batch, with house effects per election cycle. */
public final class DailyStateSpace {
    private DailyStateSpace() {}

    public record Parameters(double walkVariance, double houseScale, double covarianceMultiplier) {}

    /** Opinion state of one day, centered over the institutes active in its cycle. */
    public record Day(LocalDate date, SimpleMatrix filteredMean, SimpleMatrix filteredCovariance,
                      SimpleMatrix smoothedMean, SimpleMatrix smoothedCovariance) {}

    /** Centered house effects of one election cycle, stacked in effect order and conditioned on its last day. */
    public record Cycle(LocalDate start, LocalDate end, List<String> effects, List<Double> weights,
                        SimpleMatrix filteredMean, SimpleMatrix filteredCovariance,
                        SimpleMatrix smoothedMean, SimpleMatrix smoothedCovariance) {
        public Cycle {
            effects = List.copyOf(effects);
            weights = List.copyOf(weights);
        }
    }

    public record Fit(PollObservations.Batch batch, Parameters parameters, List<Day> days, List<Cycle> cycles,
                      double logLikelihood) {
        public Fit {
            days = List.copyOf(days);
            cycles = List.copyOf(cycles);
        }
    }

    /** The house-effect identity of a poll: its documented method era, otherwise its institute. */
    public static String effectIdentity(PollCsv.Poll poll) {
        return poll.methodEra() == null ? poll.institute() : poll.methodEra();
    }

    /** Returns internal daily states from the period start through the last observation midpoint. */
    public static Fit fit(PollObservations.Batch batch, List<LocalDate> elections, Parameters parameters) {
        var prepared = prepare(batch, elections, parameters);
        var forward = forward(prepared, parameters, true);
        var smoothed = smooth(prepared, forward, parameters);
        return new Fit(batch, parameters, smoothed.days(), smoothed.cycles(), forward.logLikelihood());
    }

    /**
     * Returns the plug-in marginal likelihood of one parameter point, running the same forward filter without
     * retaining daily states or smoothing. Every per-observation factorization check still applies.
     */
    public static double logLikelihood(PollObservations.Batch batch, List<LocalDate> elections,
                                       Parameters parameters) {
        return forward(prepare(batch, elections, parameters), parameters, false).logLikelihood();
    }

    private record Prepared(LocalDate start, int count, int dimension, List<PollObservations.Observation> observations,
                            List<Layout> layouts, List<SimpleMatrix> centers) {}

    private static Prepared prepare(PollObservations.Batch batch, List<LocalDate> elections, Parameters parameters) {
        if (!Double.isFinite(parameters.walkVariance()) || parameters.walkVariance() < 0
                || !Double.isFinite(parameters.houseScale()) || parameters.houseScale() <= 0
                || !Double.isFinite(parameters.covarianceMultiplier()) || parameters.covarianceMultiplier() <= 0)
            throw new IllegalArgumentException("Walk variance must be finite and nonnegative; house scale and covariance multiplier finite and positive");
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
        var observations = batch.observations().stream().sorted(Comparator
                .comparing(PollObservations.Observation::midpoint)
                .thenComparingInt(o -> o.poll().rowNumber())).toList();
        var start = batch.period().effectiveFrom();
        var last = observations.getLast().midpoint();
        var layouts = layouts(observations, elections, start, last, dimension);
        var centers = layouts.stream().map(DailyStateSpace::center).toList();
        return new Prepared(start, (int) ChronoUnit.DAYS.between(start, last) + 1, dimension, observations,
                layouts, centers);
    }

    private record Forward(double logLikelihood, SimpleMatrix[] filteredMeans, SimpleMatrix[] filteredCovariances,
                           int[] cycleOfDay, List<Anchor> anchors) {}

    /** The Kalman forward pass. Daily centered states and smoothing anchors are retained only when asked for. */
    private static Forward forward(Prepared prepared, Parameters parameters, boolean retain) {
        var observations = prepared.observations();
        var layouts = prepared.layouts();
        int dimension = prepared.dimension();
        int count = prepared.count();
        var cycleOfDay = retain ? new int[count] : null;
        var anchors = retain ? new ArrayList<Anchor>() : null;
        var filteredMeans = retain ? new SimpleMatrix[count] : null;
        var filteredCovariances = retain ? new SimpleMatrix[count] : null;
        var mean = new SimpleMatrix(layouts.getFirst().size(), 1);
        var covariance = prior(layouts.getFirst(), parameters);
        double logLikelihood = 0;
        int index = 0;
        int cycle = 0;
        for (int day = 0; day < count; day++) {
            var date = prepared.start().plusDays(day);
            boolean anchored = day == 0;
            if (day > 0 && cycle + 1 < layouts.size() && layouts.get(cycle + 1).start().equals(date)) {
                var reset = reset(mean, covariance, layouts.get(++cycle), parameters);
                mean = reset.mean();
                covariance = reset.covariance();
                anchored = true;
            } else if (day > 0) {
                addWalk(covariance, dimension, parameters.walkVariance());
            }
            if (retain) cycleOfDay[day] = cycle;
            while (index < observations.size() && observations.get(index).midpoint().equals(date)) {
                var observation = observations.get(index++);
                var design = design(layouts.get(cycle), layouts.get(cycle).effects()
                        .indexOf(effectIdentity(observation.poll())));
                var noise = observation.covariance().scale(parameters.covarianceMultiplier());
                var cross = covariance.mult(design.transpose());
                var system = factor(symmetric(design.mult(cross)).plus(noise));
                var gain = system.solve(cross.transpose()).transpose();
                var innovation = observation.ilr().minus(design.mult(mean));
                logLikelihood -= 0.5 * (dimension * Math.log(2 * Math.PI) + system.logDeterminant()
                        + innovation.dot(system.solve(innovation)));
                mean = mean.plus(gain.mult(innovation));
                var remaining = SimpleMatrix.identity(covariance.getNumRows()).minus(gain.mult(design));
                // Joseph form avoids subtracting two nearly equal covariances at the diffuse first update.
                covariance = symmetric(remaining.mult(covariance).mult(remaining.transpose())
                        .plus(gain.mult(noise).mult(gain.transpose())));
                anchored = true;
            }
            if (mean.hasUncountable() || !Double.isFinite(logLikelihood))
                throw new IllegalArgumentException("Nonfinite filtered state or likelihood");
            if (!retain) continue;
            factor(covariance);
            if (anchored) anchors.add(new Anchor(day, mean.copy(), covariance.copy()));
            var centered = project(prepared.centers().get(cycle), mean, covariance);
            filteredMeans[day] = centered.mean();
            filteredCovariances[day] = centered.covariance();
        }
        return new Forward(logLikelihood, filteredMeans, filteredCovariances, cycleOfDay, anchors);
    }

    /** Cycles start at the coverage-period start and at every election day it contains. */
    private record Layout(LocalDate start, LocalDate end, int dimension, List<String> effects, double[] weights) {
        int size() { return dimension * (1 + effects.size()); }
    }

    private static List<Layout> layouts(List<PollObservations.Observation> observations, List<LocalDate> elections,
                                        LocalDate start, LocalDate last, int dimension) {
        var starts = new ArrayList<>(List.of(start));
        LocalDate previous = null;
        for (var election : elections) {
            if (election == null || previous != null && !election.isAfter(previous))
                throw new IllegalArgumentException("Election dates must be distinct and ascending");
            previous = election;
            if (election.isAfter(start) && !election.isAfter(last)) starts.add(election);
        }
        var layouts = new ArrayList<Layout>();
        for (int cycle = 0; cycle < starts.size(); cycle++) {
            var from = starts.get(cycle);
            var to = cycle + 1 < starts.size() ? starts.get(cycle + 1).minusDays(1) : last;
            var eras = new TreeMap<String, TreeSet<String>>();
            for (var observation : observations)
                if (!observation.midpoint().isBefore(from) && !observation.midpoint().isAfter(to))
                    eras.computeIfAbsent(observation.poll().institute(), institute -> new TreeSet<>())
                            .add(effectIdentity(observation.poll()));
            var effects = eras.values().stream().flatMap(TreeSet::stream).distinct().sorted().toList();
            var weights = new double[effects.size()];
            // Every active institute carries 1/H, split equally over the method eras it used in this cycle.
            for (var institute : eras.values())
                for (var era : institute) weights[effects.indexOf(era)] += 1.0 / (eras.size() * institute.size());
            layouts.add(new Layout(from, to, dimension, effects, weights));
        }
        return layouts;
    }

    private record Anchor(int day, SimpleMatrix mean, SimpleMatrix covariance) {}
    private record State(SimpleMatrix mean, SimpleMatrix covariance) {}

    private record Smoothed(List<Day> days, List<Cycle> cycles) {}

    /** Adds the Rauch-Tung-Striebel smoothed states to the filtered days and centers each cycle's effects. */
    private static Smoothed smooth(Prepared prepared, Forward forward, Parameters parameters) {
        var start = prepared.start();
        var filteredMeans = forward.filteredMeans();
        var filteredCovariances = forward.filteredCovariances();
        var layouts = prepared.layouts();
        var centers = prepared.centers();
        var cycleOfDay = forward.cycleOfDay();
        var anchors = forward.anchors();
        var days = new Day[filteredMeans.length];
        var cycles = new Cycle[layouts.size()];
        int anchor = anchors.size() - 1;
        State smoothed = null;
        for (int day = days.length - 1; day >= 0; day--) {
            while (anchors.get(anchor).day() > day) anchor--;
            var layout = layouts.get(cycleOfDay[day]);
            var filtered = filtered(anchors.get(anchor), day, layout, parameters);
            if (smoothed == null) smoothed = filtered;
            else {
                var next = layouts.get(cycleOfDay[day + 1]);
                boolean transition = cycleOfDay[day] != cycleOfDay[day + 1];
                var predicted = transition ? reset(filtered.mean(), filtered.covariance(), next, parameters)
                        : new State(filtered.mean(), walked(filtered.covariance(), layout, parameters.walkVariance()));
                // A cycle transition keeps only the opinion block, so the cross-covariance drops the old effects.
                var cross = new SimpleMatrix(layout.size(), next.size());
                cross.insertIntoThis(0, 0, transition
                        ? filtered.covariance().extractMatrix(0, layout.size(), 0, layout.dimension())
                        : filtered.covariance());
                var gain = factor(predicted.covariance()).solve(cross.transpose()).transpose();
                var mean = filtered.mean().plus(gain.mult(smoothed.mean().minus(predicted.mean())));
                var covariance = symmetric(filtered.covariance().plus(gain
                        .mult(smoothed.covariance().minus(predicted.covariance())).mult(gain.transpose())));
                if (mean.hasUncountable()) throw new IllegalArgumentException("Nonfinite smoothed state");
                factor(covariance);
                smoothed = new State(mean, covariance);
            }
            var centered = project(centers.get(cycleOfDay[day]), smoothed.mean(), smoothed.covariance());
            days[day] = new Day(start.plusDays(day), filteredMeans[day], filteredCovariances[day],
                    centered.mean(), centered.covariance());
            if (layout.end().equals(days[day].date())) {
                var deviation = deviation(layout);
                var effects = project(deviation, filtered.mean(), filtered.covariance());
                var smoothedEffects = project(deviation, smoothed.mean(), smoothed.covariance());
                cycles[cycleOfDay[day]] = new Cycle(layout.start(), layout.end(), layout.effects(),
                        Arrays.stream(layout.weights()).boxed().toList(),
                        effects.mean(), effects.covariance(), smoothedEffects.mean(), smoothedEffects.covariance());
            }
        }
        return new Smoothed(List.of(days), List.of(cycles));
    }

    /** Between anchors the mean is unchanged and only the opinion block grows, by one walk per elapsed day. */
    private static State filtered(Anchor anchor, int day, Layout layout, Parameters parameters) {
        return new State(anchor.mean(), walked(anchor.covariance(), layout,
                parameters.walkVariance() * (day - anchor.day())));
    }

    /** A cycle transition carries the opinion state on and draws fresh independent house-effect priors. */
    private static State reset(SimpleMatrix mean, SimpleMatrix covariance, Layout to, Parameters parameters) {
        int dimension = to.dimension();
        var resetMean = new SimpleMatrix(to.size(), 1);
        var resetCovariance = new SimpleMatrix(to.size(), to.size());
        resetMean.insertIntoThis(0, 0, mean.extractMatrix(0, dimension, 0, 1));
        resetCovariance.insertIntoThis(0, 0, covariance.extractMatrix(0, dimension, 0, dimension));
        addWalk(resetCovariance, dimension, parameters.walkVariance());
        for (int i = dimension; i < to.size(); i++)
            resetCovariance.set(i, i, parameters.houseScale() * parameters.houseScale());
        return new State(resetMean, resetCovariance);
    }

    private static SimpleMatrix walked(SimpleMatrix covariance, Layout layout, double amount) {
        var walked = covariance.copy();
        addWalk(walked, layout.dimension(), amount);
        return walked;
    }

    private static State project(SimpleMatrix transform, SimpleMatrix mean, SimpleMatrix covariance) {
        return new State(transform.mult(mean),
                symmetric(transform.mult(covariance).mult(transform.transpose())));
    }

    private static SimpleMatrix prior(Layout layout, Parameters parameters) {
        var covariance = new SimpleMatrix(layout.size(), layout.size());
        for (int i = 0; i < layout.size(); i++)
            covariance.set(i, i, i < layout.dimension() ? 4 : parameters.houseScale() * parameters.houseScale());
        return covariance;
    }

    private static void addWalk(SimpleMatrix covariance, int dimension, double amount) {
        for (int i = 0; i < dimension; i++) covariance.set(i, i, covariance.get(i, i) + amount);
    }

    /** One poll observes the opinion state plus its own effect identity. */
    private static SimpleMatrix design(Layout layout, int effect) {
        int dimension = layout.dimension();
        var design = new SimpleMatrix(dimension, layout.size());
        for (int i = 0; i < dimension; i++) {
            design.set(i, i, 1);
            design.set(i, dimension * (1 + effect) + i, 1);
        }
        return design;
    }

    /** The centered opinion adds back the weighted ensemble effect, so the two centered blocks stay consistent. */
    private static SimpleMatrix center(Layout layout) {
        int dimension = layout.dimension();
        var center = new SimpleMatrix(dimension, layout.size());
        for (int i = 0; i < dimension; i++) {
            center.set(i, i, 1);
            for (int e = 0; e < layout.effects().size(); e++)
                center.set(i, dimension * (1 + e) + i, layout.weights()[e]);
        }
        return center;
    }

    private static SimpleMatrix deviation(Layout layout) {
        int dimension = layout.dimension();
        int effects = layout.effects().size();
        var deviation = new SimpleMatrix(dimension * effects, layout.size());
        for (int e = 0; e < effects; e++)
            for (int f = 0; f < effects; f++)
                for (int i = 0; i < dimension; i++)
                    deviation.set(dimension * e + i, dimension * (1 + f) + i, (e == f ? 1 : 0) - layout.weights()[f]);
        return deviation;
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
