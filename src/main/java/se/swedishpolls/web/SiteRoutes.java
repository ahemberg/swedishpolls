package se.swedishpolls.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import se.swedishpolls.publication.Translations;

/**
 * The translated route map. Every page family has one path per language, so a language switch maps
 * an equivalent path rather than prefixing the current one, and a shared link keeps the language it
 * was written in: nothing here reads an Accept-Language header.
 */
public final class SiteRoutes {
  /** One approved page family. The path names differ per language; the family does not. */
  public enum Family {
    OVERVIEW("overview"),
    PARTY("party"),
    SEATS("seats"),
    COALITIONS("coalitions"),
    POLLSTERS("pollsters"),
    POLLS("polls"),
    METHOD("method");

    private final String key;

    Family(String key) {
      this.key = key;
    }

    /** The text-key segment naming this family. Written out, so no case mapping runs per page. */
    public String key() {
      return key;
    }
  }

  /** A resolved request: which page, in which language, for which optional path parameter. */
  public record Route(Family family, String language, String parameter) {
    public Route {
      if (!Translations.supported(language)) {
        throw new IllegalArgumentException("Unsupported language " + language);
      }
      if ((family == Family.PARTY) != (parameter != null)) {
        throw new IllegalArgumentException(family + " does not take the parameter " + parameter);
      }
    }
  }

  /** The families the header navigation lists, in order. A party page is reached from the table. */
  public static final List<Family> NAVIGATION =
      List.of(
          Family.OVERVIEW,
          Family.SEATS,
          Family.COALITIONS,
          Family.POLLSTERS,
          Family.POLLS,
          Family.METHOD);

  /** Pages that remain useful without model results. */
  public static final List<Family> SOURCE_NAVIGATION = List.of(Family.OVERVIEW, Family.POLLS);

  private static final Map<Family, Map<String, String>> PATHS = paths();
  private static final Map<String, Map<String, String>> PARTY_SLUGS = partySlugs();

  /** Parties with individual pages, including FI's historical-only page. */
  public static final List<String> PARTIES = List.copyOf(PARTY_SLUGS.keySet());

  private SiteRoutes() {}

  /** The path of one page family in one language, with the parameter appended where one applies. */
  public static String path(Family family, String language, String parameter) {
    final String base = PATHS.get(family).get(language);
    if (base == null) {
      throw new IllegalArgumentException("Unsupported language " + language);
    }
    if (parameter == null) {
      return base;
    }
    final Map<String, String> slugs = PARTY_SLUGS.get(parameter);
    if (family != Family.PARTY || slugs == null) {
      throw new IllegalArgumentException("Unknown party " + parameter);
    }
    return base + "/" + slugs.get(language);
  }

  /** The same page in the other language, so a switch never lands on a different page. */
  public static String translated(Route route, String language) {
    return path(route.family(), language, route.parameter());
  }

  /** The route a request path names, or empty when no family claims it. */
  public static Optional<Route> resolve(String path) {
    final String normalized = normalize(path);
    for (final Map.Entry<Family, Map<String, String>> family : PATHS.entrySet()) {
      for (final Map.Entry<String, String> language : family.getValue().entrySet()) {
        final Optional<Route> route =
            match(family.getKey(), language.getKey(), language.getValue(), normalized);
        if (route.isPresent()) {
          return route;
        }
      }
    }
    return Optional.empty();
  }

  private static Optional<Route> match(
      Family family, String language, String base, String normalized) {
    if (family != Family.PARTY) {
      return normalized.equals(base)
          ? Optional.of(new Route(family, language, null))
          : Optional.empty();
    }
    final String prefix = base + "/";
    if (!normalized.startsWith(prefix)) {
      return Optional.empty();
    }
    final String slug = normalized.substring(prefix.length());
    if (slug.isEmpty() || slug.contains("/")) {
      return Optional.empty();
    }
    return PARTY_SLUGS.entrySet().stream()
        .filter(party -> slug.equals(party.getValue().get(language)))
        .map(party -> new Route(family, language, party.getKey()))
        .findFirst();
  }

  /** A trailing slash names the same page; the site root is the one path that keeps its slash. */
  private static String normalize(String path) {
    final String rooted = path.startsWith("/") ? path : "/" + path;
    return rooted.length() > 1 && rooted.endsWith("/")
        ? rooted.substring(0, rooted.length() - 1)
        : rooted;
  }

  private static Map<Family, Map<String, String>> paths() {
    final LinkedHashMap<Family, Map<String, String>> paths = new LinkedHashMap<>();
    paths.put(Family.OVERVIEW, pair("/", "/en"));
    paths.put(Family.PARTY, pair("/parti", "/en/party"));
    paths.put(Family.SEATS, pair("/mandat", "/en/seats"));
    paths.put(Family.COALITIONS, pair("/regeringsunderlag", "/en/coalitions"));
    paths.put(Family.POLLSTERS, pair("/institut", "/en/pollsters"));
    paths.put(Family.POLLS, pair("/matningar", "/en/polls"));
    paths.put(Family.METHOD, pair("/metod", "/en/method"));
    return Map.copyOf(paths);
  }

  private static Map<String, String> pair(String swedish, String english) {
    return Map.of(Translations.SWEDISH, swedish, Translations.ENGLISH, english);
  }

  private static Map<String, Map<String, String>> partySlugs() {
    final LinkedHashMap<String, Map<String, String>> slugs = new LinkedHashMap<>();
    slugs.put("S", pair("socialdemokraterna", "social-democrats"));
    slugs.put("M", pair("moderaterna", "moderates"));
    slugs.put("SD", pair("sverigedemokraterna", "sweden-democrats"));
    slugs.put("V", pair("vansterpartiet", "left-party"));
    slugs.put("C", pair("centerpartiet", "centre-party"));
    slugs.put("KD", pair("kristdemokraterna", "christian-democrats"));
    slugs.put("L", pair("liberalerna", "liberals"));
    slugs.put("MP", pair("miljopartiet", "green-party"));
    slugs.put("FI", pair("feministiskt-initiativ", "feminist-initiative"));
    return Collections.unmodifiableMap(slugs);
  }
}
