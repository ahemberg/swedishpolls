package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;

class CoalitionPrecisionTest {
  @Test
  void theRegisteredDatesAndEverySubsetMustPassWithoutWideningFailedBounds() {
    for (final boolean individualFi : List.of(false, true)) {
      final Roster.CoveragePeriod period =
          CoverageValidationTest.period(
              "fixture", LocalDate.of(2015, 1, 1), null, individualFi, true);
      final List<PollCsv.Poll> polls =
          CoverageValidationTest.weekly(LocalDate.of(2015, 1, 5), LocalDate.of(2015, 1, 19), "1");
      final CoverageValidation.Rules coverage =
          CoverageValidation.rules(Path.of("docs/validation/protocol.json"));
      final EstimateHistory.Fitted fitted =
          EstimateHistory.fitted(
              period, polls, List.of(), new DailyStateSpace.Parameters(1e-4, 0.1, 1.5), coverage);
      final CoalitionPrecision.Rules strict =
          new CoalitionPrecision.Rules(100, List.of(1L, 2L), 400, 3L, 0, 0);
      final CoalitionPrecision.Report measured =
          CoalitionPrecision.evaluate(fitted, period.id(), strict);
      assertFalse(measured.passed());
      assertTrue(measured.dates().contains(LocalDate.of(2015, 1, 5)));
      assertTrue(measured.dates().contains(LocalDate.of(2015, 1, 12)));
      assertTrue(measured.dates().contains(LocalDate.of(2015, 1, 19)));
      assertEquals(measured.dates().size() * 255 * 2, measured.comparedSummaries());
      assertTrue(measured.maxEndpointErrorPoints() > 0);
      assertThrows(IllegalStateException.class, measured::requirePassed);
      assertEquals(0, strict.maxEndpointErrorPoints());
    }
  }
}
