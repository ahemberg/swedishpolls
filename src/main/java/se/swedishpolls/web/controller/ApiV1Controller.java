package se.swedishpolls.web.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.publication.service.Publications;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.web.EstimateQuery;
import se.swedishpolls.web.PollFilters;
import se.swedishpolls.web.PublicationCoverage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The frozen v1 read surface. Every response is served from one publication's stored documents or
 * from its pinned source snapshot, so a request never refits the model and an ingestion correction
 * cannot change a page that already resolved its publication.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {
  private final Publications publications;
  private final PollQueryService queries;
  private final ObjectReader coalitions;
  private final ObjectReader histories;
  private final ObjectReader institutes;
  private final ObjectReader objects;
  private final ObjectReader publicationMetadata;

  public ApiV1Controller(Publications publications, PollQueryService queries, JsonMapper json) {
    this.publications = publications;
    this.queries = queries;
    coalitions = json.readerFor(CoalitionsResponse.class);
    histories = json.readerFor(HistoryResponse.class);
    institutes = json.readerFor(InstitutesDocument.class);
    objects = json.readerFor(ObjectNode.class);
    publicationMetadata = json.readerFor(PublicationResponse.class);
  }

  @GetMapping("/publication")
  public ResponseEntity<PublicationResponse> publication(
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(null, language);
    return json(publicationBody(resolved, Translations.of(language)), resolved);
  }

  @GetMapping("/publications/{publicationId}")
  public ResponseEntity<PublicationResponse> permanentPublication(
      @PathVariable String publicationId,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publicationId, language);
    return json(publicationBody(resolved, Translations.of(language)), resolved);
  }

  @GetMapping("/estimates/latest")
  public ResponseEntity<byte[]> latest(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final String period =
        coveragePeriod == null ? resolved.header().headlinePeriod() : coveragePeriod;
    return rawJson(
        publications.latest(resolved.header(), period, language), "coveragePeriod", resolved);
  }

  @GetMapping("/estimates/history")
  public ResponseEntity<HistoryResponse> history(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String step,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final List<PollFilters.Invalid> invalid = new ArrayList<>();
    final LocalDate fromDate = PollFilters.date(from, "from", invalid);
    final LocalDate toDate = PollFilters.date(to, "to", invalid);
    final int stepDays = step(step, invalid);
    final ObjectNode stored =
        document(publications.history(resolved.header(), language), "publication");
    if (coveragePeriod != null && !knownPeriod(stored, coveragePeriod)) {
      invalid.add(new PollFilters.Invalid("coveragePeriod", "unknown_coverage_period"));
    }
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
      invalid.add(new PollFilters.Invalid("to", "before_from"));
    }
    if (!invalid.isEmpty()) {
      throw ApiErrors.invalidFilter(invalid);
    }
    final ObjectNode sampled =
        EstimateQuery.sample(
            stored, new EstimateQuery.Range(fromDate, toDate, stepDays, coveragePeriod));
    return json(histories.readValue(sampled), resolved);
  }

  @GetMapping("/institutes")
  public ResponseEntity<InstitutesResponse> institutes(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String electionCycle,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final InstitutesDocument stored =
        document(
            publications.institutes(resolved.header(), language),
            "publication",
            institutes,
            InstitutesDocument.class);
    if (electionCycle != null && !stored.electionCycles().contains(electionCycle)) {
      throw ApiErrors.invalidFilter(
          List.of(new PollFilters.Invalid("electionCycle", "unknown_election_cycle")));
    }
    return json(stored.select(electionCycle), resolved);
  }

  @GetMapping("/elections")
  public ResponseEntity<byte[]> elections(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final String period =
        coveragePeriod == null ? resolved.header().headlinePeriod() : coveragePeriod;
    return rawJson(
        publications.elections(resolved.header(), period, language), "coveragePeriod", resolved);
  }

  @GetMapping("/seats")
  public ResponseEntity<byte[]> seats(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String election,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final int electionYear =
        election == null ? resolved.header().approximatedElection() : year(election);
    return rawJson(
        publications.seats(resolved.header(), electionYear, language), "election", resolved);
  }

  @GetMapping("/coalitions")
  public ResponseEntity<?> coalitions(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String election,
      @RequestParam(required = false) String coalition,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final int electionYear =
        election == null ? resolved.header().approximatedElection() : year(election);
    final Optional<String> body =
        publications.coalitions(resolved.header(), electionYear, language);
    if (coalition == null) {
      return rawJson(body, "election", resolved);
    }
    final CoalitionsResponse stored =
        document(body, "election", coalitions, CoalitionsResponse.class);
    final List<String> requested = List.of(coalition.split(",", -1));
    final List<String> known = Translations.COALITION_IDS;
    final List<PollFilters.Invalid> invalid = new ArrayList<>();
    for (final String id : requested) {
      if (!known.contains(id)) {
        invalid.add(new PollFilters.Invalid("coalition", "unknown_coalition"));
      }
    }
    if (!invalid.isEmpty()) {
      throw ApiErrors.invalidFilter(invalid);
    }
    return json(stored.select(requested), resolved);
  }

  @GetMapping("/polls")
  public ResponseEntity<PollsResponse> polls(
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String institute,
      @RequestParam(required = false) String party,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(required = false) String includeExcluded,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final PublicationHeader header = resolved.header();
    final List<Roster.CoveragePeriod> periods = coverage(header, language);
    final PollFilters.Parsed parsed =
        PollFilters.parse(
            from,
            to,
            institute,
            party,
            coveragePeriod,
            includeExcluded,
            period -> periods.stream().anyMatch(candidate -> candidate.id().equals(period)));
    final List<PollFilters.Invalid> invalid = new ArrayList<>(parsed.invalid());
    final PollQuery.Filters filters = parsed.filters();
    final int requestedPage = PollFilters.positive(page, "page", 1, Integer.MAX_VALUE, invalid);
    final int size =
        PollFilters.positive(
            pageSize, "pageSize", PollQuery.DEFAULT_PAGE_SIZE, PollQuery.MAX_PAGE_SIZE, invalid);
    if (!invalid.isEmpty()) {
      throw ApiErrors.invalidFilter(invalid);
    }
    final PollQuery.Result result =
        queries.query(header.snapshotId(), periods, filters, requestedPage, size);
    return json(
        PollsResponse.from(
            header, result, filters, resolved.publicationId(), Translations.of(language)),
        resolved);
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
      @RequestParam(defaultValue = Translations.SWEDISH) String language) {
    final Publications.Resolved resolved = resolve(publication, language);
    final PublicationHeader header = resolved.header();
    final List<Roster.CoveragePeriod> periods = coverage(header, language);
    final PollFilters.Parsed parsed =
        PollFilters.parse(
            from,
            to,
            institute,
            party,
            coveragePeriod,
            includeExcluded,
            period -> periods.stream().anyMatch(candidate -> candidate.id().equals(period)));
    if (!parsed.valid()) {
      throw ApiErrors.invalidFilter(parsed.invalid());
    }
    final PollQuery.Filters filters = parsed.filters();
    final PollQuery.Result result =
        queries.query(header.snapshotId(), periods, filters, 1, PollQuery.MAX_PAGE_SIZE);
    final byte[] body = PollQuery.csv(result, filters).getBytes(StandardCharsets.UTF_8);
    return Responses.render(
        body, MediaType.parseMediaType("text/csv; charset=utf-8"), resolved.permanent());
  }

  // Publication metadata, composed from the immutable rows and the current pointer.

  private PublicationResponse publicationBody(Publications.Resolved resolved, Translations text) {
    return publicationMetadata.readValue(publications.metadata(resolved.header(), text));
  }

  // Request parsing and publication resolution.

  private static int step(String value, List<PollFilters.Invalid> invalid) {
    if (value == null || value.isBlank()) {
      return 1;
    }
    try {
      final int step = Integer.parseInt(value);
      if (!EstimateQuery.STEPS.contains(step)) {
        invalid.add(new PollFilters.Invalid("step", "unsupported_step", EstimateQuery.STEPS));
        return 1;
      }
      return step;
    } catch (NumberFormatException e) {
      invalid.add(new PollFilters.Invalid("step", "unsupported_step", EstimateQuery.STEPS));
      return 1;
    }
  }

  private static int year(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw ApiErrors.invalidFilter(List.of(new PollFilters.Invalid("election", "not_an_integer")));
    }
  }

  private Publications.Resolved resolve(String publicationId, String language) {
    if (!Translations.supported(language)) {
      throw ApiErrors.invalidFilter(
          List.of(new PollFilters.Invalid("language", "unsupported_language")));
    }
    final Optional<Publications.Resolved> resolved = publications.resolve(publicationId);
    if (resolved.isPresent()) {
      return resolved.get();
    }
    if (publicationId != null) {
      throw ApiErrors.unknownPublication();
    }
    throw ApiErrors.estimatesUnavailable(publications.lastSuccessfulCheck().orElse(null));
  }

  private List<Roster.CoveragePeriod> coverage(PublicationHeader header, String language) {
    return PublicationCoverage.from(
        document(publications.latest(header, header.headlinePeriod(), language), "publication"));
  }

  private ObjectNode document(Optional<String> body, String parameterName) {
    if (body.isEmpty()) {
      throw ApiErrors.invalidFilter(
          List.of(new PollFilters.Invalid(parameterName, "unavailable_for_this_publication")));
    }
    return objects.readValue(body.get());
  }

  private static <T> T document(
      Optional<String> body, String parameterName, ObjectReader reader, Class<T> type) {
    if (body.isEmpty()) {
      throw ApiErrors.invalidFilter(
          List.of(new PollFilters.Invalid(parameterName, "unavailable_for_this_publication")));
    }
    return type.cast(reader.readValue(body.get()));
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

  private static <T> ResponseEntity<T> json(T body, Publications.Resolved resolved) {
    return Responses.render(body, resolved.permanent());
  }

  private ResponseEntity<byte[]> rawJson(
      Optional<String> body, String parameterName, Publications.Resolved resolved) {
    if (body.isEmpty()) {
      throw ApiErrors.invalidFilter(
          List.of(new PollFilters.Invalid(parameterName, "unavailable_for_this_publication")));
    }
    return Responses.render(
        body.get().getBytes(StandardCharsets.UTF_8),
        MediaType.APPLICATION_JSON,
        resolved.permanent());
  }
}
