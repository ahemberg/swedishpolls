package se.swedishpolls;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.service.PollQueryService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Everything one page shows, resolved against one publication.
 *
 * <p>The publication is resolved once here. The documents, the poll rows, the download links and
 * the share image all name that same publication, so a publish that lands while a reader is loading
 * the page cannot mix two runs into one view. The rendered HTML and the script both read this
 * object, which is why the page cannot word a number one way before the script runs and another way
 * after it.
 */
@Component
public final class SiteBootstrap {
  /** How many of the pinned snapshot's most recent polls the overview lists. */
  static final int LATEST_POLLS = 8;

  /**
   * Every component a page can name: the poll columns, the aggregate that is not a party, and the
   * cross-period remainder. A page labels all of them, so a roster change cannot leave a row
   * showing a bare key.
   */
  private static final List<String> LABELLED =
      Stream.concat(PollQuery.COMPONENTS.stream(), Stream.of("OTHER", "REMAINDER")).toList();

  /** The display step of the ranges the timeline offers, within the contract's bounded steps. */
  private static final int YEAR_STEP = 3;

  private static final int LONG_STEP = 7;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final PublicationStore store;
  private final PollQueryService queries;
  private final PublicSite site;

  public SiteBootstrap(PublicationStore store, PollQueryService queries, PublicSite site) {
    this.store = store;
    this.queries = queries;
    this.site = site;
  }

  /**
   * A page with no publication behind it. Before the first success there are no numbers, and the
   * page says that rather than showing zeros.
   */
  public ObjectNode unavailable(SiteRoutes.Route route, Instant lastSourceCheck) {
    final ObjectNode page = shell(route);
    if (lastSourceCheck == null) {
      page.putNull("lastSourceCheck");
    } else {
      page.put("lastSourceCheck", lastSourceCheck.toString());
    }
    return page;
  }

  /**
   * The filter state one polls request declares: what it asked for, which page of it, and the
   * parameters that were rejected. A rejected parameter is carried rather than thrown, so the page
   * can say which filter it ignored instead of answering a reader's link with an error.
   */
  public record PollRequest(PollQuery.Filters filters, int page, List<ApiErrors.Invalid> invalid) {
    public PollRequest {
      invalid = List.copyOf(invalid);
    }

    public static PollRequest unfiltered() {
      return new PollRequest(PollQuery.Filters.none(), 1, List.of());
    }
  }

  /**
   * One page, resolved against one publication: the shell, that publication's identity, and the
   * documents its family reads. Everything the page shows comes from this one object.
   */
  public ObjectNode page(
      SiteRoutes.Route route, PublicationStore.Header header, boolean permanent) {
    return page(route, header, permanent, PollRequest.unfiltered());
  }

  /** The same page, with the filter state a polls request declared. Other families ignore it. */
  public ObjectNode page(
      SiteRoutes.Route route,
      PublicationStore.Header header,
      boolean permanent,
      PollRequest polls) {
    final ObjectNode page = shell(route);
    publication(page, header, permanent);
    // Exhaustive on purpose: a new family has to say which documents it reads, rather than
    // inheriting an empty page from a default arm and rendering a heading with no numbers.
    switch (route.family()) {
      case OVERVIEW, PARTY -> overview(page, header);
      case SEATS, COALITIONS -> chamber(page, header);
      case POLLS -> pollTable(page, header, polls);
      case POLLSTERS, METHOD -> {}
    }
    if (route.family() == SiteRoutes.Family.PARTY) {
      party(page, header, route.parameter());
    }
    return page;
  }

  /**
   * What the seats and coalitions pages read. Both are two views of one set of joint draws: the
   * allocation and the memberships summarized from it, so both pages carry both documents and the
   * seat totals on them cannot disagree.
   */
  private void chamber(ObjectNode page, PublicationStore.Header header) {
    final String language = page.get("language").asString();
    final ObjectNode data = page.putObject("data");
    data.set(
        "latest",
        document(header, PublicationDocuments.latestSurface(header.headlinePeriod()), language));
    data.set(
        "seats",
        document(
            header, PublicationDocuments.seatsSurface(header.approximatedElection()), language));
    data.set(
        "coalitions",
        document(
            header,
            PublicationDocuments.coalitionsSurface(header.approximatedElection()),
            language));
    page.put("headlineDate", data.get("latest").get("lastFieldworkDate").asString());
  }

