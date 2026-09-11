package se.swedishpolls;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The page chrome of one language: navigation, section titles, readouts and the method footer.
 *
 * <p>It is the single source for both renderings. Spring writes this text into the basic HTML and
 * into the bootstrap the browser reads, so the React page and the page served without JavaScript
 * can never word the same number differently.
 *
 * <p>{@link Translations} stays the vocabulary of a published document. This is the vocabulary of a
 * page around one.
 */
public final class SiteText {
  private static final Map<String, String> SV = swedish();
  private static final Map<String, String> EN = english();

  private final String language;
  private final Map<String, String> text;

  private SiteText(String language, Map<String, String> text) {
    this.language = language;
    this.text = text;
  }

  public static SiteText of(String language) {
    if (Translations.SWEDISH.equals(language)) {
      return new SiteText(Translations.SWEDISH, SV);
    }
    if (Translations.ENGLISH.equals(language)) {
      return new SiteText(Translations.ENGLISH, EN);
    }
    throw new IllegalArgumentException("Unsupported language " + language);
  }

  public String language() {
    return language;
  }

  public String text(String key) {
    final String value = text.get(key);
    if (value == null) {
      throw new IllegalArgumentException("No " + language + " page text for " + key);
    }
    return value;
  }

  /** The navigation label of a page family. */
  public String navigation(SiteRoutes.Family family) {
    return text("nav." + family.key());
  }

  /** Every key, for the bootstrap the browser reads. The page and the script share one wording. */
  public Map<String, String> all() {
    return Map.copyOf(text);
  }

  /** Replaces one {@code {token}} placeholder. The browser applies the same substitution. */
  public static String fill(String template, String token, String value) {
    return template.replace("{" + token + "}", value);
  }

