package se.swedishpolls;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The HTML a request receives before any script runs: escaped route metadata, the translated shell
 * and, on the overview, the headline, its date and the results table.
 *
 * <p>The script does not hydrate this markup. It replaces the mount point, so the reader never sees
 * the same result twice and a screen reader never reads one twice either. Everything written here
 * is therefore a complete page on its own, which is what a shared link has to be when JavaScript
 * does not run.
 */
@Component
public final class SiteHtml {
  /** The element the compiled frontend replaces once it mounts. */
  static final String MOUNT = "site-root";

  /** The element the compiled frontend reads this page's resolved publication from. */
  static final String BOOTSTRAP = "site-bootstrap";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final SiteAssets assets;
  private final PublicSite site;

  public SiteHtml(SiteAssets assets, PublicSite site) {
    this.assets = assets;
    this.site = site;
  }

  /** A complete page for a resolved route, with the overview's results when the page has them. */
  @SuppressFBWarnings(
      value = "POTENTIAL_XML_INJECTION",
      justification =
          "The appended bootstrap is server-authored JSON, never request input, and every '<' in"
              + " it is written as the JSON escape \\u003c by json(), so it cannot close the script"
              + " element or introduce markup. PageIT asserts that no raw '<' survives inside the"
              + " element. Everything else appended here passes through escape().")
  public String page(ObjectNode bootstrap) {
    final String language = bootstrap.get("language").asString();
    final SiteText text = SiteText.of(language);
    final StringBuilder html = new StringBuilder(1 << 15);
    html.append("<!doctype html>\n<html lang=\"").append(escape(language)).append("\">\n<head>\n");
    head(html, bootstrap, text);
    html.append("</head>\n<body>\n");
    html.append("<a class=\"skip\" href=\"#main\">")
        .append(escape(text.text("skip")))
        .append("</a>\n");
    html.append("<div id=\"").append(MOUNT).append("\">\n");
    shell(html, bootstrap, text);
    html.append("</div>\n");
    html.append("<script type=\"application/json\" id=\"").append(BOOTSTRAP).append("\">");
    html.append(json(bootstrap));
    html.append("</script>\n");
    final String script = assets.script();
    if (script != null) {
      html.append("<script type=\"module\" src=\"").append(escape(script)).append("\"></script>\n");
    }
    html.append("</body>\n</html>\n");
    return html.toString();
  }

  // The head: escaped, route-specific, and built from the configured origin rather than the Host.

  private void head(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    final String siteName = bootstrap.get("site").get("name").asString();
    final String title = title(bootstrap, text);
    final String description = description(bootstrap, text);
    final String canonical = site.url(bootstrap.get("route").get("path").asString());
    html.append("<meta charset=\"utf-8\">\n");
    html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
    html.append("<title>").append(escape(title + " · " + siteName)).append("</title>\n");
    meta(html, "description", description);
    html.append("<link rel=\"canonical\" href=\"").append(escape(canonical)).append("\">\n");
    final JsonNode alternates = bootstrap.get("alternates");
    for (final String code : Translations.LANGUAGES) {
      alternate(html, code, site.url(alternates.get(code).asString()));
    }
    alternate(html, "x-default", site.url(alternates.get(Translations.SWEDISH).asString()));
    for (final String stylesheet : assets.stylesheets()) {
      html.append("<link rel=\"stylesheet\" href=\"").append(escape(stylesheet)).append("\">\n");
    }
    property(html, "og:type", "website");
    property(html, "og:site_name", siteName);
    property(html, "og:title", title);
    property(html, "og:description", description);
    property(html, "og:url", canonical);
    property(html, "og:locale", SiteFormat.locale(language).replace('-', '_'));
    final String image = shareImage(bootstrap);
    final String imageAlt = imageAlt(bootstrap, text);
    if (image != null) {
      property(html, "og:image", site.url(image));
      property(html, "og:image:alt", imageAlt);
      property(html, "og:image:width", Integer.toString(ShareImages.WIDTH));
      property(html, "og:image:height", Integer.toString(ShareImages.HEIGHT));
      meta(html, "twitter:card", "summary_large_image");
      meta(html, "twitter:image", site.url(image));
      meta(html, "twitter:image:alt", imageAlt);
    } else {
      meta(html, "twitter:card", "summary");
    }
    meta(html, "twitter:title", title);
    meta(html, "twitter:description", description);
  }