  /**
   * The browsable poll table: one filtered page of the pinned snapshot, the filter state that
   * produced it, and the download of exactly the same rows.
   *
   * <p>This is source data, not model output. The archived share is written through unrounded and a
   * share the source never reported stays absent, so the table says what the institute published
   * rather than what the estimator made of it. Where a reported party is outside the modeled roster
   * of the row's coverage period, the row names it: the number is a real observation, and it is not
   * one the published estimate contains.
   */
  private void pollTable(ObjectNode page, PublicationStore.Header header, PollRequest request) {
    final String language = page.get("language").asString();
    final Translations labels = Translations.of(language);
    final List<Roster.CoveragePeriod> periods = queries.periods();
    final PollQuery.Filters filters = request.filters();
    final PollQuery.Result result =
        queries.query(header.snapshotId(), filters, request.page(), PollQuery.DEFAULT_PAGE_SIZE);

    final int pages = pages(result);
    // A page number past the end lands on the last page rather than on an empty table: the caption
    // then says which page is showing, and "nothing matches" keeps meaning the filter matched
    // nothing.
    final int number = Math.min(result.page1(), pages);
    final List<PollQuery.Row> rows = slice(result, number);

    final ObjectNode table = page.putObject("pollTable");
    table.put("total", result.total());
    table.put("page", number);
    table.put("pageSize", result.pageSize());
    table.put("pages", pages);
    table.put("csv", PollFilters.csvLink(header.publicationId(), filters));
    final ArrayNode columns = table.putArray("columns");
    filters.selectedComponents().forEach(columns::add);
    declared(table.putObject("filters"), filters);
    final ArrayNode invalid = table.putArray("invalid");
    for (final ApiErrors.Invalid rejected : request.invalid()) {
      final ObjectNode entry = invalid.addObject();
      entry.put("name", rejected.name());
      entry.put("reason", rejected.reason());
    }
    options(table.putObject("options"), header, periods, language);
    final ObjectNode names = table.putObject("labels");
    for (final String component : PollQuery.COMPONENTS) {
      names.put(component, labels.component(component));
    }
    final ArrayNode published = table.putArray("polls");
    for (final PollQuery.Row row : rows) {
      published.add(pollRow(row, periods));
    }
  }

  /** One page of the matching rows, counted from the page the request settled on. */
  private static List<PollQuery.Row> slice(PollQuery.Result result, int page) {
    final int from = Math.min((page - 1) * result.pageSize(), result.total());
    return result.matching().subList(from, Math.min(from + result.pageSize(), result.total()));
  }

  /** How many pages the filtered result has. An empty result is still one page, not none. */
  private static int pages(PollQuery.Result result) {
    return Math.max(1, (result.total() + result.pageSize() - 1) / result.pageSize());
  }

  /** What the request asked for, echoed back so the controls and the download cannot disagree. */
  private static void declared(ObjectNode node, PollQuery.Filters filters) {
    put(node, "from", filters.from());
    put(node, "to", filters.to());
    final ArrayNode institutes = node.putArray("institute");
    filters.institutes().forEach(institutes::add);
    final ArrayNode parties = node.putArray("party");
    filters.parties().forEach(parties::add);
    if (filters.coveragePeriod() == null) {
      node.putNull("coveragePeriod");
    } else {
      node.put("coveragePeriod", filters.coveragePeriod());
    }
    node.put("includeExcluded", filters.includeExcluded());
  }

