package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The page chrome: one key set, both languages, and no key left untranslated. */
class SiteTextTest {
  /** The two language abbreviations are the same word in both languages. Nothing else is. */
  private static final Set<String> SHARED = Set.of("language.short.sv", "language.short.en");

  @Test
  void bothLanguagesCarryTheSameKeys() {
    assertEquals(
        SiteText.of(Translations.SWEDISH).all().keySet(),
        SiteText.of(Translations.ENGLISH).all().keySet());
  }

  @Test
  void everyKeyIsTranslatedRatherThanCopied() {
    final SiteText swedish = SiteText.of(Translations.SWEDISH);
    final SiteText english = SiteText.of(Translations.ENGLISH);
    for (final String key : swedish.all().keySet()) {
      assertFalse(swedish.text(key).isBlank(), key);
      assertFalse(english.text(key).isBlank(), key);
      if (!SHARED.contains(key)) {
        assertNotEquals(swedish.text(key), english.text(key), key + " reads the same in both");
      }
    }
  }

  @Test
  void everyNavigableFamilyHasALabelAndEveryFamilyATitle() {
    for (final String language : Translations.LANGUAGES) {
      final SiteText text = SiteText.of(language);
      for (final SiteRoutes.Family family : SiteRoutes.Family.values()) {
        assertFalse(text.navigation(family).isBlank(), language + " " + family);
        assertFalse(
            text.text("head.title." + family.name().toLowerCase(java.util.Locale.ROOT)).isBlank(),
            language + " " + family);
      }
    }
  }

  @Test
  void aPlaceholderIsFilledRatherThanLeftInThePage() {
    final SiteText text = SiteText.of(Translations.SWEDISH);
    final String filled = SiteText.fill(text.text("headline.asOf"), "date", "4 september 2026");
    assertFalse(filled.contains("{"), filled);
    assertTrue(filled.contains("4 september 2026"), filled);
  }

  @Test
  void everyTemplatePlaceholderHasTheSameNameInBothLanguages() {
    final SiteText swedish = SiteText.of(Translations.SWEDISH);
    final SiteText english = SiteText.of(Translations.ENGLISH);
    for (final String key : swedish.all().keySet()) {
      assertEquals(tokens(swedish.text(key)), tokens(english.text(key)), key);
    }
  }

  @Test
  void anUnsupportedLanguageIsRejectedRatherThanFallingBack() {
    assertThrows(IllegalArgumentException.class, () -> SiteText.of("de"));
    assertThrows(
        IllegalArgumentException.class, () -> SiteText.of(Translations.SWEDISH).text("absent"));
  }

  private static List<String> tokens(String template) {
    return java.util.regex.Pattern.compile("\\{([a-zA-Z]+)\\}")
        .matcher(template)
        .results()
        .map(match -> match.group(1))
        .sorted()
        .toList();
  }
}
