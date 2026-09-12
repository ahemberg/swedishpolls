package se.swedishpolls.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import se.swedishpolls.source.PollQuery;

/**
 * The one place a poll query is read out of request parameters, shared by the API, the poll table
 * and the download.
 *
 * <p>Parsing it twice is how a page and the CSV beside it start selecting different rows, which is
 * exactly what the pinned publication is supposed to rule out. A rejected parameter leaves its own
 * filter unapplied and is named in {@link Parsed#invalid()}; nothing here guesses what the caller
 * meant.
 */
public final class PollFilters {
  private PollFilters() {}

  /** One rejected query parameter and why it was rejected. */
  public record Invalid(String name, String reason, List<Integer> allowed) {
    public Invalid {
      allowed = List.copyOf(allowed);
    }

    public Invalid(String name, String reason) {
      this(name, reason, List.of());
    }

    @Override
    public List<Integer> allowed() {
      return List.copyOf(allowed);
    }
  }

  /** A parsed query: the filters that were understood, and every parameter that was not. */
  public record Parsed(PollQuery.Filters filters, List<Invalid> invalid) {
    public Parsed {
      invalid = List.copyOf(invalid);
    }

    public boolean valid() {
      return invalid.isEmpty();
    }
  }

  /** Reads one poll query, resolving coverage periods through the caller's own snapshot. */
  public static Parsed parse(
      String from,
      String to,
      String institute,
      String party,
      String coveragePeriod,
      String includeExcluded,
      Predicate<String> knownPeriod) {
    final List<Invalid> invalid = new ArrayList<>();
    final String requestedPeriod =
        coveragePeriod == null || coveragePeriod.isBlank() ? null : coveragePeriod;
    final boolean validPeriod = requestedPeriod == null || knownPeriod.test(requestedPeriod);
    if (!validPeriod) {
      invalid.add(new Invalid("coveragePeriod", "unknown_coverage_period"));
    }
    final LocalDate fromDate = date(from, "from", invalid);
    final LocalDate requestedToDate = date(to, "to", invalid);
    final LocalDate toDate;
    if (fromDate != null && requestedToDate != null && fromDate.isAfter(requestedToDate)) {
      invalid.add(new Invalid("to", "before_from"));
      toDate = null;
    } else {
      toDate = requestedToDate;
    }
    final List<String> parties = list(party);
    for (final String component : parties) {
      if (!PollQuery.COMPONENTS.contains(component)) {
        invalid.add(new Invalid("party", "unknown_component"));
      }
    }
    final List<String> knownParties =
        parties.stream().filter(PollQuery.COMPONENTS::contains).toList();
    boolean excluded = false;
    if (includeExcluded != null && !includeExcluded.isBlank()) {
      if (!"true".equals(includeExcluded) && !"false".equals(includeExcluded)) {
        invalid.add(new Invalid("includeExcluded", "not_a_boolean"));
      }
      excluded = "true".equals(includeExcluded);
    }
    final String period = validPeriod ? requestedPeriod : null;
    return new Parsed(
        new PollQuery.Filters(fromDate, toDate, list(institute), knownParties, period, excluded),
        invalid);
  }

  /**
   * The declared filters as query parameters, in the one order every link writes them.
   *
   * <p>Every link a page offers is built from this list: the download, the paging and anything
   * else. A second builder is how a next-page link starts selecting rows the CSV beside it does
   * not, which is precisely what the pinned publication is supposed to rule out.
   */
  public static List<String> query(PollQuery.Filters filters) {
    final List<String> parameters = new ArrayList<>();
    if (filters.from() != null) {
      parameters.add("from=" + filters.from());
    }
    if (filters.to() != null) {
      parameters.add("to=" + filters.to());
    }
    if (!filters.institutes().isEmpty()) {
      parameters.add("institute=" + joined(filters.institutes()));
    }
    if (!filters.parties().isEmpty()) {
      parameters.add("party=" + joined(filters.parties()));
    }
    if (filters.coveragePeriod() != null) {
      parameters.add("coveragePeriod=" + encode(filters.coveragePeriod()));
    }
    if (filters.includeExcluded()) {
      parameters.add("includeExcluded=true");
    }
    return List.copyOf(parameters);
  }

  /**
   * The download of exactly the filtered rows. Every declared filter is carried, so the file a
   * reader saves cannot hold rows the table above it was not showing.
   */
  public static String csvLink(String publicationId, PollQuery.Filters filters) {
    final StringBuilder link =
        new StringBuilder("/api/v1/polls.csv?publication=").append(publicationId);
    for (final String parameter : query(filters)) {
      link.append("&").append(parameter);
    }
    return link.toString();
  }

  /** A list parameter, each value escaped and the separator left as the comma the parser reads. */
  private static String joined(List<String> values) {
    return values.stream().map(PollFilters::encode).collect(Collectors.joining(","));
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * A bounded positive request parameter, such as a page number or a page size. A value outside the
   * bounds is rejected by name and falls back rather than silently clamping.
   */
  public static int positive(
      String value, String name, int fallback, int max, List<Invalid> invalid) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      final int parsed = Integer.parseInt(value);
      if (parsed < 1 || parsed > max) {
        invalid.add(new Invalid(name, "out_of_range"));
        return fallback;
      }
      return parsed;
    } catch (NumberFormatException e) {
      invalid.add(new Invalid(name, "not_an_integer"));
      return fallback;
    }
  }

  /** One ISO date bound, or null when the parameter is absent or was not a date. */
  public static LocalDate date(String value, String name, List<Invalid> invalid) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      invalid.add(new Invalid(name, "not_a_date"));
      return null;
    }
  }

  /** One comma-separated parameter, with blank entries dropped rather than matched on. */
  public static List<String> list(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return List.of(value.split(",", -1)).stream()
        .map(String::trim)
        .filter(entry -> !entry.isEmpty())
        .toList();
  }
}