  private static String title(ObjectNode bootstrap, SiteText text) {
    final String family = bootstrap.get("route").get("family").asString();
    return text.text("head.title." + family.toLowerCase(Locale.ROOT));
  }

  private static String description(ObjectNode bootstrap, SiteText text) {
    if (!bootstrap.has("publication")) {
      return text.text("unavailable.body");
    }
    final String key =
        "head.description."
            + bootstrap.get("route").get("family").asString().toLowerCase(Locale.ROOT);
    if (!text.all().containsKey(key)) {
      return text.text("notForecast");
    }
    return SiteText.fill(text.text(key), "date", fieldwork(bootstrap));
  }

  private static String imageAlt(ObjectNode bootstrap, SiteText text) {
    if (!bootstrap.has("publication")) {
      return text.text("unavailable.title");
    }
    return SiteText.fill(text.text("head.imageAlt"), "date", fieldwork(bootstrap));
  }

  /** The overview card of this publication, in this language, at its published asset version. */
  private static String shareImage(ObjectNode bootstrap) {
    if (!bootstrap.has("publication")) {
      return null;
    }
    final JsonNode overview = bootstrap.get("publication").get("assets").get(ShareImages.OVERVIEW);
    final JsonNode path =
        overview == null ? null : overview.get(bootstrap.get("language").asString());
    return path == null || path.isNull() ? null : path.asString();
  }

  /**
   * The day the headline claims: the estimate's own last day, which is not always the snapshot's
   * last fieldwork date. The two differ whenever the newest poll's midpoint falls before its final
   * day, and the page must not claim an estimate for a day the model did not estimate.
   */
  private static String fieldwork(ObjectNode bootstrap) {
    return SiteFormat.date(
        LocalDate.parse(bootstrap.get("headlineDate").asString()),
        bootstrap.get("language").asString());
  }

  // The shell and the page body, as they read with no script at all.

  private void shell(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    html.append("<header class=\"site\">\n");
    html.append("<a class=\"brand\" href=\"")
        .append(escape(SiteRoutes.path(SiteRoutes.Family.OVERVIEW, language, null)))
        .append("\">")
        .append(escape(bootstrap.get("site").get("name").asString()))
        .append("</a>\n");
    html.append("<nav class=\"nav\" aria-label=\"")
        .append(escape(text.text("nav.label")))
        .append("\">\n<ul>\n");
    for (final JsonNode entry : bootstrap.get("navigation")) {
      html.append("<li><a href=\"").append(escape(entry.get("path").asString())).append("\"");
      if (entry.get("current").asBoolean()) {
        html.append(" aria-current=\"page\"");
      }
      html.append(">").append(escape(entry.get("label").asString())).append("</a></li>\n");
    }
    html.append("</ul>\n</nav>\n");
    html.append("<nav class=\"lang\" aria-label=\"")
        .append(escape(text.text("language.label")))
        .append("\">\n");
    for (final String code : Translations.LANGUAGES) {
      final boolean current = code.equals(language);
      html.append("<a hreflang=\"")
          .append(escape(code))
          .append("\" href=\"")
          .append(escape(bootstrap.get("alternates").get(code).asString()))
          .append("\" lang=\"")
          .append(escape(code))
          .append("\"");
      if (current) {
        html.append(" aria-current=\"true\"");
      }
      html.append("><abbr title=\"")
          .append(escape(text.text("language." + code)))
          .append("\">")
          .append(escape(text.text("language.short." + code)))
          .append("</abbr></a>\n");
    }
    html.append("</nav>\n</header>\n");
    staleBanner(html, bootstrap, text);
    html.append("<main id=\"main\">\n");
    if (!bootstrap.has("publication")) {
      unavailable(html, bootstrap, text);
    } else if (SiteRoutes.Family.OVERVIEW
        .name()
        .equals(bootstrap.get("route").get("family").asString())) {
      overview(html, bootstrap, text);
    } else {
      html.append("<h1>").append(escape(title(bootstrap, text))).append("</h1>\n");
    }
    html.append("</main>\n");
    if (bootstrap.has("publication")) {
      about(html, bootstrap, text);
    }
  }

