package se.swedishpolls;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
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
  private final SnapshotIngest ingest;
  private final Roster roster;
  private final PublicSite site;

  public SiteBootstrap(
      PublicationStore store, SnapshotIngest ingest, Roster roster, PublicSite site) {
    this.store = store;
    this.ingest = ingest;
    this.roster = roster;
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
   * One page, resolved against one publication: the shell, that publication's identity, and the
   * documents its family reads. Everything the page shows comes from this one object.
   */
  public ObjectNode page(
      SiteRoutes.Route route, PublicationStore.Header header, boolean permanent) {
    final ObjectNode page = shell(route);
    publication(page, header, permanent);
    if (route.family() == SiteRoutes.Family.OVERVIEW) {
      overview(page, header);
    }
    return page;
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
    final PollQuery.Result result =
        PollQuery.filter(
            header.snapshotId(),
            ingest.polls(header.snapshotId()),
            roster.periods(),
            filters,
            1,
            LATEST_POLLS);
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