  private static Map<String, String> swedish() {
    final LinkedHashMap<String, String> text = new LinkedHashMap<>();
    text.put("nav.overview", "Översikt");
    text.put("nav.party", "Partier");
    text.put("nav.seats", "Mandat");
    text.put("nav.coalitions", "Regeringsunderlag");
    text.put("nav.pollsters", "Institut");
    text.put("nav.polls", "Mätningar");
    text.put("nav.method", "Metod");
    text.put("nav.label", "Sidor");
    text.put("language.label", "Språk");
    text.put("language.sv", "Svenska");
    text.put("language.en", "Engelska");
    text.put("language.short.sv", "SV");
    text.put("language.short.en", "EN");
    text.put("skip", "Hoppa till innehållet");

    text.put("head.title.overview", "Skattat väljarstöd");
    text.put("head.title.party", "Parti");
    text.put("head.title.seats", "Approximerad mandatfördelning");
    text.put("head.title.coalitions", "Regeringsunderlag");
    text.put("head.title.pollsters", "Institut och huseffekter");
    text.put("head.title.polls", "Publicerade mätningar");
    text.put("head.title.method", "Metod och validering");
    text.put(
        "head.description.overview",
        "Sammanvägt väljarstöd per {date}, med intervall, approximerade mandat och de mätningar"
            + " som ingår. Inte en valprognos.");
    text.put(
        "head.description.party",
        "{party}: skattat väljarstöd per {date}, mätningar och instituteffekter. Inte en"
            + " valprognos.");
    text.put(
        "head.description.partyHistorical",
        "{party}: historiska källobservationer till och med {date}. Ingen aktuell enskild"
            + " skattning finns.");
    text.put("head.imageAlt", "Sammanfattande bild: skattat väljarstöd per {date}.");
    text.put("head.imageAlt.party", "Sammanfattande bild för {party} per {date}.");
    text.put(
        "head.imageAlt.partyHistorical", "Historisk sammanfattande bild för {party} per {date}.");

    text.put("headline", "Skattat väljarstöd");
    text.put("headline.asOf", "per {date}");
    text.put("notForecast", "Inte en valprognos.");
    text.put("published", "Publicerad {timestamp}.");
    text.put("sourceChecked", "Källan kontrollerad {timestamp}.");
    text.put(
        "stale",
        "En senare uppdatering kunde inte valideras {timestamp}. Skattningen per {date} visas"
            + " tills nästa körning godkänns.");
    text.put("unavailable.title", "Ingen skattning är publicerad än");
    text.put(
        "unavailable.body",
        "Den första publiceringen har inte blivit klar. Inga siffror visas förrän en körning har"
            + " klarat alla kontroller.");
    text.put("unavailable.lastCheck", "Källan kontrollerades senast {timestamp}.");

    text.put("timeline.title", "Utveckling över tid");
    text.put("timeline.range.oneYear", "Ett år");
    text.put("timeline.range.sinceElection", "Sedan valet {year}");
    text.put("timeline.range.fourYears", "Fyra år");
    text.put("timeline.range.all", "Hela historiken");
    text.put("timeline.range.label", "Tidsintervall");
    text.put(
        "timeline.hint",
        "Dra i diagrammet, eller använd piltangenterna, för att läsa av ett datum.");
    text.put("timeline.readout.label", "Avläsning");
    text.put("timeline.coverage", "Modellperiod {period}. Partier: {roster}.");
    text.put("timeline.noCoverage", "Ingen validerad modellperiod.");
    text.put("timeline.electionDot", "Ringar är faktiska valresultat.");
    text.put("timeline.pollDots", "Fyllda prickar är enskilda publicerade mätningar.");
    text.put("timeline.bandNote", "Det skuggade fältet är {level} %-intervallet.");
    text.put("timeline.isolate", "Visa ett parti i taget");
    text.put("timeline.isolateAll", "Visa alla");
    text.put("timeline.showTable", "Visa tidsserien som tabell");
    text.put("timeline.hideTable", "Dölj tabellen");
    text.put("timeline.tableCaption", "Skattat väljarstöd per avläst datum, i procent.");
    text.put("timeline.column.date", "Datum");
    text.put(
        "timeline.summary",
        "Linjediagram över skattat väljarstöd från {from} till {to} för {parties}.");
    text.put("timeline.loading", "Hämtar tidsserien.");
    text.put("timeline.failed", "Tidsserien kunde inte hämtas.");
    text.put("timeline.threshold", "4 %-spärren");
    text.put(
        "timeline.sampling",
        "Kurvan visas med ett steg om {step} dygn. Den sista dagen i intervallet visas alltid.");
    text.put(
        "timeline.boundary",
        "Streckad linje: gräns mellan separata anpassningar. Ett brott där är ingen"
            + " väljarvandring.");
    text.put(
        "timeline.gap",
        "Ett glapp betyder att perioden saknar stöd i modellen. En dag utan ny mätning ger"
            + " inget glapp.");

    text.put("estimate.title", "Partierna");
    text.put("estimate.column.party", "Parti");
    text.put("estimate.column.estimate", "Skattning");
    text.put("estimate.column.interval", "Intervall");
    text.put("estimate.column.seats", "Mandat");
    text.put("estimate.column.trend", "Utveckling");
    text.put("estimate.verbal", "troligen mellan {lower} och {upper}");
    text.put("estimate.unavailable", "Ingen skattning för perioden");
    text.put("estimate.noSeats", "Inga mandat fördelas");
    text.put("estimate.fieldwork", "Fältarbete till och med {date}");
    text.put(
        "estimate.caption", "Skattat väljarstöd per parti, i procent, med {level} %-intervall.");

    text.put("party.fiLink", "Historiska mätningar för Feministiskt initiativ");
    text.put(
        "party.noCurrent",
        "Ingen aktuell enskild skattning finns för {party}. Historiska källobservationer visas"
            + " med sina datum.");
    text.put("party.threshold", "Sannolikhet att nå 4 %-spärren");
    text.put("party.thresholdReading", "Sannolikhet att nå 4 %-spärren: {probability}");
    text.put("party.seatsReading", "Approximerad mandatfördelning: {seats} mandat");
    text.put("party.observations", "Källobservationer");
    text.put(
        "party.observationsCaption",
        "Publicerade mätningar som redovisar {party}, även utanför perioder med en validerad"
            + " enskild skattning.");
    text.put("party.column.support", "Stöd");
    text.put("party.column.eligibility", "Modellstatus");
    text.put("party.eligible", "Ingår där perioden stöds");
    text.put("party.excluded", "Källobservation, ingår inte");
    text.put("party.houseEffects", "Huseffekter");
    text.put("party.houseEffect", "Effekt");
    text.put("party.houseInterval", "Intervall");
    text.put("party.effectRange", "{lower} till {upper}");
    text.put(
        "party.houseReference",
        "Huseffekter relativt genomsnittet för instituten i mandatperioden, i procentenheter.");
    text.put("party.houseUnavailable", "Ingen validerad huseffekt finns för perioden.");

    text.put("blocs.title", "Blockläget");
    text.put("blocs.majority", "175 för majoritet");
    text.put("blocs.majorityProbability", "{probability} sannolikhet för egen majoritet");
    text.put("blocs.seatsUnit", "mandat");
    text.put("blocs.total", "349 mandat");
    text.put("blocs.hemicycleLabel", "Mandatbåge: {summary}");
    text.put("blocs.pointSeats", "Bågen visar heltalsmandat från den skattade medelandelen.");
    text.put("blocs.caption", "Approximerade mandat per regeringsunderlag.");

    text.put("threshold.title", "Spärren 4 %");
    text.put("threshold.note", "Sannolikhet att nå 4 %-spärren nationellt.");
    text.put(
        "threshold.distinct",
        "Spärrlinjen i diagrammet är en nivå i procent. Sannolikheten här är något annat och"
            + " kommer från modellens gemensamma dragningar.");
    text.put("threshold.caption", "Sannolikhet att nå spärren, per parti.");

    text.put("polls.title", "Senaste mätningar");
    text.put("polls.column.institute", "Institut");
    text.put("polls.column.fieldwork", "Fältarbete");
    text.put("polls.column.sample", "Urval");
    text.put("polls.approximate", "Ungefärlig period");
    text.put("polls.missing", "Saknas");
    text.put("polls.all", "Alla mätningar");
    text.put(
        "polls.caption", "De senast publicerade mätningarna i den fastnålade ögonblicksbilden.");

    text.put("downloads.title", "För journalister");
    text.put("downloads.polls", "Mätningar (CSV)");
    text.put("downloads.estimates", "Skattningar (JSON)");
    text.put("downloads.seats", "Mandat (JSON)");
    text.put("downloads.houseEffects", "Huseffekter (JSON)");
    text.put("downloads.elections", "Valresultat (JSON)");
    text.put("downloads.image", "Sammanfattande bild (PNG)");
    text.put(
        "downloads.imageNote",
        "PNG:en är en daterad sammanfattande bild, inte en export av det valda diagramintervallet.");
    text.put("downloads.pinned", "Alla filer kommer från publiceringen {publication}.");

    text.put("share.title", "Dela");
    text.put("share.native", "Dela");
    text.put("share.x", "Dela på X");
    text.put("share.copy", "Kopiera länk");
    text.put("share.copied", "Länken är kopierad.");
    text.put("share.note", "Länken pekar på den här publiceringen och ändras inte av en senare.");
    text.put("share.permalink", "Permanent länk");

    text.put("about.title", "Om siffrorna");
    text.put(
        "about.method",
        "Skattningen väger samman publicerade opinionsmätningar med en daglig modell och avser"
            + " läget vid den sista fältarbetsdagen, {date}. Den skrivs inte fram och är ingen"
            + " prognos för ett kommande val.");
    text.put(
        "about.uncertainty",
        "Intervallen är {level} %-intervall och är betingade på modellens anpassade"
            + " hyperparametrar.");
    text.put("about.seats", Translations.of(Translations.SWEDISH).text("seats.note"));
    text.put(
        "about.other",
        "Övriga är samlat stöd för partier som inte särredovisas under perioden. Övriga är inget"
            + " parti och tilldelas inga mandat som grupp.");
    text.put(
        "about.missing",
        "Saknade värden gissas aldrig och fylls aldrig med noll. Kurvan får ett glapp bara när"
            + " perioden saknar stöd i modellen, inte för att en enskild dag saknar ny mätning.");
    text.put("about.source", "Källa: {source}, ögonblicksbild {snapshot}.");
    text.put("about.run", "Modellkörning {run}, kodversion {code}, frö {seed}.");

    text.put("sensitivity.title", "Känslighet");
    text.put("sensitivity.note", "Publicerad känslighetsanalys: {note}");
    return Map.copyOf(text);
  }