  /** What the controls may offer, taken from the pinned publication rather than from live data. */
  private void options(
      ObjectNode node,
      PublicationStore.Header header,
      List<Roster.CoveragePeriod> periods,
      String language) {
    final ArrayNode institutes = node.putArray("institutes");
    for (final JsonNode institute :
        document(header, PublicationDocuments.INSTITUTES_SURFACE, language).get("institutes")) {
      institutes.add(institute.get("institute").asString());
    }
    final ArrayNode coveragePeriods = node.putArray("coveragePeriods");
    for (final Roster.CoveragePeriod period : periods) {
      final ObjectNode entry = coveragePeriods.addObject();
      entry.put("id", period.id());
      put(entry, "from", period.effectiveFrom());
      put(entry, "to", period.effectiveTo());
      entry.put("supportValidated", period.supportValidated());
      entry.put("individualFi", period.individualFi());
      // The roster travels with the period so the mounted page can mark an observation outside it
      // without a second request: the frozen poll response carries no such field.
      final ArrayNode roster = entry.putArray("roster");
      period.roster().forEach(roster::add);
    }
    final ArrayNode parties = node.putArray("parties");
    PollQuery.COMPONENTS.forEach(parties::add);
  }

  /** One archived poll, at the precision the snapshot holds it. */
  private static ObjectNode pollRow(PollQuery.Row row, List<Roster.CoveragePeriod> periods) {
    final ObjectNode node = JSON.createObjectNode();
    node.put("pollId", row.pollId());
    node.put("institute", row.poll().institute());
    node.put("company", row.poll().company());
    node.put("methodEra", row.poll().methodEra());
    node.put("surveyType", row.poll().surveyType());
    put(node, "publicationDate", row.poll().publicationDate());
    put(node, "collectionFrom", row.poll().collectionFrom());
    put(node, "collectionTo", row.poll().collectionTo());
    node.put("approximatePeriod", row.approximatePeriod());
    if (row.poll().sampleSize() == null) {
      node.putNull("sampleSize");
    } else {
      node.put("sampleSize", row.poll().sampleSize());
    }
    node.put("denominatorNote", row.poll().denominatorNote());
    node.put("coveragePeriod", row.coveragePeriod());
    final ObjectNode shares = node.putObject("shares");
    final ArrayNode unmodelled = JSON.createArrayNode();
    for (final String component : PollQuery.COMPONENTS) {
      final BigDecimal share = row.poll().shares().get(component);
      if (share == null) {
        shares.putNull(component);
        continue;
      }
      shares.put(component, share);
      if (!modeled(periods, row.coveragePeriod(), component)) {
        unmodelled.add(component);
      }
    }
    if (row.poll().remainder() == null) {
      node.putNull("other");
    } else {
      node.put("other", row.poll().remainder());
    }
    if (row.uncertain() == null) {
      node.putNull("uncertain");
    } else {
      node.put("uncertain", row.uncertain());
    }
    node.put("eligible", row.poll().eligible());
    final ArrayNode reasons = node.putArray("exclusionReasons");
    row.poll().exclusionReasons().forEach(reasons::add);
    node.set("unmodelled", unmodelled);
    return node;
  }

  /** The shell every page carries: language, translated routes, wording and site identity. */
  private ObjectNode shell(SiteRoutes.Route route) {
    final String language = route.language();
    final SiteText text = SiteText.of(language);
    final Translations labels = Translations.of(language);
    final ObjectNode node = JSON.createObjectNode();
    node.put("language", language);
    node.put("locale", SiteFormat.locale(language));

    final ObjectNode current = node.putObject("route");
    current.put("family", route.family().name());
    current.put("path", SiteRoutes.path(route.family(), language, route.parameter()));
    if (route.parameter() == null) {
      current.putNull("parameter");
    } else {
      current.put("parameter", route.parameter());
    }

    final ObjectNode alternates = node.putObject("alternates");
    for (final String code : Translations.LANGUAGES) {
      alternates.put(code, SiteRoutes.translated(route, code));
    }

    final ArrayNode navigation = node.putArray("navigation");
    for (final SiteRoutes.Family family : SiteRoutes.NAVIGATION) {
      final ObjectNode entry = navigation.addObject();
      entry.put("family", family.name());
      entry.put("label", text.navigation(family));
      entry.put("path", SiteRoutes.path(family, language, null));
      entry.put("current", family == route.family());
    }

    final ObjectNode identity = node.putObject("site");
    identity.put("name", site.name(language));
    identity.put("origin", site.origin());

    final ObjectNode wording = node.putObject("text");
    for (final Map.Entry<String, String> entry : text.all().entrySet()) {
      wording.put(entry.getKey(), entry.getValue());
    }
    final ObjectNode components = node.putObject("labels");
    for (final String component : LABELLED) {
      components.put(component, labels.component(component));
    }
    for (final Coalitions.Preset preset : Coalitions.PRESETS) {
      components.put("coalition." + preset.id(), labels.coalition(preset.id()));
    }
    final ObjectNode partyPaths = node.putObject("partyPaths");
    for (final String component : SiteRoutes.PARTIES) {
      partyPaths.put(component, SiteRoutes.path(SiteRoutes.Family.PARTY, language, component));
    }
    return node;
  }

