package se.swedishpolls.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Selects existing columns and stored dates, never drawing or iterating over a requested calendar.
 */
public final class CoalitionHistoryQuery {
  private static final List<String> VALUES = List.of("mean", "lower", "upper", "availability");

  private CoalitionHistoryQuery() {}

  public record Request(CoalitionSelection selection, LocalDate from, LocalDate to, int step) {}

  public static Request parsePage(Map<String, List<String>> parameters) {
    unique(parameters, List.of("a", "b", "parties", "from", "to"));
    final boolean modern = parameters.containsKey("a") || parameters.containsKey("b");
    if (modern && parameters.containsKey("parties")) {
      throw new CoalitionSelection.Invalid(
          "parties", "mixed_parameters", "Do not mix parties with the a and b parameters.");
    }
    final CoalitionSelection selection;
    if (modern) {
      selection =
          CoalitionSelection.parse(
              parameters.containsKey("a") ? value(parameters, "a") : "",
              parameters.containsKey("b") ? value(parameters, "b") : "");
    } else if (parameters.containsKey("parties")) {
      selection = CoalitionSelection.parse(value(parameters, "parties"), "");
    } else {
      selection = CoalitionSelection.preset();
    }
    final LocalDate from = date(value(parameters, "from"), "from");
    final LocalDate to = date(value(parameters, "to"), "to");
    ordered(from, to);
    return new Request(selection, from, to, 7);
  }

  public static Request parse(Map<String, List<String>> parameters) {
    unique(parameters, List.of("a", "b", "from", "to", "step"));
    final CoalitionSelection selection =
        CoalitionSelection.parse(value(parameters, "a"), value(parameters, "b"));
    final LocalDate from = date(value(parameters, "from"), "from");
    final LocalDate to = date(value(parameters, "to"), "to");
    final String step = value(parameters, "step");
    if (step != null && !List.of("1", "3", "7").contains(step)) {
      throw new CoalitionSelection.Invalid("step", "unsupported_step", "Use a step of 1, 3 or 7.");
    }
    ordered(from, to);
    return new Request(selection, from, to, step == null ? 1 : Integer.parseInt(step));
  }

  private static void unique(Map<String, List<String>> parameters, List<String> names) {
    for (final String name : names) {
      if (parameters.containsKey(name) && parameters.get(name).size() != 1) {
        throw new CoalitionSelection.Invalid(
            name, "repeated_parameter", "Supply this parameter exactly once.");
      }
    }
  }

