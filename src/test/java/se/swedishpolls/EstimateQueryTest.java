package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.swedishpolls.estimation.EstimateHistory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class EstimateQueryTest {
  private static final LocalDate START = LocalDate.of(2020, 1, 1);
  private static final int DAYS = 100;

  /** A stored history of one hundred days, with a membership boundary two thirds in. */
  private static ObjectNode stored(LocalDate boundary) {
    final ObjectNode node = JsonNodeFactory.instance.objectNode();
    final ArrayNode dates = node.putArray("dates");
    final ArrayNode periods = node.putArray("coveragePeriodByDate");
    for (int day = 0; day < DAYS; day++) {
      final LocalDate date = START.plusDays(day);
      dates.add(date.toString());
      periods.add(boundary != null && !date.isBefore(boundary) ? "later" : "earlier");
    }
    final ArrayNode series = node.putArray("series");
    final ObjectNode s = series.addObject();
    s.put("component", "S");
    final ArrayNode mean = s.putArray("mean");
    final ArrayNode lower = s.putArray("lower");
    final ArrayNode upper = s.putArray("upper");
    for (int day = 0; day < DAYS; day++) {
      mean.add(30.0 + day / 10.0);
      lower.add(29.0 + day / 10.0);
      upper.add(31.0 + day / 10.0);
    }
    final ArrayNode boundaries = node.putArray("boundaries");
    if (boundary != null) {
      final ObjectNode entry = boundaries.addObject();
      entry.put("kind", EstimateHistory.COVERAGE_PERIOD_START);
      entry.put("date", boundary.toString());
      entry.put("periodId", "later");
      entry.put("supportValidated", true);
      entry.put("note", "a break is not voter movement");
    }
    return node;
  }

  @Test
  void aBoundedStepSamplesTheRangeAndRetainsTheLastRequestedSupportedDate() {
    final LocalDate to = START.plusDays(50);
    final ObjectNode sampled =
        EstimateQuery.sample(stored(null), new EstimateQuery.Range(START, to, 7, null));
    final List<String> dates = strings(sampled.get("dates"));
    assertEquals(START.toString(), dates.getFirst());
    assertEquals(to.toString(), dates.getLast(), "The last requested supported date is retained");
    assertEquals(dates.size(), sampled.get("coveragePeriodByDate").size());
    for (final JsonNode stream : sampled.get("series")) {
      assertEquals(dates.size(), stream.get("mean").size());
      assertEquals(dates.size(), stream.get("lower").size());
      assertEquals(dates.size(), stream.get("upper").size());
    }
    assertEquals(7, sampled.get("range").get("step").intValue());
    assertTrue(sampled.get("range").get("inclusive").booleanValue());
    assertEquals(to.toString(), sampled.get("range").get("requestedTo").asString());
  }

  @Test
  void samplingSelectsStoredDaysAndNeverInterpolatesBetweenThem() {
    final ObjectNode whole = stored(null);
    final ObjectNode sampled =
        EstimateQuery.sample(whole, new EstimateQuery.Range(null, null, 3, null));
    final List<String> dates = strings(sampled.get("dates"));
    final List<String> all = strings(whole.get("dates"));
    assertTrue(all.containsAll(dates));
    final JsonNode mean = sampled.get("series").get(0).get("mean");
    for (int index = 0; index < dates.size(); index++) {
      assertEquals(
          whole.get("series").get(0).get("mean").get(all.indexOf(dates.get(index))).doubleValue(),
          mean.get(index).doubleValue());
    }
  }

  @Test
  void aCoveragePeriodFilterKeepsOnlyThatPeriodsDays() {
    final LocalDate boundary = START.plusDays(60);
    final ObjectNode sampled =
        EstimateQuery.sample(stored(boundary), new EstimateQuery.Range(null, null, 1, "later"));
    final List<String> dates = strings(sampled.get("dates"));
    assertEquals(boundary.toString(), dates.getFirst());
    for (final JsonNode period : sampled.get("coveragePeriodByDate")) {
      assertEquals("later", period.asString());
    }
  }

  @Test
  void aBoundaryInsideTheRangeSurvivesSampling() {
    final LocalDate boundary = START.plusDays(60);
    final ObjectNode sampled =
        EstimateQuery.sample(stored(boundary), new EstimateQuery.Range(null, null, 7, null));
    assertEquals(1, sampled.get("boundaries").size());
    assertEquals(boundary.toString(), sampled.get("boundaries").get(0).get("date").asString());
  }

  @Test
  void aBoundaryOutsideTheRangeIsNotReported() {
    final LocalDate boundary = START.plusDays(60);
    final ObjectNode sampled =
        EstimateQuery.sample(
            stored(boundary), new EstimateQuery.Range(START, START.plusDays(40), 1, null));
    assertTrue(sampled.get("boundaries").isEmpty());
  }

  @Test
  void theThirtyDayChangeStaysInsideOneSupportedFit() {
    final ObjectNode sampled =
        EstimateQuery.sample(stored(null), new EstimateQuery.Range(null, null, 1, null));
    final JsonNode change = sampled.get("change30d");
    assertTrue(change.get("available").booleanValue());
    assertEquals(
        START.plusDays(DAYS - 1).minusDays(30).toString(), change.get("comparisonDate").asString());
    assertEquals(3.0, change.get("change").get("S").doubleValue(), 1e-9);
    assertTrue(change.get("reason").isNull());
  }

  @Test
  void aComparisonBeforeTheSupportedHistoryHasNoChangeAndSaysWhy() {
    final ObjectNode sampled =
        EstimateQuery.sample(
            stored(null), new EstimateQuery.Range(START, START.plusDays(10), 1, null));
    final JsonNode change = sampled.get("change30d");
    assertFalse(change.get("available").booleanValue());
    assertTrue(change.get("change").isNull());
    assertEquals(EstimateQuery.OUTSIDE_HISTORY, change.get("reason").asString());
  }

  @Test
  void aComparisonAcrossAMembershipBoundaryHasNoChangeAndSaysWhy() {
    final LocalDate boundary = START.plusDays(80);
    final ObjectNode sampled =
        EstimateQuery.sample(stored(boundary), new EstimateQuery.Range(null, null, 1, null));
    final JsonNode change = sampled.get("change30d");
    assertFalse(change.get("available").booleanValue());
    assertEquals(EstimateQuery.ACROSS_BOUNDARY, change.get("reason").asString());
  }

  private static List<String> strings(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false)
        .map(JsonNode::asString)
        .toList();
  }
}
