package se.swedishpolls;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DailyStateSpaceTest {
    private static final LocalDate START = LocalDate.of(2016, 6, 1);

    private static PollObservations.Batch batch(boolean fi, String rows) {
        var period = new Roster.CoveragePeriod("test", START, START.plusDays(30),
                fi ? Stream.concat(PollCsv.PARTIES.stream(), Stream.of("FI")).toList() : PollCsv.PARTIES,
                fi, false, "https://example.invalid");
        return PollObservations.prepare(period, PollCsv.parse(PollCsvTest.csv(rows)));
    }

    private static String row(int from, int to) {
        return PollCsvTest.SEGMENT_ROW.replace("20.123", "20")
                .replace("2016-06-01", START.plusDays(from).toString())
                .replace("2016-06-19", START.plusDays(to).toString());
    }

    @Test
    void firstPollUpdatesAnIndependentDiffusePriorForBothRosters() {
        for (boolean fi : List.of(false, true)) {
            var batch = batch(fi, row(0, 0));
            int dimension = batch.basis().getNumRows();
            var mean = new SimpleMatrix(dimension, 1);
            mean.fill(2);
            var observation = new PollObservations.Observation(batch.observations().getFirst().poll(),
                    START, mean, SimpleMatrix.identity(dimension).scale(2), 0);
            batch = new PollObservations.Batch(batch.period(), batch.components(), batch.basis(),
                    List.of(observation), batch.exclusions());
            var fit = DailyStateSpace.fit(batch, new DailyStateSpace.Parameters(0.01, 1));
            assertEquals(1, fit.days().size());
            var day = fit.days().getFirst();
            assertEquals(START, day.date());
            // Scalar conjugate normal reference: prior N(0,4), observation 2 with variance 2.
            mean.fill(4.0 / 3);
            assertMatrix(mean, day.filteredMean(), 1e-14);
            assertMatrix(SimpleMatrix.identity(dimension).scale(4.0 / 3), day.filteredCovariance(), 1e-14);
            assertMatrix(day.filteredMean(), day.smoothedMean(), 0);
            assertMatrix(day.filteredCovariance(), day.smoothedCovariance(), 0);
            assertEquals(-2.148151601152033 * dimension, fit.logLikelihood(), 1e-12);
        }
    }

    private static void assertMatrix(SimpleMatrix expected, SimpleMatrix actual, double tolerance) {
        assertEquals(expected.getNumRows(), actual.getNumRows());
        assertEquals(expected.getNumCols(), actual.getNumCols());
        assertTrue(expected.minus(actual).elementMaxAbs() <= tolerance,
                () -> "Maximum error: " + expected.minus(actual).elementMaxAbs());
    }

    @Test
    void dailyFilteringAndSmoothingMatchDenseConditioningWithGapsAndOverlappingSameDayPolls() {
        for (boolean fi : List.of(false, true)) {
            // Deliberately out of order. Two overlapping windows have midpoint day 2; the last is day 5.
            var batch = batch(fi, row(4, 6).replace("20,5,", "18,5,")
                    + row(1, 3) + row(0, 4).replace("20,5,", "19,5,"));
            var parameters = new DailyStateSpace.Parameters(0.003, 1.5);
            var fit = DailyStateSpace.fit(batch, parameters);
            assertSame(batch, fit.batch());
            assertEquals(parameters, fit.parameters());
            assertEquals(6, fit.days().size());
            for (int t = 0; t < 6; t++) {
                var day = fit.days().get(t);
                assertEquals(START.plusDays(t), day.date());
                var available = batch.observations().stream().filter(o -> !o.midpoint().isAfter(day.date())).toList();
                var filtered = dense(available, t, batch.basis().getNumRows(), parameters);
                var smoothed = dense(batch.observations(), t, batch.basis().getNumRows(), parameters);
                assertMatrix(filtered[0], day.filteredMean(), 1e-11);
                assertMatrix(filtered[1], day.filteredCovariance(), 1e-11);
                assertMatrix(smoothed[0], day.smoothedMean(), 1e-11);
                assertMatrix(smoothed[1], day.smoothedCovariance(), 1e-11);
                assertEquals(smoothed[2].get(0), fit.logLikelihood(), 1e-10);
            }
            assertEquals(0, fit.days().getFirst().filteredMean().normF());
            assertMatrix(SimpleMatrix.identity(batch.basis().getNumRows()).scale(4),
                    fit.days().getFirst().filteredCovariance(), 0);
            assertTrue(fit.days().getFirst().smoothedMean().normF() > 0.1);
        }
    }

    @Test
    void rejectsInvalidParametersEmptyBatchesAndInvalidNumericsWithoutRepair() {
        var batch = batch(false, row(0, 0));
        var parameters = new DailyStateSpace.Parameters(0.003, 1);
        for (double value : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,
                    () -> DailyStateSpace.fit(batch, new DailyStateSpace.Parameters(value, 1)));
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,
                    () -> DailyStateSpace.fit(batch, new DailyStateSpace.Parameters(0.003, value)));
        assertThrows(IllegalArgumentException.class, () -> DailyStateSpace.fit(batch(false, ""), parameters));
        var observation = batch.observations().getFirst();
        int dimension = observation.ilr().getNumRows();
        var asymmetric = SimpleMatrix.identity(dimension);
        asymmetric.set(0, 1, 0.1);
        var indefinite = SimpleMatrix.identity(dimension);
        indefinite.set(0, 1, 2);
        indefinite.set(1, 0, 2);
        var nonfinite = SimpleMatrix.identity(dimension);
        nonfinite.set(0, 0, Double.NaN);
        for (var covariance : List.of(asymmetric, indefinite, nonfinite, new SimpleMatrix(dimension, dimension),
                SimpleMatrix.identity(dimension - 1))) {
            var invalid = replace(batch, new PollObservations.Observation(observation.poll(), START,
                    observation.ilr(), covariance, 0));
            assertThrows(IllegalArgumentException.class, () -> DailyStateSpace.fit(invalid, parameters));
        }
        var nonfiniteMean = observation.ilr().copy();
        nonfiniteMean.set(0, Double.POSITIVE_INFINITY);
        for (var mean : List.of(nonfiniteMean, new SimpleMatrix(dimension, 2))) {
            var invalid = replace(batch, new PollObservations.Observation(observation.poll(), START,
                    mean, observation.covariance(), 0));
            assertThrows(IllegalArgumentException.class, () -> DailyStateSpace.fit(invalid, parameters));
        }
        for (var date : List.of(START.minusDays(1), START.plusDays(31))) {
            var invalid = replace(batch, new PollObservations.Observation(observation.poll(), date,
                    observation.ilr(), observation.covariance(), 0));
            assertThrows(IllegalArgumentException.class, () -> DailyStateSpace.fit(invalid, parameters));
        }
        // Finite parameter values can still overflow during propagation. Never return a nonfinite fit.
        assertThrows(IllegalArgumentException.class, () -> DailyStateSpace.fit(batch(false, row(4, 6)),
                new DailyStateSpace.Parameters(Double.MAX_VALUE, 1)));
    }

    private static PollObservations.Batch replace(PollObservations.Batch batch, PollObservations.Observation observation) {
        return new PollObservations.Batch(batch.period(), batch.components(), batch.basis(), List.of(observation), batch.exclusions());
    }

    @Test
    void zeroWalkIsAStaticFitAndReorderingInputsDoesNotChangeResultsOrMutateTheBatch() {
        var batch = batch(true, row(0, 0) + row(2, 4).replace("20,5,", "18,5,"));
        var original = batch.observations().getFirst().ilr().copy();
        var covariance = batch.observations().getFirst().covariance().copy();
        var parameters = new DailyStateSpace.Parameters(0, 2);
        var fit = DailyStateSpace.fit(batch, parameters);
        var reversed = new PollObservations.Batch(batch.period(), batch.components(), batch.basis(),
                batch.observations().reversed(), batch.exclusions());
        var rerun = DailyStateSpace.fit(reversed, parameters);
        var reference = dense(batch.observations(), 0, batch.basis().getNumRows(), parameters);
        for (int i = 0; i < fit.days().size(); i++) {
            var day = fit.days().get(i);
            assertMatrix(reference[0], day.smoothedMean(), 1e-11);
            assertMatrix(reference[1], day.smoothedCovariance(), 1e-11);
            assertMatrix(day.filteredMean(), rerun.days().get(i).filteredMean(), 0);
            assertMatrix(day.smoothedCovariance(), rerun.days().get(i).smoothedCovariance(), 0);
        }
        assertEquals(fit.logLikelihood(), rerun.logLikelihood());
        assertMatrix(original, batch.observations().getFirst().ilr(), 0);
        assertMatrix(covariance, batch.observations().getFirst().covariance(), 0);
    }

    /** Independent batch Gaussian conditioning: Cov(x_t,x_s) = (4 + q min(t,s)) I.
     * Inverts the full observation joint covariance, with no sequential filter or backward recursion. */
    private static SimpleMatrix[] dense(List<PollObservations.Observation> observations, int day, int dimension,
                                        DailyStateSpace.Parameters parameters) {
        var prior = SimpleMatrix.identity(dimension).scale(4 + day * parameters.walkVariance());
        if (observations.isEmpty()) return new SimpleMatrix[]{new SimpleMatrix(dimension, 1), prior};
        int size = observations.size() * dimension;
        var joint = new SimpleMatrix(size, size);
        var cross = new SimpleMatrix(dimension, size);
        var values = new SimpleMatrix(size, 1);
        for (int i = 0; i < observations.size(); i++) {
            var observation = observations.get(i);
            long t = ChronoUnit.DAYS.between(START, observation.midpoint());
            values.insertIntoThis(i * dimension, 0, observation.ilr());
            cross.insertIntoThis(0, i * dimension,
                    SimpleMatrix.identity(dimension).scale(4 + parameters.walkVariance() * Math.min(day, t)));
            for (int j = 0; j < observations.size(); j++) {
                long s = ChronoUnit.DAYS.between(START, observations.get(j).midpoint());
                var block = SimpleMatrix.identity(dimension).scale(4 + parameters.walkVariance() * Math.min(t, s));
                if (i == j) block = block.plus(observation.covariance().scale(parameters.covarianceMultiplier()));
                joint.insertIntoThis(i * dimension, j * dimension, block);
            }
        }
        var inverse = joint.invert();
        var mean = cross.mult(inverse).mult(values);
        var covariance = prior.minus(cross.mult(inverse).mult(cross.transpose()));
        double logLikelihood = -0.5 * (size * Math.log(2 * Math.PI) + Math.log(joint.determinant())
                + values.dot(inverse.mult(values)));
        return new SimpleMatrix[]{mean, covariance, new SimpleMatrix(new double[][]{{logLikelihood}})};
    }
}
