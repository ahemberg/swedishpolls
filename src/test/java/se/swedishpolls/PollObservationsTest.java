package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.testsupport.PollCsvFixtures;

class PollObservationsTest {
  private static final String ROW = PollCsvFixtures.SEGMENT_ROW.replace("20.123", "20");

  @Test
  void modelValuesDoNotExposeTheirMatrixForMutation() {
    final org.ejml.simple.SimpleMatrix source = new SimpleMatrix(new double[][] {{1, 2}, {3, 4}});
    final se.swedishpolls.ModelValues values = ModelValues.copyOf(source);
    final org.ejml.simple.SimpleMatrix matrix = values.copy();

    source.set(0, 0, 98);
    matrix.set(0, 0, 99);

    assertEquals(1, values.get(0, 0));
  }

  private static Roster.CoveragePeriod period(boolean fi) {
    return new Roster.CoveragePeriod(
        fi ? "fi_candidate" : "eight",
        LocalDate.of(2014, 4, 9),
        LocalDate.of(2018, 9, 7),
        fi ? Stream.concat(PollCsv.PARTIES.stream(), Stream.of("FI")).toList() : PollCsv.PARTIES,
        fi,
        !fi,
        "https://example.invalid/decision");
  }

  @Test
  void replacesOnlyExactZerosAndPreservesPositiveRatiosAndSourceValues() {
    final java.lang.String row = ROW.replace(",20,5,", ",0,5,").replace(",17,1,10,", ",17,0,10,");
    for (boolean fi : List.of(false, true)) {
      final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
          PollCsv.parse(PollCsvFixtures.csv(row));
      final se.swedishpolls.PollObservations.Batch batch =
          PollObservations.prepare(period(fi), polls);
      final se.swedishpolls.PollObservations.Observation observation =
          batch.observations().getFirst();
      assertEquals(fi ? 2 : 1, observation.replacedZeros());
      final org.ejml.simple.SimpleMatrix p = proportions(batch);
      assertEquals(0.0005, p.get(0), 1e-15);
      assertEquals(fi ? 0.04995 : 0.049975, p.get(1), 1e-15);
      assertEquals(fi ? 0.21978 : 0.21989, p.get(p.getNumRows() - 1), 1e-15);
      if (fi) assertEquals(0.0005, p.get(8), 1e-15);
      assertEquals(6, p.get(4) / p.get(1), 1e-13);
      assertEquals("0", polls.getFirst().raw().get("M"));
      assertEquals(0, polls.getFirst().shares().get("M").signum());
      checkGeometryAndDenseCovariance(batch, p, 1000);
    }
    final se.swedishpolls.PollObservations.Batch capped =
        PollObservations.prepare(
            period(true), PollCsv.parse(PollCsvFixtures.csv(row.replace(",1000,", ",1,"))));
    assertEquals(0.25, proportions(capped).get(0), 1e-14);
    assertEquals(0.25, proportions(capped).get(8), 1e-14);
  }

