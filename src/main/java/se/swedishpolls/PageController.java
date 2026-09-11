package se.swedishpolls;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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
  private static final long CURRENT_MAX_AGE_SECONDS = 300;
  private static final long PERMANENT_MAX_AGE_SECONDS = 31_536_000;

  private final SiteBootstrap bootstrap;
  private final SiteHtml html;
  private final PublicationStore store;

  public PageController(SiteBootstrap bootstrap, SiteHtml html, PublicationStore store) {
    this.bootstrap = bootstrap;
    this.html = html;
    this.store = store;
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
    final Optional<PublicationStore.Header> header = resolve(publication);
    final ObjectNode page =
        header
            .map(resolved -> bootstrap.page(route, resolved, permanent))
            .orElseGet(
                () -> bootstrap.unavailable(route, store.lastSuccessfulCheck().orElse(null)));
    return respond(html.page(page), permanent && header.isPresent(), ifNoneMatch);
  }

  /** The pinned permanent publication, the current one, or nothing published yet. */
  private Optional<PublicationStore.Header> resolve(String publication) {
    if (publication != null) {
      return Optional.of(store.header(publication).orElseThrow(ApiErrors::unknownPublication));
    }
    return store.current().flatMap(current -> store.header(current.publicationId()));
  }

  private static ResponseEntity<byte[]> respond(
      String page, boolean permanent, String ifNoneMatch) {
    final byte[] body = page.getBytes(StandardCharsets.UTF_8);
    final String etag = "\"" + PublicationStore.sha256(body) + "\"";
    final CacheControl cache =
        permanent
            ? CacheControl.maxAge(Duration.ofSeconds(PERMANENT_MAX_AGE_SECONDS))
                .cachePublic()
                .immutable()
            : CacheControl.maxAge(Duration.ofSeconds(CURRENT_MAX_AGE_SECONDS)).cachePublic();
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .cacheControl(cache)
        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
        .body(body);
  }
}
