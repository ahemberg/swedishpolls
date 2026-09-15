package se.swedishpolls.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;

/**
 * Filters one publication's pinned source snapshot. The table and the download share this filter
 * and this row order, so a visible page and its CSV differ only in paging.
 */
public final class PollQuery {
  private PollQuery() {}

  /** The party columns of a poll row, in the order the download writes them. */
  public static final List<String> COMPONENTS =
      List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "FI");

  public static final List<String> CSV_HEADER =
      List.of(
          "publication_date",
          "collection_from",
          "collection_to",
          "approximate_period",
          "institute",
          "company",
          "method_era",
          "survey_type",
          "sample_size",
          "denominator_note",
          "S",
          "M",
          "SD",
          "V",
          "C",
          "KD",
          "L",
          "MP",
          "FI",
          "uncertain",
          "other",
          "coverage_period",
          "eligibility");

  public static final int DEFAULT_PAGE_SIZE = 50;
  public static final int MAX_PAGE_SIZE = 200;

  /**
   * The filters a poll request declares. A null bound or an empty list means the filter is not
   * applied; both date bounds are inclusive and match on the collection period, so a poll whose
   * fieldwork overlaps the range is in it.
   */
  public record Filters(
      LocalDate from,
      LocalDate to,
      List<String> institutes,
      List<String> parties,
      String coveragePeriod,
      boolean includeExcluded) {
    public Filters {
      institutes = List.copyOf(institutes);
      parties = List.copyOf(parties);
    }

    public static Filters none() {
      return new Filters(null, null, List.of(), List.of(), null, false);
    }

    /** The party columns this request asks for, defaulting to every column. */
    public List<String> selectedComponents() {
      return parties.isEmpty()
          ? COMPONENTS
          : COMPONENTS.stream().filter(parties::contains).toList();
    }
  }

  /** One filtered poll, already resolved against the publication's coverage periods. */
  public record Row(
      String pollId,
      PollCsv.Poll poll,
      String coveragePeriod,
      boolean approximatePeriod,
      BigDecimal uncertain) {}

  /** A filtered result: every matching row, and the page of it a table shows. */
  public record Result(List<Row> matching, List<Row> page, int total, int page1, int pageSize) {
    public Result {
      matching = List.copyOf(matching);
      page = List.copyOf(page);
    }
  }

  public static Result filter(
      long snapshotId,
      List<PollCsv.Poll> polls,
      List<Roster.CoveragePeriod> periods,
      Filters filters,
      int page,
      int pageSize) {
    final List<Row> matching = new ArrayList<>();
    for (final PollCsv.Poll poll : polls) {
      if (!filters.includeExcluded() && !poll.eligible()) {
        continue;
      }
      if (!withinDates(poll, filters)) {
        continue;
      }
      if (!filters.institutes().isEmpty() && !filters.institutes().contains(poll.institute())) {
        continue;
      }
      final String periodId = coveragePeriod(periods, poll);
      if (filters.coveragePeriod() != null && !filters.coveragePeriod().equals(periodId)) {
        continue;
      }
      matching.add(
          new Row(
              snapshotId + ":" + poll.rowNumber(),
              poll,
              periodId,
              approximate(poll.raw().get("approxPeriod")),
              decimal(poll.raw().get("Uncertain"))));
    }
    matching.sort(
        Comparator.comparing(
                (Row row) -> row.poll().collectionTo(),
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(
                row -> row.poll().institute(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(row -> row.poll().rowNumber()));
    final long offset = (long) (page - 1) * pageSize;
    final int from = (int) Math.min(offset, matching.size());
    final int to = (int) Math.min((long) from + pageSize, matching.size());
    return new Result(matching, matching.subList(from, to), matching.size(), page, pageSize);
  }

  /**
   * The validated period a poll belongs to, or null. An archived row outside every validated period
   * keeps its place in the download; it simply has no period to name.
   */
  private static String coveragePeriod(List<Roster.CoveragePeriod> periods, PollCsv.Poll poll) {
    return periods.stream()
        .filter(Roster.CoveragePeriod::supportValidated)
        .filter(period -> period.covers(poll))
        .map(Roster.CoveragePeriod::id)
        .findFirst()
        .orElse(null);
  }

  /** The archived flag, read exactly as the source spells it rather than case-folded. */
  private static boolean approximate(String value) {
    return "TRUE".equals(value) || "True".equals(value) || "true".equals(value);
  }

  private static boolean withinDates(PollCsv.Poll poll, Filters filters) {
    final LocalDate start = poll.collectionFrom();
    final LocalDate end = poll.collectionTo();
    if (start == null || end == null) {
      return filters.from() == null && filters.to() == null;
    }
    return (filters.from() == null || !end.isBefore(filters.from()))
        && (filters.to() == null || !start.isAfter(filters.to()));
  }

  /** The download of the same rows, with the source precision the snapshot archived. */
  public static String csv(Result result, Filters filters) {
    return csv(result, filters, "other");
  }

  /** A current-source download with its explicit comparable-remainder heading. */
  public static String csv(Result result, Filters filters, String remainderHeader) {
    final List<String> components = filters.selectedComponents();
    final List<String> header = new ArrayList<>();
    for (final String column : CSV_HEADER) {
      if (!COMPONENTS.contains(column) || components.contains(column)) {
        header.add("other".equals(column) ? remainderHeader : column);
      }
    }
    final StringBuilder out = new StringBuilder();
    final CSVFormat format =
        CSVFormat.RFC4180.builder().setQuoteMode(QuoteMode.MINIMAL).setRecordSeparator("\n").get();
    try (final CSVPrinter printer = new CSVPrinter(out, format)) {
      printer.printRecord(header);
      for (final Row row : result.matching()) {
        final List<String> values = new ArrayList<>(header.size());
        final PollCsv.Poll poll = row.poll();
        values.add(text(poll.publicationDate()));
        values.add(text(poll.collectionFrom()));
        values.add(text(poll.collectionTo()));
        values.add(Boolean.toString(row.approximatePeriod()));
        values.add(nullToEmpty(poll.institute()));
        values.add(nullToEmpty(poll.company()));
        values.add(nullToEmpty(poll.methodEra()));
        values.add(nullToEmpty(poll.surveyType()));
        values.add(text(poll.sampleSize()));
        values.add(nullToEmpty(poll.denominatorNote()));
        for (final String component : components) {
          values.add(text(poll.shares().get(component)));
        }
        values.add(text(row.uncertain()));
        values.add(text(poll.remainder()));
        values.add(nullToEmpty(row.coveragePeriod()));
        values.add(poll.eligible() ? "eligible" : String.join("|", poll.exclusionReasons()));
        printer.printRecord(values);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toString();
  }

  /** The institutes of a snapshot, with their companies, counts and collection span. */
  public static Map<String, Institute> institutes(List<PollCsv.Poll> polls) {
    final TreeMap<String, Institute> institutes = new TreeMap<>();
    for (final PollCsv.Poll poll : polls) {
      if (!poll.eligible() || poll.institute() == null) {
        continue;
      }
      institutes.merge(
          poll.institute(), Institute.of(poll), (existing, added) -> existing.with(poll));
    }
    return new LinkedHashMap<>(institutes);
  }

  /** One institute's archived footprint in a snapshot. */
  public record Institute(
      String institute,
      Set<String> companies,
      int polls,
      LocalDate firstCollection,
      LocalDate lastCollection,
      Map<String, String> methodEras) {
    public Institute {
      // Insertion order is the display order, so the copies keep it.
      companies = Collections.unmodifiableSet(new LinkedHashSet<>(companies));
      methodEras = Collections.unmodifiableMap(new LinkedHashMap<>(methodEras));
    }

    static Institute of(PollCsv.Poll poll) {
      final LinkedHashSet<String> companies = new LinkedHashSet<>();
      if (poll.company() != null) {
        companies.add(poll.company());
      }
      final LinkedHashMap<String, String> eras = new LinkedHashMap<>();
      if (poll.methodEra() != null) {
        eras.put(poll.methodEra(), poll.methodEvidence());
      }
      return new Institute(
          poll.institute(), companies, 1, poll.collectionFrom(), poll.collectionTo(), eras);
    }

    Institute with(PollCsv.Poll poll) {
      final LinkedHashSet<String> merged = new LinkedHashSet<>(companies);
      if (poll.company() != null) {
        merged.add(poll.company());
      }
      final LinkedHashMap<String, String> eras = new LinkedHashMap<>(methodEras);
      if (poll.methodEra() != null) {
        eras.put(poll.methodEra(), poll.methodEvidence());
      }
      return new Institute(
          institute,
          merged,
          polls + 1,
          earliest(firstCollection, poll.collectionFrom()),
          latest(lastCollection, poll.collectionTo()),
          eras);
    }
  }

  private static LocalDate earliest(LocalDate left, LocalDate right) {
    if (left == null) {
      return right;
    }
    return right == null || left.isBefore(right) ? left : right;
  }

  private static LocalDate latest(LocalDate left, LocalDate right) {
    if (left == null) {
      return right;
    }
    return right == null || left.isAfter(right) ? left : right;
  }

  private static BigDecimal decimal(String value) {
    if (value == null || value.isBlank() || "-".equals(value.trim())) {
      return null;
    }
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String text(LocalDate value) {
    return value == null ? "" : value.toString();
  }

  private static String text(BigDecimal value) {
    return value == null ? "" : value.toPlainString();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
