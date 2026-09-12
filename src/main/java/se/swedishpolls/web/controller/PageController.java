package se.swedishpolls.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import se.swedishpolls.publication.service.Publications;
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

  public PageController(SiteBootstrap bootstrap, SiteHtml html, Publications publications) {
    this.bootstrap = bootstrap;
    this.html = html;
    this.publications = publications;
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
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final SiteRoutes.Route route =
        SiteRoutes.resolve(request.getRequestURI()).orElseThrow(ApiErrors::unknownRoute);
    final Optional<Publications.Resolved> resolved = resolve(publication);
    final ObjectNode page =
        resolved
            .map(selected -> bootstrap.page(route, selected))
            .orElseGet(
                () ->
                    bootstrap.unavailable(route, publications.lastSuccessfulCheck().orElse(null)));
    return Responses.respond(
        html.page(page).getBytes(StandardCharsets.UTF_8),
        new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8),
        resolved.map(Publications.Resolved::permanent).orElse(false),
        ifNoneMatch);
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

  /** The pinned permanent publication, the current one, or nothing published yet. */
  private Optional<Publications.Resolved> resolve(String publication) {
    final Optional<Publications.Resolved> resolved = publications.resolve(publication);
    if (publication != null && resolved.isEmpty()) {
      throw ApiErrors.unknownPublication();
    }
    return resolved;
  }
}
