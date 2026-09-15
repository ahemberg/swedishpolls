package se.swedishpolls.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import se.swedishpolls.publication.service.Publications;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.web.CoalitionHistoryQuery;
import se.swedishpolls.web.CoalitionSelection;
import se.swedishpolls.web.PollFilters;
import se.swedishpolls.web.SiteBootstrap;
import se.swedishpolls.web.SiteHtml;
import se.swedishpolls.web.SiteRoutes;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public pages. Every approved family is served in both languages from the translated route
 * map, and the language of the path decides the language of the page: nothing here reads an {@code
 * Accept-Language} header, so a shared link opens the same way for everyone.
 *
 * <p>A request pins one publication before it reads anything. Without {@code ?publication=} that is
 * the current one; with it, the permanent one, which never falls back to current results.
 */
@Controller
public class PageController {
  private final SiteBootstrap bootstrap;
  private final SiteHtml html;
  private final Publications publications;
  private final PollQueryService queries;

  public PageController(
      SiteBootstrap bootstrap, SiteHtml html, Publications publications, PollQueryService queries) {
    this.bootstrap = bootstrap;
    this.html = html;
    this.publications = publications;
    this.queries = queries;
  }

  /**
   * Every approved route, in both languages. The literal paths mirror {@link SiteRoutes}, which
   * resolves the request back into a family and a language; a path that drifts out of step with the
   * map fails the route test rather than silently serving the wrong language.
   */
  @GetMapping({
    "/",
    "/en",
    "/parti/{party}",
    "/en/party/{party}",
    "/mandat",
    "/en/seats",
    "/regeringsunderlag",
    "/en/coalitions",
    "/institut",
    "/en/pollsters",
    "/metod",
    "/en/method"
  })
  public ResponseEntity<byte[]> page(
      HttpServletRequest request,
      @RequestParam(required = false) String publication,
      @RequestParam MultiValueMap<String, String> parameters,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final SiteRoutes.Route route =
        SiteRoutes.resolve(request.getRequestURI()).orElseThrow(ApiErrors::unknownRoute);
    final Optional<Publications.Resolved> resolved = resolve(publication);
    if (publication == null && resolved.isEmpty() && route.family() != SiteRoutes.Family.OVERVIEW) {
      return redirect(SiteRoutes.path(SiteRoutes.Family.OVERVIEW, route.language(), null));
    }
    final Optional<Snapshot> source =
        publication == null ? queries.activeSnapshot() : Optional.empty();
    final ObjectNode page;
    if (resolved.isPresent()) {
      final Publications.Resolved selected = resolved.orElseThrow();
      page =
          source.isPresent()
                  && (route.family() == SiteRoutes.Family.OVERVIEW
                      || route.family() == SiteRoutes.Family.PARTY)
              ? bootstrap.page(route, selected, source.orElseThrow())
              : page(route, selected, parameters);
    } else if (source.isPresent()) {
      page = bootstrap.sourcePage(route, source.orElseThrow());
    } else {
      page = bootstrap.noSource(route);
    }
    return Responses.respond(
        html.page(page).getBytes(StandardCharsets.UTF_8),
        new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8),
        resolved.map(Publications.Resolved::permanent).orElse(false),
        ifNoneMatch);
  }

  private ObjectNode page(
      SiteRoutes.Route route,
      Publications.Resolved resolved,
      MultiValueMap<String, String> parameters) {
    if (route.family() != SiteRoutes.Family.COALITIONS) {
      return bootstrap.page(route, resolved);
    }
    try {
      return bootstrap.coalitionPage(route, resolved, CoalitionHistoryQuery.parsePage(parameters));
    } catch (CoalitionSelection.Invalid error) {
      return bootstrap.invalidCoalitionPage(route, resolved, parameters, error);
    }
  }

  /**
   * The browsable poll table, which is the one page family whose own query string selects rows.
   *
   * <p>The filters are read here rather than in the browser, so a filtered link is a complete page
   * before any script runs and a reader who shares one shares what they were looking at. A rejected
   * parameter does not fail the request: the page keeps the filters it understood and says which
   * ones it ignored, because a reader following a stale link is better served by the table than by
   * an error.
   */
  @GetMapping({"/matningar", "/en/polls"})
  public ResponseEntity<byte[]> pollsPage(
      HttpServletRequest request,
      @RequestParam(required = false) String publication,
      @RequestParam(required = false) String snapshot,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String institute,
      @RequestParam(required = false) String party,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(required = false) String includeExcluded,
      @RequestParam(required = false) String page,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final SiteRoutes.Route route =
        SiteRoutes.resolve(request.getRequestURI()).orElseThrow(ApiErrors::unknownRoute);
    if (publication == null) {
      final Optional<Snapshot> selected = sourceSnapshot(snapshot);
      if (selected.isEmpty()) {
        final byte[] body = html.page(bootstrap.noSource(route)).getBytes(StandardCharsets.UTF_8);
        return Responses.respond(
            body, new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8), false, ifNoneMatch);
      }
      final PollFilters.Parsed parsed =
          sourceFilters(from, to, institute, party, coveragePeriod, includeExcluded);
      final List<PollFilters.Invalid> invalid = new ArrayList<>(parsed.invalid());
      final SiteBootstrap.PollRequest polls =
          new SiteBootstrap.PollRequest(
              parsed.filters(),
              PollFilters.positive(page, "page", 1, Integer.MAX_VALUE, invalid),
              invalid);
      return Responses.respond(
          html.page(bootstrap.sourcePage(route, selected.orElseThrow(), polls))
              .getBytes(StandardCharsets.UTF_8),
          new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8),
          false,
          ifNoneMatch);
    }
    final Optional<Publications.Resolved> resolved = resolve(publication);
    if (resolved.isEmpty()) {
      final byte[] body =
          html.page(bootstrap.unavailable(route, publications.lastSuccessfulCheck().orElse(null)))
              .getBytes(StandardCharsets.UTF_8);
      return Responses.respond(
          body, new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8), false, ifNoneMatch);
    }
    final Publications.Resolved selected = resolved.orElseThrow();
    final PollFilters.Parsed parsed =
        PollFilters.parse(
            from,
            to,
            institute,
            party,
            coveragePeriod,
            includeExcluded,
            period -> bootstrap.knownPeriod(selected.header(), route.language(), period));
    final List<PollFilters.Invalid> invalid = new ArrayList<>(parsed.invalid());
    final SiteBootstrap.PollRequest polls =
        new SiteBootstrap.PollRequest(
            parsed.filters(),
            PollFilters.positive(page, "page", 1, Integer.MAX_VALUE, invalid),
            invalid);
    return Responses.respond(
        html.page(bootstrap.page(route, selected, polls)).getBytes(StandardCharsets.UTF_8),
        new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8),
        selected.permanent(),
        ifNoneMatch);
  }

  /** A source CSV read, kept outside the frozen publication API. */
  @GetMapping(value = "/source/polls.csv", produces = "text/csv; charset=utf-8")
  public ResponseEntity<byte[]> sourcePollsCsv(
      @RequestParam(required = false) String snapshot,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String institute,
      @RequestParam(required = false) String party,
      @RequestParam(required = false) String coveragePeriod,
      @RequestParam(required = false) String includeExcluded) {
    final Snapshot selected = sourceSnapshot(snapshot).orElseThrow(ApiErrors::unknownRoute);
    final List<Roster.CoveragePeriod> periods = queries.periods();
    final PollFilters.Parsed parsed =
        sourceFilters(from, to, institute, party, coveragePeriod, includeExcluded);
    if (!parsed.valid()) {
      throw ApiErrors.invalidFilter(parsed.invalid());
    }
    final PollQuery.Result result =
        queries.query(selected.id(), periods, parsed.filters(), 1, PollQuery.MAX_PAGE_SIZE);
    final byte[] body =
        PollQuery.csv(result, parsed.filters(), "Other parties, including FI")
            .getBytes(StandardCharsets.UTF_8);
    return Responses.render(body, MediaType.parseMediaType("text/csv; charset=utf-8"), false);
  }

  /**
   * The source chart's markers for one window, read outside the frozen publication API.
   *
   * <p>A range change fetches this rather than reloading the page, so the first paint carries only
   * the window it draws. The snapshot is named in the query, which is what keeps a range change on
   * the snapshot the visit resolved even when a correction lands meanwhile.
   */
  @GetMapping(value = "/source/chart", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<byte[]> sourceChart(
      @RequestParam(required = false) String snapshot,
      @RequestParam(required = false) String range,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String institute) {
    final Snapshot selected = sourceSnapshot(snapshot).orElseThrow(ApiErrors::unknownRoute);
    final PollFilters.Parsed parsed = sourceFilters(from, to, institute);
    if (!parsed.valid()) {
      throw ApiErrors.invalidFilter(parsed.invalid());
    }
    final ObjectNode chart =
        bootstrap
            .sourceChart(selected, queries.periods(), parsed.filters(), range)
            .orElseThrow(ApiErrors::unknownRoute);
    return Responses.render(
        chart.toString().getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON, false);
  }

  /** The pinned permanent publication, the current one, or nothing published yet. */
  private Optional<Publications.Resolved> resolve(String publication) {
    final Optional<Publications.Resolved> resolved = publications.resolve(publication);
    if (publication != null && resolved.isEmpty()) {
      throw ApiErrors.unknownPublication();
    }
    return resolved;
  }

  private Optional<Snapshot> sourceSnapshot(String snapshot) {
    if (snapshot == null) {
      return queries.activeSnapshot();
    }
    try {
      final long id = Long.parseLong(snapshot);
      if (id < 1) {
        throw ApiErrors.unknownRoute();
      }
      final Optional<Snapshot> resolved = queries.snapshot(id);
      if (resolved.isEmpty()) {
        throw ApiErrors.unknownRoute();
      }
      return resolved;
    } catch (NumberFormatException error) {
      throw ApiErrors.unknownRoute();
    }
  }

  /** The filters a source chart reads: the table's dates and institutes, and nothing else. */
  private static PollFilters.Parsed sourceFilters(String from, String to, String institute) {
    return sourceFilters(from, to, institute, null, null, null);
  }

  private static PollFilters.Parsed sourceFilters(
      String from,
      String to,
      String institute,
      String party,
      String coveragePeriod,
      String includeExcluded) {
    final PollFilters.Parsed parsed =
        PollFilters.parse(from, to, institute, null, null, includeExcluded, ignored -> false);
    final List<PollFilters.Invalid> invalid = new ArrayList<>(parsed.invalid());
    if (party != null && !party.isBlank()) {
      invalid.add(new PollFilters.Invalid("party", "unsupported_filter"));
    }
    if (coveragePeriod != null && !coveragePeriod.isBlank()) {
      invalid.add(new PollFilters.Invalid("coveragePeriod", "unsupported_filter"));
    }
    return new PollFilters.Parsed(parsed.filters(), invalid);
  }

  private static ResponseEntity<byte[]> redirect(String path) {
    return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
        .location(URI.create(path))
        .build();
  }
}
