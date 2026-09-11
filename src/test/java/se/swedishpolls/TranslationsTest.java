package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Font;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranslationsTest {
  private static final List<String> KEYS =
      List.of(
          "site.name",
          "card.overview",
          "card.parties",
          "card.seats",
          "card.coalitions",
          "card.asOf",
          "card.interval",
          "card.majority",
          "card.seatsOf",
          "card.historical",
          "card.unavailable",
          "assets.note",
          "elections.note",
          "institutes.reference",
          "remainder.definition",
          "seats.note",
          "seats.tie",
          "coalitions.note",
          "coverage.otherAlsoIncludes");

  @Test
  void bothLanguagesTranslateEveryPublishedLabel() {
    for (final String language : Translations.LANGUAGES) {
      final Translations text = Translations.of(language);
      for (final String key : KEYS) {
        assertFalse(text.text(key).isBlank(), language + " is missing " + key);
      }
      for (final String component :
          List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI", "OTHER", "REMAINDER")) {
        assertFalse(text.component(component).isBlank(), language + " is missing " + component);
      }
      for (final Coalitions.Preset preset : Coalitions.PRESETS) {
        assertFalse(text.coalition(preset.id()).isBlank(), language + " is missing " + preset.id());
      }
    }
  }

  @Test
  void theSwedishTranslationRendersInTheCardFont() {
    final Font font = new Font("DejaVu Sans", Font.PLAIN, 24);
    final Translations text = Translations.of(Translations.SWEDISH);
    for (final String key : KEYS) {
      assertEquals(-1, font.canDisplayUpTo(text.text(key)), key + " has an undrawable glyph");
    }
  }

  @Test
  void anUnsupportedLanguageIsRejectedRatherThanFallingBack() {
    assertFalse(Translations.supported("de"));
    assertThrows(IllegalArgumentException.class, () -> Translations.of("de"));
    assertThrows(
        IllegalArgumentException.class, () -> Translations.of(Translations.ENGLISH).text("absent"));
  }
}
