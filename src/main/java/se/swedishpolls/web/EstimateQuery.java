package se.swedishpolls.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cuts a stored history document to a requested range, coverage period and display step. Sampling
 * selects days that were already estimated, so no request refits the model, and the last requested
 * supported date is always retained even when the step would skip it.
 */
public final class EstimateQuery {
  private EstimateQuery() {}

  /** The display steps the frozen contract allows. */
  public static final List<Integer> STEPS = List.of(1, 3, 7);

  /** The fixed horizon of the published change. */
  public static final int CHANGE_DAYS = 30;

  public static final String OUTSIDE_HISTORY = "comparison_date_outside_supported_history";
  public static final String ACROSS_BOUNDARY = "comparison_date_across_boundary";
  public static final String OUTSIDE_FIT = "comparison_date_outside_supported_fit";

  /** A history request after parsing. A null bound means the stored range's own bound. */
  public record Range(LocalDate from, LocalDate to, int step, String coveragePeriod) {}

  /**
   * The stored document cut to the request. Boundary and gap information inside the range is
   * retained: sampling drops days, never the reason a series breaks.
   */
  public static ObjectNode sample(ObjectNode stored, Range range) {
    final List<LocalDate> dates = dates(stored);
    final List<String> periods = periods(stored);
    final LocalDate from = range.from() == null ? dates.getFirst() : range.from();
    final LocalDate to = range.to() == null ? dates.getLast() : range.to();

    final List<Integer> inRange = new ArrayList<>();
    for (int index = 0; index < dates.size(); index++) {
      final LocalDate date = dates.get(index);
      if (date.isBefore(from) || date.isAfter(to)) {
        continue;
      }
      if (range.coveragePeriod() != null && !range.coveragePeriod().equals(periods.get(index))) {
        continue;
      }
      inRange.add(index);
    }
    final List<Integer> sampled = new ArrayList<>();
    for (int position = 0; position < inRange.size(); position += range.step()) {
      sampled.add(inRange.get(position));
    }
    if (!inRange.isEmpty() && !sampled.contains(inRange.getLast())) {
      sampled.add(inRange.getLast());
    }

    final ObjectNode node = stored.deepCopy();
    final ObjectNode window = node.putObject("range");
    window.put("from", from.toString());
    window.put("to", inRange.isEmpty() ? to.toString() : dates.get(inRange.getLast()).toString());
    window.put("step", range.step());
    window.put("inclusive", true);
    window.put("requestedTo", to.toString());
    if (range.coveragePeriod() != null) {
      window.put("coveragePeriod", range.coveragePeriod());
    }

    final ArrayNode dateNodes = node.putArray("dates");
    final ArrayNode periodNodes = node.putArray("coveragePeriodByDate");
    for (final int index : sampled) {
      dateNodes.add(dates.get(index).toString());
      if (periods.get(index) == null) {
        periodNodes.addNull();
      } else {
        periodNodes.add(periods.get(index));
      }
    }
    final ArrayNode series = node.putArray("series");
    for (final JsonNode stream : stored.get("series")) {
      final ObjectNode entry = series.addObject();
      entry.put("component", stream.get("component").asString());
      copy(stream, entry, "mean", sampled);
      copy(stream, entry, "lower", sampled);
      copy(stream, entry, "upper", sampled);
    }
    final ArrayNode boundaries = node.putArray("boundaries");
    for (final JsonNode boundary : stored.get("boundaries")) {
      final LocalDate date = LocalDate.parse(boundary.get("date").asString());
      if (!date.isBefore(from) && !date.isAfter(to)) {
        boundaries.add(boundary.deepCopy());
      }
    }
    node.set("change30d", change(stored, sampled.isEmpty() ? null : dates.get(sampled.getLast())));
    return node;
  }

  private static void copy(JsonNode source, ObjectNode target, String field, List<Integer> keep) {
    final JsonNode values = source.get(field);
    final ArrayNode selected = target.putArray(field);
    for (final int index : keep) {
      selected.add(values.get(index).deepCopy());
    }
  }

  /**
   * The change over the fixed horizon, inside one publication and one supported fit. A comparison
   * date outside supported history, outside the fit or across a membership boundary has no change,
   * and says which.
   */
  static ObjectNode change(ObjectNode stored, LocalDate to) {
    final ObjectNode node = stored.objectNode();
    if (to == null) {
      node.put("available", false);
      node.putNull("comparisonDate");
      node.putNull("change");
      node.put("reason", OUTSIDE_HISTORY);
      return node;
    }
    final LocalDate comparisonDate = to.minusDays(CHANGE_DAYS);
    node.put("comparisonDate", comparisonDate.toString());
    final List<LocalDate> dates = dates(stored);
    final List<String> periods = periods(stored);
    final int target = dates.indexOf(to);
    final int comparison = dates.indexOf(comparisonDate);
    if (comparison < 0 || target < 0) {
      return unavailable(node, OUTSIDE_HISTORY);
    }
    if (periods.get(comparison) == null || !periods.get(comparison).equals(periods.get(target))) {
      return unavailable(node, ACROSS_BOUNDARY);
    }
    for (final JsonNode boundary : stored.get("boundaries")) {
      final LocalDate date = LocalDate.parse(boundary.get("date").asString());
      if (date.isAfter(comparisonDate) && !date.isAfter(to)) {
        return unavailable(node, ACROSS_BOUNDARY);
      }
    }
    final Map<String, Double> change = new LinkedHashMap<>();
    for (final JsonNode stream : stored.get("series")) {
      final JsonNode mean = stream.get("mean");
      if (mean.get(target).isNull() || mean.get(comparison).isNull()) {
        return unavailable(node, OUTSIDE_FIT);
      }
      change.put(
          stream.get("component").asString(),
          round(mean.get(target).doubleValue() - mean.get(comparison).doubleValue()));
    }
    node.put("available", true);
    final ObjectNode values = node.putObject("change");
    change.forEach(values::put);
    node.putNull("reason");
    return node;
  }

  private static ObjectNode unavailable(ObjectNode node, String reason) {
    node.put("available", false);
    node.putNull("change");
    node.put("reason", reason);
    return node;
  }

  private static double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }

  static List<LocalDate> dates(ObjectNode stored) {
    final List<LocalDate> dates = new ArrayList<>();
    for (final JsonNode date : stored.get("dates")) {
      dates.add(LocalDate.parse(date.asString()));
    }
    return dates;
  }

  private static List<String> periods(ObjectNode stored) {
    final List<String> periods = new ArrayList<>();
    for (final JsonNode period : stored.get("coveragePeriodByDate")) {
      periods.add(period.isNull() ? null : period.asString());
    }
    return periods;
  }
}
