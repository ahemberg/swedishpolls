package se.swedishpolls.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import se.swedishpolls.publication.Translations;

/**
 * The public identity of this deployment: the HTTPS origin every canonical, alternate and image URL
 * is built from, and the name a share preview shows.
 *
 * <p>The origin is configuration, never the incoming {@code Host} header. A shared link has to name
 * the same page whichever proxy or host name the request arrived on, and a forged header must not
 * be able to point a canonical URL somewhere else.
 */
@Component
public final class PublicSite {
  private final String origin;
  private final String name;

  public PublicSite(
      @Value("${site.origin:https://localhost}") String origin,
      @Value("${site.name:}") String name) {
    this.origin = strip(origin);
    this.name = name;
  }

  /** The configured origin, without a trailing slash. */
  public String origin() {
    return origin;
  }

  /** An absolute public URL for a site-relative path. */
  public String url(String path) {
    return origin + (path.startsWith("/") ? path : "/" + path);
  }

  /** The configured site name, falling back to the translated one when no operator set it. */
  public String name(String language) {
    return name.isBlank() ? Translations.of(language).text("site.name") : name;
  }

  private static String strip(String value) {
    return value.endsWith("/") && value.length() > 1
        ? value.substring(0, value.length() - 1)
        : value;
  }
}
