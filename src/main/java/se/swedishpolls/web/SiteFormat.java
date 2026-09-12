package se.swedishpolls.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;
import se.swedishpolls.publication.Translations;

/**
 * The number and date wording of one language. The browser formats the same values with the same
 * locales, so a figure never reads one way before the script runs and another way after it.
 */
public final class SiteFormat {
  /** The zone published timestamps are shown in. The stored instants stay UTC. */
  public static final ZoneId ZONE = ZoneId.of("Europe/Stockholm");

  private static final Map<String, Locale> LOCALES =
      Map.of(
          Translations.SWEDISH,
          Locale.forLanguageTag("sv-SE"),
          Translations.ENGLISH,
          Locale.forLanguageTag("en-GB"));

  private static final double LOWER_BOUND = 0.01;
  private static final double UPPER_BOUND = 0.99;

  private SiteFormat() {}

  /** The BCP 47 tag the browser formats this language with. */
  public static String locale(String language) {
    return localeOf(language).toLanguageTag();
  }

  /** A fieldwork or reference date, spelled out: {@code 4 september 2026}. */
  public static String date(LocalDate date, String language) {
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        .withLocale(localeOf(language))
        .format(date);
  }

  /** A publication or check timestamp, in the site's zone rather than the reader's. */
  public static String timestamp(Instant instant, String language) {
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
        .withLocale(localeOf(language))
        .format(instant.atZone(ZONE));
  }

  /** A support or seat figure at one decimal, with the language's decimal separator. */
  public static String decimal(double value, String language) {
    final String plain = String.format(Locale.ROOT, "%.1f", value);
    return Translations.SWEDISH.equals(language) ? plain.replace('.', ',') : plain;
  }

  /**
   * A probability as whole percent. It never reads as 0 % or 100 %: a Monte Carlo estimate of zero
   * draws is not proof of impossibility, so the display says less than or greater than, the same
   * rule the share card applies.
   */
  public static String probability(double value, String language) {
    if (value < LOWER_BOUND) {
      return percent("<1", language);
    }
    if (value > UPPER_BOUND) {
      return percent(">99", language);
    }
    return percent(Long.toString(Math.round(value * 100)), language);
  }

  /** An interval level as whole percent: the stored 0.95 reads as {@code 95}. */
  public static String level(double fraction) {
    return Long.toString(Math.round(fraction * 100));
  }

  /** Swedish writes a space before the percent sign; English writes none. */
  public static String percent(String number, String language) {
    return Translations.SWEDISH.equals(language) ? number + " %" : number + "%";
  }

  private static Locale localeOf(String language) {
    final Locale locale = LOCALES.get(language);
    if (locale == null) {
      throw new IllegalArgumentException("Unsupported language " + language);
    }
    return locale;
  }
}
