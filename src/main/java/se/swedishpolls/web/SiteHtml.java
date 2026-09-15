package se.swedishpolls.web;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import se.swedishpolls.publication.ShareImages;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.source.PollQuery;
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
  public static final String MOUNT = "site-root";

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
    final SiteRoutes.Family family = family(bootstrap);
    if (!bootstrap.has("publication")) {
      return text.text(family == SiteRoutes.Family.POLLS ? "source.polls.title" : "source.title");
    }
    if (family == SiteRoutes.Family.PARTY) {
      return partyName(bootstrap);
    }
    return text.text("head.title." + family.key());
  }

  private static SiteRoutes.Family family(ObjectNode bootstrap) {
    return SiteRoutes.Family.valueOf(bootstrap.get("route").get("family").asString());
  }

  private static String description(ObjectNode bootstrap, SiteText text) {
    if (!bootstrap.has("publication")) {
      return bootstrap.has("source")
          ? text.text("source.description." + family(bootstrap).key())
          : text.text("source.noPolls.body");
    }
    final SiteRoutes.Family family = family(bootstrap);
    if (family == SiteRoutes.Family.COALITIONS && bootstrap.path("data").has("coalitionHistory")) {
      final JsonNode history = bootstrap.get("data").get("coalitionHistory");
      String result =
          SiteText.fill(
              text.text("head.description.coalitionComparison"),
              "a",
              members(history.get("selection").get("a"), text));
      result = SiteText.fill(result, "b", members(history.get("selection").get("b"), text));
      result = SiteText.fill(result, "from", history.get("requestedRange").get("from").asString());
      return SiteText.fill(result, "to", history.get("requestedRange").get("to").asString());
    }
    final boolean party = family == SiteRoutes.Family.PARTY;
    final String key =
        party && bootstrap.get("data").get("party").get("historicalOnly").asBoolean()
            ? "head.description.partyHistorical"
            : "head.description." + family.key();
    if (!text.all().containsKey(key)) {
      return text.text("notForecast");
    }
    final String dated = SiteText.fill(text.text(key), "date", fieldwork(bootstrap));
    return party ? SiteText.fill(dated, "party", partyName(bootstrap)) : dated;
  }

  private static String members(JsonNode selection, SiteText text) {
    final List<String> members = new ArrayList<>();
    selection.forEach(party -> members.add(party.asString()));
    return members.isEmpty()
        ? text.text("coalitionHistory.noParties")
        : String.join(" + ", members);
  }

  private static String imageAlt(ObjectNode bootstrap, SiteText text) {
    if (!bootstrap.has("publication")) {
      return text.text("unavailable.title");
    }
    if (family(bootstrap) == SiteRoutes.Family.PARTY) {
      final boolean historical =
          bootstrap.get("data").get("party").get("historicalOnly").asBoolean();
      final String party = historical ? "head.imageAlt.partyHistorical" : "head.imageAlt.party";
      return SiteText.fill(
          SiteText.fill(text.text(party), "party", partyName(bootstrap)),
          "date",
          fieldwork(bootstrap));
    }
    final String key = "head.imageAlt." + family(bootstrap).key();
    final String template =
        text.all().containsKey(key) ? text.text(key) : text.text("head.imageAlt");
    return SiteText.fill(template, "date", fieldwork(bootstrap));
  }

  /**
   * This route's card, in this language, at its published asset version.
   *
   * <p>A party page shares its own party's card; every other family shares the publication-wide
   * card that summarizes it, so a seats link does not preview as the overview.
   */
  private static String shareImage(ObjectNode bootstrap) {
    if (!bootstrap.has("publication")) {
      return null;
    }
    if (family(bootstrap) == SiteRoutes.Family.COALITIONS
        && bootstrap.path("customCoalitionSelection").asBoolean()) {
      return null;
    }
    final JsonNode card = bootstrap.get("publication").get("assets").get(cardKind(bootstrap));
    final JsonNode path = card == null ? null : card.get(bootstrap.get("language").asString());
    return path == null || path.isNull() ? null : path.asString();
  }

  /** Which published card a route shares. Polls, pollsters and method reuse the overview one. */
  private static String cardKind(ObjectNode bootstrap) {
    return switch (family(bootstrap)) {
      case PARTY -> ShareImages.partyKind(bootstrap.get("route").get("parameter").asString());
      case SEATS -> ShareImages.SEATS;
      case COALITIONS -> ShareImages.COALITIONS;
      case OVERVIEW, POLLSTERS, POLLS, METHOD -> ShareImages.OVERVIEW;
    };
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

  private static String partyName(ObjectNode bootstrap) {
    final String component = bootstrap.get("route").get("parameter").asString();
    return bootstrap.get("labels").get(component).asString();
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
    if (bootstrap.has("publication")) {
      // Exhaustive on purpose: a family this build has no rendering for yet still gets its own
      // title, and adding one has to be a deliberate choice here rather than a silent fallback.
      switch (family(bootstrap)) {
        case OVERVIEW -> overview(html, bootstrap, text);
        case PARTY -> party(html, bootstrap, text);
        case SEATS -> seats(html, bootstrap, text);
        case COALITIONS -> coalitions(html, bootstrap, text);
        case POLLS -> polls(html, bootstrap, text);
        case POLLSTERS -> pollsters(html, bootstrap, text);
        case METHOD -> method(html, bootstrap, text);
      }
    } else if (bootstrap.has("source")) {
      switch (family(bootstrap)) {
        case OVERVIEW -> sourceOverview(html, bootstrap, text);
        case POLLS -> polls(html, bootstrap, text);
        default -> throw new IllegalArgumentException("Source page for " + family(bootstrap));
      }
    } else {
      noSource(html, bootstrap, text);
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

  private static void noSource(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    html.append("<h1>").append(escape(text.text("source.noPolls.title"))).append("</h1>\n");
    html.append("<p>").append(escape(text.text("source.noPolls.body"))).append("</p>\n");
    html.append("<p><a class=\"btn primary\" href=\"")
        .append(escape(bootstrap.get("route").get("path").asString()))
        .append("\">")
        .append(escape(text.text("source.retry")))
        .append("</a></p>\n");
  }

  private static void sourceOverview(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    html.append("<h1>").append(escape(text.text("source.title"))).append("</h1>\n");
    sourceUpdated(html, bootstrap, text);
    sourceLatestPolls(html, bootstrap, text);
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
    html.append("<p><a href=\"")
        .append(
            escape(
                SiteRoutes.path(
                    SiteRoutes.Family.PARTY, bootstrap.get("language").asString(), "FI")))
        .append("\">")
        .append(escape(text.text("party.fiLink")))
        .append("</a></p>\n");
    blocs(html, bootstrap, text);
    downloads(html, bootstrap, text);
    if (bootstrap.has("sourcePolls")) {
      sourceUpdated(html, bootstrap, text);
      sourceLatestPolls(html, bootstrap, text);
    }
  }

  private static void sourceUpdated(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String updated =
        SiteFormat.timestamp(
            Instant.parse(bootstrap.get("source").get("capturedAt").asString()),
            bootstrap.get("language").asString());
    html.append("<p class=\"meta\">")
        .append(escape(SiteText.fill(text.text("source.updated"), "timestamp", updated)))
        .append("</p>\n");
  }

  /** The current source rows on the overview, complete before the script replaces them. */
  private static void sourceLatestPolls(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode polls = bootstrap.get("sourcePolls");
    final String language = bootstrap.get("language").asString();
    html.append("<section class=\"sec o-polls\"><h2>")
        .append(escape(text.text("polls.title")))
        .append("</h2><div class=\"scroll\"><table><caption>")
        .append(escape(text.text("polls.caption")))
        .append("</caption><thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.institute")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("polls.column.fieldwork")))
        .append("</th><th scope=\"col\" class=\"num\">")
        .append(escape(text.text("polls.column.sample")))
        .append("</th>");
    for (final String component : PollQuery.COMPONENTS) {
      html.append("<th scope=\"col\" class=\"num\" title=\"")
          .append(escape(polls.get("labels").get(component).asString()))
          .append("\">")
          .append(escape(component))
          .append("</th>");
    }
    html.append("</tr></thead><tbody>");
    for (final JsonNode poll : polls.get("polls")) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(poll.get("institute").asString()))
          .append("</th><td>")
          .append(escape(fieldwork(poll, language, text)))
          .append("</td><td class=\"num\">")
          .append(escape(sampleSize(poll.get("sampleSize"), language, text)))
          .append("</td>");
      for (final String component : PollQuery.COMPONENTS) {
        html.append("<td class=\"num\">")
            .append(escape(sourceShare(poll.get("shares").get(component), language, text)))
            .append("</td>");
      }
      html.append("</tr>");
    }
    html.append("</tbody></table></div></section>\n");
  }

  /**
   * The seats page, read without a script: the integer allocation that fills the chamber, the
   * posterior summaries beside it as separate quantities, and what the approximation leaves out.
   */
  private static void seats(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    final JsonNode labels = bootstrap.get("labels");
    final JsonNode seats = bootstrap.get("data").get("seats");
    final JsonNode rule = seats.get("allocationRule");
    final String level = SiteFormat.level(seats.get("intervalLevel").asDouble());
    html.append("<h1>").append(escape(text.text("seats.title"))).append("</h1>\n");
    html.append("<p class=\"meta\">")
        .append(escape(SiteText.fill(text.text("seats.lead"), "date", fieldwork(bootstrap))))
        .append(" ")
        .append(escape(text.text("notForecast")))
        .append("</p>\n");
    html.append("<p class=\"meta\">")
        .append(
            escape(
                SiteText.fill(
                    text.text("coalitions.majorityLine"),
                    "majority",
                    Integer.toString(
                        bootstrap.get("data").get("coalitions").get("majoritySeats").asInt()))))
        .append("</p>\n");
    html.append("<div class=\"scroll\">\n<table class=\"seats\">\n<caption>")
        .append(escape(SiteText.fill(text.text("seats.caption"), "level", level)))
        .append("</caption>\n<thead><tr>");
    for (final String column :
        List.of(
            "estimate.column.party",
            "seats.column.point",
            "seats.column.mean",
            "seats.column.interval",
            "seats.column.threshold")) {
      html.append("<th scope=\"col\">").append(escape(text.text(column))).append("</th>");
    }
    html.append("</tr></thead>\n<tbody>\n");
    for (final JsonNode party : seats.get("parties")) {
      final String key = party.get("component").asString();
      html.append("<tr><th scope=\"row\">")
          .append(escape(label(labels, key)))
          .append("</th><td class=\"num\">")
          .append(escape(count(party.get("pointSeats"), text)))
          .append("</td><td class=\"num\">")
          .append(escape(decimal(party.get("meanSeats"), language, text)))
          .append("</td><td class=\"num\">")
          .append(escape(interval(party.get("seatInterval"))))
          .append("</td><td class=\"num\">")
          .append(escape(chance(party.get("thresholdProbability"), language, text)))
          .append("</td></tr>\n");
    }
    for (final Map.Entry<String, JsonNode> missing : seats.get("unavailable").properties()) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(label(labels, missing.getKey())))
          .append("</th><td colspan=\"4\">")
          .append(escape(text.text("estimate.unavailable")))
          .append("</td></tr>\n");
    }
    for (final JsonNode excluded : seats.get("excludedFromAllocation")) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(label(labels, excluded.asString())))
          .append("</th><td colspan=\"4\">")
          .append(escape(text.text("estimate.noSeats")))
          .append("</td></tr>\n");
    }
    html.append("</tbody>\n</table>\n</div>\n");
    note(html, text.text("seats.pointVersusMean"));
    note(
        html,
        SiteText.fill(
            text.text("seats.era"), "year", Integer.toString(rule.get("electionYear").asInt())));
    note(html, text.text("seats.threshold"));
    note(html, text.text("seats.other"));
    note(html, seats.get("note").asString());
    note(html, rule.get("tieNote").asString());
    sensitivity(html, seats, text);
    downloads(html, bootstrap, text);
  }

  /**
   * The coalitions page: all ten memberships, and every pair of them compared over the same draws.
   * A label is membership and nothing more, which the page says next to the catalogue.
   */
  private static void coalitionHistory(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode history = bootstrap.path("data").path("coalitionHistory");
    html.append("<section class=\"sec\"><h2>")
        .append(escape(text.text("coalitionHistory.title")))
        .append("</h2>");
    final JsonNode invalid = bootstrap.path("coalitionLinkError");
    if (!invalid.isMissingNode()) {
      html.append("<div role=\"alert\"><p>")
          .append(escape(text.text("coalitionHistory.invalid")))
          .append(" ")
          .append(escape(invalid.get("reason").asString()))
          .append("</p><a href=\"")
          .append(escape(invalid.get("reset").asString()))
          .append("\">")
          .append(escape(text.text("coalitionHistory.reset")))
          .append("</a></div></section>");
      return;
    }
    if (history.isMissingNode()) {
      html.append("<p>")
          .append(escape(text.text("coalitionHistory.unavailable")))
          .append("</p></section>");
      return;
    }
    final String language = bootstrap.get("language").asString();
    html.append("<p>").append(escape(text.text("coalitionHistory.note"))).append("</p>");
    html.append("<p>")
        .append(
            escape(
                SiteText.fill(
                    text.text("coalitionHistory.published"),
                    "date",
                    history.get("publishedAt").asString())))
        .append("</p>");
    html.append("<h3>")
        .append(
            escape(
                SiteText.fill(
                    text.text("coalitionHistory.latest"),
                    "date",
                    history.get("latest").get("date").asString())))
        .append("</h3>");
    for (final String block : List.of("a", "b")) {
      final JsonNode summary = history.get("latest").get(block);
      html.append("<p>").append(escape(text.text("coalitionHistory." + block))).append(": ");
      for (final JsonNode party : history.get("selection").get(block))
        html.append(escape(party.asString())).append(" ");
      html.append(
              escape(
                  coalitionInterval(
                      summary.get("mean"),
                      summary.get("lower"),
                      summary.get("upper"),
                      language,
                      text)))
          .append("</p>");
    }
    final String path = bootstrap.get("route").get("path").asString();
    final String action = path.substring(0, path.indexOf('?'));
    html.append("<form class=\"coalition-range\" method=\"get\" action=\"")
        .append(escape(action))
        .append("\">");
    hidden(html, "a", joined(history.get("selection").get("a")));
    hidden(html, "b", joined(history.get("selection").get("b")));
    coalitionDate(
        html, text, true, LocalDate.parse(history.get("requestedRange").get("from").asString()));
    coalitionDate(
        html, text, false, LocalDate.parse(history.get("requestedRange").get("to").asString()));
    html.append("<button type=\"submit\">")
        .append(escape(text.text("coalitionHistory.apply")))
        .append("</button></form>");
    if (history.get("dates").isEmpty()) {
      html.append("<p>").append(escape(text.text("coalitionHistory.empty"))).append("</p>");
    }
    html.append("<div class=\"scroll\"><table class=\"coalition-history\"><caption>")
        .append(escape(text.text("coalitionHistory.table")))
        .append("</caption><thead><tr><th scope=\"col\">")
        .append(escape(text.text("timeline.column.date")))
        .append("</th>");
    for (final String block : List.of("a", "b"))
      html.append("<th scope=\"col\">")
          .append(escape(text.text("coalitionHistory." + block)))
          .append("</th>");
    html.append("</tr></thead><tbody>");
    for (int index = 0; index < history.get("dates").size(); index++) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(history.get("dates").get(index).asString()))
          .append("</th>");
      for (final String block : List.of("a", "b")) {
        final JsonNode series = history.get("series").get(block);
        html.append("<td>")
            .append(
                escape(
                    coalitionInterval(
                        series.get("mean").get(index),
                        series.get("lower").get(index),
                        series.get("upper").get(index),
                        language,
                        text)))
            .append("</td>");
      }
      html.append("</tr>");
    }
    html.append("</tbody></table></div><p><a href=\"")
        .append(escape(bootstrap.get("coalitionShare").asString()))
        .append("\">")
        .append(escape(text.text("coalitionHistory.share")))
        .append("</a></p></section>");
  }

  private static String joined(JsonNode parties) {
    final List<String> values = new ArrayList<>();
    parties.forEach(party -> values.add(party.asString()));
    return String.join(",", values);
  }

  private static void coalitionDate(
      StringBuilder html, SiteText text, boolean from, LocalDate value) {
    final String field = from ? "from" : "to";
    html.append("<label>")
        .append(escape(text.text("coalitionHistory." + field)))
        .append("<input type=\"date\" name=\"")
        .append(field)
        .append("\" min=\"0001-01-01\" max=\"9999-12-31\" value=\"")
        .append(value)
        .append("\" required></label>");
  }

  private static String coalitionInterval(
      JsonNode mean, JsonNode lower, JsonNode upper, String language, SiteText text) {
    if (!mean.isNumber() || !lower.isNumber() || !upper.isNumber())
      return text.text("estimate.unavailable");
    return text.text("coalitionHistory.interval")
        .replace("{mean}", SiteFormat.decimal(mean.asDouble(), language))
        .replace("{lower}", SiteFormat.decimal(lower.asDouble(), language))
        .replace("{upper}", SiteFormat.decimal(upper.asDouble(), language));
  }

  private static void coalitions(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    final JsonNode labels = bootstrap.get("labels");
    final JsonNode coalitions = bootstrap.get("data").get("coalitions");
    html.append("<h1>").append(escape(text.text("coalitions.title"))).append("</h1>\n");
    html.append("<p class=\"meta\">")
        .append(escape(SiteText.fill(text.text("coalitions.lead"), "date", fieldwork(bootstrap))))
        .append(" ")
        .append(escape(text.text("notForecast")))
        .append("</p>\n");
    html.append("<p class=\"meta\">")
        .append(
            escape(
                SiteText.fill(
                    text.text("coalitions.majorityLine"),
                    "majority",
                    Integer.toString(coalitions.get("majoritySeats").asInt()))))
        .append("</p>\n");
    coalitionHistory(html, bootstrap, text);
    html.append("<div class=\"scroll\">\n<table class=\"coalitions\">\n<caption>")
        .append(escape(text.text("coalitions.caption")))
        .append("</caption>\n<thead><tr>");
    for (final String column :
        List.of(
            "estimate.column.party",
            "coalitions.column.parties",
            "seats.column.point",
            "seats.column.mean",
            "seats.column.interval",
            "coalitions.column.majority")) {
      html.append("<th scope=\"col\">").append(escape(text.text(column))).append("</th>");
    }
    html.append("</tr></thead>\n<tbody>\n");
    for (final JsonNode coalition : coalitions.get("coalitions")) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(labels.get("coalition." + coalition.get("id").asString()).asString()))
          .append("</th><td>")
          .append(escape(members(labels, coalition.get("parties"))))
          .append("</td><td class=\"num\">")
          .append(escape(Integer.toString(coalition.get("pointSeats").asInt())))
          .append("</td><td class=\"num\">")
          .append(escape(SiteFormat.decimal(coalition.get("meanSeats").asDouble(), language)))
          .append("</td><td class=\"num\">")
          .append(escape(interval(coalition.get("seatInterval"))))
          .append("</td><td class=\"num\">")
          .append(
              escape(
                  SiteFormat.probability(
                      coalition.get("majorityProbability").asDouble(), language)))
          .append("</td></tr>\n");
    }
    html.append("</tbody>\n</table>\n</div>\n");
    note(html, coalitions.get("note").asString());
    sensitivity(html, coalitions, text);
    pairwise(html, bootstrap, text, coalitions);
    downloads(html, bootstrap, text);
  }

  /** Every ordered question about two coalitions, answered from one set of draws. */
  private static void pairwise(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode coalitions) {
    final String language = bootstrap.get("language").asString();
    final JsonNode labels = bootstrap.get("labels");
    html.append("<h2>").append(escape(text.text("pairwise.title"))).append("</h2>\n");
    html.append("<div class=\"scroll\">\n<table class=\"pairwise\">\n<caption>")
        .append(escape(text.text("pairwise.caption")))
        .append("</caption>\n<thead><tr>");
    for (final String column :
        List.of(
            "pairwise.column.left",
            "pairwise.column.right",
            "pairwise.column.leftLeads",
            "pairwise.column.rightLeads",
            "pairwise.tied")) {
      html.append("<th scope=\"col\">").append(escape(text.text(column))).append("</th>");
    }
    html.append("</tr></thead>\n<tbody>\n");
    for (final JsonNode pair : coalitions.get("comparison").get("pairs")) {
      html.append("<tr class=\"pair\"><th scope=\"row\">")
          .append(escape(labels.get("coalition." + pair.get("left").asString()).asString()))
          .append("</th><td>")
          .append(escape(labels.get("coalition." + pair.get("right").asString()).asString()))
          .append("</td><td class=\"num\">")
          .append(escape(SiteFormat.probability(pair.get("leftLeads").asDouble(), language)))
          .append("</td><td class=\"num\">")
          .append(escape(SiteFormat.probability(pair.get("rightLeads").asDouble(), language)))
          .append("</td><td class=\"num\">")
          .append(escape(SiteFormat.probability(pair.get("tied").asDouble(), language)))
          .append("</td></tr>\n");
    }
    html.append("</tbody>\n</table>\n</div>\n");
    note(html, text.text("pairwise.tieNote"));
  }

  /** A published sensitivity movement, next to the numbers it moves rather than in a footer. */
  private static void sensitivity(StringBuilder html, JsonNode document, SiteText text) {
    final JsonNode published = document.get("sensitivity");
    if (published == null || published.isNull()) {
      return;
    }
    note(html, SiteText.fill(text.text("sensitivity.note"), "note", published.asString()));
  }

  private static void note(StringBuilder html, String body) {
    html.append("<p class=\"meta\">").append(escape(body)).append("</p>\n");
  }

  /** A seat interval, as the two integers the publication carries. */
  private static String interval(JsonNode bounds) {
    if (bounds == null || bounds.isNull()) {
      return "";
    }
    return bounds.get(0).asInt() + "–" + bounds.get(1).asInt();
  }

  // A published value a page has to show. Null means the publication has no number for it, which
  // is not zero and not a certainty: it reads as unavailable, the way the mounted page reads it.

  private static String count(JsonNode value, SiteText text) {
    return absent(value) ? text.text("estimate.unavailable") : Integer.toString(value.asInt());
  }

  private static String decimal(JsonNode value, String language, SiteText text) {
    return absent(value)
        ? text.text("estimate.unavailable")
        : SiteFormat.decimal(value.asDouble(), language);
  }

  private static String chance(JsonNode value, String language, SiteText text) {
    return absent(value)
        ? text.text("estimate.unavailable")
        : SiteFormat.probability(value.asDouble(), language);
  }

  private static boolean absent(JsonNode value) {
    return value == null || value.isNull();
  }

  /** The parties a coalition counts, named rather than left as keys. */
  private static String members(JsonNode labels, JsonNode parties) {
    final List<String> named = new ArrayList<>();
    for (final JsonNode party : parties) {
      named.add(label(labels, party.asString()));
    }
    return String.join(", ", named);
  }

  private static String label(JsonNode labels, String key) {
    final JsonNode label = labels.get(key);
    return label == null ? key : label.asString();
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
      html.append("<tr><th scope=\"row\">");
      if (SiteRoutes.PARTIES.contains(key)) {
        html.append("<a href=\"")
            .append(
                escape(
                    SiteRoutes.path(
                        SiteRoutes.Family.PARTY, bootstrap.get("language").asString(), key)))
            .append("\">")
            .append(escape(labels.get(key).asString()))
            .append("</a>");
      } else {
        html.append(escape(labels.get(key).asString()));
      }
      html.append("</th>");
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

  private static void party(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode party = bootstrap.get("data").get("party");
    final String name = partyName(bootstrap);
    html.append("<h1>").append(escape(name)).append("</h1>\n");
    html.append("<p class=\"meta\">").append(escape(text.text("notForecast"))).append("</p>\n");
    partyEstimate(html, bootstrap, text, party, name);
    partyObservations(html, bootstrap, text, party, name);
    partyHouseEffects(html, bootstrap, text, party);
    partyDownloads(html, bootstrap, text, party.get("component").asString());
  }

  private static void partyEstimate(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode party, String name) {
    final String language = bootstrap.get("language").asString();
    final JsonNode estimate = party.get("estimate");
    html.append("<h2>").append(escape(text.text("estimate.title"))).append("</h2>\n");
    html.append("<table class=\"party-estimate\"><thead><tr>");
    for (final String column :
        List.of(
            "estimate.column.estimate",
            "estimate.column.interval",
            "party.threshold",
            "estimate.column.seats")) {
      html.append("<th scope=\"col\">").append(escape(text.text(column))).append("</th>");
    }
    html.append("</tr></thead><tbody><tr>");
    if (estimate.get("mean").isNull()) {
      html.append("<td colspan=\"4\">")
          .append(escape(SiteText.fill(text.text("party.noCurrent"), "party", name)))
          .append("</td>");
    } else {
      html.append("<td class=\"num\">")
          .append(
              escape(
                  SiteFormat.percent(
                      SiteFormat.decimal(estimate.get("mean").asDouble(), language), language)))
          .append("</td><td>")
          .append(escape(verbal(text, estimate, language)))
          .append("</td><td class=\"num\">")
          .append(
              escape(
                  party.get("thresholdProbability").isNull()
                      ? text.text("estimate.unavailable")
                      : SiteFormat.probability(
                          party.get("thresholdProbability").asDouble(), language)))
          .append("</td><td class=\"num\">")
          .append(
              escape(
                  party.get("pointSeats").isNull()
                      ? text.text("estimate.unavailable")
                      : Integer.toString(party.get("pointSeats").asInt())))
          .append("</td>");
    }
    html.append("</tr></tbody></table>\n");
  }

  private static void partyObservations(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode party, String name) {
    final String language = bootstrap.get("language").asString();
    html.append("<h2>").append(escape(text.text("party.observations"))).append("</h2>\n");
    html.append("<table class=\"party-observations\"><caption>")
        .append(escape(SiteText.fill(text.text("party.observationsCaption"), "party", name)))
        .append("</caption><thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.institute")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("polls.column.fieldwork")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("party.column.support")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("party.column.eligibility")))
        .append("</th></tr></thead><tbody>");
    for (final JsonNode observation : party.get("observations")) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(observation.get("institute").asString()))
          .append("</th><td>")
          .append(escape(fieldwork(observation, language, text)))
          .append("</td><td class=\"num\">")
          .append(
              escape(
                  SiteFormat.percent(
                      SiteFormat.decimal(observation.get("share").asDouble(), language), language)))
          .append("</td><td>")
          .append(
              escape(
                  observation.get("modeled").asBoolean()
                      ? text.text("party.eligible")
                      : text.text("party.excluded")))
          .append("</td></tr>\n");
    }
    html.append("</tbody></table>\n");
  }

  private static String fieldwork(JsonNode observation, String language, SiteText text) {
    final JsonNode from = observation.get("collectionFrom");
    final JsonNode to = observation.get("collectionTo");
    if ((from == null || from.isNull()) && (to == null || to.isNull())) {
      return text.text("polls.missing");
    }
    final String first =
        from == null || from.isNull()
            ? null
            : SiteFormat.date(LocalDate.parse(from.asString()), language);
    final String last =
        to == null || to.isNull()
            ? null
            : SiteFormat.date(LocalDate.parse(to.asString()), language);
    final String span =
        first == null ? last : last == null || first.equals(last) ? first : first + " - " + last;
    final JsonNode approximate = observation.get("approximatePeriod");
    return approximate != null && approximate.asBoolean()
        ? span + " (" + text.text("polls.approximate") + ")"
        : span;
  }

  private static void partyHouseEffects(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode party) {
    final String language = bootstrap.get("language").asString();
    final JsonNode house = party.get("houseEffects");
    html.append("<h2>").append(escape(text.text("party.houseEffects"))).append("</h2>\n");
    html.append("<p>").append(escape(text.text("party.houseReference"))).append("</p>\n");
    if (house.get("effects").isEmpty()) {
      html.append("<p>").append(escape(text.text("party.houseUnavailable"))).append("</p>\n");
      return;
    }
    html.append("<table class=\"house-effects\"><thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.institute")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("party.houseEffect")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("party.houseInterval")))
        .append("</th></tr></thead><tbody>");
    for (final JsonNode effect : house.get("effects")) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(effect.get("institute").asString()))
          .append("</th><td class=\"num\">")
          .append(escape(SiteFormat.decimal(effect.get("mean").asDouble(), language)))
          .append("</td><td>")
          .append(
              escape(
                  SiteText.fill(
                      SiteText.fill(
                          text.text("party.effectRange"),
                          "lower",
                          SiteFormat.decimal(effect.get("lower").asDouble(), language)),
                      "upper",
                      SiteFormat.decimal(effect.get("upper").asDouble(), language))))
          .append("</td></tr>\n");
    }
    html.append("</tbody></table>\n<p class=\"meta\">")
        .append(escape(house.get("reference").asString()))
        .append("</p>\n");
  }

  private static void partyDownloads(
      StringBuilder html, ObjectNode bootstrap, SiteText text, String component) {
    final String publication = bootstrap.get("api").get("publication").asString();
    final String language = bootstrap.get("language").asString();
    final String pin = "?publication=" + publication + "&language=" + language;
    html.append("<h2>")
        .append(escape(text.text("downloads.title")))
        .append("</h2>\n<ul class=\"downloads\">\n");
    link(html, pollDownload(bootstrap, component), text.text("downloads.polls"));
    link(html, "/api/v1/estimates/history" + pin, text.text("downloads.estimates"));
    link(html, "/api/v1/seats" + pin, text.text("downloads.seats"));
    link(html, "/api/v1/institutes" + pin, text.text("downloads.houseEffects"));
    link(html, "/api/v1/elections" + pin, text.text("downloads.elections"));
    final String image = shareImage(bootstrap);
    if (image != null) {
      link(html, image, text.text("downloads.image"));
    }
    html.append("</ul>\n<p class=\"meta\">")
        .append(escape(SiteText.fill(text.text("downloads.pinned"), "publication", publication)))
        .append("</p>\n");
  }

  // The poll table: one filtered page of archived source observations, and its matching download.

  /**
   * The browsable poll table, read without a script.
   *
   * <p>The filter is a plain form and the paging is plain links, so the filtered view a reader
   * shares is a whole page on its own. Every one of those links carries the publication this page
   * already resolved, which is what keeps the next page, the filtered table and the CSV on one
   * snapshot even when a corrected one publishes mid-visit.
   */
  private static void polls(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode table = bootstrap.get("pollTable");
    final boolean source = table.get("source").asBoolean();
    html.append("<h1>")
        .append(escape(text.text(source ? "source.polls.title" : "head.title.polls")))
        .append("</h1>\n");
    html.append("<p class=\"meta\">")
        .append(escape(text.text(source ? "source.polls.lead" : "polls.lead")))
        .append("</p>\n");
    if (source) {
      sourceUpdated(html, bootstrap, text);
    }
    pollFilters(html, bootstrap, text, table);
    pollNotices(html, text, table, source);
    if (!table.get("polls").isEmpty()) {
      pollRows(html, bootstrap, text, table);
      pollPaging(html, bootstrap, text, table);
    }
    pollDownloads(html, bootstrap, text, table);
  }

  /** The filter form. It submits to this page's own path, so no script is needed to apply it. */
  private static void pollFilters(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode table) {
    final String path = bootstrap.get("route").get("path").asString();
    final boolean source = table.get("source").asBoolean();
    final String pinName = source ? "snapshot" : "publication";
    final String pin =
        source
            ? bootstrap.get("source").get("snapshotId").asString()
            : bootstrap.get("api").get("publication").asString();
    final String clear = path + "?" + pinName + "=" + pin;
    final JsonNode filters = table.get("filters");
    final JsonNode options = table.get("options");
    html.append("<form class=\"filters\" method=\"get\" action=\"")
        .append(escape(path))
        .append("\">\n<fieldset>\n<legend>")
        .append(escape(text.text("polls.filters")))
        .append("</legend>\n");
    hidden(html, pinName, pin);
    dateFilter(html, text, "from", "polls.filter.from", filters.get("from"));
    dateFilter(html, text, "to", "polls.filter.to", filters.get("to"));
    selectFilter(
        html,
        text,
        "institute",
        "polls.filter.institute",
        "polls.filter.anyInstitute",
        options.get("institutes"),
        first(filters.get("institute")));
    if (!source) {
      selectFilter(
          html,
          text,
          "party",
          "polls.filter.party",
          "polls.filter.allParties",
          options.get("parties"),
          first(filters.get("party")),
          bootstrap.get("labels"));
      periodFilter(html, text, options.get("coveragePeriods"), filters.get("coveragePeriod"));
    }
    html.append("<p class=\"check\"><label for=\"polls-includeExcluded\">")
        .append(
            "<input type=\"checkbox\" id=\"polls-includeExcluded\" name=\"includeExcluded\""
                + " value=\"true\"")
        .append(filters.get("includeExcluded").asBoolean() ? " checked" : "")
        .append("> ")
        .append(
            escape(
                text.text(
                    source
                        ? "source.polls.filter.includeExcluded"
                        : "polls.filter.includeExcluded")))
        .append("</label></p>\n");
    html.append("<p class=\"actions\"><button class=\"btn primary\" type=\"submit\">")
        .append(escape(text.text("polls.filter.apply")))
        .append("</button> <a class=\"btn\" href=\"")
        .append(escape(clear))
        .append("\">")
        .append(escape(text.text("polls.filter.clear")))
        .append("</a></p>\n<p class=\"footnote\">")
        .append(escape(text.text("polls.filter.dateHint")))
        .append("</p>\n</fieldset>\n</form>\n");
  }

  private static void hidden(StringBuilder html, String name, String value) {
    html.append("<input type=\"hidden\" name=\"")
        .append(escape(name))
        .append("\" value=\"")
        .append(escape(value))
        .append("\">\n");
  }

  private static void dateFilter(
      StringBuilder html, SiteText text, String name, String label, JsonNode value) {
    html.append("<p><label for=\"polls-")
        .append(escape(name))
        .append("\">")
        .append(escape(text.text(label)))
        .append("</label> <input type=\"date\" id=\"polls-")
        .append(escape(name))
        .append("\" name=\"")
        .append(escape(name))
        .append("\" value=\"")
        .append(value.isNull() ? "" : escape(value.asString()))
        .append("\"></p>\n");
  }

  private static void selectFilter(
      StringBuilder html,
      SiteText text,
      String name,
      String label,
      String anyLabel,
      JsonNode values,
      String selected) {
    selectFilter(html, text, name, label, anyLabel, values, selected, null);
  }

  private static void selectFilter(
      StringBuilder html,
      SiteText text,
      String name,
      String label,
      String anyLabel,
      JsonNode values,
      String selected,
      JsonNode labels) {
    html.append("<p><label for=\"polls-")
        .append(escape(name))
        .append("\">")
        .append(escape(text.text(label)))
        .append("</label> <select id=\"polls-")
        .append(escape(name))
        .append("\" name=\"")
        .append(escape(name))
        .append("\">\n");
    option(html, "", text.text(anyLabel), selected == null);
    for (final JsonNode value : values) {
      final String key = value.asString();
      final JsonNode named = labels == null ? null : labels.get(key);
      option(html, key, named == null ? key : named.asString(), key.equals(selected));
    }
    html.append("</select></p>\n");
  }

  private static void periodFilter(
      StringBuilder html, SiteText text, JsonNode periods, JsonNode selected) {
    html.append("<p><label for=\"polls-coveragePeriod\">")
        .append(escape(text.text("polls.filter.coveragePeriod")))
        .append("</label> <select id=\"polls-coveragePeriod\" name=\"coveragePeriod\">\n");
    option(html, "", text.text("polls.filter.anyPeriod"), selected.isNull());
    for (final JsonNode period : periods) {
      final String id = period.get("id").asString();
      option(html, id, id, !selected.isNull() && id.equals(selected.asString()));
    }
    html.append("</select></p>\n");
  }

  private static void option(StringBuilder html, String value, String label, boolean selected) {
    html.append("<option value=\"")
        .append(escape(value))
        .append("\"")
        .append(selected ? " selected" : "")
        .append(">")
        .append(escape(label))
        .append("</option>\n");
  }

  /** The first value of a declared list filter, or null when the filter is not applied. */
  private static String first(JsonNode values) {
    return values.isEmpty() ? null : values.get(0).asString();
  }

  /** What the page has to say before the table: a rejected filter, or nothing matching. */
  private static void pollNotices(
      StringBuilder html, SiteText text, JsonNode table, boolean source) {
    final JsonNode invalid = table.get("invalid");
    if (!invalid.isEmpty()) {
      final List<String> names = new ArrayList<>();
      for (final JsonNode rejected : invalid) {
        names.add(rejected.get("name").asString());
      }
      html.append("<p class=\"notice\" role=\"alert\">")
          .append(
              escape(SiteText.fill(text.text("polls.invalid"), "names", String.join(", ", names))))
          .append("</p>\n");
    }
    if (table.get("polls").isEmpty()) {
      html.append("<p class=\"notice\" role=\"status\">")
          .append(escape(text.text(source ? "source.polls.empty" : "polls.empty")))
          .append("</p>\n");
    }
  }

  /** One page of archived rows, at the precision the source published them. */
  private static void pollRows(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode table) {
    final String language = bootstrap.get("language").asString();
    final JsonNode filters = table.get("filters");
    final boolean eligibility = filters.get("includeExcluded").asBoolean();
    final boolean source = table.get("source").asBoolean();
    final List<String> components = new ArrayList<>();
    for (final JsonNode component : table.get("columns")) {
      components.add(component.asString());
    }
    final String mark = text.text("polls.unmodeledMark");
    html.append("<div class=\"scroll\">\n<table class=\"polls\">\n<caption>")
        .append(escape(pollCaption(text, table)))
        .append("</caption><thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.institute")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("polls.column.method")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("polls.column.fieldwork")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("polls.column.published")))
        .append("</th><th scope=\"col\" class=\"num\">")
        .append(escape(text.text("polls.column.sample")))
        .append("</th>");
    for (final String component : components) {
      html.append("<th scope=\"col\" class=\"num\" title=\"")
          .append(escape(table.get("labels").get(component).asString()))
          .append("\">")
          .append(escape(component))
          .append("</th>");
    }
    html.append("<th scope=\"col\" class=\"num\">")
        .append(escape(text.text(source ? "source.polls.column.other" : "polls.column.other")))
        .append("</th>");
    if (!source) {
      html.append("<th scope=\"col\">")
          .append(escape(text.text("polls.column.period")))
          .append("</th>");
    }
    if (eligibility) {
      html.append("<th scope=\"col\">")
          .append(
              escape(
                  text.text(
                      source ? "source.polls.column.eligibility" : "polls.column.eligibility")))
          .append("</th>");
    }
    html.append("</tr></thead><tbody>\n");
    boolean unmodeled = false;
    for (final JsonNode poll : table.get("polls")) {
      // The bootstrap row carries which components sit outside its coverage period's roster; the
      // classification is made once beside the rosters, not re-derived per rendering.
      final Set<String> outside = new HashSet<>();
      for (final JsonNode component : poll.get("unmodeled")) {
        outside.add(component.asString());
      }
      unmodeled = unmodeled || !outside.isEmpty();
      html.append("<tr><th scope=\"row\">")
          .append(escape(sourceIdentity(poll, text)))
          .append("</th><td>");
      final JsonNode methodEvidence = poll.get("methodEvidence");
      if (methodEvidence == null
          || methodEvidence.isNull()
          || methodEvidence.asString().isBlank()) {
        html.append(escape(method(poll, text)));
      } else {
        html.append("<a href=\"")
            .append(escape(methodEvidence.asString()))
            .append("\">")
            .append(escape(method(poll, text)))
            .append("</a>");
      }
      html.append("</td><td>")
          .append(escape(fieldwork(poll, language, text)))
          .append("</td><td>")
          .append(escape(published(poll, language, text)))
          .append("</td><td class=\"num\">");
      final String sample = escape(sampleSize(poll.get("sampleSize"), language, text));
      final JsonNode denominator = poll.get("denominatorNote");
      if (denominator == null || denominator.isNull() || denominator.asString().isBlank()) {
        html.append(sample);
      } else {
        html.append("<abbr title=\"")
            .append(escape(denominator.asString()))
            .append("\">")
            .append(sample)
            .append("</abbr>");
      }
      html.append("</td>");
      for (final String component : components) {
        final JsonNode share = poll.get("shares").get(component);
        final boolean flagged = outside.contains(component);
        html.append("<td class=\"num\">")
            .append(escape(sourceShare(share, language, text)))
            .append(flagged && !source ? escape(mark) : "")
            .append("</td>");
      }
      html.append("<td class=\"num\">")
          .append(escape(sourceShare(poll.get("other"), language, text)))
          .append("</td>");
      if (!source) {
        html.append("<td>")
            .append(
                escape(
                    poll.get("coveragePeriod").isNull()
                        ? text.text("polls.noPeriod")
                        : poll.get("coveragePeriod").asString()))
            .append("</td>");
      }
      if (eligibility) {
        html.append("<td>").append(escape(eligibility(poll, text, source))).append("</td>");
      }
      html.append("</tr>\n");
    }
    html.append("</tbody></table>\n</div>\n");
    html.append("<p class=\"footnote\">")
        .append(escape(text.text("polls.sourceNote")))
        .append("</p>\n");
    if (unmodeled && !source) {
      html.append("<p class=\"footnote\">")
          .append(escape(text.text("polls.unmodeled")))
          .append("</p>\n");
    }
  }

  /**
   * An archived share, written with the digits the source published rather than rounded to the
   * estimate's display precision. A share the source never reported reads as missing, never as
   * zero.
   *
   * <p>The bootstrap carries the same archived digits separately so React can preserve the scale
   * after hydration; the numeric value remains available for classification.
   */
  private static String sourceShare(JsonNode value, String language, SiteText text) {
    if (value == null || value.isNull()) {
      return text.text("polls.missing");
    }
    final String plain = value.decimalValue().toPlainString();
    return Translations.SWEDISH.equals(language) ? plain.replace('.', ',') : plain;
  }

  /** A sample size the source recorded, or the missing marker where it recorded none. */
  private static String sampleSize(JsonNode value, String language, SiteText text) {
    return value == null || value.isNull()
        ? text.text("polls.missing")
        : SiteFormat.count(value.asInt(), language);
  }

  /**
   * Who published the poll: the institute, and the company behind it when the two differ. A brand
   * rename is not a method change, so the page names both rather than collapsing them.
   */
  private static String sourceIdentity(JsonNode poll, SiteText text) {
    final JsonNode institute = poll.get("institute");
    final JsonNode company = poll.get("company");
    if (institute.isNull()) {
      return text.text("polls.missing");
    }
    if (company.isNull() || company.asString().equals(institute.asString())) {
      return institute.asString();
    }
    return institute.asString() + " (" + company.asString() + ")";
  }

  /** The documented method era of the series, with the survey type the source recorded. */
  private static String method(JsonNode poll, SiteText text) {
    final List<String> parts = new ArrayList<>();
    for (final String field : List.of("methodEra", "surveyType")) {
      final JsonNode value = poll.get(field);
      if (value != null && !value.isNull() && !value.asString().isBlank()) {
        parts.add(value.asString());
      }
    }
    return parts.isEmpty() ? text.text("polls.missing") : String.join(", ", parts);
  }

  /** The day the institute published the poll, which is not the day it collected it. */
  private static String published(JsonNode poll, String language, SiteText text) {
    final JsonNode value = poll.get("publicationDate");
    return value == null || value.isNull()
        ? text.text("polls.missing")
        : SiteFormat.date(LocalDate.parse(value.asString()), language);
  }

  private static String pollCaption(SiteText text, JsonNode table) {
    final int total = table.get("total").asInt();
    final int pageSize = table.get("pageSize").asInt();
    final int page = table.get("page").asInt();
    final int first = (page - 1) * pageSize + 1;
    final int last = first + table.get("polls").size() - 1;
    String caption = text.text("polls.tableCaption");
    caption = SiteText.fill(caption, "first", Integer.toString(first));
    caption = SiteText.fill(caption, "last", Integer.toString(last));
    caption = SiteText.fill(caption, "total", Integer.toString(total));
    caption = SiteText.fill(caption, "page", Integer.toString(page));
    return SiteText.fill(caption, "pages", table.get("pages").asString());
  }

  /** Why a row is or is not one of the polls the estimate was fitted to. */
  private static String eligibility(JsonNode poll, SiteText text, boolean source) {
    final String prefix = source ? "source." : "";
    if (poll.get("eligible").asBoolean()) {
      return text.text(prefix + "polls.eligible");
    }
    final List<String> reasons = new ArrayList<>();
    for (final JsonNode reason : poll.get("exclusionReasons")) {
      reasons.add(reason.asString());
    }
    return SiteText.fill(
        text.text(prefix + "polls.excluded"), "reasons", String.join(", ", reasons));
  }

  /** Plain links, so paging works with no script and every page keeps the filter and the pin. */
  private static void pollPaging(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode table) {
    final int page = table.get("page").asInt();
    final int pages = table.get("pages").asInt();
    if (pages <= 1) {
      return;
    }
    html.append("<nav class=\"paging\" aria-label=\"")
        .append(escape(text.text("head.title.polls")))
        .append("\">\n");
    if (page > 1) {
      link(html, pollPageLink(bootstrap, table, page - 1), text.text("polls.previous"));
    }
    html.append("<span class=\"meta\">")
        .append(
            escape(
                SiteText.fill(
                    SiteText.fill(text.text("polls.pageOf"), "page", Integer.toString(page)),
                    "pages",
                    Integer.toString(pages))))
        .append("</span>\n");
    if (page < pages) {
      link(html, pollPageLink(bootstrap, table, page + 1), text.text("polls.next"));
    }
    html.append("</nav>\n");
  }

  /** This page's own path with the declared filters and one page number on it. */
  private static String pollPageLink(ObjectNode bootstrap, JsonNode table, int page) {
    final List<String> query = new ArrayList<>();
    if (table.get("source").asBoolean()) {
      query.add("snapshot=" + bootstrap.get("source").get("snapshotId").asString());
    } else {
      query.add("publication=" + bootstrap.get("api").get("publication").asString());
    }
    final String declared = table.get("query").asString();
    if (!declared.isEmpty()) {
      query.add(declared);
    }
    query.add("page=" + page);
    return bootstrap.get("route").get("path").asString() + "?" + String.join("&", query);
  }

  /** The download of exactly these rows, from exactly this publication. */
  private static void pollDownloads(
      StringBuilder html, ObjectNode bootstrap, SiteText text, JsonNode table) {
    final boolean source = table.get("source").asBoolean();
    final String language = bootstrap.get("language").asString();
    html.append("<h2>")
        .append(escape(text.text("downloads.title")))
        .append("</h2>\n<ul class=\"downloads\">\n");
    link(html, table.get("csv").asString() + "&language=" + language, text.text("polls.download"));
    if (!source) {
      final String publication = bootstrap.get("api").get("publication").asString();
      link(
          html,
          "/api/v1/estimates/latest?publication=" + publication + "&language=" + language,
          text.text("downloads.estimates"));
    }
    html.append("</ul>\n<p class=\"footnote\">")
        .append(escape(text.text(source ? "source.polls.downloadNote" : "polls.downloadNote")))
        .append("</p>\n");
    if (source) {
      html.append("<p class=\"meta\">")
          .append(
              escape(
                  SiteText.fill(
                      text.text("source.polls.snapshot"),
                      "snapshot",
                      bootstrap.get("source").get("snapshotId").asString())))
          .append("</p>\n");
    } else {
      final String publication = bootstrap.get("api").get("publication").asString();
      html.append("<p class=\"meta\">")
          .append(escape(SiteText.fill(text.text("downloads.pinned"), "publication", publication)))
          .append("</p>\n");
    }
  }

  // The pollsters page: institutes, their method eras and their house effects per cycle.

  /**
   * The pollsters page, read without a script: who the institutes are, how they measure, and one
   * heat table per election cycle beside its own table alternative. Both renderings read the same
   * cells, so a number shown in colour and the same number shown as a row cannot disagree.
   */
  private static void pollsters(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode pollsters = bootstrap.get("data").get("pollsters");
    html.append("<h1>").append(escape(text.text("head.title.pollsters"))).append("</h1>\n");
    html.append("<p class=\"meta\">").append(escape(text.text("pollsters.lead"))).append("</p>\n");
    html.append("<p class=\"meta\">").append(escape(text.text("notForecast"))).append("</p>\n");
    instituteTable(html, text, pollsters);
    effects(html, bootstrap, text);
    pollstersDownloads(html, bootstrap, text);
  }

  /** The house-effect download and the summary image, pinned to this publication. */
  private static void pollstersDownloads(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String publication = bootstrap.get("api").get("publication").asString();
    final String language = bootstrap.get("language").asString();
    final String pin = "?publication=" + publication + "&language=" + language;
    html.append("<h2>")
        .append(escape(text.text("downloads.title")))
        .append("</h2>\n<ul class=\"downloads\">\n");
    link(html, "/api/v1/institutes" + pin, text.text("downloads.houseEffects"));
    final String image = shareImage(bootstrap);
    if (image != null) {
      link(html, image, text.text("downloads.image"));
    }
    html.append("</ul>\n<p class=\"meta\">")
        .append(escape(SiteText.fill(text.text("downloads.pinned"), "publication", publication)))
        .append("</p>\n");
  }

  /** Who each institute is: the companies behind it, its eras and its archived footprint. */
  private static void instituteTable(StringBuilder html, SiteText text, JsonNode pollsters) {
    html.append("<h2>").append(escape(text.text("pollsters.metadata"))).append("</h2>\n");
    html.append("<div class=\"scroll\">\n<table class=\"institutes\"><thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.institute")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("pollsters.column.companies")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("pollsters.column.eras")))
        .append("</th><th scope=\"col\" class=\"num\">")
        .append(escape(text.text("pollsters.column.polls")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("pollsters.column.first")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("pollsters.column.last")))
        .append("</th></tr></thead><tbody>\n");
    for (final JsonNode institute : pollsters.get("institutes")) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(institute.get("institute").asString()))
          .append("</th><td>")
          .append(escape(String.join(", ", strings(institute.get("companies")))))
          .append("</td><td>");
      eras(html, institute.get("methodEras"));
      html.append("</td><td class=\"num\">")
          .append(escape(Integer.toString(institute.get("polls").asInt())))
          .append("</td><td>")
          .append(escape(span(institute.get("firstCollection"), text)))
          .append("</td><td>")
          .append(escape(span(institute.get("lastCollection"), text)))
          .append("</td></tr>\n");
    }
    html.append("</tbody></table>\n</div>\n");
    html.append("<p class=\"footnote\">")
        .append(escape(text.text("pollsters.erasNote")))
        .append("</p>\n");
  }

  /** The documented eras of one series, each linked to its evidence when the source carries it. */
  private static void eras(StringBuilder html, JsonNode methodEras) {
    String separator = "";
    for (final JsonNode era : methodEras) {
      final String id = era.get("id").asString();
      final JsonNode evidence = era.get("evidence");
      html.append(separator);
      if (evidence == null || evidence.isNull() || evidence.asString().isBlank()) {
        html.append(escape(id));
      } else {
        html.append("<a href=\"")
            .append(escape(evidence.asString()))
            .append("\">")
            .append(escape(id))
            .append("</a>");
      }
      separator = ", ";
    }
  }

  private static List<String> strings(JsonNode values) {
    final List<String> strings = new ArrayList<>();
    for (final JsonNode value : values) {
      strings.add(value.asString());
    }
    return strings;
  }

  /** A collection date the archive holds, or the missing marker where it holds none. */
  private static String span(JsonNode value, SiteText text) {
    return value == null || value.isNull() ? text.text("polls.missing") : value.asString();
  }

  /** One heat table and one table alternative per election cycle, then the shared reference. */
  private static void effects(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    final JsonNode pollsters = bootstrap.get("data").get("pollsters");
    html.append("<h2>").append(escape(text.text("party.houseEffects"))).append("</h2>\n");
    final JsonNode matrices = pollsters.get("matrices");
    if (matrices.isEmpty()) {
      html.append("<p>").append(escape(text.text("party.houseUnavailable"))).append("</p>\n");
      return;
    }
    boolean shrunk = false;
    for (final JsonNode matrix : matrices) {
      shrunk = cycleGrid(html, language, text, pollsters, matrix) || shrunk;
      shrunk = cycleTable(html, language, text, bootstrap.get("labels"), matrix) || shrunk;
    }
    html.append("<p class=\"footnote\">")
        .append(escape(pollsters.get("reference").asString()))
        .append("</p>\n");
    if (shrunk) {
      html.append("<p class=\"footnote\">")
          .append(escape(text.text("pollsters.shrunkNote")))
          .append("</p>\n");
    }
  }

  /** The heat table of one cycle: institute rows, party columns, the number in the cell. */
  private static boolean cycleGrid(
      StringBuilder html, String language, SiteText text, JsonNode pollsters, JsonNode matrix) {
    final JsonNode components = pollsters.get("components");
    html.append("<div class=\"scroll\">\n<table class=\"heat-grid\">\n<caption>")
        .append(
            escape(
                SiteText.fill(
                    text.text("pollsters.gridCaption"), "cycle", matrix.get("cycle").asString())))
        .append("</caption>\n<thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.institute")))
        .append("</th>");
    for (final JsonNode component : components) {
      html.append("<th scope=\"col\" class=\"num\">")
          .append(escape(component.asString()))
          .append("</th>");
    }
    html.append("</tr></thead>\n<tbody>\n");
    boolean shrunk = false;
    for (final JsonNode row : matrix.get("rows")) {
      html.append("<tr><th scope=\"row\">")
          .append(escape(row.get("institute").asString()))
          .append("</th>");
      final Map<String, JsonNode> cells = cellsByComponent(row.get("cells"));
      for (final JsonNode component : components) {
        final JsonNode cell = cells.get(component.asString());
        if (cell == null) {
          html.append("<td class=\"num\"></td>");
          continue;
        }
        shrunk = shrunk || cell.get("shrunk").asBoolean();
        html.append("<td class=\"num heat ")
            .append(escape(cell.get("heat").asString()))
            .append("\" title=\"")
            .append(
                escape(
                    SiteText.fill(
                        SiteText.fill(
                            text.text("party.effectRange"),
                            "lower",
                            SiteFormat.decimal(cell.get("lower").asDouble(), language)),
                        "upper",
                        SiteFormat.decimal(cell.get("upper").asDouble(), language))))
            .append("\">")
            .append(escape(SiteFormat.decimal(cell.get("mean").asDouble(), language)))
            .append("</td>");
      }
      html.append("</tr>\n");
    }
    html.append("</tbody></table>\n</div>\n");
    return shrunk;
  }

  /** The table alternative of one cycle: effect, interval and the shrunk marker per cell. */
  private static boolean cycleTable(
      StringBuilder html, String language, SiteText text, JsonNode labels, JsonNode matrix) {
    html.append("<div class=\"scroll\">\n<table class=\"heat-table\">\n<caption>")
        .append(escape(text.text("pollsters.intervalCaption")))
        .append("</caption><thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.institute")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("estimate.column.party")))
        .append("</th><th scope=\"col\" class=\"num\">")
        .append(escape(text.text("party.houseEffect")))
        .append("</th><th scope=\"col\" class=\"num\">")
        .append(escape(text.text("party.houseInterval")))
        .append("</th></tr></thead><tbody>\n");
    boolean shrunk = false;
    for (final JsonNode row : matrix.get("rows")) {
      for (final JsonNode cell : row.get("cells")) {
        shrunk = shrunk || cell.get("shrunk").asBoolean();
        final String effect =
            cell.get("shrunk").asBoolean()
                ? SiteText.fill(
                    text.text("pollsters.shrunkCell"),
                    "effect",
                    SiteFormat.decimal(cell.get("mean").asDouble(), language))
                : SiteFormat.decimal(cell.get("mean").asDouble(), language);
        html.append("<tr><th scope=\"row\">")
            .append(escape(row.get("institute").asString()))
            .append("</th><td>")
            .append(escape(label(labels, cell.get("component").asString())))
            .append("</td><td class=\"num\">")
            .append(escape(effect))
            .append("</td><td class=\"num\">")
            .append(
                escape(
                    SiteText.fill(
                        SiteText.fill(
                            text.text("party.effectRange"),
                            "lower",
                            SiteFormat.decimal(cell.get("lower").asDouble(), language)),
                        "upper",
                        SiteFormat.decimal(cell.get("upper").asDouble(), language))))
            .append("</td></tr>\n");
      }
    }
    html.append("</tbody></table>\n</div>\n");
    return shrunk;
  }

  private static Map<String, JsonNode> cellsByComponent(JsonNode cells) {
    final Map<String, JsonNode> byComponent = new java.util.LinkedHashMap<>();
    for (final JsonNode cell : cells) {
      byComponent.put(cell.get("component").asString(), cell);
    }
    return byComponent;
  }

  // The method page: the written explanation of the estimate, its validation and its reproduction.

  private static void method(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final String language = bootstrap.get("language").asString();
    final Translations words = Translations.of(language);
    html.append("<h1>").append(escape(text.text("head.title.method"))).append("</h1>\n");
    html.append("<p class=\"meta\">").append(escape(text.text("method.lead"))).append("</p>\n");
    html.append("<h2>").append(escape(text.text("method.data.title"))).append("</h2>\n");
    note(html, text.text("method.data.history"));
    note(html, text.text("method.data.provenance"));
    note(html, text.text("method.data.eligibility"));
    note(html, text.text("method.data.eras"));
    coverage(html, bootstrap, text);
    html.append("<h2>").append(escape(text.text("method.model.title"))).append("</h2>\n");
    note(html, text.text("method.model.observations"));
    note(html, text.text("method.model.estimand"));
    note(html, text.text("method.model.house"));
    note(html, text.text("method.model.overdispersion"));
    note(html, text.text("method.model.hyper"));
    note(html, text.text("method.model.draws"));
    validation(html, bootstrap, text);
    reproduction(html, bootstrap, text);
    html.append("<h2>").append(escape(text.text("method.seats.title"))).append("</h2>\n");
    note(
        html,
        SiteText.fill(
            text.text("seats.era"),
            "year",
            Integer.toString(bootstrap.get("approximatedElection").asInt())));
    note(html, text.text("seats.threshold"));
    note(html, words.text("seats.note"));
    note(html, words.text("seats.tie"));
    note(html, text.text("seats.pointVersusMean"));
  }

  /** The coverage periods of the pinned publication, their rosters and their owner decisions. */
  private static void coverage(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode periods = bootstrap.get("data").get("latest").get("coveragePeriods");
    final JsonNode labels = bootstrap.get("labels");
    html.append("<h2>").append(escape(text.text("method.coverage.title"))).append("</h2>\n");
    html.append("<div class=\"scroll\">\n<table class=\"coverage\"><caption>")
        .append(escape(text.text("method.coverage.rosterCaption")))
        .append("</caption><thead><tr><th scope=\"col\">")
        .append(escape(text.text("polls.column.period")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("method.coverage.column.span")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("method.coverage.column.roster")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("polls.column.other")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("method.coverage.column.status")))
        .append("</th><th scope=\"col\">")
        .append(escape(text.text("method.coverage.column.decision")))
        .append("</th></tr></thead><tbody>\n");
    for (final JsonNode period : periods) {
      final List<String> roster = new ArrayList<>();
      for (final JsonNode component : period.get("roster")) {
        roster.add(label(labels, component.asString()));
      }
      final List<String> other = new ArrayList<>();
      for (final JsonNode component : period.get("otherMembers")) {
        other.add(label(labels, component.asString()));
      }
      final JsonNode decision = period.get("decision");
      html.append("<tr><th scope=\"row\">")
          .append(escape(period.get("id").asString()))
          .append("</th><td>")
          .append(
              escape(
                  period.get("to").isNull()
                      ? period.get("from").asString() + "–"
                      : period.get("from").asString() + "–" + period.get("to").asString()))
          .append("</td><td>")
          .append(escape(String.join(", ", roster)))
          .append("</td><td>")
          .append(escape(String.join(", ", other)))
          .append("</td><td>")
          .append(
              escape(
                  text.text(
                      period.get("supportValidated").asBoolean()
                          ? "method.coverage.validated"
                          : "method.coverage.candidate")))
          .append("</td><td>");
      if (decision == null || decision.isNull() || decision.asString().isBlank()) {
        html.append(escape(text.text("polls.missing")));
      } else {
        html.append("<a href=\"")
            .append(escape(decision.asString()))
            .append("\">")
            .append(escape(text.text("method.coverage.column.decision")))
            .append("</a>");
      }
      html.append("</td></tr>\n");
    }
    html.append("</tbody></table>\n</div>\n");
    note(html, text.text("method.coverage.otherNote"));
    note(html, text.text("method.coverage.fiNote"));
    note(html, text.text("method.coverage.boundaryNote"));
  }

  /** The recorded verdict, the registered predictive gates, and the per-period requirements. */
  private static void validation(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode method = bootstrap.get("data").get("method");
    final JsonNode verdict = method.get("verdict");
    html.append("<h2>").append(escape(text.text("method.validation.title"))).append("</h2>\n");
    note(
        html,
        text.text(
            verdict.get("released").asBoolean()
                ? "method.validation.verdictReleased"
                : "method.validation.verdictBlocked"));
    if (!verdict.get("failedGates").isEmpty()) {
      final List<String> gates = strings(verdict.get("failedGates"));
      note(
          html,
          SiteText.fill(text.text("method.validation.failed"), "gates", String.join(", ", gates)));
    }
    note(html, text.text("method.validation.gatesLead"));
    html.append("<ul>\n");
    for (final String key :
        List.of(
            "method.validation.gateScore",
            "method.validation.gateCoverage",
            "method.validation.gateMisfit")) {
      html.append("<li>").append(escape(text.text(key))).append("</li>\n");
    }
    html.append("</ul>\n");
    gateTable(html, text, method.get("coverage"));
    note(html, text.text("method.validation.sensitivity"));
  }

  /** The registered per-period requirements, with the frozen values beside them. */
  private static void gateTable(StringBuilder html, SiteText text, JsonNode coverage) {
    html.append("<p class=\"meta\">")
        .append(escape(text.text("method.validation.coverageLead")))
        .append("</p>\n");
    html.append("<table class=\"gates\"><tbody>\n");
    gateRow(html, text, coverage, "method.coverage.gate.polls", "minObservations");
    gateRow(html, text, coverage, "method.coverage.gate.institutes", "minInstitutes");
    gateRow(html, text, coverage, "method.coverage.gate.gap", "maxInternalGapDays");
    html.append("<tr><th scope=\"row\">")
        .append(escape(text.text("method.coverage.gate.shifts")))
        .append("</th><td class=\"num\">")
        .append(escape(String.join(", ", strings(coverage.get("boundaryShiftDays")))))
        .append("</td></tr>\n");
    gateRow(html, text, coverage, "method.coverage.gate.burnIn", "stabilityBurnInDays");
    gateRow(html, text, coverage, "method.coverage.gate.stability", "maxStabilityShiftPoints");
    html.append("<tr><th scope=\"row\">")
        .append(escape(text.text("method.coverage.gate.development")))
        .append("</th><td class=\"num\">")
        .append(escape(coverage.get("developmentThrough").asString()))
        .append("</td></tr>\n");
    html.append("</tbody></table>\n");
  }

  private static void gateRow(
      StringBuilder html, SiteText text, JsonNode coverage, String key, String field) {
    html.append("<tr><th scope=\"row\">")
        .append(escape(text.text(key)))
        .append("</th><td class=\"num\">")
        .append(escape(coverage.get(field).asText()))
        .append("</td></tr>\n");
  }

  /** What it takes to rerun this deployment: seed, draws, resolution and versions. */
  private static void reproduction(StringBuilder html, ObjectNode bootstrap, SiteText text) {
    final JsonNode draws = bootstrap.get("data").get("method").get("draws");
    final JsonNode estimator = bootstrap.get("data").get("method").get("estimator");
    html.append("<h2>").append(escape(text.text("method.reproduction.title"))).append("</h2>\n");
    note(
        html,
        SiteText.fill(
            text.text("method.reproduction.seed"),
            "seed",
            Long.toString(draws.get("seed").asLong())));
    note(
        html,
        SiteText.fill(
            text.text("method.reproduction.draws"),
            "draws",
            Integer.toString(draws.get("count").asInt())));
    note(
        html,
        SiteText.fill(
            text.text("method.reproduction.decimals"),
            "decimals",
            Integer.toString(draws.get("decimals").asInt())));
    note(
        html,
        SiteText.fill(
            SiteText.fill(
                text.text("method.reproduction.estimator"),
                "version",
                estimator.get("version").asString()),
            "library",
            estimator.get("numericalLibrary").asString()));
    note(
        html,
        SiteText.fill(
            SiteText.fill(
                text.text("method.reproduction.protocols"),
                "development",
                estimator.get("developmentProtocol").asString()),
            "release",
            estimator.get("releaseProtocol").asString()));
    note(html, text.text("method.reproduction.inputs"));
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
    link(html, pollDownload(bootstrap, null), text.text("downloads.polls"));
    link(html, "/api/v1/estimates/latest" + pin, text.text("downloads.estimates"));
    link(html, "/api/v1/seats" + pin, text.text("downloads.seats"));
    link(html, "/api/v1/coalitions" + pin, text.text("downloads.coalitions"));
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

  private static String pollDownload(ObjectNode bootstrap, String component) {
    final String language = bootstrap.get("language").asString();
    final StringBuilder link = new StringBuilder();
    if (bootstrap.has("source")) {
      link.append("/source/polls.csv?snapshot=")
          .append(bootstrap.get("source").get("snapshotId").asString());
    } else {
      link.append("/api/v1/polls.csv?publication=")
          .append(bootstrap.get("api").get("publication").asString());
    }
    link.append("&language=").append(language);
    if (component != null) {
      link.append("&party=").append(component);
    }
    return link.toString();
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
    final JsonNode latest = bootstrap.has("data") ? bootstrap.get("data").get("latest") : null;
    final String level =
        latest != null && latest.has("intervalLevel")
            ? SiteFormat.level(latest.get("intervalLevel").asDouble())
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
  public static String escape(String value) {
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