  private static void ordered(LocalDate from, LocalDate to) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new CoalitionSelection.Invalid(
          "to", "reversed_range", "The end date must be on or after the start date.");
    }
  }

  private static String value(Map<String, List<String>> parameters, String field) {
    return parameters.containsKey(field) ? parameters.get(field).getFirst() : null;
  }

  private static LocalDate date(String value, String field) {
    if (value == null) return null;
    if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) {
      throw new CoalitionSelection.Invalid(
          field, "malformed_date", "Use a real YYYY-MM-DD date in years 0001 through 9999.");
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException error) {
      throw new CoalitionSelection.Invalid(field, "invalid_date", "Use a real calendar date.");
    }
  }

  public static ObjectNode sample(ObjectNode stored, Request request) {
    final JsonNode dates = stored.get("dates");
    final LocalDate first = LocalDate.parse(dates.get(0).asString());
    final LocalDate last = LocalDate.parse(dates.get(dates.size() - 1).asString());
    final LocalDate from = request.from() == null ? first : request.from();
    final LocalDate to = request.to() == null ? last : request.to();
    if (from.isAfter(to)) {
      throw new CoalitionSelection.Invalid(
          "to", "resolved_reversed_range", "The resolved end date precedes the start date.");
    }
    final List<Integer> inRange = new ArrayList<>();
    for (int i = 0; i < dates.size(); i++) {
      final LocalDate date = LocalDate.parse(dates.get(i).asString());
      if (!date.isBefore(from) && !date.isAfter(to)) inRange.add(i);
    }
    final TreeSet<Integer> keep = new TreeSet<>();
    for (int i = 0; i < inRange.size(); i++) {
      final int index = inRange.get(i);
      if (i % request.step() == 0
          || i == 0
          || i == inRange.size() - 1
          || split(stored, inRange.get(i - 1), index)
          || split(stored, index, inRange.get(i + 1))) {
        keep.add(index);
      }
    }
    final ObjectNode result = stored.objectNode();
    for (final String name :
        List.of(
            "schemaVersion",
            "publicationId",
            "modelRunId",
            "sourceSnapshotId",
            "lastFieldworkDate",
            "publishedAt",
            "interval",
            "coveragePeriods")) {
      result.set(name, stored.get(name).deepCopy());
    }
    final ObjectNode range = result.putObject("requestedRange");
    range.put("from", from.toString());
    range.put("to", to.toString());
    range.put("step", request.step());
    final ObjectNode selection = result.putObject("selection");
    request.selection().a().forEach(selection.putArray("a")::add);
    request.selection().b().forEach(selection.putArray("b")::add);
    for (final String name : List.of("dates", "fitIds", "coveragePeriodIds")) {
      final ArrayNode column = result.putArray(name);
      for (final int index : keep) column.add(stored.get(name).get(index).deepCopy());
    }
    final ObjectNode series = result.putObject("series");
    series.set("a", series(stored, CoalitionSelection.mask(request.selection().a()), keep));
    series.set("b", series(stored, CoalitionSelection.mask(request.selection().b()), keep));
    final ArrayNode gaps = result.putArray("gaps");
    if (from.isBefore(first)) gap(gaps, from, to.isBefore(first) ? to : first.minusDays(1));
    for (final JsonNode gap : stored.get("gaps")) {
      final LocalDate start = LocalDate.parse(gap.get("from").asString());
      final LocalDate end = LocalDate.parse(gap.get("to").asString());
      if (!end.isBefore(from) && !start.isAfter(to)) {
        gap(gaps, start.isBefore(from) ? from : start, end.isAfter(to) ? to : end);
      }
    }
    if (to.isAfter(last)) gap(gaps, from.isAfter(last) ? from : last.plusDays(1), to);
    final ArrayNode boundaries = result.putArray("fitBoundaries");
    for (final JsonNode boundary : stored.get("fitBoundaries")) {
      final LocalDate date = LocalDate.parse(boundary.get("date").asString());
      if (!date.isBefore(from) && !date.isAfter(to)) boundaries.add(boundary.deepCopy());
    }
    result.set("latest", latest(stored, request.selection()));
    return result;
  }

  private static boolean split(ObjectNode stored, int left, int right) {
    if (!stored.get("fitIds").get(left).equals(stored.get("fitIds").get(right))) return true;
    final String from = stored.get("dates").get(left).asString();
    final String to = stored.get("dates").get(right).asString();
    for (final JsonNode boundary : stored.get("fitBoundaries")) {
      final String date = boundary.get("date").asString();
      if (date.compareTo(from) > 0 && date.compareTo(to) <= 0) return true;
    }
    for (final JsonNode gap : stored.get("gaps")) {
      if (gap.get("from").asString().compareTo(to) < 0
          && gap.get("to").asString().compareTo(from) > 0) return true;
    }
    return false;
  }

  private static ObjectNode series(ObjectNode stored, int mask, Iterable<Integer> keep) {
    final ObjectNode result = stored.objectNode();
    for (final String field : VALUES) {
      final ArrayNode column = result.putArray(field);
      for (final int index : keep) {
        if (mask != 0)
          column.add(stored.get("subsets").get(mask - 1).get(field).get(index).deepCopy());
        else if (field.equals("availability")) column.add("empty_selection");
        else column.addNull();
      }
    }
    return result;
  }

  private static ObjectNode latest(ObjectNode stored, CoalitionSelection selection) {
    final ObjectNode latest = ((ObjectNode) stored.get("latest")).deepCopy();
    final int index = latest.remove("historyIndex").intValue();
    for (final String block : List.of("a", "b")) {
      final int mask = CoalitionSelection.mask(block.equals("a") ? selection.a() : selection.b());
      final ObjectNode summary = latest.putObject(block);
      for (final String field : VALUES) {
        if (mask != 0)
          summary.set(field, stored.get("subsets").get(mask - 1).get(field).get(index).deepCopy());
        else if (field.equals("availability")) summary.put(field, "empty_selection");
        else summary.putNull(field);
      }
    }
    double unassigned = 0;
    boolean available = !latest.get("comparableRemainderMean").isNull();
    for (final String party : CoalitionSelection.ROSTER) {
      final JsonNode mean = latest.get("partyMeans").get(party);
      if (mean == null || mean.isNull()) available = false;
      else if (!selection.a().contains(party) && !selection.b().contains(party))
        unassigned += mean.doubleValue();
    }
    if (available) {
      latest.put("unassignedMean", unassigned);
      latest.put(
          "outsideBothMean", unassigned + latest.get("comparableRemainderMean").doubleValue());
    } else {
      latest.putNull("unassignedMean");
      latest.putNull("outsideBothMean");
    }
    latest.put("availability", available ? "available" : "party_unavailable");
    return latest;
  }

  private static void gap(ArrayNode gaps, LocalDate from, LocalDate to) {
    final ObjectNode gap = gaps.addObject();
    gap.put("from", from.toString());
    gap.put("to", to.toString());
    gap.put("reason", "unsupported_date");
  }
}