  @Test
  void sharesInvertTheTransformBackToTheReplacedCompositionInComponentOrder() {
    for (boolean fi : List.of(false, true)) {
      final se.swedishpolls.PollObservations.Batch batch =
          PollObservations.prepare(period(fi), PollCsv.parse(PollCsvFixtures.csv(ROW)));
      final java.util.Map<java.lang.String, java.lang.Double> shares =
          PollObservations.shares(batch, batch.observations().getFirst().ilr());
      assertEquals(batch.components(), List.copyOf(shares.keySet()));
      assertEquals(100, shares.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
      final org.ejml.simple.SimpleMatrix expected = proportions(batch);
      for (int c = 0; c < batch.components().size(); c++)
        assertEquals(100 * expected.get(c), shares.get(batch.components().get(c)), 1e-11);
      assertEquals(20, shares.get("M"), 1e-11);
      assertEquals(fi ? 1 : 2, shares.get(fi ? "FI" : "OTHER"), 1e-11);
      // A state far from the data still closes to a composition instead of overflowing.
      final org.ejml.simple.SimpleMatrix extreme = batch.observations().getFirst().ilr().scale(400);
      assertEquals(
          100,
          PollObservations.shares(batch, extreme).values().stream()
              .mapToDouble(Double::doubleValue)
              .sum(),
          1e-12);
      for (org.ejml.simple.SimpleMatrix invalid :
          List.of(
              new SimpleMatrix(batch.components().size(), 1),
              new SimpleMatrix(batch.components().size() - 1, 2)))
        assertThrows(IllegalArgumentException.class, () -> PollObservations.shares(batch, invalid));
    }
  }

  private static SimpleMatrix proportions(PollObservations.Batch batch) {
    final org.ejml.simple.SimpleMatrix p =
        batch.basis().transpose().mult(batch.observations().getFirst().ilr().copy()).elementExp();
    return p.divide(p.elementSum());
  }

  @Test
  void keepsOneObservationPerPollAndRecordsExclusionsWithoutChangingRoster() {
    final java.lang.String rows =
        ROW.replace("2016-06-19", "2016-06-02")
            + ROW.replace("Ipsos", "Novus")
                .replace("2016-06-19", "2016-06-01")
                .replace("2016-06-20", "NA")
            + ROW.replace("Ipsos", "MissingFi").replace(",17,1,10,", ",17,NA,10,")
            + ROW.replace("Ipsos", "VALU")
            + ROW.replace("Ipsos", "Invalid").replace(",1000,", ",0,")
            + ROW.replace("Ipsos", "Missing").replace(",20,5,", ",NA,5,")
            + ROW.replace("Ipsos", "NegativeResidual").replace(",17,1,10,", ",17,3,10,")
            + ROW.replace("Ipsos", "Straddles").replace("2016-06-01", "2014-04-08")
            + ROW.replace("Ipsos", "Reversed").replace("2016-06-19", "2016-05-31");
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        PollCsv.parse(PollCsvFixtures.csv(rows));
    final se.swedishpolls.PollObservations.Batch batch =
        PollObservations.prepare(period(true), polls);
    assertEquals(
        List.of(1, 2), batch.observations().stream().map(o -> o.poll().rowNumber()).toList());
    assertEquals(
        List.of(LocalDate.of(2016, 6, 1), LocalDate.of(2016, 6, 1)),
        batch.observations().stream().map(PollObservations.Observation::midpoint).toList());
    assertNull(batch.observations().getLast().poll().publicationDate());
    assertFalse(batch.period().supportValidated());
    assertEquals(List.of("missing_share:FI"), batch.exclusions().getFirst().reasons());
    assertEquals(List.of("exit_or_election_day"), batch.exclusions().get(1).reasons());
    assertTrue(batch.exclusions().get(2).reasons().contains("invalid_sample_size"));
    assertTrue(batch.exclusions().get(3).reasons().contains("missing_share:M"));
    assertEquals(List.of("negative_residual"), batch.exclusions().get(4).reasons());
    assertEquals(
        List.of("outside_coverage_period:fi_candidate"), batch.exclusions().get(5).reasons());
    assertTrue(batch.exclusions().get(6).reasons().contains("reversed_collection_dates"));
    final se.swedishpolls.PollObservations.Batch eight =
        PollObservations.prepare(period(false), polls);
    assertEquals(
        List.of(1, 2, 3, 7), eight.observations().stream().map(o -> o.poll().rowNumber()).toList());
  }

  @Test
  void rejectsMalformedRostersAndUnrepresentableNumericalInputs() {
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
        PollCsv.parse(PollCsvFixtures.csv(ROW));
    for (java.util.List<java.lang.String> roster :
        List.of(List.of("M"), Stream.concat(PollCsv.PARTIES.stream(), Stream.of("M")).toList())) {
      final se.swedishpolls.source.Roster.CoveragePeriod invalid =
          new Roster.CoveragePeriod(
              "bad",
              period(false).effectiveFrom(),
              period(false).effectiveTo(),
              roster,
              false,
              false,
              "https://example.invalid");
      assertThrows(IllegalArgumentException.class, () -> PollObservations.prepare(invalid, polls));
    }
    // These remain valid decimal source values; double underflow must not turn a positive share
    // into a valid zero.
    for (java.lang.String row :
        List.of(ROW.replace(",20,5,", ",1e-999,5,"), ROW.replace(",1000,", ",1e999,")))
      assertThrows(
          IllegalArgumentException.class,
          () -> PollObservations.prepare(period(false), PollCsv.parse(PollCsvFixtures.csv(row))));
  }

  @Test
  void handlesZeroRemaindersWithoutClippingSmallPositiveSharesAndFollowsDeclaredOrder() {
    for (boolean fi : List.of(false, true)) {
      final java.lang.String zeroRemainder = ROW.replace(",20,5,", fi ? ",21,5," : ",22,5,");
      final se.swedishpolls.PollObservations.Batch batch =
          PollObservations.prepare(period(fi), PollCsv.parse(PollCsvFixtures.csv(zeroRemainder)));
      assertEquals(1, batch.observations().getFirst().replacedZeros());
      assertEquals(0.0005, proportions(batch).get(batch.components().size() - 1), 1e-15);
    }
    final se.swedishpolls.PollObservations.Batch small =
        PollObservations.prepare(
            period(false),
            PollCsv.parse(PollCsvFixtures.csv(ROW.replace(",20,5,", ",0.000001,5,"))));
    assertEquals(0, small.observations().getFirst().replacedZeros());
    assertEquals(1e-8, proportions(small).get(0), 1e-20);
    final se.swedishpolls.source.Roster.CoveragePeriod reverse =
        new Roster.CoveragePeriod(
            "reverse",
            period(false).effectiveFrom(),
            period(false).effectiveTo(),
            PollCsv.PARTIES.reversed(),
            false,
            true,
            "https://example.invalid");
    final se.swedishpolls.PollObservations.Batch reordered =
        PollObservations.prepare(reverse, PollCsv.parse(PollCsvFixtures.csv(ROW)));
    assertEquals("SD", reordered.components().getFirst());
    assertEquals(0.17, proportions(reordered).get(0), 1e-14);
    assertEquals(0.20, proportions(reordered).get(7), 1e-14);
    checkGeometryAndDenseCovariance(reordered, proportions(reordered), 1000);
  }

  private static void checkGeometryAndDenseCovariance(
      PollObservations.Batch batch, SimpleMatrix p, double n) {
    final se.swedishpolls.ModelValues h = batch.basis();
    assertTrue(h.mult(h.transpose()).minus(SimpleMatrix.identity(h.getNumRows())).normF() < 1e-14);
    final org.ejml.simple.SimpleMatrix ones = new SimpleMatrix(h.getNumCols(), 1);
    ones.fill(1);
    assertTrue(h.mult(ones).normF() < 1e-14);
    // Unsimplified delta method: J Cov(p) J', including all negative multinomial cross-terms.
    final org.ejml.simple.SimpleMatrix diagonal = new SimpleMatrix(p.getNumRows(), p.getNumRows());
    final org.ejml.simple.SimpleMatrix inverse = diagonal.copy();
    for (int i = 0; i < p.getNumRows(); i++) {
      diagonal.set(i, i, p.get(i));
      inverse.set(i, i, 1 / p.get(i));
    }
    final org.ejml.simple.SimpleMatrix jacobian = h.mult(inverse);
    final org.ejml.simple.SimpleMatrix reference =
        jacobian.mult(diagonal.minus(p.mult(p.transpose())).divide(n)).mult(jacobian.transpose());
    final se.swedishpolls.ModelValues actual = batch.observations().getFirst().covariance();
    assertTrue(actual.minus(reference).normF() < 1e-12);
    assertEquals(0, actual.minus(actual.transpose()).normF());
    assertTrue(
        DecompositionFactory_DDRM.chol(actual.getNumRows(), true)
            .decompose(actual.copy().getDDRM()));
  }

  @Test
  void convertsBothRostersAtTheMidpointWithReferenceIlrAndFullCovariance() {
    // Independent 50-digit Decimal log/sqrt calculations for the stated percentage composition,
    // n=1000.
    final double[][] expected = {
      {
        0.9802581434685472,
        0.18219594670412284,
        0.5358670722168778,
        -1.1875175397137705,
        0.2369884708195098,
        0.6354306213564618,
        -0.5944380354331128,
        1.4934286579968497
      },
      {
        0.9802581434685472,
        0.18219594670412284,
        0.5358670722168778,
        -1.1875175397137705,
        0.2369884708195098,
        0.6354306213564618,
        -0.5944380354331128,
        2.146934086975881,
        1.9202762246758065
      }
    };
    for (boolean fi : List.of(false, true)) {
      final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls =
          PollCsv.parse(PollCsvFixtures.csv(ROW));
      final se.swedishpolls.PollObservations.Batch batch =
          PollObservations.prepare(period(fi), polls);
      assertEquals(1, batch.observations().size());
      assertTrue(batch.exclusions().isEmpty());
      final se.swedishpolls.PollObservations.Observation observation =
          batch.observations().getFirst();
      assertSame(polls.getFirst(), observation.poll());
      assertEquals(LocalDate.of(2016, 6, 10), observation.midpoint());
      assertEquals(period(fi), batch.period());
      assertEquals(fi ? "RESIDUAL" : "OTHER", batch.components().getLast());
      assertEquals(0, observation.replacedZeros());
      assertArrayEquals(expected[fi ? 1 : 0], observation.ilr().toArray(), 1e-13);
      final se.swedishpolls.ModelValues covariance = observation.covariance();
      assertEquals(0.0125, covariance.get(0, 0), 1e-15);
      assertEquals(-0.004330127018922193, covariance.get(0, 1), 1e-15);
      final int last = covariance.getNumRows() - 1;
      assertEquals(
          fi ? 0.09221350762527233 : 0.04582244008714597, covariance.get(last, last), 1e-15);
      assertEquals(
          fi ? -0.008705563128087417 : 0.0008213933808860658,
          covariance.get(last, last - 1),
          1e-15);
      checkGeometryAndDenseCovariance(batch, proportions(batch), 1000);
    }
  }
}