  private static void staleBanner(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    if (!bootstrap.has("publication") || !bootstrap.get("publication").get("stale").asBoolean()) {
      return;
    }
    final String language = bootstrap.get("language").asString();
    final String since =
        SiteFormat.timestamp(
            Instant.parse(bootstrap.get("publication").get("staleSince").asString()), language);
    html.append("<p class=\"stale\" role=\"status\">")
        .append(
            escape(
                SiteText.fill(
                    SiteText.fill(text.text("stale"), "timestamp", since),
                    "date",
                    fieldwork(bootstrap))))
        .append("</p>\n");
  }

  private static void unavailable(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    html.append("<h1>").append(escape(text.text("unavailable.title"))).append("</h1>\n");
    html.append("<p>").append(escape(text.text("unavailable.body"))).append("</p>\n");
    final JsonNode checked = bootstrap.get("lastSourceCheck");
    if (checked != null && !checked.isNull()) {
      final String when =
          SiteFormat.timestamp(
              Instant.parse(checked.asString()), bootstrap.get("language").asString());
      html.append("<p class=\"meta\">")
          .append(escape(SiteText.fill(text.text("unavailable.lastCheck"), "timestamp", when)))
          .append("</p>\n");
    }
  }

  private void overview(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    final JsonNode publication = bootstrap.get("publication");
    final JsonNode latest = bootstrap.get("data").get("latest");
    final String date = fieldwork(bootstrap);
    html.append("<h1>")
        .append(escape(text.text("headline")))
        .append(" <span class=\"asof\">")
        .append(escape(SiteText.fill(text.text("headline.asOf"), "date", date)))
        .append("</span></h1>\n");
    html.append("<p class=\"meta\">")
        .append(escape(text.text("notForecast")))
        .append(" ")
        .append(
            escape(
                SiteText.fill(
                    text.text("published"),
                    "timestamp",
                    SiteFormat.timestamp(
                        Instant.parse(publication.get("publishedAt").asString()), language))))
        .append("</p>\n");
    estimateTable(html, bootstrap, text, latest);
    blocs(html, bootstrap, text);
    downloads(html, bootstrap, text);
  }

  private static void estimateTable(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode latest) {
    final String language = bootstrap.get("language").asString();
    final JsonNode labels = bootstrap.get("labels");
    final JsonNode seats = bootstrap.get("data").get("seats");
    final String level = SiteFormat.level(latest.get("intervalLevel").asDouble());
    html.append("<h2>").append(escape(text.text("estimate.title"))).append("</h2>\n");
    html.append("<table class=\"estimates\">\n<caption>")
        .append(escape(SiteText.fill(text.text("estimate.caption"), "level", level)))
        .append("</caption>\n<thead><tr>");
    for (final String column :
        List.of(
            "estimate.column.party",
            "estimate.column.estimate",
            "estimate.column.interval",
            "estimate.column.seats")) {
      html.append("<th scope=\"col\">").append(escape(text.text(column))).append("</th>");
    }
    html.append("</tr></thead>\n<tbody>\n");
    for (final JsonNode component : latest.get("components")) {
      final String key = component.get("component").asString();
      html.append("<tr><th scope=\"row\">")
          .append(escape(labels.get(key).asString()))
          .append("</th>");
      if (component.get("mean").isNull()) {
        html.append("<td colspan=\"3\">")
            .append(escape(text.text("estimate.unavailable")))
            .append("</td>");
      } else {
        html.append("<td class=\"num\">")
            .append(
                escape(
                    SiteFormat.percent(
                        SiteFormat.decimal(component.get("mean").asDouble(), language), language)))
            .append("</td><td>")
            .append(escape(verbal(text, component, language)))
            .append("</td><td class=\"num\">")
            .append(escape(seatsOf(seats, key, text)))
            .append("</td>");
      }
      html.append("</tr>\n");
    }
    html.append("</tbody>\n</table>\n");
  }

