package se.swedishpolls.publication;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import se.swedishpolls.estimation.CoalitionHistory;
import se.swedishpolls.estimation.EstimateHistory;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The full-precision subset artifact. Each column uses the same stored date axis. */
public final class CoalitionHistoryDocument {
  public static final String SURFACE = "coalition-history";
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private CoalitionHistoryDocument() {}

  static ObjectNode render(
      PublicationDocuments.Identity identity,
      PublicationRun.Results results,
      ModelFreeze freeze,
      Instant publishedAt,
      ArrayNode coveragePeriods) {
    final List<CoalitionHistory.Day> days =
        results.periods().stream()
            .flatMap(period -> period.coalitionHistory().stream())
            .sorted(Comparator.comparing(CoalitionHistory.Day::date))
            .toList();
    if (days.isEmpty()) {
      throw new IllegalStateException("A coalition artifact must contain supported days");
    }
    final ObjectNode node = NODES.objectNode();
    node.put("schemaVersion", 1);
    node.put("aggregationVersion", 1);
    node.put("publicationId", identity.publicationId());
    node.put("modelRunId", identity.runId());
    node.put("sourceSnapshotId", identity.snapshotId());
    node.put("sourceSha256", results.snapshotSha256());
    node.put("lastFieldworkDate", results.lastFieldworkDate().toString());
    node.put("publishedAt", publishedAt.toString());
    node.put("draws", freeze.uncertainty().draws());
    node.put("seed", freeze.uncertainty().seed());
    final ObjectNode precision = node.putObject("precision");
    precision.put("protocolVersion", "coalition-history-1");
    precision.put("referenceDraws", freeze.coalitionPrecision().referenceDraws());
    precision.put("referenceSeed", freeze.coalitionPrecision().referenceSeed());
    precision.put("maxMeanErrorPoints", freeze.coalitionPrecision().maxMeanErrorPoints());
    precision.put("maxEndpointErrorPoints", freeze.coalitionPrecision().maxEndpointErrorPoints());
    final ArrayNode seeds = precision.putArray("seeds");
    freeze.coalitionPrecision().seeds().forEach(seeds::add);
    final ArrayNode evidence = precision.putArray("periods");
    for (final PublicationRun.Period period : results.periods()) {
      period.coalitionPrecision().requirePassed();
      final ObjectNode entry = evidence.addObject();
      entry.put("periodId", period.period().id());
      entry.put("comparedSummaries", period.coalitionPrecision().comparedSummaries());
      entry.put("maxMeanErrorPoints", period.coalitionPrecision().maxMeanErrorPoints());
      entry.put("maxEndpointErrorPoints", period.coalitionPrecision().maxEndpointErrorPoints());
      final ArrayNode checked = entry.putArray("dates");
      period.coalitionPrecision().dates().forEach(date -> checked.add(date.toString()));
    }
    final ArrayNode roster = node.putArray("roster");
    CoalitionHistory.ROSTER.forEach(roster::add);
    final ObjectNode interval = node.putObject("interval");
    interval.put("level", 0.95);
    interval.put("kind", "pointwise_equal_tailed");
    interval.put("quantileMethod", "linear_n_minus_1");
    interval.put("unit", "percent");
    interval.put("hyperparameterTreatment", "conditional_on_frozen_hyperparameters");
    node.set("coveragePeriods", coveragePeriods.deepCopy());
    final ArrayNode dates = node.putArray("dates");
    final ArrayNode fits = node.putArray("fitIds");
    final ArrayNode periods = node.putArray("coveragePeriodIds");
    final ArrayNode gaps = node.putArray("gaps");
    final ArrayNode boundaries = node.putArray("fitBoundaries");
    final List<EstimateHistory.Boundary> declared =
        results.periods().stream()
            .flatMap(period -> period.history().boundaries().stream())
            .toList();
    CoalitionHistory.Day previous = null;
    for (final CoalitionHistory.Day day : days) {
      if (previous != null && !day.date().isAfter(previous.date())) {
        throw new IllegalStateException("Overlapping coalition fits");
      }
      dates.add(day.date().toString());
      fits.add(day.fitId());
      periods.add(day.periodId());
      if (previous == null
          || !previous.fitId().equals(day.fitId())
          || declared.stream().anyMatch(boundary -> boundary.date().equals(day.date()))) {
        final ObjectNode boundary = boundaries.addObject();
        boundary.put("date", day.date().toString());
        boundary.put("fitId", day.fitId());
        boundary.put("coveragePeriodId", day.periodId());
      }
      if (previous != null && day.date().isAfter(previous.date().plusDays(1))) {
        gap(gaps, previous.date().plusDays(1).toString(), day.date().minusDays(1).toString());
      }
      previous = day;
    }
    final ArrayNode subsets = node.putArray("subsets");
    for (int mask = 1; mask < 256; mask++) {
      final ObjectNode subset = subsets.addObject();
      final ArrayNode mean = subset.putArray("mean");
      final ArrayNode lower = subset.putArray("lower");
      final ArrayNode upper = subset.putArray("upper");
      final ArrayNode availability = subset.putArray("availability");
      for (final CoalitionHistory.Day day : days) {
        if (day.subsets().size() != 255) {
          throw new IllegalStateException("Incomplete coalition artifact");
        }
        final CoalitionHistory.Summary summary = day.subsets().get(mask - 1);
        validate(summary);
        mean.add(summary.mean());
        lower.add(summary.lower());
        upper.add(summary.upper());
        availability.add(summary.availability());
      }
    }
    final ObjectNode latest = node.putObject("latest");
    final EstimateHistory.Day last = PublicationDocuments.lastDay(results.headline().history());
    latest.put("date", last.date().toString());
    latest.put("fitId", results.headline().coalitionHistory().getLast().fitId());
    final ObjectNode partyMeans = latest.putObject("partyMeans");
    for (final String party : CoalitionHistory.ROSTER) {
      final EstimateHistory.Estimate estimate = last.components().get(party);
      if (estimate == null) partyMeans.putNull(party);
      else partyMeans.put(party, estimate.mean());
    }
    latest.put(
        "comparableRemainderMean",
        PublicationDocuments.lastRemainder(results.headline().remainder()).mean());
    latest.put("historyIndex", days.indexOf(results.headline().coalitionHistory().getLast()));
    return node;
  }

  private static void validate(CoalitionHistory.Summary summary) {
    if (!summary.availability().equals("available")) {
      if (summary.mean() != null || summary.lower() != null || summary.upper() != null) {
        throw new IllegalStateException("Unavailable coalition values must all be null");
      }
      return;
    }
    if (!Double.isFinite(summary.mean())
        || !Double.isFinite(summary.lower())
        || !Double.isFinite(summary.upper())
        || summary.mean() < 0
        || summary.mean() > 100
        || summary.lower() < 0
        || summary.upper() > 100
        || summary.lower() > summary.upper()) {
      throw new IllegalStateException("Invalid coalition summary");
    }
  }

  private static void gap(ArrayNode gaps, String from, String to) {
    final ObjectNode gap = gaps.addObject();
    gap.put("from", from);
    gap.put("to", to);
    gap.put("reason", "unsupported_date");
  }
}
