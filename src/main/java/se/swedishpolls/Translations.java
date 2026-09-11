package se.swedishpolls;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The translated labels of a published representation. Language is part of the representation, so a
 * Swedish and an English document of the same publication are two documents with two ETags.
 */
public final class Translations {
  public static final String SWEDISH = "sv";
  public static final String ENGLISH = "en";
  public static final List<String> LANGUAGES = List.of(SWEDISH, ENGLISH);

  private static final Map<String, String> SV = swedish();
  private static final Map<String, String> EN = english();

  private final String language;
  private final Map<String, String> text;

  private Translations(String language, Map<String, String> text) {
    this.language = language;
    this.text = text;
  }

  public static boolean supported(String language) {
    return LANGUAGES.contains(language);
  }

  public static Translations of(String language) {
    if (SWEDISH.equals(language)) {
      return new Translations(SWEDISH, SV);
    }
    if (ENGLISH.equals(language)) {
      return new Translations(ENGLISH, EN);
    }
    throw new IllegalArgumentException("Unsupported language " + language);
  }

  public String language() {
    return language;
  }

  public String text(String key) {
    final String value = text.get(key);
    if (value == null) {
      throw new IllegalArgumentException("No " + language + " text for " + key);
    }
    return value;
  }

  /** The display name of a modeled component, including the OTHER aggregate. */
  public String component(String component) {
    return text("component." + component);
  }

  /** The display name of one of the ten approved coalition memberships. */
  public String coalition(String id) {
    return text("coalition." + id);
  }

  /** Replaces one {@code {token}} placeholder in a translated template. */
  public static String fill(String template, String token, String value) {
    return template.replace("{" + token + "}", value);
  }

  private static Map<String, String> english() {
    final LinkedHashMap<String, String> text = new LinkedHashMap<>();
    text.put("site.name", "Swedish poll of polls");
    text.put("card.overview", "Voting intention");
    text.put("card.parties", "Parties");
    text.put("card.seats", "National seat approximation");
    text.put("card.coalitions", "Coalitions");
    text.put("card.asOf", "Fieldwork through");
    text.put("card.interval", "{level}% interval");
    text.put("card.majority", "Majority probability");
    text.put("card.seatsOf", "of 349 seats");
    text.put("card.historical", "Historical estimate, not a current one");
    text.put("card.unavailable", "No published estimate");
    text.put("assets.note", ASSETS_NOTE_EN);
    text.put("elections.note", ELECTIONS_NOTE_EN);
    text.put("institutes.reference", INSTITUTES_REFERENCE_EN);
    text.put("remainder.definition", REMAINDER_DEFINITION_EN);
    text.put("seats.note", NationalSeats.APPROXIMATION_NOTE);
    text.put("seats.tie", NationalSeats.TIE_NOTE);
    text.put("coalitions.note", Coalitions.MEMBERSHIP_NOTE);
    text.put("coverage.otherAlsoIncludes", "every other party outside the roster");
    text.put(
        "sensitivity.movement", "{quantity} moves {points} percentage points under {alternative}.");
    text.put("sensitivity.quantity.threshold", "the 4% threshold probability for {label}");
    text.put("sensitivity.quantity.majority", "the majority probability for {label}");
    text.put(
        "sensitivity.alternative." + SeatOutcomes.POLL_COUNT,
        "weighting each institute by how many polls it published");
    text.put("component.S", "Social Democrats");
    text.put("component.M", "Moderates");
    text.put("component.SD", "Sweden Democrats");
    text.put("component.V", "Left Party");
    text.put("component.C", "Centre Party");
    text.put("component.KD", "Christian Democrats");
    text.put("component.L", "Liberals");
    text.put("component.MP", "Green Party");
    text.put("component.FI", "Feminist Initiative");
    text.put("component.OTHER", "Other parties");
    text.put("component.REMAINDER", "Comparable remainder");
    text.put("coalition.left", "S, V and MP");
    text.put("coalition.right", "M, L, C and KD");
    text.put("coalition.tido", "M, L, KD and SD");
    text.put("coalition.opposition", "S, C, V and MP");
    text.put("coalition.s_c_mp", "S, C and MP");
    text.put("coalition.s_m", "S and M");
    text.put("coalition.c_l_mp_s", "C, L, MP and S");
    text.put("coalition.m_kd_sd", "M, KD and SD");
    text.put("coalition.s_sd", "S and SD");
    text.put("coalition.m_sd", "M and SD");
    return Map.copyOf(text);
  }

