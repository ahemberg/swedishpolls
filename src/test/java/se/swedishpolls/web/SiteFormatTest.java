package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import se.swedishpolls.publication.Translations;

/** The wording of a number: the language's separators, and probabilities that stay honest. */
class SiteFormatTest {
  @Test
  void aSupportFigureUsesTheLanguagesDecimalSeparator() {
    assertEquals("33,8", SiteFormat.decimal(33.8, Translations.SWEDISH));
    assertEquals("33.8", SiteFormat.decimal(33.8, Translations.ENGLISH));
  }

  @Test
  void swedishSpacesThePercentSignAndEnglishDoesNot() {
    assertEquals("4 %", SiteFormat.percent("4", Translations.SWEDISH));
    assertEquals("4%", SiteFormat.percent("4", Translations.ENGLISH));
  }

  @Test
  void aProbabilityIsWholePercentAndNeverRoundsIntoCertainty() {
    assertEquals("71%", SiteFormat.probability(0.71, Translations.ENGLISH));
    assertEquals("71 %", SiteFormat.probability(0.71, Translations.SWEDISH));
    assertEquals("<1%", SiteFormat.probability(0.001, Translations.ENGLISH));
    assertEquals(">99%", SiteFormat.probability(0.999, Translations.ENGLISH));
    assertEquals(
        "<1%",
        SiteFormat.probability(0.0, Translations.ENGLISH),
        "Zero draws out of a finite sample is not proof that it cannot happen");
    assertEquals(
        ">99%",
        SiteFormat.probability(1.0, Translations.ENGLISH),
        "Every draw out of a finite sample is not proof that it must happen");
  }

  @Test
  void anIntervalLevelReadsAsWholePercent() {
    assertEquals("95", SiteFormat.level(0.95));
    assertEquals("50", SiteFormat.level(0.5));
  }

  @Test
  void aDateIsSpelledOutInTheReadersLanguage() {
    final LocalDate date = LocalDate.of(2026, 9, 4);
    assertTrue(SiteFormat.date(date, Translations.SWEDISH).contains("september"));
    assertTrue(SiteFormat.date(date, Translations.ENGLISH).contains("September"));
    assertTrue(SiteFormat.date(date, Translations.SWEDISH).contains("2026"));
  }

  @Test
  void aTimestampIsShownInTheSitesZoneRatherThanTheReaders() {
    final String shown = SiteFormat.timestamp(Instant.parse("2026-09-08T05:12:33Z"), "en");
    assertTrue(shown.contains("2026"), shown);
    assertTrue(shown.contains("07:12") || shown.contains("06:12"), shown);
  }

  @Test
  void anUnsupportedLanguageIsRejectedRatherThanFallingBack() {
    assertThrows(IllegalArgumentException.class, () -> SiteFormat.locale("de"));
  }
}
