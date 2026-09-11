package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The published wording of a sensitivity movement, in the language of the document it sits in. */
class PublicationDocumentsTest {
  private static SeatOutcomes.Sensitivity moved(String quantity, double points) {
    return new SeatOutcomes.Sensitivity(
        "centering",
        SeatOutcomes.POLL_COUNT,
        Map.of(),
        Map.of(),
        points,
        quantity,
        points > SeatOutcomes.DISCLOSED_SHIFT_POINTS);
  }

  @Test
  void staysSilentWhenNoAlternativeEarnsADisclosure() {
    assertNull(
        PublicationDocuments.sensitivityNote(
            List.of(moved("majority:tido", 4.2)), Translations.of(Translations.ENGLISH)));
    assertNull(
        PublicationDocuments.sensitivityNote(List.of(), Translations.of(Translations.SWEDISH)));
  }

  @Test
  void namesTheMovedMajorityTheDistanceAndTheAlternativeInBothLanguages() {
    final List<SeatOutcomes.Sensitivity> sensitivity = List.of(moved("majority:tido", 12.4));
    final String english =
        PublicationDocuments.sensitivityNote(sensitivity, Translations.of(Translations.ENGLISH));
    assertNotNull(english);
    assertTrue(english.contains("majority probability for M, L, KD and SD"), english);
    assertTrue(english.contains("12 percentage points"), english);
    assertTrue(english.contains("poll"), english);

    final String swedish =
        PublicationDocuments.sensitivityNote(sensitivity, Translations.of(Translations.SWEDISH));
    assertNotNull(swedish);
    assertTrue(swedish.contains("12 procentenheter"), swedish);
    assertNotEquals(english, swedish);
  }

  @Test
  void namesTheThresholdQuantityByTheParty() {
    final String english =
        PublicationDocuments.sensitivityNote(
            List.of(moved("threshold:L", 11.6)), Translations.of(Translations.ENGLISH));
    assertNotNull(english);
    assertTrue(english.contains("4% threshold probability for Liberals"), english);
    assertTrue(english.contains("12 percentage points"), english);
  }

  @Test
  void listsEveryDisclosedMovementAndSkipsTheRest() {
    final String english =
        PublicationDocuments.sensitivityNote(
            List.of(moved("majority:left", 18.0), moved("threshold:MP", 3.0)),
            Translations.of(Translations.ENGLISH));
    assertNotNull(english);
    assertTrue(english.contains("18 percentage points"), english);
    assertFalse(english.contains("3 percentage points"), english);
  }
}
