package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.source.PollQuery;

/** The translated route map: one path per family per language, and a switch that keeps the page. */
class SiteRoutesTest {
  @Test
  void everyFamilyHasADistinctPathInEveryLanguage() {
    final Set<String> paths = new HashSet<>();
    for (final SiteRoutes.Family family : SiteRoutes.Family.values()) {
      for (final String language : Translations.LANGUAGES) {
        final String parameter = family == SiteRoutes.Family.PARTY ? "S" : null;
        final String path = SiteRoutes.path(family, language, parameter);
        assertTrue(path.startsWith("/"), path);
        assertTrue(paths.add(path), path + " is claimed twice");
      }
    }
  }

  @Test
  void aPathResolvesBackToItsOwnFamilyAndLanguage() {
    for (final SiteRoutes.Family family : SiteRoutes.Family.values()) {
      for (final String language : Translations.LANGUAGES) {
        final String parameter = family == SiteRoutes.Family.PARTY ? "M" : null;
        final String path = SiteRoutes.path(family, language, parameter);
        final SiteRoutes.Route route = SiteRoutes.resolve(path).orElseThrow();
        assertEquals(family, route.family(), path);
        assertEquals(language, route.language(), path);
        assertEquals(parameter, route.parameter(), path);
      }
    }
  }

  @Test
  void switchingLanguageMapsTheEquivalentPathRatherThanPrefixingTheCurrentOne() {
    final SiteRoutes.Route swedish = SiteRoutes.resolve("/regeringsunderlag").orElseThrow();
    assertEquals("/en/coalitions", SiteRoutes.translated(swedish, Translations.ENGLISH));
    assertEquals("/regeringsunderlag", SiteRoutes.translated(swedish, Translations.SWEDISH));

    final SiteRoutes.Route party = SiteRoutes.resolve("/en/party/liberals").orElseThrow();
    assertEquals("L", party.parameter());
    assertEquals("/parti/liberalerna", SiteRoutes.translated(party, Translations.SWEDISH));
  }

  @Test
  void theSwedishOverviewIsTheRootAndTheEnglishOneIsNotAPrefixOfIt() {
    assertEquals("/", SiteRoutes.path(SiteRoutes.Family.OVERVIEW, Translations.SWEDISH, null));
    assertEquals("/en", SiteRoutes.path(SiteRoutes.Family.OVERVIEW, Translations.ENGLISH, null));
    assertEquals(SiteRoutes.Family.OVERVIEW, SiteRoutes.resolve("/en/").orElseThrow().family());
  }

  @Test
  void aPathNoFamilyClaimsResolvesToNothing() {
    for (final String path :
        java.util.List.of(
            "/parti",
            "/parti/a/b",
            "/parti/okant",
            "/en/parties",
            "/en/party/socialdemokraterna",
            "/sv",
            "/mandat/extra",
            "/api")) {
      assertEquals(Optional.empty(), SiteRoutes.resolve(path), path);
    }
  }

  @Test
  void theNavigationListsEveryFamilyAReaderCanReachDirectly() {
    assertTrue(SiteRoutes.NAVIGATION.contains(SiteRoutes.Family.OVERVIEW));
    assertFalse(
        SiteRoutes.NAVIGATION.contains(SiteRoutes.Family.PARTY),
        "A party page is reached from the estimate table, not from the navigation");
    assertEquals(
        SiteRoutes.Family.values().length - 1,
        SiteRoutes.NAVIGATION.size(),
        "Every other family is navigable");
  }

  @Test
  void aRouteRejectsAParameterTheFamilyDoesNotTake() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SiteRoutes.Route(SiteRoutes.Family.SEATS, Translations.SWEDISH, "extra"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SiteRoutes.Route(SiteRoutes.Family.PARTY, Translations.SWEDISH, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SiteRoutes.Route(SiteRoutes.Family.SEATS, "de", null));
  }

  /**
   * The share cards are rendered in the publication package against the poll components, and every
   * one of them links to a party page here. A party that gains a card without gaining a page, or
   * the reverse, has to fail loudly rather than ship a dead link.
   */
  @Test
  void everyPartyWithAPageIsAPollComponentAndTheReverse() {
    assertEquals(PollQuery.COMPONENTS, SiteRoutes.PARTIES);
  }
}
