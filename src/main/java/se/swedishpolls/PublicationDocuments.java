package se.swedishpolls;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders one model run into the stored documents a publication serves. Everything a request may
 * vary is left to the reader: filters, ranges, sampling and coalition selection all cut these
 * documents rather than the model.
 */
public final class PublicationDocuments {
  private PublicationDocuments() {}

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** The comparable remainder aggregate, published beside the components. */
  public static final String OTHER = "OTHER";

  public static final String NO_VALIDATED_PERIOD = "no_validated_coverage_period";

  /** The identity every dependent response repeats. */
  public record Identity(String publicationId, String runId, long snapshotId) {}

  /** One rendered document and the key it is stored under. */
  public record Document(String surface, String language, ObjectNode body) {
    public Document {
      body = body.deepCopy();
    }

    @Override
    public ObjectNode body() {
      return body.deepCopy();
    }
  }

  public static String latestSurface(String periodId) {
    return "estimates-latest:" + periodId;
  }

  public static String electionsSurface(String periodId) {
    return "elections:" + periodId;
  }

  public static String seatsSurface(int electionYear) {
    return "seats:" + electionYear;
  }

  public static String coalitionsSurface(int electionYear) {
    return "coalitions:" + electionYear;
  }

  public static final String HISTORY_SURFACE = "estimates-history";
  public static final String INSTITUTES_SURFACE = "institutes";

  /** Every document of one publication, in both languages. */
  public static List<Document> all(
      Identity identity, PublicationRun.Results results, ModelFreeze freeze) {
    final List<Document> documents = new ArrayList<>();
    for (final String language : Translations.LANGUAGES) {
      final Translations text = Translations.of(language);
      for (final PublicationRun.Period period : results.periods()) {
        documents.add(
            new Document(
                latestSurface(period.period().id()),
                language,
                latest(identity, results, period, freeze, text)));
        documents.add(
            new Document(
                electionsSurface(period.period().id()),
                language,
                elections(identity, results, period, text)));
      }
      documents.add(new Document(HISTORY_SURFACE, language, history(identity, results, freeze)));
      documents.add(
          new Document(INSTITUTES_SURFACE, language, institutes(identity, results, freeze, text)));
      for (final NationalSeats.Rules rules : results.allocationRules()) {
        final NationalSeats.SeatDraws drawn =
            NationalSeats.allocateDraws(results.headline().draws(), rules);
        documents.add(
            new Document(
                seatsSurface(rules.electionYear()),
                language,
                seats(identity, results, drawn, freeze, text)));
        documents.add(
            new Document(
                coalitionsSurface(rules.electionYear()),
                language,
                coalitions(identity, results, drawn, freeze, text)));
      }
    }
    return List.copyOf(documents);
  }

  private static ObjectNode identityNode(Identity identity) {
    final ObjectNode node = NODES.objectNode();
    node.put("publicationId", identity.publicationId());
    node.put("runId", identity.runId());
    node.put("snapshotId", identity.snapshotId());
    return node;
  }

  private static ObjectNode dependent(Identity identity) {
    final ObjectNode node = NODES.objectNode();
    node.set("publication", identityNode(identity));
    return node;
  }

  // The latest estimate of one coverage period, on the last day its own fit reaches.