  private static Map<String, String> swedish() {
    final LinkedHashMap<String, String> text = new LinkedHashMap<>();
    text.put("site.name", "Svensk sammanvägning av opinionsmätningar");
    text.put("card.overview", "Väljarstöd");
    text.put("card.parties", "Partier");
    text.put("card.seats", "Approximerad mandatfördelning");
    text.put("card.coalitions", "Koalitioner");
    text.put("card.asOf", "Fältarbete till och med");
    text.put("card.interval", "{level} %-intervall");
    text.put("card.majority", "Sannolikhet för egen majoritet");
    text.put("card.seatsOf", "av 349 mandat");
    text.put("card.historical", "Historisk skattning, inte en aktuell");
    text.put("card.unavailable", "Ingen publicerad skattning");
    text.put("assets.note", ASSETS_NOTE_SV);
    text.put("elections.note", ELECTIONS_NOTE_SV);
    text.put("institutes.reference", INSTITUTES_REFERENCE_SV);
    text.put("remainder.definition", REMAINDER_DEFINITION_SV);
    text.put("seats.note", SEATS_NOTE_SV);
    text.put("seats.tie", SEATS_TIE_SV);
    text.put("coalitions.note", COALITIONS_NOTE_SV);
    text.put("coverage.otherAlsoIncludes", "varje annat parti utanför uppsättningen");
    text.put(
        "sensitivity.movement", "{quantity} ändras {points} procentenheter vid {alternative}.");
    text.put("sensitivity.quantity.threshold", "chansen att {label} klarar fyraprocentsspärren");
    text.put("sensitivity.quantity.majority", "chansen till majoritet för {label}");
    text.put(
        "sensitivity.alternative." + SeatOutcomes.POLL_COUNT,
        "viktning av varje institut efter hur många mätningar det publicerat");
    text.put("component.S", "Socialdemokraterna");
    text.put("component.M", "Moderaterna");
    text.put("component.SD", "Sverigedemokraterna");
    text.put("component.V", "Vänsterpartiet");
    text.put("component.C", "Centerpartiet");
    text.put("component.KD", "Kristdemokraterna");
    text.put("component.L", "Liberalerna");
    text.put("component.MP", "Miljöpartiet de gröna");
    text.put("component.FI", "Feministiskt initiativ");
    text.put("component.OTHER", "Övriga partier");
    text.put("component.REMAINDER", "Jämförbar restpost");
    text.put("coalition.left", "S, V och MP");
    text.put("coalition.right", "M, L, C och KD");
    text.put("coalition.tido", "M, L, KD och SD");
    text.put("coalition.opposition", "S, C, V och MP");
    text.put("coalition.s_c_mp", "S, C och MP");
    text.put("coalition.s_m", "S och M");
    text.put("coalition.c_l_mp_s", "C, L, MP och S");
    text.put("coalition.m_kd_sd", "M, KD och SD");
    text.put("coalition.s_sd", "S och SD");
    text.put("coalition.m_sd", "M och SD");
    return Map.copyOf(text);
  }

  private static final String ASSETS_NOTE_EN =
      "Versioned immutable links. A renderer change creates a new version and never overwrites"
          + " published bytes.";
  private static final String ASSETS_NOTE_SV =
      "Versionerade oföränderliga länkar. En ändrad renderare skapar en ny version och skriver"
          + " aldrig över publicerade bytes.";
  private static final String ELECTIONS_NOTE_EN =
      "Official outcomes are display references, never model observations. Shares derive from"
          + " unrounded vote counts.";
  private static final String ELECTIONS_NOTE_SV =
      "Officiella valresultat är referenser för visning, aldrig observationer i modellen. Andelar"
          + " beräknas från oavrundade röstetal.";
  private static final String INSTITUTES_REFERENCE_EN =
      "House effects are deviations from the ensemble of institutes active in the cycle, centered"
          + " with equal institute weights. They are not deviations from true opinion.";
  private static final String INSTITUTES_REFERENCE_SV =
      "Institutseffekter är avvikelser från de institut som är aktiva under mandatperioden,"
          + " centrerade med lika vikt per institut. De är inte avvikelser från den sanna"
          + " opinionen.";
  private static final String REMAINDER_DEFINITION_EN =
      "Support outside the fixed eight parties. It includes FI in every period.";
  private static final String REMAINDER_DEFINITION_SV =
      "Stöd utanför de åtta fasta partierna. Det innehåller FI i varje period.";
  private static final String SEATS_NOTE_SV =
      "En approximerad nationell mandatfördelning från nationella andelar. Den utelämnar"
          + " valkretsreglerna, inklusive 12-procentsundantaget, uppdelningen i 310 fasta och 39"
          + " utjämningsmandat och återföringen av överskjutande fasta mandat, och är ingen"
          + " officiell fördelning.";
  private static final String SEATS_TIE_SV =
      "Exakt lika jämförelsetal avgörs av den fasta partiordning som är registrerad med regeln,"
          + " vilket gör en körning reproducerbar. Den officiella regeln lottar, så detta är en"
          + " approximation av regeln och inte det rättsliga förfarandet.";
  private static final String COALITIONS_NOTE_SV =
      "En etikett anger vilka partier som räknas samman och innebär inget avtal om att regera"
          + " tillsammans. Sannolikheterna kommer från samma gemensamma dragningar som"
          + " mandatfördelningen.";
}