  /** The publication identity every page carries, whichever family it belongs to. */
  private void publication(ObjectNode page, PublicationStore.Header header, boolean permanent) {
    final String language = page.get("language").asString();
    page.set("publication", PublicationMetadata.of(store, header, Translations.of(language)));
    page.put("permanent", permanent);
    page.put("headlineDate", header.lastFieldworkDate().toString());
    final ObjectNode api = page.putObject("api");
    api.put("base", "/api/v1");
    api.put("publication", header.publicationId());
    api.put("language", language);
  }

  /** The documents the overview reads, every one of them from the already pinned publication. */
  private void overview(ObjectNode page, PublicationStore.Header header) {
    final String language = page.get("language").asString();
    final Translations labels = Translations.of(language);

    final ObjectNode data = page.putObject("data");
    data.set(
        "latest",
        document(header, PublicationDocuments.latestSurface(header.headlinePeriod()), language));
    data.set(
        "seats",
        document(
            header, PublicationDocuments.seatsSurface(header.approximatedElection()), language));
    data.set(
        "coalitions",
        document(
            header,
            PublicationDocuments.coalitionsSurface(header.approximatedElection()),
            language));
    data.set(
        "elections",
        document(header, PublicationDocuments.electionsSurface(header.headlinePeriod()), language));

    // The headline is dated at the estimate's own last day, not at the snapshot's last fieldwork
    // date: the two differ whenever the newest poll's midpoint falls before its final day, and the
    // page must not claim an estimate for a day the model did not estimate.
    page.put("headlineDate", data.get("latest").get("lastFieldworkDate").asString());

    final ObjectNode history = document(header, PublicationDocuments.HISTORY_SURFACE, language);
    final List<LocalDate> dates = historyDates(history);
    final ArrayNode ranges = page.putArray("ranges");
    final LocalDate last = header.lastFieldworkDate();
    final LocalDate first = dates.isEmpty() ? last : dates.getFirst();
    final LocalDate election = lastElectionOnOrBefore(data.get("elections"), last);
    range(ranges, "oneYear", max(first, last.minusYears(1)), last, YEAR_STEP, null);
    if (election != null) {
      range(ranges, "sinceElection", max(first, election), last, LONG_STEP, election.getYear());
    }
    range(ranges, "fourYears", max(first, last.minusYears(4)), last, LONG_STEP, null);
    range(ranges, "all", first, last, LONG_STEP, null);
    final String selected = election == null ? "fourYears" : "sinceElection";
    page.put("defaultRange", selected);
    data.set("history", sampled(history, ranges, selected));

    data.set("polls", polls(header, labels));
  }

  /** One party page, cut from the same documents and snapshot as the overview. */
  private void party(ObjectNode page, PublicationStore.Header header, String component) {
    final ObjectNode data = (ObjectNode) page.get("data");
    final ObjectNode party = data.putObject("party");
    party.put("component", component);

    final JsonNode current = component(data.get("latest").get("components"), component);
    if (current == null) {
      final ObjectNode unavailable = party.putObject("estimate");
      unavailable.putNull("mean");
      unavailable.putNull("lower");
      unavailable.putNull("upper");
      party.put("historicalOnly", true);
    } else {
      party.set("estimate", current.deepCopy());
      party.put("historicalOnly", false);
    }

    final JsonNode seats = component(data.get("seats").get("parties"), component);
    if (seats == null) {
      party.putNull("thresholdProbability");
      party.putNull("pointSeats");
    } else {
      party.set("thresholdProbability", seats.get("thresholdProbability").deepCopy());
      party.set("pointSeats", seats.get("pointSeats").deepCopy());
    }

    final ArrayNode observations = observations(header, component);
    party.set("observations", observations);
    if (current == null) {
      final LocalDate lastObservation = lastObservation(observations);
      if (lastObservation != null) {
        page.put("headlineDate", lastObservation.toString());
      }
    }
    party.set("houseEffects", houseEffects(header, page.get("language").asString(), component));
  }

