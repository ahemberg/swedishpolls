package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import se.swedishpolls.estimation.SeatOutcomes;

/** The published wording of a sensitivity movement, in the language of the document it sits in. */
class PublicationDocumentsTest {
  private static SeatOutcomes.Sensitivity moved(
      Map<String, Double> thresholds, Map<String, Double> majorities) {
    return SeatOutcomes.sensitivity(
        "centering",
        SeatOutcomes.POLL_COUNT,
        new SeatOutcomes.Headline(zeroed(thresholds), zeroed(majorities)),
        new SeatOutcomes.Headline(fractions(thresholds), fractions(majorities)));
  }

  private static Map<String, Double> zeroed(Map<String, Double> points) {
    return points.keySet().stream()
        .collect(java.util.stream.Collectors.toMap(key -> key, key -> 0.0));
  }

  private static Map<String, Double> fractions(Map<String, Double> points) {
    return points.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() / 100));
  }

  @Test
  void staysSilentWhenNoProbabilityMovesPastTheThreshold() {
    final List<SeatOutcomes.Sensitivity> sensitivity =
        List.of(moved(Map.of("L", 4.2), Map.of("tido", 9.9)));
    assertNull(
        PublicationDocuments.sensitivityNote(
            sensitivity, "threshold", Translations.of(Translations.ENGLISH)));
    assertNull(
        PublicationDocuments.sensitivityNote(
            sensitivity, "majority", Translations.of(Translations.SWEDISH)));
  }

  @Test
  void namesTheMovedQuantityTheDistanceAndTheAlternativeInBothLanguages() {
    final List<SeatOutcomes.Sensitivity> sensitivity =
        List.of(moved(Map.of(), Map.of("tido", 12.4)));
    final String english =
        PublicationDocuments.sensitivityNote(
            sensitivity, "majority", Translations.of(Translations.ENGLISH));
    assertNotNull(english);
    assertTrue(english.contains("majority probability for M, L, KD and SD"), english);
    assertTrue(english.contains("12.4 percentage points"), english);
    assertTrue(english.contains("poll"), english);

    final String swedish =
        PublicationDocuments.sensitivityNote(
            sensitivity, "majority", Translations.of(Translations.SWEDISH));
    assertNotNull(swedish);
    assertTrue(swedish.contains("12,4 procentenheter"), swedish);
    assertNotEquals(english, swedish);
  }

  @Test
  void keepsADecimalSoAMovementJustOverTheRuleNeverPrintsAsTheRuleItself() {
    final String english =
        PublicationDocuments.sensitivityNote(
            List.of(moved(Map.of("L", 10.4), Map.of())),
            "threshold",
            Translations.of(Translations.ENGLISH));
    assertNotNull(english);
    assertTrue(english.contains("4% threshold probability for Liberals"), english);
    assertTrue(english.contains("10.4 percentage points"), english);
  }

  @Test
  void namesEveryProbabilityThatMovedPastTheThresholdAndNoOther() {
    final String english =
        PublicationDocuments.sensitivityNote(
            List.of(moved(Map.of("L", 18.0, "MP", 14.0, "C", 3.0), Map.of())),
            "threshold",
            Translations.of(Translations.ENGLISH));
    assertNotNull(english);
    assertTrue(english.contains("18.0 percentage points"), english);
    assertTrue(english.contains("14.0 percentage points"), english);
    assertFalse(english.contains("3.0 percentage points"), english);
    assertTrue(english.contains("Liberals"), english);
    assertTrue(english.contains("Green Party"), english);
    assertFalse(english.contains("Centre Party"), english);
  }

  @Test
  void eachPageDisclosesOnlyTheProbabilitiesItShows() {
    final List<SeatOutcomes.Sensitivity> sensitivity =
        List.of(moved(Map.of("L", 18.0), Map.of("tido", 15.0)));
    final Translations text = Translations.of(Translations.ENGLISH);
    final String seats = PublicationDocuments.sensitivityNote(sensitivity, "threshold", text);
    final String coalitions = PublicationDocuments.sensitivityNote(sensitivity, "majority", text);
    assertNotNull(seats);
    assertNotNull(coalitions);
    assertTrue(seats.contains("Liberals"), seats);
    assertFalse(seats.contains("M, L, KD and SD"), seats);
    assertTrue(coalitions.contains("M, L, KD and SD"), coalitions);
    assertFalse(coalitions.contains("Liberals"), coalitions);
  }
}