  private static String verbal(SiteText text, JsonNode component, String language) {
    final String lower =
        SiteFormat.percent(
            SiteFormat.decimal(component.get("lower").asDouble(), language), language);
    final String upper =
        SiteFormat.percent(
            SiteFormat.decimal(component.get("upper").asDouble(), language), language);
    return SiteText.fill(
        SiteText.fill(text.text("estimate.verbal"), "lower", lower), "upper", upper);
  }

  /** Integer point seats, the allocation the hemicycle uses. OTHER is never allocated any. */
  private static String seatsOf(JsonNode seats, String component, SiteText text) {
    for (final JsonNode excluded : seats.get("excludedFromAllocation")) {
      if (excluded.asString().equals(component)) {
        return text.text("estimate.noSeats");
      }
    }
    for (final JsonNode party : seats.get("parties")) {
      if (party.get("component").asString().equals(component)) {
        return party.get("pointSeats").isNull()
            ? text.text("estimate.unavailable")
            : Integer.toString(party.get("pointSeats").asInt());
      }
    }
    return text.text("estimate.unavailable");
  }

  private static void blocs(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    final JsonNode coalitions = bootstrap.get("data").get("coalitions");
    final JsonNode labels = bootstrap.get("labels");
    final List<String> defaults = new ArrayList<>();
    for (final JsonNode id : coalitions.get("overviewDefaults")) {
      defaults.add(id.asString());
    }
    html.append("<h2>").append(escape(text.text("blocs.title"))).append("</h2>\n");
    html.append("<table class=\"blocs\">\n<caption>")
        .append(escape(text.text("blocs.caption")))
        .append("</caption>\n<thead><tr><th scope=\"col\">")
        .append(escape(text.text("estimate.column.party")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("estimate.column.seats")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("blocs.majority")))
        .append("</th></tr></thead>\n<tbody>\n");
    for (final JsonNode coalition : coalitions.get("coalitions")) {
      final String id = coalition.get("id").asString();
      if (!defaults.contains(id)) {
        continue;
      }
      html.append("<tr><th scope=\"row\">")
          .append(escape(labels.get("coalition." + id).asString()))
          .append("</th><td class=\"num\">")
          .append(escape(Integer.toString(coalition.get("pointSeats").asInt())))
          .append("</td><td class=\"num\">")
          .append(
              escape(
                  SiteFormat.probability(
                      coalition.get("majorityProbability").asDouble(), language)))
          .append("</td></tr>\n");
    }
    html.append("</tbody>\n</table>\n");
    html.append("<p class=\"meta\">")
        .append(escape(text.text("blocs.pointSeats")))
        .append("</p>\n");
  }

  private static void downloads(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String publication = bootstrap.get("api").get("publication").asString();
    final String language = bootstrap.get("language").asString();
    final String pin = "?publication=" + publication + "&language=" + language;
    html.append("<h2>")
        .append(escape(text.text("downloads.title")))
        .append("</h2>\n<ul class=\"downloads\">\n");
    link(html, "/api/v1/polls.csv" + pin, text.text("downloads.polls"));
    link(html, "/api/v1/estimates/latest" + pin, text.text("downloads.estimates"));
    link(html, "/api/v1/seats" + pin, text.text("downloads.seats"));
    final String image = shareImage(bootstrap);
    if (image != null) {
      link(html, image, text.text("downloads.image"));
    }
    html.append("</ul>\n");
    html.append("<p class=\"meta\">")
        .append(escape(text.text("downloads.imageNote")))
        .append("</p>\n");
    html.append("<p class=\"meta\">")
        .append(escape(SiteText.fill(text.text("downloads.pinned"), "publication", publication)))
        .append("</p>\n");
  }

