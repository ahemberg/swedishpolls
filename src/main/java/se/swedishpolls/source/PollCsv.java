package se.swedishpolls.source;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;

/** Parses the source without rounding shares or filling missing values. */
public final class PollCsv {
  public static final List<String> PARTIES = List.of("M", "L", "C", "KD", "S", "V", "MP", "SD");

  private static final List<String> REQUIRED =
      List.of(
          "PublYearMonth",
          "Company",
          "M",
          "L",
          "C",
          "KD",
          "S",
          "V",
          "MP",
          "SD",
          "FI",
          "Uncertain",
          "n",
          "PublDate",
          "collectPeriodFrom",
          "collectPeriodTo",
          "approxPeriod",
          "house");
  private static final String METHOD_EVIDENCE =
      "https://github.com/MansMeg/SwedishPolls/blob/f0390c05854d87bbf21db9d31c6431ffa0f07f7e/RPackage/R/handle_demoskop_inizio_merger.R";
  private static final Pattern EXIT_POLL =
      Pattern.compile("valu|valdag|exit|svt|tv4", Pattern.CASE_INSENSITIVE);

  public record Poll(
      int rowNumber,
      Map<String, String> raw,
      String company,
      String institute,
      String methodEra,
      String methodEvidence,
      String surveyType,
      String denominatorNote,
      LocalDate publicationDate,
      LocalDate collectionFrom,
      LocalDate collectionTo,
      BigDecimal sampleSize,
      Map<String, BigDecimal> shares,
      BigDecimal remainder,
      List<String> exclusionReasons) {
    public Poll {
      raw = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(raw));
      shares = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(shares));
      exclusionReasons = List.copyOf(exclusionReasons);
    }

    public boolean eligible() {
      return exclusionReasons.isEmpty();
    }

    public boolean publicationTimeEligible() {
      return eligible() && publicationDate != null;
    }
  }

  public static List<Poll> parse(byte[] bytes) {
    try (final org.apache.commons.csv.CSVParser csv =
        CSVFormat.RFC4180
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .get()
            .parse(
                new java.io.StringReader(
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()))) {
      if (!new HashSet<>(csv.getHeaderNames()).containsAll(REQUIRED))
        throw new IllegalArgumentException("Missing source columns");
      final java.util.List<org.apache.commons.csv.CSVRecord> records = csv.getRecords();
      if (records.isEmpty()) throw new IllegalArgumentException("Empty source snapshot");
      final java.util.HashMap<java.util.List<java.lang.String>, java.lang.Integer> keys =
          new HashMap<List<String>, Integer>();
      for (org.apache.commons.csv.CSVRecord record : records) {
        if (!record.isConsistent())
          throw new IllegalArgumentException("Incomplete CSV row " + record.getRecordNumber());
        keys.merge(key(record.toMap()), 1, Integer::sum);
      }
      final java.util.ArrayList<se.swedishpolls.source.PollCsv.Poll> polls = new ArrayList<Poll>();
      for (org.apache.commons.csv.CSVRecord record : records) {
        final java.util.Map<java.lang.String, java.lang.String> raw = record.toMap();
        final java.util.ArrayList<java.lang.String> reasons = new ArrayList<String>();
        if (keys.get(key(raw)) > 1) reasons.add("duplicate_natural_key");
        if (!List.of("TRUE", "FALSE").contains(raw.get("approxPeriod")))
          reasons.add("invalid_approx_period");
        final java.util.LinkedHashMap<java.lang.String, java.math.BigDecimal> shares =
            new LinkedHashMap<String, BigDecimal>();
        for (java.lang.String party : PARTIES) shares.put(party, share(raw, party, true, reasons));
        shares.put("FI", share(raw, "FI", false, reasons));
        share(raw, "Uncertain", false, reasons);
        BigDecimal remainder = null;
        if (PARTIES.stream().allMatch(p -> shares.get(p) != null)) {
          remainder =
              new BigDecimal("100")
                  .subtract(
                      PARTIES.stream().map(shares::get).reduce(BigDecimal.ZERO, BigDecimal::add));
          if (remainder.signum() < 0) reasons.add("negative_remainder");
        }
        final java.time.LocalDate publication = date(raw, "PublDate", false, reasons);
        final java.time.LocalDate from = date(raw, "collectPeriodFrom", true, reasons);
        final java.time.LocalDate to = date(raw, "collectPeriodTo", true, reasons);
        if (from != null && to != null && from.isAfter(to))
          reasons.add("reversed_collection_dates");
        if (publication != null && to != null && publication.isBefore(to))
          reasons.add("publication_before_collection_end");
        if (from != null && from.isBefore(LocalDate.of(2010, 1, 1)))
          reasons.add("outside_supported_history");
        final java.math.BigDecimal sampleSize = number(raw.get("n"), "sample_size", true, reasons);
        if (sampleSize != null
            && (sampleSize.signum() <= 0 || sampleSize.stripTrailingZeros().scale() > 0))
          reasons.add("invalid_sample_size");
        final java.lang.String company = raw.get("Company");
        final java.lang.String institute = raw.get("house");
        if (missing(company)) reasons.add("missing_company");
        if (missing(institute)) reasons.add("missing_institute");
        final boolean exit = EXIT_POLL.matcher(company + " " + institute).find();
        if (exit) reasons.add("exit_or_election_day");
        String era = "Inizio".equals(institute) ? "inizio_continuation" : null;
        if ("Demoskop".equals(institute) && publication != null)
          era =
              publication.isBefore(LocalDate.of(2019, 11, 1))
                  ? "demoskop_before_2019_11"
                  : "inizio_continuation";
        polls.add(
            new Poll(
                (int) record.getRecordNumber(),
                raw,
                company,
                institute,
                era,
                era == null ? null : METHOD_EVIDENCE,
                exit ? "exit_or_election_day" : "voting_intention",
                "Sentio".equals(institute)
                    ? "party preferences; older denominator uncertain"
                    : "Ipsos".equals(institute)
                        ? "respondents; upstream Ipsos normalization may apply"
                        : "respondents; effective sample size unknown",
                publication,
                from,
                to,
                sampleSize,
                shares,
                remainder,
                reasons));
      }
      return List.copyOf(polls);
    } catch (IOException | java.io.UncheckedIOException e) {
      throw new IllegalArgumentException("Invalid source CSV", e);
    }
  }

  private static List<String> key(Map<String, String> raw) {
    return Arrays.asList("house", "PublDate", "collectPeriodFrom", "collectPeriodTo", "n").stream()
        .map(
            field -> {
              final String value = raw.get(field);
              if (missing(value)) return null;
              if (field.equals("n")) {
                try {
                  return new BigDecimal(value).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException ignored) {
                  /* Invalid values still have a source identity. */
                }
              }
              return value;
            })
        .toList();
  }

  private static BigDecimal share(
      Map<String, String> raw, String field, boolean required, List<String> reasons) {
    final java.math.BigDecimal value = number(raw.get(field), "share:" + field, required, reasons);
    if (value != null && (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0))
      reasons.add("invalid_share:" + field);
    return value;
  }

  private static BigDecimal number(
      String text, String field, boolean required, List<String> reasons) {
    if (missing(text)) {
      if (required) reasons.add("missing_" + field);
      return null;
    }
    try {
      return new BigDecimal(text);
    } catch (NumberFormatException e) {
      reasons.add("invalid_" + field);
      return null;
    }
  }

  private static LocalDate date(
      Map<String, String> raw, String field, boolean required, List<String> reasons) {
    final java.lang.String text = raw.get(field);
    if (missing(text)) {
      if (required) reasons.add("missing_" + field);
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (java.time.DateTimeException e) {
      reasons.add("invalid_" + field);
      return null;
    }
  }

  private static boolean missing(String value) {
    return value == null || value.isEmpty() || value.equals("NA");
  }
}
