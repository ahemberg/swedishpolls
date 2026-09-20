package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.swedishpolls.publication.Translations;

/** The page chrome: one key set, both languages, and no key left untranslated. */
class SiteTextTest {
  /**
   * The two language abbreviations are the same word in both languages, and the footnote marker is
   * a typographic symbol rather than a word. Nothing else may read the same in both.
   */
  private static final Set<String> SHARED =
      Set.of("language.short.sv", "language.short.en", "polls.unmodeledMark");

  private static final Set<String> METHOD_PAGE_KEYS =
      Set.of(
          "about.seats",
          "head.title.method",
          "method.coverage.boundaryNote",
          "method.coverage.candidate",
          "method.coverage.column.decision",
          "method.coverage.column.roster",
          "method.coverage.column.span",
          "method.coverage.column.status",
          "method.coverage.fiNote",
          "method.coverage.gate.burnIn",
          "method.coverage.gate.development",
          "method.coverage.gate.gap",
          "method.coverage.gate.institutes",
          "method.coverage.gate.polls",
          "method.coverage.gate.shifts",
          "method.coverage.gate.stability",
          "method.coverage.otherNote",
          "method.coverage.rosterCaption",
          "method.coverage.title",
          "method.coverage.validated",
          "method.data.eligibility",
          "method.data.eras",
          "method.data.history",
          "method.data.provenance",
          "method.data.title",
          "method.lead",
          "method.model.draws",
          "method.model.estimand",
          "method.model.house",
          "method.model.hyper",
          "method.model.observations",
          "method.model.overdispersion",
          "method.model.title",
          "method.reproduction.decimals",
          "method.reproduction.draws",
          "method.reproduction.estimator",
          "method.reproduction.inputs",
          "method.reproduction.protocols",
          "method.reproduction.seed",
          "method.reproduction.title",
          "method.seats.title",
          "method.validation.coverageLead",
          "method.validation.failed",
          "method.validation.gateCoverage",
          "method.validation.gateMisfit",
          "method.validation.gateScore",
          "method.validation.gatesLead",
          "method.validation.sensitivity",
          "method.validation.title",
          "method.validation.verdictBlocked",
          "method.validation.verdictReleased",
          "polls.column.other",
          "polls.column.period",
          "polls.missing",
          "seats.era",
          "seats.pointVersusMean",
          "seats.threshold",
          "seats.tie");

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
  void everyMethodPageTextExistsInBothLanguages() {
    for (final String language : Translations.LANGUAGES) {
      final SiteText text = SiteText.of(language);
      for (final String key : METHOD_PAGE_KEYS) {
        assertDoesNotThrow(() -> text.text(key), language + " " + key);
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
