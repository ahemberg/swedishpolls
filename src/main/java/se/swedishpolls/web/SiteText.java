package se.swedishpolls.web;

import java.util.LinkedHashMap;
import java.util.Map;
import se.swedishpolls.publication.Translations;

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
    text.put(
        "head.description.seats",
        "Approximerad nationell mandatfördelning per {date}: heltalsmandat, väntevärden och"
            + " intervall. Inte en valprognos.");
    text.put(
        "head.description.polls",
        "Publicerade mätningar till och med {date}: institut, fältarbete, urval och redovisade"
            + " andelar, med källans precision. Inte en valprognos.");
    text.put(
        "head.description.pollsters",
        "Instituten bakom sammanvägningen, deras metodepoker och deras huseffekter per {date},"
            + " med intervall. Inte en valprognos.");
    text.put(
        "head.description.method",
        "Hur skattningen tas fram: källdata och täckning, modellen, osäkerheten, valideringen"
            + " och reproduktionen. Inte en valprognos.");
    text.put(
        "head.description.coalitions",
        "Tio regeringsunderlag per {date}: mandatintervall, sannolikhet för egen majoritet och"
            + " jämförelser mellan dem. Inte en valprognos.");
    text.put(
        "head.description.coalitionComparison",
        "Jämför block A ({a}) med block B ({b}) från {from} till {to}.");

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
    text.put("source.title", "Opinionsmätningar");
    text.put("source.updated", "Källdata uppdaterad {timestamp}.");
    text.put("source.noPolls.title", "Inga mätningar tillgängliga");
    text.put("source.noPolls.body", "Det finns inga sparade mätningar.");
    text.put("source.retry", "Försök igen");
    text.put(
        "source.description.overview",
        "De senaste insamlade opinionsmätningarna med fältarbete, urval och redovisade andelar.");
    text.put(
        "source.description.polls",
        "Insamlade opinionsmätningar med filter, urval och redovisade andelar med källans precision.");
    text.put("source.polls.title", "Insamlade mätningar");
    text.put("source.polls.lead", "Redovisade andelar från den sparade källan.");
    text.put("source.polls.empty", "Inga matchande mätningar. Ändra eller rensa filtret.");
    text.put("source.polls.column.other", "Övriga partier, inklusive FI");
    text.put("source.polls.column.eligibility", "Urvalsstatus");
    text.put("source.polls.filter.includeExcluded", "Visa även uteslutna mätningar");
    text.put("source.polls.eligible", "Inkluderad");
    text.put("source.polls.excluded", "Utesluten: {reasons}");
    text.put(
        "source.polls.downloadNote",
        "CSV-filen innehåller alla matchande rader från samma ögonblicksbild som tabellen, med"
            + " källans precision bevarad.");
    text.put("source.polls.snapshot", "Ögonblicksbild {snapshot}.");

    text.put("source.chart.title", "Redovisade andelar");
    text.put(
        "source.chart.summary",
        "Redovisade andelar {from} till {to} för {parties}, en markering per mätperiod.");
    text.put("source.chart.cursor.label", "Välj mätning");
    text.put("source.chart.sample", "Urval {sample}.");
    text.put("source.chart.noSample", "Urvalsstorlek saknas.");
    text.put("source.chart.empty", "Inga mätningar i det valda intervallet.");
    text.put(
        "source.chart.clipped",
        "Mätperioden sträcker sig utanför det valda intervallet och är avkortad i diagrammet.");
    text.put(
        "source.chart.note",
        "Varje markering sträcker sig över mätningens faktiska fältarbetsdagar. Dra i diagrammet,"
            + " eller använd reglaget, för att läsa av en mätning.");
    text.put(
        "source.chart.approximateNote",
        "Streckade markeringar har ungefärlig mätperiod; heldragna har redovisad period.");
    text.put(
        "source.chart.sourceNote",
        "Detta är insamlade mätningar, inte en skattning. En andel som institutet inte redovisat"
            + " saknar markering.");
    text.put("source.chart.loading", "Hämtar mätningarna.");
    text.put("source.chart.failed", "Mätningarna kunde inte hämtas.");

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
    text.put("polls.column.method", "Metod");
    text.put("polls.column.fieldwork", "Fältarbete");
    text.put("polls.column.published", "Publicerad");
    text.put("polls.column.sample", "Urval");
    text.put("polls.approximate", "Ungefärlig period");
    text.put("polls.missing", "Saknas");
    text.put("polls.all", "Alla mätningar");
    text.put(
        "polls.caption", "De senast publicerade mätningarna i den fastnålade ögonblicksbilden.");

    text.put(
        "polls.lead",
        "Källobservationer ur den fastnålade ögonblicksbilden: vad instituten själva publicerat,"
            + " inte modellens skattningar.");
    text.put(
        "polls.sourceNote",
        "Andelarna är källans egna, oavrundade. En andel som källan inte redovisat lämnas tom och"
            + " fylls aldrig med en nolla.");
    text.put("polls.filters", "Filtrera");
    text.put("polls.filter.from", "Från och med");
    text.put("polls.filter.to", "Till och med");
    text.put("polls.filter.institute", "Institut");
    text.put("polls.filter.anyInstitute", "Alla institut");
    text.put("polls.filter.coveragePeriod", "Täckningsperiod");
    text.put("polls.filter.anyPeriod", "Alla perioder");
    text.put("polls.filter.party", "Parti");
    text.put("polls.filter.allParties", "Alla partier");
    text.put("polls.filter.includeExcluded", "Visa även mätningar som modellen uteslutit");
    text.put("polls.filter.apply", "Filtrera");
    text.put("polls.filter.clear", "Rensa filtret");
    text.put("polls.filter.dateHint", "Datumgränserna är inklusive och matchar fältarbetet.");
    text.put(
        "polls.invalid",
        "Filtret {names} kunde inte läsas och användes inte. Tabellen visar de filter som gick att"
            + " läsa.");
    text.put("polls.empty", "Ingen mätning matchar filtret. Ändra eller rensa det.");
    text.put(
        "polls.tableCaption",
        "Mätning {first} till {last} av {total}, sida {page} av {pages}, i den fastnålade"
            + " ögonblicksbilden.");
    text.put("polls.column.period", "Täckningsperiod");
    text.put("polls.column.other", "Övriga");
    text.put("polls.column.eligibility", "Med i modellen");
    text.put("polls.eligible", "Ja");
    text.put("polls.excluded", "Nej: {reasons}");
    text.put("polls.noPeriod", "Utanför validerad period");
    text.put("polls.unmodeledMark", "†");
    text.put(
        "polls.unmodeled",
        "† Källan redovisar partiet, men det ingår inte i de modellerade komponenterna för"
            + " mätningens täckningsperiod. Talet är en observation, inte en skattning. Det gäller"
            + " Feministiskt initiativ utanför de perioder som har en egen FI-skattning.");
    text.put("polls.previous", "Föregående sida");
    text.put("polls.next", "Nästa sida");
    text.put("polls.pageOf", "Sida {page} av {pages}");
    text.put("polls.download", "Filtrerade mätningar (CSV)");
    text.put(
        "polls.downloadNote",
        "CSV-filen innehåller exakt de filtrerade raderna ur samma publicering och samma"
            + " ögonblicksbild som tabellen, med källans precision bevarad.");

    text.put(
        "pollsters.lead",
        "Instituten bakom sammanvägningen: vem som mätt, hur de mätt och hur deras skattningar"
            + " systematiskt avviker från institutsgenomsnittet i mandatperioden.");
    text.put("pollsters.metadata", "Institut och metodepoker");
    text.put("pollsters.column.companies", "Företag");
    text.put("pollsters.column.eras", "Metodepoker");
    text.put("pollsters.column.polls", "Mätningar");
    text.put("pollsters.column.first", "Första fältarbete");
    text.put("pollsters.column.last", "Senaste fältarbete");
    text.put(
        "pollsters.erasNote",
        "Metodepoken beskriver mätsättningen, inte namnet: ett varumärkesbyte är ingen"
            + " metodändring, och en metod kan bestå under ett nytt namn.");
    text.put(
        "pollsters.gridCaption",
        "Huseffekter under {cycle}, i procentenheter. Färgen förstärks med avvikelsen; talet"
            + " står alltid i rutan.");
    text.put(
        "pollsters.intervalCaption",
        "Samma effekter som tabell, med intervall: en rad per institut och parti.");
    text.put("pollsters.shrunkCell", "{effect} (skjuten mot noll)");
    text.put(
        "pollsters.shrunkNote",
        "En effekt märkt \u201dskjuten mot noll\u201d är skattad under modellens prior med"
            + " noll som väntevärde, som drar varje effekt mot institutsgenomsnittet."
            + " Märkningen säger att priorn tillämpas, inte att institutet har få mätningar"
            + " eller svagt underlag; intervallet bredvid talet visar hur mycket mätningarna"
            + " säger.");

    text.put(
        "method.lead",
        "Siffrorna på den här sajten är skattningar av väljaropinion, inte prognoser. Den"
            + " här sidan förklarar var de kommer från, vad de gäller och vad som är"
            + " validerat.");
    text.put("method.data.title", "Data och källor");
    text.put(
        "method.data.history",
        "Publicerad historia är korrigerad historia: varje publicering bygger på den"
            + " fullständiga ögonblicksbild som då är aktuell, med de rättelser och"
            + " borttagningar den innehåller. Den påstår inte att visa vad en besökare såg"
            + " vid den tiden.");
    text.put(
        "method.data.provenance",
        "Varje mätning arkiveras med institut, företag, metodepok, datum, urval och de"
            + " andelar som redovisats, med källans egen precision. Mätningar modellen"
            + " uteslutit arkiveras med sina skäl.");
    text.put(
        "method.data.eligibility",
        "Modellen använder vanliga opinionsmätningar. Exit- och valdagsmätningar utesluts,"
            + " liksom tills vidare mätningar som saknar en nödvändig andel, urvalsstorlek"
            + " eller användbara fältarbetsdatum. Ett värde källan inte redovisat gissas"
            + " aldrig och fylls aldrig med noll.");
    text.put(
        "method.data.eras",
        "Institut, företag och metodepoker förs isär. Ett varumärkesbyte är ingen"
            + " metodändring, och samma metod kan fortsätta under ett nytt namn.");
    text.put("method.coverage.title", "Täckningsperioder");
    text.put(
        "method.coverage.rosterCaption",
        "De perioder som har en validerad enskild skattning, vilka partier som"
            + " särredovisas och vad Övriga innehåller.");
    text.put("method.coverage.column.roster", "Särredovisade partier");
    text.put("method.coverage.column.span", "Från–till");
    text.put("method.coverage.column.status", "Stöd i modellen");
    text.put("method.coverage.validated", "Validerat");
    text.put("method.coverage.candidate", "Under validering");
    text.put("method.coverage.column.decision", "Ägarbeslut");
    text.put(
        "method.coverage.otherNote",
        "Övriga är samlat stöd utanför periodens särredovisade partier och tilldelas inga"
            + " mandat som grupp. Vid en jämförelse över periodgränser används den"
            + " jämförbara restposten: stödet utanför de åtta fasta partierna, med FI"
            + " inräknat i varje period.");
    text.put(
        "method.coverage.fiNote",
        "Feministiskt initiativ är särskattat bara i de perioder som tabellen markerar med"
            + " eget stöd. Utanför dem är mätningarna kvar i källtabellen, men den enskilda"
            + " skattningen är otillgänglig — den är aldrig noll.");
    text.put(
        "method.coverage.boundaryNote",
        "Varje period anpassas för sig, utan antagen kontinuitet över en gräns. Ett brott i"
            + " kurvan vid en gräns är ingen väljarvandring.");
    text.put("method.model.title", "Modellen");
    text.put(
        "method.model.observations",
        "Varje mätning bidrar med en observation vid fältarbetets mittpunkt, i en sammansatt"
            + " ilr-parametrisering med full multinomial kovarians. En daglig random walk i"
            + " samma rum knyter dagarna samman.");
    text.put(
        "method.model.estimand",
        "Den publicerade kurvan är den utjämnade skattningen av väljaropinion. Rubriktalet"
            + " gäller den sista fältarbetsdagen och skrivs aldrig framåt i tiden.");
    text.put(
        "method.model.house",
        "Institutseffekter nollställs per mandatperiod, skjuts mot noll av en prior med noll"
            + " som väntevärde och centreras med lika vikt per institut. De mäter avvikelsen"
            + " från institutsgenomsnittet i perioden, inte från den sanna opinionen.");
    text.put(
        "method.model.overdispersion",
        "Spridningen mellan mätningar utöver ren sampling fångas av en gemensam faktor på"
            + " observationskovariansen, delad av alla institut.");
    text.put(
        "method.model.hyper",
        "Hyperparametrarna — walkvarians, effektskala och överdispersion — väljs per period"
            + " på ett förut registrerat rutnät. Den publicerade osäkerheten är betingad"
            + " på de valda värdena.");
    text.put(
        "method.model.draws",
        "Sannolikheter och mandatintervall räknas från samma gemensamma dragningar, så att"
            + " ett partis siffra och ett underslags siffra hänger ihop. Valresultat visas"
            + " som referenspunkter, aldrig som modellobservationer.");
    text.put("method.validation.title", "Validering och grindar");
    text.put(
        "method.validation.verdictReleased",
        "Registrerad status för det här protokollet: släppt. Alla blockerande grindar är"
            + " klarade.");
    text.put(
        "method.validation.verdictBlocked",
        "Registrerad status för det här protokollet: blockerad. Ingen ny skattning"
            + " publiceras förrän grindarna är klarade, och bara ett registrerat"
            + " ägarbeslut kan undanta en grind.");
    text.put("method.validation.failed", "Blockerande grindar som inte är klarade: {gates}");
    text.put("method.validation.gatesLead", "De registrerade prediktiva grindarna:");
    text.put(
        "method.validation.gateScore",
        "Den genomsnittliga prediktiva poängen slår den registrerade recensviktade"
            + " baslinjen på de frusna utvecklingsvecken, och är inte väsentligt sämre än"
            + " referensen.");
    text.put(
        "method.validation.gateCoverage",
        "95 %-intervallen träffar inom 90–98 procent av tiden och 50 %-intervallen inom"
            + " 40–60 procent, med mätnivåbruset inkluderat i utfallet.");
    text.put(
        "method.validation.gateMisfit",
        "Ingen oförklarad systematisk avvikelse per parti, institut eller fältarbetets"
            + " längd; residualautokorrelation granskas och förklaras.");
    text.put("method.validation.coverageLead", "Registrerade krav på en täckningsperiod:");
    text.put("method.coverage.gate.polls", "Minst antal mätningar");
    text.put("method.coverage.gate.institutes", "Minst antal institut");
    text.put("method.coverage.gate.gap", "Största tillåtna glapp, i dagar");
    text.put("method.coverage.gate.shifts", "Gränsförskjutningar som provas, i dagar");
    text.put("method.coverage.gate.burnIn", "Inbränning innan stabiliteten mäts, i dagar");
    text.put(
        "method.coverage.gate.stability",
        "Största tillåtna rörelse vid inbränningen, i procentenheter");
    text.put("method.coverage.gate.development", "Utvecklingsdata avsnörs");
    text.put(
        "method.validation.sensitivity",
        "För varje publicering räknas känsligheten mot registrerade alternativ: ett"
            + " institut i taget utelämnat, och likaviktning istället för viktning efter"
            + " antal mätningar. Rör ett alternativ en publicerad sannolikhet mer än den"
            + " registrerade tröskeln står upplysningen bredvid talet, på mandat- och"
            + " underlagssidorna.");
    text.put("method.reproduction.title", "Reproduktion");
    text.put("method.reproduction.seed", "Frö: {seed}");
    text.put("method.reproduction.draws", "Gemensamma dragningar: {draws}");
    text.put(
        "method.reproduction.decimals", "Publicerade andelar avrundas till {decimals} decimal.");
    text.put("method.reproduction.estimator", "Estimator {version}, numerik {library}.");
    text.put(
        "method.reproduction.protocols",
        "Utvecklingsprotokoll {development}, släppprotokoll {release}.");
    text.put(
        "method.reproduction.inputs",
        "Ögonblicksbilden, körningen, kodversionen och fröet står i sidfoten. Arkiverade"
            + " ingångar och parametrar ska reproducera publicerade resultat.");
    text.put("method.seats.title", "Nationella mandat");

    text.put("downloads.title", "För journalister");
    text.put("downloads.polls", "Mätningar (CSV)");
    text.put("downloads.estimates", "Skattningar (JSON)");
    text.put("downloads.seats", "Mandat (JSON)");
    text.put("downloads.coalitions", "Regeringsunderlag (JSON)");
    text.put("downloads.houseEffects", "Huseffekter (JSON)");
    text.put("downloads.elections", "Valresultat (JSON)");
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
    text.put("seats.tie", Translations.of(Translations.SWEDISH).text("seats.tie"));
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

    text.put("seats.title", "Approximerad mandatfördelning");
    text.put(
        "seats.lead", "Fördelningen av 349 mandat utifrån det skattade väljarstödet per {date}.");
    text.put("seats.column.point", "Mandat");
    text.put("seats.column.mean", "Väntevärde");
    text.put("seats.column.interval", "Mandatintervall");
    text.put("seats.column.threshold", "Över spärren");
    text.put("seats.caption", "Heltalsmandat, väntevärde och {level} %-intervall per parti.");
    text.put(
        "seats.pointVersusMean",
        "Mandatkolumnen är en heltalsfördelning av den skattade medelandelen och summerar till"
            + " 349. Väntevärdet är genomsnittet av mandaten över modellens dragningar och är"
            + " något annat; bågen visar heltalsfördelningen och inte avrundade väntevärden.");
    text.put(
        "seats.era",
        "Fördelningen följer de jämkade uddatalsregler som gällde vid valet {year}, inte dagens"
            + " regler tillämpade bakåt i tiden.");
    text.put(
        "seats.threshold",
        "Ett parti fördelas mandat först vid 4 % nationellt. Spärren jämförs mot den oavrundade"
            + " andelen, och exakt 4 % räcker. Sannolikheten avser att nå 4 % nationellt, inte"
            + " hela den rättsliga vägen in i riksdagen.");
    text.put(
        "seats.other",
        "Övriga är samlat stöd utanför de särredovisade partierna och tilldelas inga mandat som"
            + " grupp.");

    text.put("coalitions.title", "Regeringsunderlag");
    text.put("coalitionHistory.title", "Blockens väljarstöd");
    text.put("coalitionHistory.breaks", "Glapp och modellgränser");
    text.put("coalitionHistory.fit", "Modellgräns {date}");
    text.put(
        "coalitionHistory.note",
        "Medelvärde och punktvisa 95 % osäkerhetsintervall. Intervallen gäller varje dag för sig, med modellens frysta hyperparametrar.");
    text.put("coalitionHistory.unavailable", "Blockhistorik saknas för denna publicering.");
    text.put("coalitionHistory.a", "Block A, heldragen");
    text.put("coalitionHistory.b", "Block B, streckad");
    text.put("coalitionHistory.latest", "Senaste skattning, {date}");
    text.put("coalitionHistory.published", "Publicerad {date}");
    text.put("coalitionHistory.from", "Från");
    text.put("coalitionHistory.to", "Till");
    text.put("coalitionHistory.apply", "Visa period");
    text.put("coalitionHistory.share", "Länk till jämförelsen");
    text.put("coalitionHistory.invalid", "Länken till jämförelsen är ogiltig.");
    text.put("coalitionHistory.invalid.overlap", "Ett parti får inte ingå i båda blocken.");
    text.put(
        "coalitionHistory.invalid.missing_block",
        "Både a och b krävs. Ett tomt värde väljer inga partier.");
    text.put(
        "coalitionHistory.invalid.invalid_party",
        "Använd varje versal kod för de åtta partierna högst en gång, utan blanksteg eller tomma delar.");
    text.put(
        "coalitionHistory.invalid.mixed_parameters", "Blanda inte parametern parties med a och b.");
    text.put("coalitionHistory.invalid.repeated_parameter", "Ange parametern exakt en gång.");
    text.put(
        "coalitionHistory.invalid.reversed_range",
        "Slutdatumet måste vara samma som eller senare än startdatumet.");
    text.put(
        "coalitionHistory.invalid.malformed_date",
        "Använd ett riktigt datum som ÅÅÅÅ-MM-DD från år 0001 till 9999.");
    text.put("coalitionHistory.invalid.invalid_date", "Använd ett riktigt kalenderdatum.");
    text.put(
        "coalitionHistory.invalid.resolved_reversed_range",
        "Det valda slutdatumet är före startdatumet.");
    text.put("coalitionHistory.invalid.unsupported_step", "Använd steget 1, 3 eller 7.");
    text.put("coalitionHistory.reset", "Återställ standardblocken");
    text.put("coalitionHistory.noParties", "inga partier");
    text.put("coalitionHistory.loading", "Uppdaterar perioden…");
    text.put("coalitionHistory.error", "Perioden kunde inte hämtas. Försök igen.");
    text.put("coalitionHistory.retry", "Försök igen");
    text.put("coalitionHistory.cursor", "Gemensamt datum för båda blocken");
    text.put("coalitionHistory.table", "Datum, medelvärde och punktvist 95 % intervall");
    text.put("coalitionHistory.remainder", "Jämförbar rest");
    text.put("coalitionHistory.outside", "Utanför båda blocken");
    text.put("coalitionHistory.unassigned", "Ej tilldelade partier");
    text.put("coalitionHistory.empty", "Inga skattningar i den valda perioden.");
    text.put("coalitionHistory.interval", "{mean} %, 95 % intervall {lower} till {upper} %");
    text.put("coalitionEditor.title", "Bygg två block");
    text.put(
        "coalitionEditor.help",
        "Dra en partibricka eller välj den och flytta den med knapparna. Escape avbryter ett val.");
    text.put("coalitionEditor.support", "Senaste väljarstödet");
    text.put("coalitionEditor.supportUnavailable", "Senaste väljarstödet kan inte visas.");
    text.put("coalitionEditor.supportLabel", "Block A {a}, block B {b}, utanför båda {outside}");
    text.put("coalitionEditor.a", "Block A");
    text.put("coalitionEditor.b", "Block B");
    text.put("coalitionEditor.unassigned", "Ej tilldelade partier");
    text.put("coalitionEditor.remainder", "Jämförbar rest");
    text.put("coalitionEditor.outside", "Utanför båda");
    text.put("coalitionEditor.notSeats", "Väljarstöd, inte mandat");
    text.put("coalitionEditor.moved", "{party} flyttades till {destination}");
    text.put("coalitionEditor.moveHere", "Flytta hit {party}");
    text.put("coalitionEditor.partyLabel", "{party}, {destination}. Välj för att flytta.");
    text.put("coalitionEditor.emptyZone", "Släpp partier här");
    text.put("coalitionEditor.reset", "Återställ");
    text.put("coalitionEditor.clear", "Rensa");
    text.put("coalitionEditor.noParties", "Inga partier");
    text.put("coalitionEditor.assignParty", "Tilldela ett parti för att jämföra");
    text.put(
        "coalitions.lead",
        "Tio bestämda partikombinationer per {date}. Etiketten anger vilka partier som räknas"
            + " samman.");
    text.put("coalitions.column.parties", "Partier");
    text.put("coalitions.column.majority", "Egen majoritet");
    text.put("coalitions.caption", "Mandat och sannolikhet för egen majoritet per underlag.");
    text.put(
        "coalitions.majorityLine",
        "Ett underlag behöver {majority} av 349 mandat för egen majoritet.");
    text.put("pairwise.title", "Jämförelse mellan underlag");
    text.put(
        "pairwise.caption",
        "Sannolikhet att det ena underlaget får fler mandat än det andra, över samma dragningar.");
    text.put("pairwise.column.left", "Underlag");
    text.put("pairwise.column.right", "Jämfört med");
    text.put("pairwise.column.leftLeads", "Fler mandat");
    text.put("pairwise.column.rightLeads", "Färre mandat");
    text.put("pairwise.tied", "Lika");
    text.put(
        "pairwise.tieNote",
        "Exakt lika mandattal räknas som att ingen av sidorna vinner, och redovisas i egen"
            + " kolumn. De tre kolumnerna summerar till 100 %.");
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
    text.put(
        "head.description.seats",
        "National seat approximation as of {date}: integer seats, posterior means and intervals."
            + " Not an election forecast.");
    text.put(
        "head.description.polls",
        "Published polls up to {date}: pollster, fieldwork, sample and reported shares, at the"
            + " source's own precision. Not an election forecast.");
    text.put(
        "head.description.pollsters",
        "The pollsters behind the pooled estimate, their method eras and their house effects"
            + " as of {date}, with intervals. Not an election forecast.");
    text.put(
        "head.description.method",
        "How the estimate is produced: source data and coverage, the model, uncertainty,"
            + " validation and reproduction. Not an election forecast.");
    text.put(
        "head.description.coalitions",
        "Ten coalitions as of {date}: seat intervals, majority probabilities and comparisons"
            + " between them. Not an election forecast.");
    text.put(
        "head.description.coalitionComparison",
        "Compare block A ({a}) with block B ({b}) from {from} to {to}.");

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
    text.put("source.title", "Opinion polls");
    text.put("source.updated", "Source data updated {timestamp}.");
    text.put("source.noPolls.title", "No polls available");
    text.put("source.noPolls.body", "There are no retained polls to show.");
    text.put("source.retry", "Retry");
    text.put(
        "source.description.overview",
        "The latest collected opinion polls with fieldwork dates, samples and reported shares.");
    text.put(
        "source.description.polls",
        "Collected opinion polls with filters, samples and reported shares at source precision.");
    text.put("source.polls.title", "Collected polls");
    text.put("source.polls.lead", "Reported shares from the retained source.");
    text.put("source.polls.empty", "No matching polls. Change or clear the filters.");
    text.put("source.polls.column.other", "Other parties, including FI");
    text.put("source.polls.column.eligibility", "Status");
    text.put("source.polls.filter.includeExcluded", "Also show excluded polls");
    text.put("source.polls.eligible", "Included");
    text.put("source.polls.excluded", "Excluded: {reasons}");
    text.put(
        "source.polls.downloadNote",
        "The CSV contains every matching row from the same snapshot as the table, with source"
            + " precision preserved.");
    text.put("source.polls.snapshot", "Snapshot {snapshot}.");

    text.put("source.chart.title", "Reported shares");
    text.put(
        "source.chart.summary",
        "Reported shares from {from} to {to} for {parties}, one mark per interview period.");
    text.put("source.chart.cursor.label", "Choose a poll");
    text.put("source.chart.sample", "Sample {sample}.");
    text.put("source.chart.noSample", "No sample size reported.");
    text.put("source.chart.empty", "No polls in the chosen range.");
    text.put(
        "source.chart.clipped",
        "The interview period reaches outside the chosen range and is clipped in the chart.");
    text.put(
        "source.chart.note",
        "Each mark spans the poll's actual interview days. Drag on the chart, or use the slider,"
            + " to read off a poll.");
    text.put(
        "source.chart.approximateNote",
        "Dashed marks have an approximate interview period; solid marks have a reported one.");
    text.put(
        "source.chart.sourceNote",
        "These are collected polls, not an estimate. A share the pollster did not report has no"
            + " mark.");
    text.put("source.chart.loading", "Fetching the polls.");
    text.put("source.chart.failed", "The polls could not be fetched.");

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
    text.put("polls.column.method", "Method");
    text.put("polls.column.fieldwork", "Fieldwork");
    text.put("polls.column.published", "Published");
    text.put("polls.column.sample", "Sample");
    text.put("polls.approximate", "Approximate period");
    text.put("polls.missing", "Missing");
    text.put("polls.all", "All polls");
    text.put("polls.caption", "The most recently published polls in the pinned snapshot.");

    text.put(
        "polls.lead",
        "Source observations from the pinned snapshot: what the pollsters published themselves,"
            + " not the model's estimates.");
    text.put(
        "polls.sourceNote",
        "Shares are the source's own, unrounded. A share the source did not report is left empty"
            + " and is never filled with a zero.");
    text.put("polls.filters", "Filter");
    text.put("polls.filter.from", "From");
    text.put("polls.filter.to", "To");
    text.put("polls.filter.institute", "Pollster");
    text.put("polls.filter.anyInstitute", "All pollsters");
    text.put("polls.filter.coveragePeriod", "Coverage period");
    text.put("polls.filter.anyPeriod", "All periods");
    text.put("polls.filter.party", "Party");
    text.put("polls.filter.allParties", "All parties");
    text.put("polls.filter.includeExcluded", "Also show polls the model excluded");
    text.put("polls.filter.apply", "Filter");
    text.put("polls.filter.clear", "Clear the filter");
    text.put("polls.filter.dateHint", "Both bounds are inclusive and match the fieldwork period.");
    text.put(
        "polls.invalid",
        "The {names} filter could not be read and was not applied. The table shows the filters"
            + " that could be read.");
    text.put("polls.empty", "No poll matches this filter. Change or clear it.");
    text.put(
        "polls.tableCaption",
        "Poll {first} to {last} of {total}, page {page} of {pages}, in the pinned snapshot.");
    text.put("polls.column.period", "Coverage period");
    text.put("polls.column.other", "Other");
    text.put("polls.column.eligibility", "In the model");
    text.put("polls.eligible", "Yes");
    text.put("polls.excluded", "No: {reasons}");
    text.put("polls.noPeriod", "Outside any validated period");
    text.put("polls.unmodeledMark", "\u2020");
    text.put(
        "polls.unmodeled",
        "\u2020 The source reports this party, but it is not one of the modeled components of the"
            + " poll's coverage period. The number is an observation, not an estimate. This"
            + " applies to the Feminist Initiative outside the periods that carry an individual FI"
            + " estimate.");
    text.put("polls.previous", "Previous page");
    text.put("polls.next", "Next page");
    text.put("polls.pageOf", "Page {page} of {pages}");
    text.put("polls.download", "Filtered polls (CSV)");
    text.put(
        "polls.downloadNote",
        "The CSV holds exactly the filtered rows, from the same publication and the same snapshot"
            + " as the table, with the source's precision preserved.");

    text.put(
        "pollsters.lead",
        "The pollsters behind the pooled estimate: who has measured, how they measure, and how"
            + " their estimates systematically deviate from the institute ensemble of the"
            + " election cycle.");
    text.put("pollsters.metadata", "Pollsters and method eras");
    text.put("pollsters.column.companies", "Companies");
    text.put("pollsters.column.eras", "Method eras");
    text.put("pollsters.column.polls", "Polls");
    text.put("pollsters.column.first", "First fieldwork");
    text.put("pollsters.column.last", "Latest fieldwork");
    text.put(
        "pollsters.erasNote",
        "A method era describes the measurement, not the name: a brand rename is not a method"
            + " change, and a method can continue under a new name.");
    text.put(
        "pollsters.gridCaption",
        "House effects during {cycle}, in percentage points. The colour deepens with the"
            + " deviation; the number is always in the cell.");
    text.put(
        "pollsters.intervalCaption",
        "The same effects as a table, with intervals: one row per pollster and party.");
    text.put("pollsters.shrunkCell", "{effect} (shrunk toward zero)");
    text.put(
        "pollsters.shrunkNote",
        "An effect marked \u201cshrunk toward zero\u201d is estimated under the model's"
            + " zero-centred prior, which pulls every effect toward the pollster ensemble. The"
            + " mark says the prior applies, not that the pollster has few polls or weak"
            + " evidence; the interval beside the number carries what the polls say.");

    text.put(
        "method.lead",
        "The numbers on this site are estimates of voting intention, not forecasts. This page"
            + " explains where they come from, what they cover, and what has been validated.");
    text.put("method.data.title", "Data and sources");
    text.put(
        "method.data.history",
        "Published history is corrected history: every publication is built from the complete"
            + " snapshot current at the time, with the corrections and removals it carries. It"
            + " does not claim to show what a visitor saw back then.");
    text.put(
        "method.data.provenance",
        "Every poll is archived with its pollster, company, method era, dates, sample and"
            + " reported shares, at the source's own precision. Polls the model excluded are"
            + " archived with their reasons.");
    text.put(
        "method.data.eligibility",
        "The model uses ordinary opinion polls. Exit and election-day polls are excluded, as"
            + " are, for now, polls missing a required share, sample size or usable fieldwork"
            + " dates. A value the source did not report is never guessed and never filled"
            + " with zero.");
    text.put(
        "method.data.eras",
        "Pollster, company and method era are kept apart. A brand rename is not a method"
            + " change, and the same method can continue under a new name.");
    text.put("method.coverage.title", "Coverage periods");
    text.put(
        "method.coverage.rosterCaption",
        "The periods that carry a validated individual estimate, which parties are reported"
            + " separately, and what Other includes.");
    text.put("method.coverage.column.roster", "Separately reported parties");
    text.put("method.coverage.column.span", "From–to");
    text.put("method.coverage.column.status", "Support in the model");
    text.put("method.coverage.validated", "Validated");
    text.put("method.coverage.candidate", "Under validation");
    text.put("method.coverage.column.decision", "Owner decision");
    text.put(
        "method.coverage.otherNote",
        "Other is combined support outside the period's separately reported parties and"
            + " receives no seats as a group. A comparison across period boundaries uses the"
            + " comparable remainder: support outside the fixed eight parties, counting FI in"
            + " every period.");
    text.put(
        "method.coverage.fiNote",
        "Feminist Initiative is estimated individually only in the periods the table marks as"
            + " supported. Outside them the observations stay in the source table, but the"
            + " individual estimate is unavailable — it is never zero.");
    text.put(
        "method.coverage.boundaryNote",
        "Each period is fitted on its own, with no continuity assumed across a boundary. A"
            + " break in the curve at a boundary is not voter movement.");
    text.put("method.model.title", "The model");
    text.put(
        "method.model.observations",
        "Every poll contributes one observation at its fieldwork midpoint, in a compositional"
            + " ilr parametrisation with the full multinomial covariance. A daily random walk"
            + " in the same space ties the days together.");
    text.put(
        "method.model.estimand",
        "The published curve is the smoothed estimate of voting intention. The headline number"
            + " refers to the last fieldwork day and is never projected forward.");
    text.put(
        "method.model.house",
        "House effects reset every election cycle, are shrunk toward zero by a zero-centred"
            + " prior, and are centred with equal weight per pollster. They measure the"
            + " deviation from the cycle's institute ensemble, not from true opinion.");
    text.put(
        "method.model.overdispersion",
        "Poll-to-poll variation beyond pure sampling is carried by one shared factor on the"
            + " observation covariance, common to every pollster.");
    text.put(
        "method.model.hyper",
        "The hyperparameters — walk variance, house-effect scale and overdispersion — are"
            + " chosen per period over a pre-registered grid. The published uncertainty is"
            + " conditional on the chosen values.");
    text.put(
        "method.model.draws",
        "Probabilities and seat intervals are computed from the same joint draws, so a party's"
            + " number and a coalition's number move together. Election outcomes are shown as"
            + " reference dots, never as model observations.");
    text.put("method.validation.title", "Validation and gates");
    text.put(
        "method.validation.verdictReleased",
        "Recorded verdict for this protocol: released. Every blocking gate has passed.");
    text.put(
        "method.validation.verdictBlocked",
        "Recorded verdict for this protocol: blocked. No new estimate publishes until the"
            + " gates pass, and only a recorded owner decision can waive one.");
    text.put("method.validation.failed", "Blocking gates not passed: {gates}");
    text.put("method.validation.gatesLead", "The registered predictive gates:");
    text.put(
        "method.validation.gateScore",
        "The mean predictive score beats the registered recency-weighted baseline on the"
            + " frozen development folds, and is not materially worse than the reference.");
    text.put(
        "method.validation.gateCoverage",
        "The 95% intervals hit within 90–98 percent of the time and the 50% intervals within"
            + " 40–60 percent, with poll noise included in the outcome.");
    text.put(
        "method.validation.gateMisfit",
        "No unexplained systematic misfit by party, pollster or fieldwork length; residual"
            + " autocorrelation is inspected and explained.");
    text.put("method.validation.coverageLead", "Registered requirements for a coverage period:");
    text.put("method.coverage.gate.polls", "Minimum number of polls");
    text.put("method.coverage.gate.institutes", "Minimum number of pollsters");
    text.put("method.coverage.gate.gap", "Largest allowed gap, in days");
    text.put("method.coverage.gate.shifts", "Boundary shifts tested, in days");
    text.put("method.coverage.gate.burnIn", "Burn-in before stability is measured, in days");
    text.put(
        "method.coverage.gate.stability", "Largest allowed shift at burn-in, in percentage points");
    text.put("method.coverage.gate.development", "Development data cut off at");
    text.put(
        "method.validation.sensitivity",
        "Every publication is rechecked against the registered alternatives: one pollster left"
            + " out at a time, and equal weighting instead of weighting by poll count. When an"
            + " alternative moves a published probability past the registered threshold, the"
            + " disclosure stands next to the number, on the seats and coalitions pages.");
    text.put("method.reproduction.title", "Reproduction");
    text.put("method.reproduction.seed", "Seed: {seed}");
    text.put("method.reproduction.draws", "Joint draws: {draws}");
    text.put(
        "method.reproduction.decimals", "Published shares are quoted to {decimals} decimal place.");
    text.put("method.reproduction.estimator", "Estimator {version}, numerics {library}.");
    text.put(
        "method.reproduction.protocols",
        "Development protocol {development}, release protocol {release}.");
    text.put(
        "method.reproduction.inputs",
        "The snapshot, run, code version and seed are in the footer. Archived inputs and"
            + " parameters must reproduce published results.");
    text.put("method.seats.title", "National seats");

    text.put("downloads.title", "For journalists");
    text.put("downloads.polls", "Polls (CSV)");
    text.put("downloads.estimates", "Estimates (JSON)");
    text.put("downloads.seats", "Seats (JSON)");
    text.put("downloads.coalitions", "Coalitions (JSON)");
    text.put("downloads.houseEffects", "House effects (JSON)");
    text.put("downloads.elections", "Election results (JSON)");
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
    text.put("seats.tie", Translations.of(Translations.ENGLISH).text("seats.tie"));
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

    text.put("seats.title", "National seat approximation");
    text.put("seats.lead", "The 349 seats allocated from estimated voter support as of {date}.");
    text.put("seats.column.point", "Seats");
    text.put("seats.column.mean", "Posterior mean");
    text.put("seats.column.interval", "Seat interval");
    text.put("seats.column.threshold", "Above the threshold");
    text.put("seats.caption", "Integer seats, posterior mean and {level}% interval per party.");
    text.put(
        "seats.pointVersusMean",
        "The seat column is an integer allocation of the estimated mean support and sums to 349."
            + " The posterior mean is the average number of seats across the model's draws and is"
            + " a different quantity; the arc shows the integer allocation, not rounded posterior"
            + " means.");
    text.put(
        "seats.era",
        "The allocation follows the modified Sainte-Lague rules in force at the {year} election,"
            + " not today's rules applied backwards.");
    text.put(
        "seats.threshold",
        "A party is allocated seats only at 4% nationally. The threshold is compared with the"
            + " unrounded share, and exactly 4% qualifies. The probability is of reaching 4%"
            + " nationally, not of the full legal path into parliament.");
    text.put(
        "seats.other",
        "Other is combined support outside the separately reported parties and is allocated no"
            + " seats as a group.");

    text.put("coalitions.title", "Coalitions");
    text.put("coalitionHistory.title", "Coalition vote shares");
    text.put("coalitionHistory.breaks", "Gaps and model boundaries");
    text.put("coalitionHistory.fit", "Model boundary {date}");
    text.put(
        "coalitionHistory.note",
        "Mean and pointwise 95% uncertainty intervals. Intervals apply to each day separately, conditional on the frozen model hyperparameters.");
    text.put(
        "coalitionHistory.unavailable", "Coalition history is unavailable for this publication.");
    text.put("coalitionHistory.a", "Block A, solid");
    text.put("coalitionHistory.b", "Block B, dashed");
    text.put("coalitionHistory.latest", "Latest estimate, {date}");
    text.put("coalitionHistory.published", "Published {date}");
    text.put("coalitionHistory.from", "From");
    text.put("coalitionHistory.to", "To");
    text.put("coalitionHistory.apply", "Show range");
    text.put("coalitionHistory.share", "Link to this comparison");
    text.put("coalitionHistory.invalid", "This comparison link is invalid.");
    text.put("coalitionHistory.invalid.overlap", "A party cannot belong to both coalitions.");
    text.put(
        "coalitionHistory.invalid.missing_block",
        "Both a and b are required; an empty value selects no parties.");
    text.put(
        "coalitionHistory.invalid.invalid_party",
        "Use each uppercase eight-party code at most once, without spaces or empty tokens.");
    text.put(
        "coalitionHistory.invalid.mixed_parameters",
        "Do not mix parties with the a and b parameters.");
    text.put("coalitionHistory.invalid.repeated_parameter", "Supply this parameter exactly once.");
    text.put(
        "coalitionHistory.invalid.reversed_range",
        "The end date must be on or after the start date.");
    text.put(
        "coalitionHistory.invalid.malformed_date",
        "Use a real YYYY-MM-DD date in years 0001 through 9999.");
    text.put("coalitionHistory.invalid.invalid_date", "Use a real calendar date.");
    text.put(
        "coalitionHistory.invalid.resolved_reversed_range",
        "The resolved end date precedes the start date.");
    text.put("coalitionHistory.invalid.unsupported_step", "Use a step of 1, 3 or 7.");
    text.put("coalitionHistory.reset", "Reset to the preset coalitions");
    text.put("coalitionHistory.noParties", "no parties");
    text.put("coalitionHistory.loading", "Updating range…");
    text.put("coalitionHistory.error", "Could not load this range. Try again.");
    text.put("coalitionHistory.retry", "Retry");
    text.put("coalitionHistory.cursor", "Shared date for both blocks");
    text.put("coalitionHistory.table", "Date, mean and pointwise 95% interval");
    text.put("coalitionHistory.remainder", "Comparable remainder");
    text.put("coalitionHistory.outside", "Outside both blocks");
    text.put("coalitionHistory.unassigned", "Unassigned parties");
    text.put("coalitionHistory.empty", "No estimates in the selected range.");
    text.put("coalitionHistory.interval", "{mean}%, 95% interval {lower} to {upper}%");
    text.put("coalitionEditor.title", "Build two coalitions");
    text.put(
        "coalitionEditor.help",
        "Drag a party tile, or select it and use the move buttons. Escape cancels a selection.");
    text.put("coalitionEditor.support", "Latest voting intention");
    text.put("coalitionEditor.supportUnavailable", "Latest voting intention is unavailable.");
    text.put(
        "coalitionEditor.supportLabel", "Coalition A {a}, coalition B {b}, outside both {outside}");
    text.put("coalitionEditor.a", "Coalition A");
    text.put("coalitionEditor.b", "Coalition B");
    text.put("coalitionEditor.unassigned", "Unassigned parties");
    text.put("coalitionEditor.remainder", "Comparable remainder");
    text.put("coalitionEditor.outside", "Outside both");
    text.put("coalitionEditor.notSeats", "Support, not seats");
    text.put("coalitionEditor.moved", "{party} moved to {destination}");
    text.put("coalitionEditor.moveHere", "Move here {party}");
    text.put("coalitionEditor.partyLabel", "{party}, {destination}. Select to move.");
    text.put("coalitionEditor.emptyZone", "Drop parties here");
    text.put("coalitionEditor.reset", "Reset");
    text.put("coalitionEditor.clear", "Clear");
    text.put("coalitionEditor.noParties", "No parties");
    text.put("coalitionEditor.assignParty", "Assign a party to compare");
    text.put(
        "coalitions.lead",
        "Ten fixed party combinations as of {date}. The label states which parties are counted"
            + " together.");
    text.put("coalitions.column.parties", "Parties");
    text.put("coalitions.column.majority", "Own majority");
    text.put("coalitions.caption", "Seats and majority probability per coalition.");
    text.put(
        "coalitions.majorityLine", "A coalition needs {majority} of the 349 seats for a majority.");
    text.put("pairwise.title", "Comparison between coalitions");
    text.put(
        "pairwise.caption",
        "Probability that one coalition takes more seats than the other, over the same draws.");
    text.put("pairwise.column.left", "Coalition");
    text.put("pairwise.column.right", "Compared with");
    text.put("pairwise.column.leftLeads", "More seats");
    text.put("pairwise.column.rightLeads", "Fewer seats");
    text.put("pairwise.tied", "Tied");
    text.put(
        "pairwise.tieNote",
        "An exact tie in seats counts as neither side winning and is reported in its own column."
            + " The three columns sum to 100%.");
    return Map.copyOf(text);
  }
}