  static ObjectNode latest(
      Identity identity,
      PublicationRun.Results results,
      PublicationRun.Period period,
      ModelFreeze freeze,
      Translations text) {
    final EstimateHistory.Day day = lastDay(period.history());
    final ObjectNode node = dependent(identity);
    node.put("lastFieldworkDate", day.date().toString());
    node.put("intervalLevel", results.intervalLevel());
    node.put("coveragePeriod", period.period().id());
    node.set("coveragePeriods", coveragePeriods(results, text));
    final ArrayNode components = node.putArray("components");
    for (final String component : displayComponents(period.period())) {
      final EstimateHistory.Estimate estimate = day.components().get(component);
      if (estimate == null) {
        continue;
      }
      final ObjectNode entry = components.addObject();
      entry.put("component", component);
      entry.put("label", text.component(component));
      quote(entry, "mean", estimate.mean(), freeze);
      interval(entry, estimate.intervals(), results.intervalLevel(), freeze);
    }
    final ComparableRemainder.Day remainder = lastRemainder(period.remainder());
    final ObjectNode comparable = node.putObject("comparableRemainder");
    quote(comparable, "mean", remainder.mean(), freeze);
    interval(comparable, remainder.intervals(), results.intervalLevel(), freeze);
    comparable.put("definition", text.text("remainder.definition"));
    node.set("unavailable", unavailableComponents(results, period.period()));
    return node;
  }

  /** Components with no estimate in this period, reported as null rather than zero. */
  private static ObjectNode unavailableComponents(
      PublicationRun.Results results, Roster.CoveragePeriod period) {
    final ObjectNode unavailable = NODES.objectNode();
    for (final String component : everyComponent(results)) {
      if (displayComponents(period).contains(component)) {
        continue;
      }
      final ObjectNode entry = unavailable.putObject(component);
      entry.putNull("mean");
      entry.putNull("lower");
      entry.putNull("upper");
      entry.put("reason", NO_VALIDATED_PERIOD);
    }
    return unavailable;
  }

  private static ArrayNode coveragePeriods(PublicationRun.Results results, Translations text) {
    final ArrayNode periods = NODES.arrayNode();
    for (final Roster.CoveragePeriod period : results.coveragePeriods()) {
      final ObjectNode entry = periods.addObject();
      entry.put("id", period.id());
      entry.put("from", period.effectiveFrom().toString());
      if (period.effectiveTo() == null) {
        entry.putNull("to");
      } else {
        entry.put("to", period.effectiveTo().toString());
      }
      final ArrayNode roster = entry.putArray("roster");
      period.roster().forEach(roster::add);
      final ArrayNode otherMembers = entry.putArray("otherMembers");
      if (!period.individualFi()) {
        otherMembers.add("FI");
      }
      entry.put("individualFi", period.individualFi());
      entry.put("supportValidated", period.supportValidated());
      entry.put("decision", period.decisionUrl());
      entry.put("otherAlsoIncludes", text.text("coverage.otherAlsoIncludes"));
    }
    return periods;
  }

  // The columnar history: one date axis, one coverage period per date, one series per component.

  static ObjectNode history(Identity identity, PublicationRun.Results results, ModelFreeze freeze) {
    final List<LocalDate> dates = timeline(results);
    final ObjectNode node = dependent(identity);
    node.put("intervalLevel", results.intervalLevel());
    final ObjectNode range = node.putObject("range");
    range.put("from", dates.getFirst().toString());
    range.put("to", dates.getLast().toString());
    range.put("step", 1);
    range.put("inclusive", true);
    range.put("requestedTo", dates.getLast().toString());
    final ArrayNode dateNodes = node.putArray("dates");
    dates.forEach(date -> dateNodes.add(date.toString()));
    final ArrayNode periodByDate = node.putArray("coveragePeriodByDate");
    final Map<LocalDate, PublicationRun.Period> owner = owners(results);
    for (final LocalDate date : dates) {
      final PublicationRun.Period period = owner.get(date);
      if (period == null) {
        periodByDate.addNull();
      } else {
        periodByDate.add(period.period().id());
      }
    }
    final ArrayNode series = node.putArray("series");
    for (final String component : everyComponent(results)) {
      final ObjectNode entry = series.addObject();
      entry.put("component", component);
      final ArrayNode mean = entry.putArray("mean");
      final ArrayNode lower = entry.putArray("lower");
      final ArrayNode upper = entry.putArray("upper");
      for (final LocalDate date : dates) {
        final PublicationRun.Period period = owner.get(date);
        final EstimateHistory.Estimate estimate =
            period == null ? null : estimate(period.history(), date, component);
        if (estimate == null) {
          mean.addNull();
          lower.addNull();
          upper.addNull();
          continue;
        }
        final JointUncertainty.Interval bounds =
            bounds(estimate.intervals(), results.intervalLevel());
        mean.add(freeze.resolution().quote(estimate.mean()));
        lower.add(freeze.resolution().quote(bounds.lower()));
        upper.add(freeze.resolution().quote(bounds.upper()));
      }
    }
    final ArrayNode boundaries = node.putArray("boundaries");
    for (final PublicationRun.Period period : results.periods()) {
      for (final EstimateHistory.Boundary boundary : period.history().boundaries()) {
        final ObjectNode entry = boundaries.addObject();
        entry.put("kind", boundary.kind());
        entry.put("date", boundary.date().toString());
        entry.put("periodId", boundary.periodId());
        entry.put("supportValidated", period.period().supportValidated());
        entry.put("note", boundary.note());
      }
    }
    return node;
  }