  private static void link(StringBuilder html, String href, String label) {
    html.append("<li><a href=\"")
        .append(escape(href))
        .append("\">")
        .append(escape(label))
        .append("</a></li>\n");
  }

  /** The one method footer: the model, the seat approximation, OTHER and the missing-data rule. */
  private static void about(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode publication = bootstrap.get("publication");
    final String level =
        bootstrap.has("data")
            ? SiteFormat.level(bootstrap.get("data").get("latest").get("intervalLevel").asDouble())
            : null;
    html.append("<footer class=\"about\">\n<h2>")
        .append(escape(text.text("about.title")))
        .append("</h2>\n");
    html.append("<p>")
        .append(escape(SiteText.fill(text.text("about.method"), "date", fieldwork(bootstrap))))
        .append("</p>\n");
    if (level != null) {
      html.append("<p>")
          .append(escape(SiteText.fill(text.text("about.uncertainty"), "level", level)))
          .append("</p>\n");
    }
    html.append("<p>").append(escape(text.text("about.seats"))).append("</p>\n");
    html.append("<p>").append(escape(text.text("about.other"))).append("</p>\n");
    html.append("<p>").append(escape(text.text("about.missing"))).append("</p>\n");
    final JsonNode snapshot = publication.get("snapshot");
    html.append("<p class=\"meta\">")
        .append(
            escape(
                SiteText.fill(
                    SiteText.fill(
                        text.text("about.source"), "source", snapshot.get("sourceUrl").asString()),
                    "snapshot",
                    snapshot.get("sha256").asString())))
        .append("</p>\n");
    final JsonNode run = publication.get("modelRun");
    html.append("<p class=\"meta\">")
        .append(
            escape(
                SiteText.fill(
                    SiteText.fill(
                        SiteText.fill(text.text("about.run"), "run", run.get("runId").asString()),
                        "code",
                        run.get("codeVersion").asString()),
                    "seed",
                    Long.toString(run.get("seed").asLong()))))
        .append("</p>\n");
    html.append("</footer>\n");
  }

  // Escaping. Everything above passes through one of these two.

  /** Escapes text and attribute values alike, so one rule covers every insertion point. */
  static String escape(String value) {
    final StringBuilder escaped = new StringBuilder(value.length() + 16);
    for (int index = 0; index < value.length(); index++) {
      final char character = value.charAt(index);
      switch (character) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(character);
      }
    }
    return escaped.toString();
  }

  /**
   * The bootstrap, with every {@code <} escaped as a JSON string escape. Structural JSON carries
   * none, so this cannot close the script element early and cannot change the parsed value.
   */
  private static String json(ObjectNode bootstrap) {
    return JSON.writeValueAsString(bootstrap).replace("<", "\\u003c");
  }

  private static void meta(StringBuilder html, String name, String content) {
    html.append("<meta name=\"")
        .append(escape(name))
        .append("\" content=\"")
        .append(escape(content))
        .append("\">\n");
  }

  private static void property(StringBuilder html, String property, String content) {
    html.append("<meta property=\"")
        .append(escape(property))
        .append("\" content=\"")
        .append(escape(content))
        .append("\">\n");
  }

  private static void alternate(StringBuilder html, String hreflang, String href) {
    html.append("<link rel=\"alternate\" hreflang=\"")
        .append(escape(hreflang))
        .append("\" href=\"")
        .append(escape(href))
        .append("\">\n");
  }
}
