package se.swedishpolls.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import se.swedishpolls.publication.ModelFreeze;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.publication.service.Publications;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.Snapshot;
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

  private final Publications publications;
  private final PollQueryService queries;
  private final PublicSite site;

  public SiteBootstrap(Publications publications, PollQueryService queries, PublicSite site) {
    this.publications = publications;
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

  /** An empty source archive. Retrying this page only reads retained data again. */
  public ObjectNode noSource(SiteRoutes.Route route) {
    final ObjectNode page = shell(route, SiteRoutes.SOURCE_NAVIGATION);
    page.put("noSource", true);
    return page;
  }

  /** A source-only overview or poll table pinned to one retained snapshot. */
  public ObjectNode sourcePage(SiteRoutes.Route route, Snapshot snapshot) {
    return sourcePage(route, snapshot, PollRequest.unfiltered());
  }

  public ObjectNode sourcePage(SiteRoutes.Route route, Snapshot snapshot, PollRequest pollRequest) {
    final ObjectNode page = shell(route, SiteRoutes.SOURCE_NAVIGATION);
    source(page, snapshot);
    final List<Roster.CoveragePeriod> periods = queries.periods();
    switch (route.family()) {
      case OVERVIEW ->
          page.set(
              "sourcePolls",
              polls(snapshot.id(), periods, Translations.of(route.language()), LATEST_POLLS));
      case POLLS -> pollTable(page, snapshot, periods, pollRequest);
      default -> throw new IllegalArgumentException("Source data cannot render " + route.family());
    }
    return page;
  }

  /**
   * The filter state one polls request declares: what it asked for, which page of it, and the
   * parameters that were rejected. A rejected parameter is carried rather than thrown, so the page
   * can say which filter it ignored instead of answering a reader's link with an error.
   */
  public record PollRequest(
      PollQuery.Filters filters, int page, List<PollFilters.Invalid> invalid) {
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
  public ObjectNode page(SiteRoutes.Route route, Publications.Resolved resolved) {
    return page(
        route,
        resolved,
        PollRequest.unfiltered(),
        new CoalitionHistoryQuery.Request(CoalitionSelection.preset(), null, null, LONG_STEP));
  }

  /**
   * A current estimate page whose poll rows come from the source snapshot selected for the visit.
   */
  public ObjectNode page(
      SiteRoutes.Route route, Publications.Resolved resolved, Snapshot sourceSnapshot) {
    final ObjectNode page = page(route, resolved);
    source(page, sourceSnapshot);
    if (route.family() == SiteRoutes.Family.OVERVIEW) {
      final ObjectNode sourcePolls =
          polls(
              sourceSnapshot.id(),
              queries.periods(),
              Translations.of(route.language()),
              LATEST_POLLS);
      page.set("sourcePolls", sourcePolls.deepCopy());
      ((ObjectNode) page.get("data")).set("polls", sourcePolls);
    }
    return page;
  }

  public ObjectNode coalitionPage(
      SiteRoutes.Route route,
      Publications.Resolved resolved,
      CoalitionHistoryQuery.Request request) {
    return page(route, resolved, PollRequest.unfiltered(), request);
  }

  public ObjectNode invalidCoalitionPage(
      SiteRoutes.Route route,
      Publications.Resolved resolved,
      Map<String, List<String>> parameters,
      CoalitionSelection.Invalid error) {
    final ObjectNode page = shell(route);
    publication(page, resolved.header(), resolved.permanent());
    chamber(page, resolved.header());
    coalitionLinkError(page, route, parameters, error);
    return page;
  }

  /** The same page, with the filter state a polls request declared. Other families ignore it. */
  public ObjectNode page(
      SiteRoutes.Route route, Publications.Resolved resolved, PollRequest polls) {
    return page(
        route,
        resolved,
        polls,
        new CoalitionHistoryQuery.Request(CoalitionSelection.preset(), null, null, LONG_STEP));
  }

  private ObjectNode page(
      SiteRoutes.Route route,
      Publications.Resolved resolved,
      PollRequest polls,
      CoalitionHistoryQuery.Request coalitionRequest) {
    final PublicationHeader header = resolved.header();
    final ObjectNode page = shell(route);
    publication(page, header, resolved.permanent());
    // Exhaustive on purpose: a new family has to say which documents it reads, rather than
    // inheriting an empty page from a default arm and rendering a heading with no numbers.
    switch (route.family()) {
      case OVERVIEW, PARTY -> overview(page, header);
      case SEATS -> chamber(page, header);
      case COALITIONS -> {
        chamber(page, header);
        publications
            .coalitionHistory(header)
            .ifPresent(
                body ->
                    coalitionHistory(
                        page, route, (ObjectNode) JSON.readTree(body), coalitionRequest));
      }
      case POLLS -> pollTable(page, header, polls);
      case POLLSTERS -> pollsters(page, header);
      case METHOD -> method(page, header);
    }
    if (route.family() == SiteRoutes.Family.PARTY) {
      party(page, header, route.parameter());
    }
    return page;
  }

  private static void coalitionHistory(
      ObjectNode page,
      SiteRoutes.Route route,
      ObjectNode stored,
      CoalitionHistoryQuery.Request request) {
    final ObjectNode history = CoalitionHistoryQuery.sample(stored, request);
    ((ObjectNode) page.get("data")).set("coalitionHistory", history);
    final String query =
        "?a="
            + joined(history, "a")
            + "&b="
            + joined(history, "b")
            + "&from="
            + history.get("requestedRange").get("from").asString()
            + "&to="
            + history.get("requestedRange").get("to").asString();
    final String path =
        SiteRoutes.path(route.family(), route.language(), route.parameter()) + query;
    ((ObjectNode) page.get("route")).put("path", path);
    final ObjectNode alternates = (ObjectNode) page.get("alternates");
    for (final String language : Translations.LANGUAGES) {
      alternates.put(
          language, SiteRoutes.path(route.family(), language, route.parameter()) + query);
    }
    page.put("coalitionShare", path);
    page.put("customCoalitionSelection", !request.selection().equals(CoalitionSelection.preset()));
  }

  private static void coalitionLinkError(
      ObjectNode page,
      SiteRoutes.Route route,
      Map<String, List<String>> parameters,
      CoalitionSelection.Invalid error) {
    final ObjectNode invalid = page.putObject("coalitionLinkError");
    invalid.put("field", error.field());
    invalid.put(
        "reason", SiteText.of(route.language()).text("coalitionHistory.invalid." + error.reason()));
    invalid.put("reset", SiteRoutes.path(route.family(), route.language(), route.parameter()));
    final ObjectNode requested = invalid.putObject("requested");
    for (final String name : List.of("a", "b", "parties", "from", "to")) {
      if (parameters.containsKey(name)) {
        final ArrayNode values = requested.putArray(name);
        parameters.get(name).forEach(values::add);
      }
    }
    page.put("customCoalitionSelection", true);
  }

  private static String joined(ObjectNode history, String block) {
    final List<String> parties = new ArrayList<>();
    history.get("selection").get(block).forEach(party -> parties.add(party.asString()));
    return String.join(",", parties);
  }

  /**
   * What the seats and coalitions pages read. Both are two views of one set of joint draws: the
   * allocation and the memberships summarized from it, so both pages carry both documents and the
   * seat totals on them cannot disagree.
   */
  private void chamber(ObjectNode page, PublicationHeader header) {
    final String language = page.get("language").asString();
    final ObjectNode data = page.putObject("data");
    data.set("latest", document(publications.latest(header, header.headlinePeriod(), language)));
    data.set(
        "seats", document(publications.seats(header, header.approximatedElection(), language)));
    data.set(
        "coalitions",
        document(publications.coalitions(header, header.approximatedElection(), language)));
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
  private void pollTable(ObjectNode page, PublicationHeader header, PollRequest request) {
    final String language = page.get("language").asString();
    final List<Roster.CoveragePeriod> periods = periods(header, language);
    pollTable(
        page,
        header.snapshotId(),
        periods,
        request,
        PollFilters.csvLink(header.publicationId(), request.filters()),
        publicationInstitutes(header, language),
        false);
  }

  private void pollTable(
      ObjectNode page,
      Snapshot snapshot,
      List<Roster.CoveragePeriod> periods,
      PollRequest request) {
    pollTable(
        page,
        snapshot.id(),
        periods,
        request,
        PollFilters.sourceCsvLink(snapshot.id(), request.filters()),
        queries.institutes(snapshot.id()),
        true);
  }

  private void pollTable(
      ObjectNode page,
      long snapshotId,
      List<Roster.CoveragePeriod> periods,
      PollRequest request,
      String csv,
      List<String> institutes,
      boolean source) {
    final String language = page.get("language").asString();
    final Translations labels = Translations.of(language);
    final PollQuery.Filters filters = request.filters();
    final PollQuery.Result result =
        queries.query(snapshotId, periods, filters, request.page(), PollQuery.DEFAULT_PAGE_SIZE);

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
    table.put("csv", csv);
    table.put("source", source);
    // The one query string every link on this page appends to. Building a second one is how a
    // next-page link starts selecting rows the download beside it does not.
    table.put("query", String.join("&", PollFilters.query(filters)));
    final List<String> components = filters.selectedComponents();
    final ArrayNode columns = table.putArray("columns");
    components.forEach(columns::add);
    declared(table.putObject("filters"), filters);
    final ArrayNode invalid = table.putArray("invalid");
    for (final PollFilters.Invalid rejected : request.invalid()) {
      final ObjectNode entry = invalid.addObject();
      entry.put("name", rejected.name());
      entry.put("reason", rejected.reason());
    }
    options(
        table.putObject("options"),
        institutes,
        source ? List.of() : periods,
        source ? List.of() : PollQuery.COMPONENTS);
    final ObjectNode names = table.putObject("labels");
    for (final String component : PollQuery.COMPONENTS) {
      names.put(component, labels.component(component));
    }
    final ArrayNode published = table.putArray("polls");
    for (final PollQuery.Row row : rows) {
      published.add(pollRow(row, periods, components, source));
    }
  }

  /** One page of the matching rows, counted from the page the request settled on. */
  private static List<PollQuery.Row> slice(PollQuery.Result result, int page) {
    final int from = (int) Math.min((long) (page - 1) * result.pageSize(), result.total());
    final int to = (int) Math.min((long) from + result.pageSize(), result.total());
    return result.matching().subList(from, to);
  }

  /** How many pages the filtered result has. An empty result is still one page, not none. */
  private static int pages(PollQuery.Result result) {
    return Math.max(1, (int) ((result.total() + (long) result.pageSize() - 1) / result.pageSize()));
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
  private static void options(
      ObjectNode node,
      List<String> instituteNames,
      List<Roster.CoveragePeriod> periods,
      List<String> parties) {
    final ArrayNode institutes = node.putArray("institutes");
    instituteNames.forEach(institutes::add);
    final ArrayNode coveragePeriods = node.putArray("coveragePeriods");
    for (final Roster.CoveragePeriod period : periods) {
      coveragePeriods.addObject().put("id", period.id());
    }
    final ArrayNode partyOptions = node.putArray("parties");
    parties.forEach(partyOptions::add);
  }

  private List<String> publicationInstitutes(PublicationHeader header, String language) {
    final List<String> names = new ArrayList<>();
    for (final JsonNode institute :
        document(publications.institutes(header, language)).get("institutes")) {
      names.add(institute.get("institute").asString());
    }
    return List.copyOf(names);
  }

  /**
   * One archived poll, at the precision the snapshot holds it.
   *
   * <p>The row also carries which of the displayed components sit outside the modeled roster of the
   * row's own coverage period. The classification is made once here, beside the rosters, so both
   * renderings read the same answer instead of each re-deriving it.
   */
  private static ObjectNode pollRow(
      PollQuery.Row row,
      List<Roster.CoveragePeriod> periods,
      List<String> components,
      boolean source) {
    final ObjectNode node = JSON.createObjectNode();
    node.put("pollId", row.pollId());
    node.put("institute", row.poll().institute());
    node.put("company", row.poll().company());
    node.put("methodEra", row.poll().methodEra());
    node.put("methodEvidence", row.poll().methodEvidence());
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
    if (source) {
      node.putNull("coveragePeriod");
    } else {
      node.put("coveragePeriod", row.coveragePeriod());
    }
    final ArrayNode unmodeled = node.putArray("unmodeled");
    if (!source) {
      for (final String component : components) {
        if (row.poll().shares().get(component) != null && outsideRoster(periods, row, component)) {
          unmodeled.add(component);
        }
      }
    }
    final ObjectNode shares = node.putObject("shares");
    final ObjectNode displayShares = node.putObject("displayShares");
    for (final String component : PollQuery.COMPONENTS) {
      final BigDecimal share = row.poll().shares().get(component);
      if (share == null) {
        shares.putNull(component);
        displayShares.putNull(component);
      } else {
        shares.put(component, share);
        displayShares.put(component, share.toPlainString());
      }
    }
    if (row.poll().remainder() == null) {
      node.putNull("other");
      node.putNull("displayOther");
    } else {
      node.put("other", row.poll().remainder());
      node.put("displayOther", row.poll().remainder().toPlainString());
    }
    node.put("eligible", row.poll().eligible());
    final ArrayNode reasons = node.putArray("exclusionReasons");
    row.poll().exclusionReasons().forEach(reasons::add);
    return node;
  }

  /**
   * Whether a reported share sits outside the modeled roster of its own coverage period.
   *
   * <p>A row with no coverage period belongs to no modeled roster, so every reported component in
   * it is an observation the published estimate does not contain.
   */
  private static boolean outsideRoster(
      List<Roster.CoveragePeriod> periods, PollQuery.Row row, String component) {
    final String periodId = row.coveragePeriod();
    if (periodId == null) {
      return true;
    }
    return periods.stream()
        .filter(period -> period.id().equals(periodId))
        .noneMatch(period -> period.roster().contains(component));
  }

  /**
   * The pollsters page: every institute's archived footprint, its method eras, and its house
   * effects per election cycle with the uncertainty the fit gives them. All cycles are carried, so
   * a page served before any script runs still shows every cycle the publication fitted, and the
   * heatmap and its table alternative read the same cells rather than deriving them twice.
   */
  private void pollsters(ObjectNode page, PublicationHeader header) {
    final String language = page.get("language").asString();
    final ObjectNode institutes = document(publications.institutes(header, language));
    final ObjectNode node = page.putObject("data").putObject("pollsters");
    node.set("reference", institutes.get("reference").deepCopy());
    node.set("components", effectComponents(institutes));

    final ArrayNode metadata = node.putArray("institutes");
    for (final JsonNode institute : institutes.get("institutes")) {
      final ObjectNode entry = metadata.addObject();
      entry.set("institute", institute.get("institute").deepCopy());
      entry.set("companies", institute.get("companies").deepCopy());
      entry.set("polls", institute.get("polls").deepCopy());
      entry.set("firstCollection", institute.get("firstCollection").deepCopy());
      entry.set("lastCollection", institute.get("lastCollection").deepCopy());
      entry.set("methodEras", institute.get("methodEras").deepCopy());
    }

    final ArrayNode matrices = node.putArray("matrices");
    for (final JsonNode cycle : institutes.get("electionCycles")) {
      final ObjectNode matrix = matrices.addObject();
      matrix.set("cycle", cycle.deepCopy());
      final ArrayNode rows = matrix.putArray("rows");
      for (final JsonNode institute : institutes.get("institutes")) {
        final ObjectNode row = rows.addObject();
        row.set("institute", institute.get("institute").deepCopy());
        final ArrayNode cells = row.putArray("cells");
        for (final JsonNode effect : institute.get("houseEffects")) {
          if (!cycle.asString().equals(effect.get("electionCycle").asString())) {
            continue;
          }
          final ObjectNode cell = cells.addObject();
          cell.set("component", effect.get("component").deepCopy());
          cell.set("mean", effect.get("mean").deepCopy());
          cell.set("lower", effect.get("lower").deepCopy());
          cell.set("upper", effect.get("upper").deepCopy());
          cell.set("shrunk", effect.get("shrunk").deepCopy());
          cell.put("heat", heat(effect.get("mean").asDouble()));
        }
      }
    }
  }

  /** The components house effects exist for, in the poll columns' order, extras after them. */
  private static ArrayNode effectComponents(ObjectNode institutes) {
    final Set<String> seen = new LinkedHashSet<>();
    for (final JsonNode institute : institutes.get("institutes")) {
      for (final JsonNode effect : institute.get("houseEffects")) {
        seen.add(effect.get("component").asString());
      }
    }
    final List<String> ordered = new ArrayList<>();
    for (final String component : PollQuery.COMPONENTS) {
      if (seen.contains(component)) {
        ordered.add(component);
      }
    }
    for (final String component : seen) {
      if (!ordered.contains(component)) {
        ordered.add(component);
      }
    }
    final ArrayNode components = JSON.createArrayNode();
    ordered.forEach(components::add);
    return components;
  }

  /**
   * The colour step of one heatmap cell, from the effect's size. Half-point steps keep a cell
   * readable at both ends of the scale; the number stays in the cell, so colour is never the only
   * cue.
   */
  static String heat(double mean) {
    if (Math.abs(mean) < 0.25) {
      return "z";
    }
    final int steps = Math.min(3, (int) Math.floor((Math.abs(mean) + 0.25) / 0.5));
    return (mean > 0 ? "p" : "n") + steps;
  }

  /**
   * The method page: the explanations are the page's own wording, and the numbers beside them come
   * from the shipped estimator contract and the pinned publication's coverage classification. The
   * latest document is carried whole, so the footer's interval line and the coverage table read the
   * publication every other page reads.
   */
  private void method(ObjectNode page, PublicationHeader header) {
    final String language = page.get("language").asString();
    final ObjectNode data = page.putObject("data");
    data.set("latest", document(publications.latest(header, header.headlinePeriod(), language)));
    page.put("approximatedElection", header.approximatedElection());

    final ModelFreeze freeze = publications.freeze();
    final ObjectNode node = data.putObject("method");

    final ObjectNode verdict = node.putObject("verdict");
    verdict.put("status", freeze.releaseStatus());
    verdict.put("released", freeze.released());
    final ArrayNode failed = verdict.putArray("failedGates");
    freeze.failedBlockingGates().forEach(failed::add);

    final ObjectNode estimator = node.putObject("estimator");
    estimator.put("version", freeze.estimatorVersion());
    estimator.put("numericalLibrary", freeze.numericalLibrary());
    estimator.put("developmentProtocol", freeze.developmentProtocolVersion());
    estimator.put("releaseProtocol", freeze.releaseProtocolVersion());

    final ObjectNode draws = node.putObject("draws");
    draws.put("seed", freeze.uncertainty().seed());
    draws.put("count", freeze.uncertainty().draws());
    draws.put("decimals", freeze.resolution().decimals());
    final ArrayNode levels = draws.putArray("intervalLevels");
    freeze.uncertainty().intervalLevels().forEach(levels::add);

    final ObjectNode coverage = node.putObject("coverage");
    coverage.put(
        "developmentThrough", freeze.developmentCoverage().developmentThrough().toString());
    coverage.put("minObservations", freeze.developmentCoverage().minObservations());
    coverage.put("minInstitutes", freeze.developmentCoverage().minInstitutes());
    coverage.put("maxInternalGapDays", freeze.developmentCoverage().maxInternalGapDays());
    final ArrayNode shifts = coverage.putArray("boundaryShiftDays");
    freeze.developmentCoverage().boundaryShiftDays().forEach(shifts::add);
    coverage.put("stabilityBurnInDays", freeze.developmentCoverage().stabilityBurnInDays());
    coverage.put("maxStabilityShiftPoints", freeze.developmentCoverage().maxStabilityShiftPoints());
  }

  /** The shell every page carries: language, translated routes, wording and site identity. */
  private ObjectNode shell(SiteRoutes.Route route) {
    return shell(route, SiteRoutes.NAVIGATION);
  }

  private ObjectNode shell(SiteRoutes.Route route, List<SiteRoutes.Family> families) {
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
    for (final SiteRoutes.Family family : families) {
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
    for (final String id : Translations.COALITION_IDS) {
      components.put("coalition." + id, labels.coalition(id));
    }
    final ObjectNode partyPaths = node.putObject("partyPaths");
    for (final String component : SiteRoutes.PARTIES) {
      partyPaths.put(component, SiteRoutes.path(SiteRoutes.Family.PARTY, language, component));
    }
    return node;
  }

  private static void source(ObjectNode page, Snapshot snapshot) {
    final ObjectNode source = page.putObject("source");
    source.put("snapshotId", snapshot.id());
    source.put("sha256", snapshot.sha256());
    source.put("capturedAt", snapshot.capturedAt().toString());
  }

  /** The publication identity every page carries, whichever family it belongs to. */
  private void publication(ObjectNode page, PublicationHeader header, boolean permanent) {
    final String language = page.get("language").asString();
    page.set("publication", publications.metadata(header, Translations.of(language)));
    page.put("permanent", permanent);
    page.put("headlineDate", header.lastFieldworkDate().toString());
    final ObjectNode api = page.putObject("api");
    api.put("base", "/api/v1");
    api.put("publication", header.publicationId());
    api.put("language", language);
  }

  /** The documents the overview reads, every one of them from the already pinned publication. */
  private void overview(ObjectNode page, PublicationHeader header) {
    final String language = page.get("language").asString();
    final Translations labels = Translations.of(language);

    final ObjectNode data = page.putObject("data");
    data.set("latest", document(publications.latest(header, header.headlinePeriod(), language)));
    data.set(
        "seats", document(publications.seats(header, header.approximatedElection(), language)));
    data.set(
        "coalitions",
        document(publications.coalitions(header, header.approximatedElection(), language)));
    data.set(
        "elections", document(publications.elections(header, header.headlinePeriod(), language)));

    // The headline is dated at the estimate's own last day, not at the snapshot's last fieldwork
    // date: the two differ whenever the newest poll's midpoint falls before its final day, and the
    // page must not claim an estimate for a day the model did not estimate.
    page.put("headlineDate", data.get("latest").get("lastFieldworkDate").asString());

    final ObjectNode history = document(publications.history(header, language));
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

    data.set(
        "polls",
        polls(header.snapshotId(), periods(header, labels.language()), labels, LATEST_POLLS));
  }

  /** One party page, cut from the same documents and snapshot as the overview. */
  private void party(ObjectNode page, PublicationHeader header, String component) {
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

    final ArrayNode observations = observations(header, page.get("language").asString(), component);
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
  private ArrayNode observations(PublicationHeader header, String language, String component) {
    final List<Roster.CoveragePeriod> periods = periods(header, language);
    final PollQuery.Filters filters =
        new PollQuery.Filters(null, null, List.of(), List.of(component), null, true);
    final PollQuery.Result result =
        queries.query(header.snapshotId(), periods, filters, 1, Integer.MAX_VALUE);
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
  private ObjectNode houseEffects(PublicationHeader header, String language, String component) {
    final ObjectNode institutes = document(publications.institutes(header, language));
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
  private ObjectNode polls(
      long snapshotId, List<Roster.CoveragePeriod> periods, Translations labels, int pageSize) {
    final PollQuery.Filters filters = PollQuery.Filters.none();
    final PollQuery.Result result = queries.query(snapshotId, periods, filters, 1, pageSize);
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

  private static ObjectNode document(Optional<String> body) {
    if (body.isEmpty()) {
      throw new IllegalStateException("Stored publication document is unavailable");
    }
    return (ObjectNode) JSON.readTree(body.get());
  }

  /** Coverage classification stored with this publication, never the repository's current rows. */
  private List<Roster.CoveragePeriod> periods(PublicationHeader header, String language) {
    return PublicationCoverage.from(
        document(publications.latest(header, header.headlinePeriod(), language)));
  }

  public boolean knownPeriod(PublicationHeader header, String language, String period) {
    return periods(header, language).stream().anyMatch(candidate -> candidate.id().equals(period));
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