  private static JsonNode component(JsonNode values, String component) {
    for (final JsonNode value : values) {
      if (component.equals(value.get("component").asString())) {
        return value;
      }
    }
    return null;
  }

  /** Every dated source observation of the party, including rows outside modeled support. */
  private ArrayNode observations(PublicationStore.Header header, String component) {
    final List<Roster.CoveragePeriod> periods = queries.periods();
    final PollQuery.Filters filters =
        new PollQuery.Filters(null, null, List.of(), List.of(component), null, true);
    final PollQuery.Result result =
        queries.query(header.snapshotId(), filters, 1, Integer.MAX_VALUE);
    final ArrayNode observations = JSON.createArrayNode();
    for (final PollQuery.Row row : result.matching()) {
      final BigDecimal share = row.poll().shares().get(component);
      if (share == null) {
        continue;
      }
      final ObjectNode observation = observations.addObject();
      observation.put("pollId", row.pollId());
      observation.put("institute", row.poll().institute());
      put(observation, "collectionFrom", row.poll().collectionFrom());
      put(observation, "collectionTo", row.poll().collectionTo());
      observation.put("approximatePeriod", row.approximatePeriod());
      if (row.poll().sampleSize() == null) {
        observation.putNull("sampleSize");
      } else {
        observation.put("sampleSize", row.poll().sampleSize());
      }
      observation.put("share", share);
      if (row.coveragePeriod() == null) {
        observation.putNull("coveragePeriod");
      } else {
        observation.put("coveragePeriod", row.coveragePeriod());
      }
      observation.put("eligible", row.poll().eligible());
      observation.put(
          "modeled", row.poll().eligible() && modeled(periods, row.coveragePeriod(), component));
      final ArrayNode reasons = observation.putArray("exclusionReasons");
      row.poll().exclusionReasons().forEach(reasons::add);
    }
    return observations;
  }

  private static boolean modeled(
      List<Roster.CoveragePeriod> periods, String periodId, String component) {
    return periods.stream()
        .filter(period -> period.id().equals(periodId))
        .anyMatch(period -> period.roster().contains(component));
  }

  private static LocalDate lastObservation(ArrayNode observations) {
    LocalDate last = null;
    for (final JsonNode observation : observations) {
      final JsonNode to = observation.get("collectionTo");
      final JsonNode from = observation.get("collectionFrom");
      final JsonNode date = to != null && !to.isNull() ? to : from;
      if (date == null || date.isNull()) {
        continue;
      }
      final LocalDate value = LocalDate.parse(date.asString());
      if (last == null || value.isAfter(last)) {
        last = value;
      }
    }
    return last;
  }

  /** The selected party's effects in the latest published election cycle. */
  private ObjectNode houseEffects(
      PublicationStore.Header header, String language, String component) {
    final ObjectNode institutes =
        document(header, PublicationDocuments.INSTITUTES_SURFACE, language);
    final ObjectNode result = JSON.createObjectNode();
    result.set("reference", institutes.get("reference").deepCopy());
    final JsonNode cycles = institutes.get("electionCycles");
    final String selected = cycles.isEmpty() ? null : cycles.get(cycles.size() - 1).asString();
    if (selected == null) {
      result.putNull("electionCycle");
    } else {
      result.put("electionCycle", selected);
    }
    final ArrayNode effects = result.putArray("effects");
    for (final JsonNode institute : institutes.get("institutes")) {
      for (final JsonNode effect : institute.get("houseEffects")) {
        if (component.equals(effect.get("component").asString())
            && (selected == null || selected.equals(effect.get("electionCycle").asString()))) {
          final ObjectNode value = effects.addObject();
          value.put("institute", institute.get("institute").asString());
          value.set("mean", effect.get("mean").deepCopy());
          value.set("lower", effect.get("lower").deepCopy());
          value.set("upper", effect.get("upper").deepCopy());
          value.set("shrunk", effect.get("shrunk").deepCopy());
        }
      }
    }
    return result;
  }