  private static Map<String, String> english() {
    final LinkedHashMap<String, String> text = new LinkedHashMap<>();
    text.put("nav.overview", "Overview");
    text.put("nav.party", "Parties");
    text.put("nav.seats", "Seats");
    text.put("nav.coalitions", "Coalitions");
    text.put("nav.pollsters", "Pollsters");
    text.put("nav.polls", "Polls");
    text.put("nav.method", "Method");
    text.put("nav.label", "Pages");
    text.put("language.label", "Language");
    text.put("language.sv", "Swedish");
    text.put("language.en", "English");
    text.put("language.short.sv", "SV");
    text.put("language.short.en", "EN");
    text.put("skip", "Skip to content");

    text.put("head.title.overview", "Estimated voter support");
    text.put("head.title.party", "Party");
    text.put("head.title.seats", "National seat approximation");
    text.put("head.title.coalitions", "Coalitions");
    text.put("head.title.pollsters", "Pollsters and house effects");
    text.put("head.title.polls", "Published polls");
    text.put("head.title.method", "Method and validation");
    text.put(
        "head.description.overview",
        "Pooled voting intention as of {date}, with intervals, approximated seats and the polls"
            + " behind them. Not an election forecast.");
    text.put(
        "head.description.party",
        "{party}: estimated voter support as of {date}, source polls and house effects. Not an"
            + " election forecast.");
    text.put(
        "head.description.partyHistorical",
        "{party}: historical source observations through {date}. No current individual estimate"
            + " is available.");
    text.put("head.imageAlt", "Summary image: estimated voter support as of {date}.");
    text.put("head.imageAlt.party", "Summary image for {party} as of {date}.");
    text.put("head.imageAlt.partyHistorical", "Historical summary image for {party} as of {date}.");

    text.put("headline", "Estimated voter support");
    text.put("headline.asOf", "as of {date}");
    text.put("notForecast", "Not an election forecast.");
    text.put("published", "Published {timestamp}.");
    text.put("sourceChecked", "Source checked {timestamp}.");
    text.put(
        "stale",
        "A later update could not be validated on {timestamp}. The estimate as of {date} stays"
            + " visible until the next run passes its checks.");
    text.put("unavailable.title", "No estimate is published yet");
    text.put(
        "unavailable.body",
        "The first publication has not completed. No numbers are shown until a run passes every"
            + " check.");
    text.put("unavailable.lastCheck", "The source was last checked {timestamp}.");

    text.put("timeline.title", "Support over time");
    text.put("timeline.range.oneYear", "One year");
    text.put("timeline.range.sinceElection", "Since the {year} election");
    text.put("timeline.range.fourYears", "Four years");
    text.put("timeline.range.all", "All history");
    text.put("timeline.range.label", "Time range");
    text.put("timeline.hint", "Drag on the chart, or use the arrow keys, to read off a date.");
    text.put("timeline.readout.label", "Readout");
    text.put("timeline.coverage", "Model period {period}. Parties: {roster}.");
    text.put("timeline.noCoverage", "No validated model period.");
    text.put("timeline.electionDot", "Rings are actual election results.");
    text.put("timeline.pollDots", "Solid dots are individual published polls.");
    text.put("timeline.bandNote", "The shaded band is the {level}% interval.");
    text.put("timeline.isolate", "Show one party at a time");
    text.put("timeline.isolateAll", "Show all");
    text.put("timeline.showTable", "Show the series as a table");
    text.put("timeline.hideTable", "Hide the table");
    text.put("timeline.tableCaption", "Estimated voter support per sampled date, in percent.");
    text.put("timeline.column.date", "Date");
    text.put(
        "timeline.summary",
        "Line chart of estimated voter support from {from} to {to} for {parties}.");
    text.put("timeline.loading", "Loading the series.");
    text.put("timeline.failed", "The series could not be loaded.");
    text.put("timeline.threshold", "The 4% threshold");
    text.put(
        "timeline.sampling",
        "The curve is drawn at a step of {step} days. The last day of the range is always kept.");
    text.put(
        "timeline.boundary",
        "Dashed line: a boundary between separate fits. A break there is not voter movement.");
    text.put(
        "timeline.gap",
        "A gap means the period has no supported estimate. A day without a new poll does not"
            + " create one.");

    text.put("estimate.title", "The parties");
    text.put("estimate.column.party", "Party");
    text.put("estimate.column.estimate", "Estimate");
    text.put("estimate.column.interval", "Interval");
    text.put("estimate.column.seats", "Seats");
    text.put("estimate.column.trend", "Trend");
    text.put("estimate.verbal", "probably between {lower} and {upper}");
    text.put("estimate.unavailable", "No estimate for this period");
    text.put("estimate.noSeats", "Receives no seats");
    text.put("estimate.fieldwork", "Fieldwork through {date}");
    text.put(
        "estimate.caption",
        "Estimated voter support per party, in percent, with {level}% intervals.");

    text.put("party.fiLink", "Historical polls for Feminist Initiative");
    text.put(
        "party.noCurrent",
        "No current individual estimate is available for {party}. Historical source observations"
            + " retain their dates.");
    text.put("party.threshold", "Probability of reaching the 4% threshold");
    text.put("party.thresholdReading", "Probability of reaching the 4% threshold: {probability}");
    text.put("party.seatsReading", "Approximated seat allocation: {seats} seats");
    text.put("party.observations", "Source observations");
    text.put(
        "party.observationsCaption",
        "Published polls reporting {party}, including observations outside periods with a"
            + " validated individual estimate.");
    text.put("party.column.support", "Support");
    text.put("party.column.eligibility", "Model status");
    text.put("party.eligible", "Included where the period is supported");
    text.put("party.excluded", "Source observation, not included");
    text.put("party.houseEffects", "House effects");
    text.put("party.houseEffect", "Effect");
    text.put("party.houseInterval", "Interval");
    text.put("party.effectRange", "{lower} to {upper}");
    text.put(
        "party.houseReference",
        "House effects relative to the ensemble of institutes in the election cycle, in"
            + " percentage points.");
    text.put("party.houseUnavailable", "No validated house effect is available for the period.");

    text.put("blocs.title", "Bloc standings");
    text.put("blocs.majority", "175 for a majority");
    text.put("blocs.majorityProbability", "{probability} probability of a majority");
    text.put("blocs.seatsUnit", "seats");
    text.put("blocs.total", "349 seats");
    text.put("blocs.hemicycleLabel", "Seat arc: {summary}");
    text.put(
        "blocs.pointSeats",
        "The arc shows integer seats allocated from the estimated mean support.");
    text.put("blocs.caption", "Approximated seats per coalition.");

    text.put("threshold.title", "The 4% threshold");
    text.put("threshold.note", "Probability of reaching the national 4% threshold.");
    text.put(
        "threshold.distinct",
        "The threshold line on the chart is a level in percent. The probability here is a"
            + " different quantity and comes from the model's joint draws.");
    text.put("threshold.caption", "Probability of clearing the threshold, per party.");

    text.put("polls.title", "Latest polls");
    text.put("polls.column.institute", "Pollster");
    text.put("polls.column.fieldwork", "Fieldwork");
    text.put("polls.column.sample", "Sample");
    text.put("polls.approximate", "Approximate period");
    text.put("polls.missing", "Missing");
    text.put("polls.all", "All polls");
    text.put("polls.caption", "The most recently published polls in the pinned snapshot.");

    text.put("downloads.title", "For journalists");
    text.put("downloads.polls", "Polls (CSV)");
    text.put("downloads.estimates", "Estimates (JSON)");
    text.put("downloads.seats", "Seats (JSON)");
    text.put("downloads.houseEffects", "House effects (JSON)");
    text.put("downloads.elections", "Election results (JSON)");
    text.put("downloads.image", "Summary image (PNG)");
    text.put(
        "downloads.imageNote",
        "The PNG is a dated summary image, not an export of the selected chart range.");
    text.put("downloads.pinned", "Every file comes from publication {publication}.");

    text.put("share.title", "Share");
    text.put("share.native", "Share");
    text.put("share.x", "Share on X");
    text.put("share.copy", "Copy link");
    text.put("share.copied", "The link is copied.");
    text.put("share.note", "The link names this publication and a later one does not change it.");
    text.put("share.permalink", "Permanent link");

    text.put("about.title", "About the numbers");
    text.put(
        "about.method",
        "The estimate pools published opinion polls with a daily model and refers to the last"
            + " fieldwork day, {date}. It is not projected forward and is not a forecast of any"
            + " coming election.");
    text.put(
        "about.uncertainty",
        "Intervals are {level}% intervals and are conditional on the model's fitted"
            + " hyperparameters.");
    text.put("about.seats", Translations.of(Translations.ENGLISH).text("seats.note"));
    text.put(
        "about.other",
        "Other parties is combined support for parties not shown individually in the period. It"
            + " is not a single party and receives no seats as a group.");
    text.put(
        "about.missing",
        "Missing values are never invented and never filled with zero. The curve breaks only"
            + " where the period has no supported estimate, not because one day lacks a new poll.");
    text.put("about.source", "Source: {source}, snapshot {snapshot}.");
    text.put("about.run", "Model run {run}, code version {code}, seed {seed}.");

    text.put("sensitivity.title", "Sensitivity");
    text.put("sensitivity.note", "Published sensitivity analysis: {note}");
    return Map.copyOf(text);
  }
}
