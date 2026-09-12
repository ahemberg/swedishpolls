package se.swedishpolls;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.service.PollQueryService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The frozen v1 read surface. Every response is served from one publication's stored documents or
 * from its pinned source snapshot, so a request never refits the model and an ingestion correction
 * cannot change a page that already resolved its publication.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final long CURRENT_MAX_AGE_SECONDS = 300;
  private static final long IMMUTABLE_MAX_AGE_SECONDS = 31_536_000;

  private final PublicationStore store;
  private final PollQueryService queries;

  public ApiV1Controller(PublicationStore store, PollQueryService queries) {
    this.store = store;
    this.queries = queries;
  }

  /** A resolved publication: which one, and whether the caller pinned it permanently. */
  private record Resolved(String publicationId, boolean permanent) {}

  @GetMapping("/publication")
  public ResponseEntity<byte[]> publication(
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(null, language);
    return json(publicationBody(resolved, Translations.of(language)), resolved, ifNoneMatch);
  }

  @GetMapping("/publications/{publicationId}")
  public ResponseEntity<byte[]> permanentPublication(
      @PathVariable String publicationId,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publicationId, language);
    return json(publicationBody(resolved, Translations.of(language)), resolved, ifNoneMatch);
  }

  @GetMapping("/estimates/latest")
  public ResponseEntity<byte[]> latest(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final String period =
        coveragePeriod == null ? header(resolved).headlinePeriod() : coveragePeriod;
    return json(
        document(resolved, PublicationDocuments.latestSurface(period), language, "coveragePeriod"),
        resolved,
        ifNoneMatch);
  }

  @GetMapping("/estimates/history")
  public ResponseEntity<byte[]> history(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String step,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final List<ApiErrors.Invalid> invalid = new ArrayList<>();
    final LocalDate fromDate = PollFilters.date(from, "from", invalid);
    final LocalDate toDate = PollFilters.date(to, "to", invalid);
    final int stepDays = step(step, invalid);
    final ObjectNode stored =
        document(resolved, PublicationDocuments.HISTORY_SURFACE, language, "publication");
    if (coveragePeriod != null && !knownPeriod(stored, coveragePeriod)) {
      invalid.add(new ApiErrors.Invalid("coveragePeriod", "unknown_coverage_period"));
    }
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
      invalid.add(new ApiErrors.Invalid("to", "before_from"));
    }
    if (!invalid.isEmpty()) {
      throw ApiErrors.invalidFilter(invalid);
    }
    return json(
        EstimateQuery.sample(
            stored, new EstimateQuery.Range(fromDate, toDate, stepDays, coveragePeriod)),
        resolved,
        ifNoneMatch);
  }

  @GetMapping("/institutes")
  public ResponseEntity<byte[]> institutes(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String electionCycle,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final ObjectNode stored =
        document(resolved, PublicationDocuments.INSTITUTES_SURFACE, language, "publication");
    final List<String> cycles = new ArrayList<>();
    for (final JsonNode cycle : stored.get("electionCycles")) {
      cycles.add(cycle.asString());
    }
    if (electionCycle != null && !cycles.contains(electionCycle)) {
      throw ApiErrors.invalidFilter(
          List.of(new ApiErrors.Invalid("electionCycle", "unknown_election_cycle")));
    }
    final String selected =
        electionCycle == null ? (cycles.isEmpty() ? null : cycles.getLast()) : electionCycle;
    final ObjectNode node = stored.deepCopy();
    if (selected == null) {
      node.putNull("electionCycle");
    } else {
      node.put("electionCycle", selected);
    }
    for (final JsonNode institute : node.get("institutes")) {
      final ArrayNode effects = (ArrayNode) institute.get("houseEffects");
      final ArrayNode kept = node.arrayNode();
      for (final JsonNode effect : effects) {
        if (selected == null || selected.equals(effect.get("electionCycle").asString())) {
          kept.add(effect.deepCopy());
        }
      }
      ((ObjectNode) institute).set("houseEffects", kept);
    }
    return json(node, resolved, ifNoneMatch);
  }

  @GetMapping("/elections")
  public ResponseEntity<byte[]> elections(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final String period =
        coveragePeriod == null ? header(resolved).headlinePeriod() : coveragePeriod;
    return json(
        document(
            resolved, PublicationDocuments.electionsSurface(period), language, "coveragePeriod"),
        resolved,
        ifNoneMatch);
  }

  @GetMapping("/seats")
  public ResponseEntity<byte[]> seats(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String election,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final int electionYear =
        election == null ? header(resolved).approximatedElection() : year(election);
    return json(
        document(resolved, PublicationDocuments.seatsSurface(electionYear), language, "election"),
        resolved,
        ifNoneMatch);
  }

  @GetMapping("/coalitions")
  public ResponseEntity<byte[]> coalitions(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String election,
      @RequestParam(required = false) String coalition,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final int electionYear =
        election == null ? header(resolved).approximatedElection() : year(election);
    final ObjectNode stored =
        document(
            resolved, PublicationDocuments.coalitionsSurface(electionYear), language, "election");
    if (coalition == null) {
      return json(stored, resolved, ifNoneMatch);
    }
    final List<String> requested = List.of(coalition.split(",", -1));
    final List<String> known = Coalitions.PRESETS.stream().map(Coalitions.Preset::id).toList();
    final List<ApiErrors.Invalid> invalid = new ArrayList<>();
    for (final String id : requested) {
      if (!known.contains(id)) {
        invalid.add(new ApiErrors.Invalid("coalition", "unknown_coalition"));
      }
    }
    if (!invalid.isEmpty()) {
      throw ApiErrors.invalidFilter(invalid);
    }
    final ObjectNode node = stored.deepCopy();
    final ArrayNode kept = node.arrayNode();
    for (final JsonNode entry : stored.get("coalitions")) {
      if (requested.contains(entry.get("id").asString())) {
        kept.add(entry.deepCopy());
      }
    }
    node.set("coalitions", kept);
    return json(node, resolved, ifNoneMatch);
  }

  @GetMapping("/polls")
  public ResponseEntity<byte[]> polls(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String institute,
      @RequestParam(required = false) String party,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(required = false) String includeExcluded,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final PollFilters.Parsed parsed =
        PollFilters.parse(
            from, to, institute, party, coveragePeriod, includeExcluded, queries::knownPeriod);
    final List<ApiErrors.Invalid> invalid = new ArrayList<>(parsed.invalid());
    final PollQuery.Filters filters = parsed.filters();
    final int requestedPage = PollFilters.positive(page, "page", 1, Integer.MAX_VALUE, invalid);
    final int size =
        PollFilters.positive(
            pageSize, "pageSize", PollQuery.DEFAULT_PAGE_SIZE, PollQuery.MAX_PAGE_SIZE, invalid);
    if (!invalid.isEmpty()) {
      throw ApiErrors.invalidFilter(invalid);
    }
    final PublicationStore.Header header = header(resolved);
    final PollQuery.Result result =
        queries.query(header.snapshotId(), filters, requestedPage, size);
    return json(
        pollsBody(header, result, filters, resolved, Translations.of(language)),
        resolved,
        ifNoneMatch);
  }

  @GetMapping(value = "/polls.csv", produces = "text/csv; charset=utf-8")
  public ResponseEntity<byte[]> pollsCsv(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String institute,
      @RequestParam(required = false) String party,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(required = false) String includeExcluded,
      @RequestParam(defaultValue = Translations.SWEDISH) String language,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Resolved resolved = resolve(publication, language);
    final PollFilters.Parsed parsed =
        PollFilters.parse(
            from, to, institute, party, coveragePeriod, includeExcluded, queries::knownPeriod);
    if (!parsed.valid()) {
      throw ApiErrors.invalidFilter(parsed.invalid());
    }
    final PollQuery.Filters filters = parsed.filters();
    final PublicationStore.Header header = header(resolved);
    final PollQuery.Result result =
        queries.query(header.snapshotId(), filters, 1, PollQuery.MAX_PAGE_SIZE);
    final byte[] body = PollQuery.csv(result, filters).getBytes(StandardCharsets.UTF_8);
    return respond(
        body,
        MediaType.parseMediaType("text/csv; charset=utf-8"),
        resolved.permanent(),
        ifNoneMatch);
  }

  // Publication metadata, composed from the immutable rows and the current pointer.

  private ObjectNode publicationBody(Resolved resolved, Translations text) {
    return PublicationMetadata.of(store, header(resolved), text);
  }

  private ObjectNode pollsBody(
      PublicationStore.Header header,
      PollQuery.Result result,
      PollQuery.Filters filters,
      Resolved resolved,
      Translations text) {
    final ObjectNode node = JSON.createObjectNode();
    final ObjectNode identity = node.putObject("publication");
    identity.put("publicationId", header.publicationId());
    identity.put("runId", header.runId());
    identity.put("snapshotId", header.snapshotId());
    final ObjectNode declared = node.putObject("filters");
    if (filters.from() == null) {
      declared.putNull("from");
    } else {
      declared.put("from", filters.from().toString());
    }
    if (filters.to() == null) {
      declared.putNull("to");
    } else {
      declared.put("to", filters.to().toString());
    }
    final ArrayNode institutes = declared.putArray("institute");
    filters.institutes().forEach(institutes::add);
    final ArrayNode parties = declared.putArray("party");
    filters.selectedComponents().forEach(parties::add);
    declared.put("includeExcluded", filters.includeExcluded());
    if (filters.coveragePeriod() == null) {
      declared.putNull("coveragePeriod");
    } else {
      declared.put("coveragePeriod", filters.coveragePeriod());
    }
    if (filters.coveragePeriod() == null) {
      node.put("coveragePeriod", header.headlinePeriod());
    } else {
      node.put("coveragePeriod", filters.coveragePeriod());
    }
    node.put("total", result.total());
    node.put("page", result.page1());
    node.put("pageSize", result.pageSize());
    node.put("csv", PollFilters.csvLink(resolved.publicationId(), filters));
    final ObjectNode labels = node.putObject("labels");
    for (final String component : filters.selectedComponents()) {
      labels.put(component, text.component(component));
    }
    final ArrayNode polls = node.putArray("polls");
    for (final PollQuery.Row row : result.page()) {
      polls.add(pollNode(row, filters));
    }
    return node;
  }

  /** An absent value is null, never zero and never an empty string standing in for one. */
  private static void putOrNull(ObjectNode node, String field, Object value) {
    switch (value) {
      case null -> node.putNull(field);
      case String text -> node.put(field, text);
      case LocalDate date -> node.put(field, date.toString());
      case BigDecimal number -> node.put(field, number);
      default -> throw new IllegalArgumentException("Unpublishable value for " + field);
    }
  }

  private ObjectNode pollNode(PollQuery.Row row, PollQuery.Filters filters) {
    final PollCsv.Poll poll = row.poll();
    final ObjectNode node = JSON.createObjectNode();
    node.put("pollId", row.pollId());
    node.put("institute", poll.institute());
    node.put("company", poll.company());
    node.put("methodEra", poll.methodEra());
    node.put("methodEvidence", poll.methodEvidence());
    node.put("surveyType", poll.surveyType());
    putOrNull(node, "publicationDate", poll.publicationDate());
    putOrNull(node, "collectionFrom", poll.collectionFrom());
    putOrNull(node, "collectionTo", poll.collectionTo());
    node.put("approximatePeriod", row.approximatePeriod());
    putOrNull(node, "sampleSize", poll.sampleSize());
    node.put("denominatorNote", poll.denominatorNote());
    final ObjectNode shares = node.putObject("shares");
    for (final String component : filters.selectedComponents()) {
      putOrNull(shares, component, poll.shares().get(component));
    }
    putOrNull(node, "other", poll.remainder());
    putOrNull(node, "uncertain", row.uncertain());
    node.put("coveragePeriod", row.coveragePeriod());
    node.put("eligible", poll.eligible());
    final ArrayNode reasons = node.putArray("exclusionReasons");
    poll.exclusionReasons().forEach(reasons::add);
    return node;
  }

  // Request parsing and publication resolution.

  private static int step(String value, List<ApiErrors.Invalid> invalid) {
    if (value == null || value.isBlank()) {
      return 1;
    }
    try {
      final int step = Integer.parseInt(value);
      if (!EstimateQuery.STEPS.contains(step)) {
        invalid.add(new ApiErrors.Invalid("step", "unsupported_step", EstimateQuery.STEPS));
        return 1;
      }
      return step;
    } catch (NumberFormatException e) {
      invalid.add(new ApiErrors.Invalid("step", "unsupported_step", EstimateQuery.STEPS));
      return 1;
    }
  }

  private static int year(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw ApiErrors.invalidFilter(List.of(new ApiErrors.Invalid("election", "not_an_integer")));
    }
  }

  private Resolved resolve(String publicationId, String language) {
    if (!Translations.supported(language)) {
      throw ApiErrors.invalidFilter(
          List.of(new ApiErrors.Invalid("language", "unsupported_language")));
    }
    if (publicationId != null) {
      if (store.header(publicationId).isEmpty()) {
        throw ApiErrors.unknownPublication();
      }
      return new Resolved(publicationId, true);
    }
    return new Resolved(
        store
            .current()
            .map(PublicationStore.Current::publicationId)
            .orElseThrow(
                () -> ApiErrors.estimatesUnavailable(store.lastSuccessfulCheck().orElse(null))),
        false);
  }

  private PublicationStore.Header header(Resolved resolved) {
    return store.header(resolved.publicationId()).orElseThrow(ApiErrors::unknownPublication);
  }

  private ObjectNode document(
      Resolved resolved, String surface, String language, String parameterName) {
    final Optional<String> body = store.document(resolved.publicationId(), surface, language);
    if (body.isEmpty()) {
      throw ApiErrors.invalidFilter(
          List.of(new ApiErrors.Invalid(parameterName, "unavailable_for_this_publication")));
    }
    return (ObjectNode) JSON.readTree(body.get());
  }

  private static boolean knownPeriod(ObjectNode history, String coveragePeriod) {
    for (final JsonNode period : history.get("coveragePeriodByDate")) {
      if (!period.isNull() && coveragePeriod.equals(period.asString())) {
        return true;
      }
    }
    return false;
  }

  // Response shaping: content ETags and the two cache classes.

  private ResponseEntity<byte[]> json(ObjectNode body, Resolved resolved, String ifNoneMatch) {
    return respond(
        JSON.writeValueAsBytes(body),
        MediaType.APPLICATION_JSON,
        resolved.permanent(),
        ifNoneMatch);
  }

  static ResponseEntity<byte[]> respond(
      byte[] body, MediaType mediaType, boolean permanent, String ifNoneMatch) {
    final String etag = "\"" + PublicationStore.sha256(body) + "\"";
    final CacheControl cache =
        permanent
            ? CacheControl.maxAge(Duration.ofSeconds(IMMUTABLE_MAX_AGE_SECONDS))
                .cachePublic()
                .immutable()
            : CacheControl.maxAge(Duration.ofSeconds(CURRENT_MAX_AGE_SECONDS)).cachePublic();
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
    }
    return ResponseEntity.ok().eTag(etag).cacheControl(cache).contentType(mediaType).body(body);
  }
}