  private ObjectNode sampled(ObjectNode history, ArrayNode ranges, String selected) {
    for (final JsonNode range : ranges) {
      if (selected.equals(range.get("id").asString())) {
        return EstimateQuery.sample(
            history,
            new EstimateQuery.Range(
                LocalDate.parse(range.get("from").asString()),
                LocalDate.parse(range.get("to").asString()),
                range.get("step").asInt(),
                null));
      }
    }
    throw new IllegalStateException("No range named " + selected);
  }

  private static void range(
      ArrayNode ranges, String id, LocalDate from, LocalDate to, int step, Integer year) {
    final ObjectNode node = ranges.addObject();
    node.put("id", id);
    node.put("from", from.toString());
    node.put("to", to.toString());
    node.put("step", step);
    if (year == null) {
      node.putNull("year");
    } else {
      node.put("year", year.intValue());
    }
  }

  /** The most recently published polls of the pinned snapshot, in the poll table's own shape. */
  private ObjectNode polls(PublicationStore.Header header, Translations labels) {
    final PollQuery.Filters filters = PollQuery.Filters.none();
    final PollQuery.Result result = queries.query(header.snapshotId(), filters, 1, LATEST_POLLS);
    final ObjectNode node = JSON.createObjectNode();
    node.put("total", result.total());
    final ArrayNode rows = node.putArray("polls");
    for (final PollQuery.Row row : result.page()) {
      final ObjectNode poll = rows.addObject();
      poll.put("pollId", row.pollId());
      poll.put("institute", row.poll().institute());
      poll.put("methodEra", row.poll().methodEra());
      put(poll, "collectionFrom", row.poll().collectionFrom());
      put(poll, "collectionTo", row.poll().collectionTo());
      poll.put("approximatePeriod", row.approximatePeriod());
      if (row.poll().sampleSize() == null) {
        poll.putNull("sampleSize");
      } else {
        poll.put("sampleSize", row.poll().sampleSize());
      }
      poll.put("coveragePeriod", row.coveragePeriod());
      final ObjectNode shares = poll.putObject("shares");
      for (final String component : PollQuery.COMPONENTS) {
        if (row.poll().shares().get(component) == null) {
          shares.putNull(component);
        } else {
          shares.put(component, row.poll().shares().get(component));
        }
      }
      if (row.poll().remainder() == null) {
        poll.putNull("other");
      } else {
        poll.put("other", row.poll().remainder());
      }
    }
    final ObjectNode names = node.putObject("labels");
    for (final String component : PollQuery.COMPONENTS) {
      names.put(component, labels.component(component));
    }
    return node;
  }

  private static void put(ObjectNode node, String field, LocalDate value) {
    if (value == null) {
      node.putNull(field);
    } else {
      node.put(field, value.toString());
    }
  }

  private ObjectNode document(PublicationStore.Header header, String surface, String language) {
    final Optional<String> body = store.document(header.publicationId(), surface, language);
    if (body.isEmpty()) {
      throw new IllegalStateException(
          header.publicationId() + " has no " + surface + " in " + language);
    }
    return (ObjectNode) JSON.readTree(body.get());
  }

  private static List<LocalDate> historyDates(ObjectNode history) {
    final List<LocalDate> dates = new ArrayList<>();
    for (final JsonNode date : history.get("dates")) {
      dates.add(LocalDate.parse(date.asString()));
    }
    return dates;
  }

  /** The most recent actual election the pinned publication references, or null before any. */
  private static LocalDate lastElectionOnOrBefore(JsonNode elections, LocalDate last) {
    LocalDate latest = null;
    for (final JsonNode election : elections.get("elections")) {
      final LocalDate date = LocalDate.parse(election.get("electionDate").asString());
      if (!date.isAfter(last) && (latest == null || date.isAfter(latest))) {
        latest = date;
      }
    }
    return latest;
  }

  private static LocalDate max(LocalDate left, LocalDate right) {
    return left.isAfter(right) ? left : right;
  }
}
