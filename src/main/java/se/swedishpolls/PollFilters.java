package se.swedishpolls;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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

  /** A parsed query: the filters that were understood, and every parameter that was not. */
  public record Parsed(PollQuery.Filters filters, List<ApiErrors.Invalid> invalid) {
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
    final List<ApiErrors.Invalid> invalid = new ArrayList<>();
    if (coveragePeriod != null && !knownPeriod.test(coveragePeriod)) {
      invalid.add(new ApiErrors.Invalid("coveragePeriod", "unknown_coverage_period"));
    }
    final LocalDate fromDate = date(from, "from", invalid);
    final LocalDate toDate = date(to, "to", invalid);
    if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
      invalid.add(new ApiErrors.Invalid("to", "before_from"));
    }
    final List<String> parties = list(party);
    for (final String component : parties) {
      if (!PollQuery.COMPONENTS.contains(component)) {
        invalid.add(new ApiErrors.Invalid("party", "unknown_component"));
      }
    }
    boolean excluded = false;
    if (includeExcluded != null && !includeExcluded.isBlank()) {
      if (!"true".equals(includeExcluded) && !"false".equals(includeExcluded)) {
        invalid.add(new ApiErrors.Invalid("includeExcluded", "not_a_boolean"));
      }
      excluded = "true".equals(includeExcluded);
    }
    final String period =
        coveragePeriod != null && knownPeriod.test(coveragePeriod) ? coveragePeriod : null;
    return new Parsed(
        new PollQuery.Filters(fromDate, toDate, list(institute), parties, period, excluded),
        invalid);
  }

  /**
   * The download of exactly the filtered rows. Every declared filter is carried, so the file a
   * reader saves cannot hold rows the table above it was not showing.
   */
  public static String csvLink(String publicationId, PollQuery.Filters filters) {
    final StringBuilder link =
        new StringBuilder("/api/v1/polls.csv?publication=").append(publicationId);
    if (filters.from() != null) {
      link.append("&from=").append(filters.from());
    }
    if (filters.to() != null) {
      link.append("&to=").append(filters.to());
    }
    if (!filters.institutes().isEmpty()) {
      link.append("&institute=").append(String.join(",", filters.institutes()));
    }
    if (!filters.parties().isEmpty()) {
      link.append("&party=").append(String.join(",", filters.parties()));
    }
    if (filters.coveragePeriod() != null) {
      link.append("&coveragePeriod=").append(filters.coveragePeriod());
    }
    if (filters.includeExcluded()) {
      link.append("&includeExcluded=true");
    }
    return link.toString();
  }

  /** One ISO date bound, or null when the parameter is absent or was not a date. */
  public static LocalDate date(String value, String name, List<ApiErrors.Invalid> invalid) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      invalid.add(new ApiErrors.Invalid(name, "not_a_date"));
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
