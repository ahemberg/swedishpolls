package se.swedishpolls.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.service.Publications;
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
    "/matningar",
    "/en/polls",
    "/metod",
    "/en/method"
  })
  public ResponseEntity<byte[]> page(
      HttpServletRequest request,
      @RequestParam(required = false) String publication,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final SiteRoutes.Route route =
        SiteRoutes.resolve(request.getRequestURI()).orElseThrow(ApiErrors::unknownRoute);
    final boolean permanent = publication != null;
    final Optional<PublicationHeader> header = resolve(publication);
    final ObjectNode page =
        header
            .map(resolved -> bootstrap.page(route, resolved, permanent))
            .orElseGet(
                () ->
                    bootstrap.unavailable(route, publications.lastSuccessfulCheck().orElse(null)));
    return Responses.respond(
        html.page(page).getBytes(StandardCharsets.UTF_8),
        new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8),
        permanent && header.isPresent(),
        ifNoneMatch);
  }

  /** The pinned permanent publication, the current one, or nothing published yet. */
  private Optional<PublicationHeader> resolve(String publication) {
    if (publication != null) {
      return Optional.of(
          publications.header(publication).orElseThrow(ApiErrors::unknownPublication));
    }
    return publications.currentHeader();
  }
}