  /** Every day any validated period estimates, from the earliest to the latest, with no holes. */
  static List<LocalDate> timeline(PublicationRun.Results results) {
    LocalDate first = null;
    LocalDate last = null;
    for (final PublicationRun.Period period : results.periods()) {
      for (final EstimateHistory.Segment segment : period.history().segments()) {
        first = first == null || segment.from().isBefore(first) ? segment.from() : first;
        last = last == null || segment.to().isAfter(last) ? segment.to() : last;
      }
    }
    if (first == null) {
      throw new IllegalStateException("A publication estimates at least one day");
    }
    final List<LocalDate> dates = new ArrayList<>();
    for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
      dates.add(date);
    }
    return List.copyOf(dates);
  }

  private static Map<LocalDate, PublicationRun.Period> owners(PublicationRun.Results results) {
    final Map<LocalDate, PublicationRun.Period> owner = new LinkedHashMap<>();
    for (final PublicationRun.Period period : results.periods()) {
      for (final EstimateHistory.Segment segment : period.history().segments()) {
        for (LocalDate date = segment.from();
            !date.isAfter(segment.to());
            date = date.plusDays(1)) {
          owner.put(date, period);
        }
      }
    }
    return owner;
  }

  private static EstimateHistory.Estimate estimate(
      EstimateHistory.Estimated estimated, LocalDate date, String component) {
    for (final EstimateHistory.Segment segment : estimated.segments()) {
      if (segment.covers(date)) {
        return segment
            .days()
            .get((int) java.time.temporal.ChronoUnit.DAYS.between(segment.from(), date))
            .components()
            .get(component);
      }
    }
    return null;
  }

  // Institutes, their archived footprint and their house effects.

  static ObjectNode institutes(
      Identity identity, PublicationRun.Results results, ModelFreeze freeze, Translations text) {
    final ObjectNode node = dependent(identity);
    node.put("reference", text.text("institutes.reference"));
    final ArrayNode cycles = node.putArray("electionCycles");
    cycleLabels(results).forEach(cycles::add);
    final ArrayNode institutes = node.putArray("institutes");
    for (final PollQuery.Institute institute : results.institutes().values()) {
      final ObjectNode entry = institutes.addObject();
      entry.put("institute", institute.institute());
      final ArrayNode companies = entry.putArray("companies");
      institute.companies().forEach(companies::add);
      entry.put("polls", institute.polls());
      entry.put(
          "firstCollection",
          institute.firstCollection() == null ? null : institute.firstCollection().toString());
      entry.put(
          "lastCollection",
          institute.lastCollection() == null ? null : institute.lastCollection().toString());
      final ArrayNode eras = entry.putArray("methodEras");
      for (final Map.Entry<String, String> era : institute.methodEras().entrySet()) {
        final ObjectNode method = eras.addObject();
        method.put("id", era.getKey());
        method.put("evidence", era.getValue());
      }
      final ArrayNode effects = entry.putArray("houseEffects");
      for (final PublicationRun.Period period : results.periods()) {
        for (final HouseEffects.Effect effect : period.houseEffects()) {
          if (!matches(effect, institute)) {
            continue;
          }
          final ObjectNode value = effects.addObject();
          value.put("electionCycle", effect.electionCycle());
          value.put("component", effect.component());
          quote(value, "mean", effect.mean(), freeze);
          quote(value, "lower", effect.lower(), freeze);
          quote(value, "upper", effect.upper(), freeze);
          value.put("shrunk", effect.shrunk());
        }
      }
    }
    return node;
  }

  private static boolean matches(HouseEffects.Effect effect, PollQuery.Institute institute) {
    return effect.effect().equals(institute.institute())
        || institute.methodEras().containsKey(effect.effect());
  }

  private static List<String> cycleLabels(PublicationRun.Results results) {
    final LinkedHashSet<String> labels = new LinkedHashSet<>();
    for (final PublicationRun.Period period : results.periods()) {
      for (final HouseEffects.Effect effect : period.houseEffects()) {
        labels.add(effect.electionCycle());
      }
    }
    return List.copyOf(labels);
  }

  // Official election references, grouped the way the compared estimate groups its components.

  static ObjectNode elections(
      Identity identity,
      PublicationRun.Results results,
      PublicationRun.Period period,
      Translations text) {
    final ObjectNode node = dependent(identity);
    node.put("note", text.text("elections.note"));
    final ArrayNode elections = node.putArray("elections");
    for (final ElectionReferences.Election election : results.elections()) {
      final ObjectNode entry = elections.addObject();
      entry.put("electionDate", election.electionDate().toString());
      entry.put("electionYear", election.electionYear());
      entry.put("validVotes", election.validVotes());
      entry.put("sourceUrl", election.sourceUrl());
      entry.put("officialSeatsSourceUrl", election.officialSeatsSourceUrl());
      entry.put("retrievedOn", election.retrievedOn().toString());
      final ObjectNode grouping = entry.putObject("comparableGrouping");
      grouping.put("coveragePeriod", period.period().id());
      final ArrayNode other = grouping.putArray("other");
      // The election components the compared estimate holds outside its roster. FI is separate
      // here only where the roster keeps it separate; the comparable remainder is the same
      // number either way.
      if (!period.period().individualFi()) {
        other.add("FI");
      }
      other.add("RESIDUAL");
      grouping.put("comparableRemainder", text.text("remainder.definition"));
      final ObjectNode values = entry.putObject("results");
      for (final ElectionReferences.Party party : election.results()) {
        final ObjectNode value = values.putObject(party.component());
        value.put("votes", party.votes());
        value.put("officialSeats", party.officialSeats());
      }
    }
    return node;
  }

  // National seats and the ten approved coalition memberships, from one set of joint draws.

  static ObjectNode seats(
      Identity identity,
      PublicationRun.Results results,
      NationalSeats.SeatDraws drawn,
      ModelFreeze freeze,
      Translations text) {
    final NationalSeats.Summary summary = NationalSeats.summarize(drawn, results.intervalLevel());
    final ObjectNode node = dependent(identity);
    node.put("lastFieldworkDate", summary.date().toString());
    node.put("totalSeats", summary.rules().seats());
    node.put("intervalLevel", summary.intervalLevel());
    node.put("note", text.text("seats.note"));
    node.set("allocationRule", allocationRule(summary.rules(), text));
    final ArrayNode parties = node.putArray("parties");
    for (final NationalSeats.PartySeats party : summary.parties()) {
      final ObjectNode entry = parties.addObject();
      entry.put("component", party.component());
      entry.put("label", text.component(party.component()));
      entry.put("pointSeats", party.pointSeats());
      entry.put("meanSeats", freeze.resolution().quote(party.meanSeats()));
      final ArrayNode interval = entry.putArray("seatInterval");
      interval.add(party.lowerSeats());
      interval.add(party.upperSeats());
      entry.put("thresholdProbability", party.thresholdProbability());
    }
    final ObjectNode unavailable = node.putObject("unavailable");
    for (final NationalSeats.Unavailable missing : summary.unavailable()) {
      final ObjectNode entry = unavailable.putObject(missing.component());
      entry.putNull("pointSeats");
      entry.putNull("meanSeats");
      entry.putNull("thresholdProbability");
      entry.put("reason", missing.reason());
    }
    final ArrayNode excluded = node.putArray("excludedFromAllocation");
    summary.excludedFromAllocation().forEach(excluded::add);
    sensitivity(node, results, drawn, text);
    return node;
  }

  private static ObjectNode allocationRule(NationalSeats.Rules rules, Translations text) {
    final ObjectNode node = NODES.objectNode();
    node.put("electionYear", rules.electionYear());
    node.put("seats", rules.seats());
    node.put("firstDivisor", rules.firstDivisor());
    node.put("subsequentDivisorFormula", rules.subsequentDivisorFormula());
    node.put("thresholdPercent", rules.thresholdPercent());
    node.put("thresholdInclusive", rules.thresholdInclusive());
    node.put("otherReceivesSeats", rules.otherReceivesSeats());
    final ArrayNode tieOrder = node.putArray("tieOrder");
    rules.tieOrder().forEach(tieOrder::add);
    node.put("officialTieRule", rules.officialTieRule());
    node.put("constituencyExceptionsIncluded", rules.constituencyExceptionsIncluded());
    node.put("sourceUrl", rules.sourceUrl());
    node.put("tieNote", text.text("seats.tie"));
    return node;
  }

  static ObjectNode coalitions(
      Identity identity,
      PublicationRun.Results results,
      NationalSeats.SeatDraws drawn,
      ModelFreeze freeze,
      Translations text) {
    final Coalitions.Result summary = Coalitions.summarize(drawn, results.intervalLevel());
    final ObjectNode node = dependent(identity);
    node.put("lastFieldworkDate", summary.date().toString());
    node.put("majoritySeats", summary.majoritySeats());
    node.put("intervalLevel", summary.intervalLevel());
    node.put("electionYear", drawn.rules().electionYear());
    final ArrayNode defaults = node.putArray("overviewDefaults");
    summary.overviewDefaults().forEach(defaults::add);
    node.put("note", text.text("coalitions.note"));
    final ArrayNode coalitions = node.putArray("coalitions");
    for (final Coalitions.Seats seats : summary.coalitions()) {
      final ObjectNode entry = coalitions.addObject();
      entry.put("id", seats.id());
      entry.put("label", text.coalition(seats.id()));
      final ArrayNode parties = entry.putArray("parties");
      seats.parties().forEach(parties::add);
      entry.put("pointSeats", seats.pointSeats());
      entry.put("meanSeats", freeze.resolution().quote(seats.meanSeats()));
      final ArrayNode interval = entry.putArray("seatInterval");
      interval.add(seats.lowerSeats());
      interval.add(seats.upperSeats());
      entry.put("majorityProbability", seats.majorityProbability());
    }
    final ObjectNode comparison = node.putObject("comparison");
    final ArrayNode pairs = comparison.putArray("pairs");
    for (final Coalitions.Comparison pair : summary.comparison()) {
      final ObjectNode entry = pairs.addObject();
      entry.put("left", pair.left());
      entry.put("right", pair.right());
      entry.put("leftLeads", pair.leftLeads());
      entry.put("rightLeads", pair.rightLeads());
      entry.put("tied", pair.tied());
    }
    comparison.put("tie", summary.tie());
    sensitivity(node, results, drawn, text);
    return node;
  }

  /**
   * The disclosure the registered alternative fits earn against these same numbers. Each
   * alternative is allocated under the rule of the document it sits in, so the movement is measured
   * on the probabilities the page shows rather than on another election's allocation.
   */
  private static void sensitivity(
      ObjectNode node,
      PublicationRun.Results results,
      NationalSeats.SeatDraws drawn,
      Translations text) {
    final SeatOutcomes.Headline published = SeatOutcomes.headline(drawn);
    final List<SeatOutcomes.Sensitivity> sensitivity =
        results.alternatives().stream()
            .map(
                alternative ->
                    SeatOutcomes.sensitivity(
                        alternative.kind(),
                        alternative.label(),
                        published,
                        SeatOutcomes.headline(
                            NationalSeats.allocateDraws(alternative.draws(), drawn.rules()))))
            .toList();
    final String note = sensitivityNote(sensitivity, text);
    if (note != null) {
      node.put("sensitivity", note);
    }
  }

  /**
   * The registered alternative fits that moved a headline probability far enough to be disclosed,
   * worded in the language of the document they sit beside. Null when none of them did, so a
   * publication with nothing to disclose carries no field rather than an empty one.
   */
  static String sensitivityNote(List<SeatOutcomes.Sensitivity> sensitivity, Translations text) {
    final List<String> movements =
        sensitivity.stream()
            .filter(SeatOutcomes.Sensitivity::needsDisclosure)
            .map(alternative -> movement(alternative, text))
            .toList();
    return movements.isEmpty() ? null : String.join(" ", movements);
  }

  private static String movement(SeatOutcomes.Sensitivity alternative, Translations text) {
    final String quantity = alternative.largestMovement();
    final int separator = quantity.indexOf(':');
    final String kind = quantity.substring(0, separator);
    final String subject = quantity.substring(separator + 1);
    final String label =
        "threshold".equals(kind) ? text.component(subject) : text.coalition(subject);
    return Translations.fill(
        Translations.fill(
            Translations.fill(
                text.text("sensitivity.movement"),
                "quantity",
                Translations.fill(text.text("sensitivity.quantity." + kind), "label", label)),
            "points",
            Long.toString(Math.round(alternative.maxAbsoluteDifferencePoints()))),
        "alternative",
        text.text("sensitivity.alternative." + alternative.label()));
  }

  // Shared helpers.

  /** The components a period publishes individually, then the aggregate holding the rest. */
  public static List<String> displayComponents(Roster.CoveragePeriod period) {
    final List<String> components = new ArrayList<>(period.roster());
    components.add(OTHER);
    return List.copyOf(components);
  }

  /** Every component any period of this run publishes, in a stable display order. */
  public static List<String> everyComponent(PublicationRun.Results results) {
    final LinkedHashSet<String> components = new LinkedHashSet<>();
    for (final PublicationRun.Period period : results.periods()) {
      components.addAll(period.period().roster());
    }
    components.add(OTHER);
    for (final Roster.CoveragePeriod period : results.coveragePeriods()) {
      components.addAll(period.roster());
    }
    return List.copyOf(components);
  }

  static EstimateHistory.Day lastDay(EstimateHistory.Estimated estimated) {
    return estimated.segments().getLast().days().getLast();
  }

  static ComparableRemainder.Day lastRemainder(ComparableRemainder.Estimated estimated) {
    return estimated.segments().getLast().days().getLast();
  }

  static JointUncertainty.Interval bounds(List<JointUncertainty.Interval> intervals, double level) {
    return intervals.stream()
        .filter(interval -> Math.abs(interval.level() - level) < 1e-12)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No interval at level " + level));
  }

  private static void quote(ObjectNode node, String field, double value, ModelFreeze freeze) {
    node.put(field, freeze.resolution().quote(value));
  }

  private static void interval(
      ObjectNode node,
      List<JointUncertainty.Interval> intervals,
      double level,
      ModelFreeze freeze) {
    final JointUncertainty.Interval bounds = bounds(intervals, level);
    node.put("lower", freeze.resolution().quote(bounds.lower()));
    node.put("upper", freeze.resolution().quote(bounds.upper()));
  }
}
