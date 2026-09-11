package se.swedishpolls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frozen v1 contract: every surface, its example and the rules a later publication must keep.
 */
class ApiContractTest {
  private static final List<String> SURFACES =
      List.of(
          "publication",
          "estimates-latest",
          "estimates-history",
          "polls",
          "polls-csv",
          "institutes",
          "elections",
          "seats",
          "coalitions");
  private static final List<String> ROSTER = List.of("S", "M", "SD", "V", "C", "KD", "L", "MP");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static JsonNode read(String name) throws IOException {
    try (final java.io.InputStream input =
        ApiContractTest.class.getResourceAsStream("/api/v1/" + name)) {
      assertNotNull(input, name + " is missing from the frozen contract");
      return JSON.readTree(input);
    }
  }

  private static String readText(String name) throws IOException {
    try (final java.io.InputStream input =
        ApiContractTest.class.getResourceAsStream("/api/v1/" + name)) {
      assertNotNull(input, name + " is missing from the frozen contract");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static List<String> texts(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
  }

  private static List<String> field(JsonNode array, String name) {
    return StreamSupport.stream(array.spliterator(), false)
        .map(element -> element.get(name).asString())
        .toList();
  }

  @Test
  void freezesEveryV1SurfaceWithOneExampleAndSharedRequestRules() throws Exception {
    final tools.jackson.databind.JsonNode contract = read("contract.json");
    assertEquals("v1", contract.get("version").asString());
    assertEquals("/api/v1", contract.get("basePath").asString());

    final tools.jackson.databind.JsonNode surfaces = contract.get("surfaces");
    assertEquals(SURFACES, field(surfaces, "id"));
    for (tools.jackson.databind.JsonNode surface : surfaces) {
      final java.lang.String id = surface.get("id").asString();
      assertTrue(
          surface.get("path").asString().startsWith("/api/v1"),
          id + " must live under the versioned base path");
      assertTrue(List.of("current", "immutable").contains(surface.get("cache").asString()), id);
      assertFalse(readText(surface.get("example").asString()).isBlank(), id);
      assertTrue(
          field(surface.get("query"), "name").contains("language"),
          id + " must name the translated representation it returns");
      // A page resolves the publication once and pins every dependent request to it.
      if (!id.equals("publication")) {
        assertTrue(field(surface.get("query"), "name").contains("publication"), id);
        assertTrue(
            texts(surface.get("errors")).contains("unknown_publication"),
            id + " must reject an unknown pinned publication rather than fall back");
      }
    }
    // Only the poll table pages; the download applies the same filters to every matching row.
    assertEquals(
        List.of("page", "pageSize"),
        field(surfaces.get(SURFACES.indexOf("polls")).get("paging"), "name"));
    assertFalse(surfaces.get(SURFACES.indexOf("polls-csv")).get("paging").asBoolean());

    final tools.jackson.databind.JsonNode rules = contract.get("rules");
    assertEquals(
        List.of(1, 3, 7), texts(rules.get("displaySteps")).stream().map(Integer::valueOf).toList());
    assertEquals(300, rules.get("currentMaxAgeSeconds").asInt());
    assertEquals("inclusive", rules.get("dateBounds").asString());
    assertEquals("null", rules.get("missingValue").asString());
    assertTrue(rules.get("immutableMaxAgeSeconds").asInt() >= 31536000);
    assertTrue(rules.get("etag").asString().contains("content"));
    assertTrue(
        rules.get("probabilities").asString().contains(">99%"),
        "Probabilities stay unrounded and are never displayed as 0% or 100%");
    assertTrue(rules.get("change30d").asString().contains("coverage boundary"));
    // A sensitivity movement is disclosed beside the numbers it moves; it never blocks publication.
    assertTrue(rules.get("sensitivity").asString().contains("10 percentage points"));
    assertTrue(rules.get("sensitivity").asString().contains("never a block"));
    assertTrue(rules.get("assets").asString().contains("immutable"));

    final java.util.LinkedHashMap<java.lang.String, java.lang.Integer> errors =
        new LinkedHashMap<String, Integer>();
    for (tools.jackson.databind.JsonNode error : contract.get("errors"))
      errors.put(error.get("code").asString(), error.get("status").asInt());
    assertEquals(
        Map.of(
            "unknown_version",
            404,
            "unknown_route",
            404,
            "unknown_publication",
            404,
            "invalid_filter",
            400,
            "estimates_unavailable",
            503),
        errors);
    assertFalse(
        read("examples/errors.json").get("estimates_unavailable").get("body").has("estimates"),
        "A request before the first publication returns unavailability, never zero estimates");
  }

  @Test
  void identifiesThePublicationAndModelRunOnEveryDependentResponse() throws Exception {
    final tools.jackson.databind.JsonNode publication = read("examples/publication.json");
    for (java.lang.String name :
        List.of(
            "publicationId",
            "publishedAt",
            "sourceCheckedAt",
            "lastFieldworkDate",
            "stale",
            "permalink")) assertTrue(publication.has(name), name);
    for (java.lang.String name : List.of("runId", "codeVersion", "estimatorVersion", "seed"))
      assertTrue(publication.get("modelRun").has(name), name);
    for (java.lang.String name : List.of("snapshotId", "sha256", "sourceUrl", "capturedAt"))
      assertTrue(publication.get("snapshot").has(name), name);
    // The estimate's fieldwork date, the source check and the publication time are three distinct
    // instants.
    assertEquals(
        3,
        List.of(
                publication.get("publishedAt").asString(),
                publication.get("sourceCheckedAt").asString(),
                publication.get("lastFieldworkDate").asString())
            .stream()
            .distinct()
            .count());

    final java.util.Map<java.lang.String, java.lang.String> identity =
        Map.of(
            "publicationId",
            publication.get("publicationId").asString(),
            "runId",
            publication.get("modelRun").get("runId").asString(),
            "snapshotId",
            publication.get("snapshot").get("snapshotId").asString());
    for (java.lang.String name :
        List.of(
            "examples/estimates-latest.json",
            "examples/estimates-history.json",
            "examples/polls.json",
            "examples/institutes.json",
            "examples/elections.json",
            "examples/seats.json",
            "examples/coalitions.json")) {
      final tools.jackson.databind.JsonNode dependent = read(name).get("publication");
      assertEquals(
          identity,
          dependent.properties().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      Map.Entry::getKey, entry -> entry.getValue().asString())),
          name + " must identify its publication and model run");
    }
  }

  @Test
  void statesCoveragePeriodsRosterAndOtherMembershipWithUnsupportedValuesNull() throws Exception {
    final tools.jackson.databind.JsonNode latest = read("examples/estimates-latest.json");
    final tools.jackson.databind.JsonNode periods = latest.get("coveragePeriods");
    assertEquals(List.of("eight_party_2010", "fi_candidate_2014_2018"), field(periods, "id"));

    final tools.jackson.databind.JsonNode current = periods.get(0);
    assertEquals("2010-01-01", current.get("from").asString());
    assertTrue(current.get("to").isNull());
    assertEquals(ROSTER, texts(current.get("roster")));
    assertTrue(
        texts(current.get("otherMembers")).contains("FI"), "OTHER membership must be explicit");
    assertTrue(current.get("supportValidated").asBoolean());

    final tools.jackson.databind.JsonNode candidate = periods.get(1);
    assertEquals(
        List.of("2014-04-09", "2018-09-07"),
        List.of(candidate.get("from").asString(), candidate.get("to").asString()));
    assertTrue(texts(candidate.get("roster")).contains("FI"));
    assertFalse(
        candidate.get("supportValidated").asBoolean(), "No individual FI estimate is enabled yet");
    assertFalse(texts(candidate.get("otherMembers")).contains("FI"));

    final tools.jackson.databind.JsonNode components = latest.get("components");
    assertEquals(concat(ROSTER, "OTHER"), field(components, "component"));
    for (tools.jackson.databind.JsonNode component : components) {
      assertTrue(component.get("lower").asDouble() <= component.get("mean").asDouble());
      assertTrue(component.get("mean").asDouble() <= component.get("upper").asDouble());
    }
    assertEquals("eight_party_2010", latest.get("coveragePeriod").asString());
    assertTrue(
        latest.get("unavailable").get("FI").get("mean").isNull(),
        "An unsupported individual estimate is null, never zero");
    assertTrue(
        latest.get("comparableRemainder").get("mean").isDouble(),
        "The comparable remainder includes FI in every period");
  }

  @Test
  void usesOneColumnarHistoryWithInclusiveRangesAndBoundedDisplaySampling() throws Exception {
    final tools.jackson.databind.JsonNode history = read("examples/estimates-history.json");
    final tools.jackson.databind.JsonNode range = history.get("range");
    assertTrue(range.get("inclusive").asBoolean());
    assertEquals(3, range.get("step").asInt());
    final java.util.List<java.lang.String> dates = texts(history.get("dates"));
    assertEquals(range.get("from").asString(), dates.getFirst());
    assertEquals(
        range.get("to").asString(),
        dates.getLast(),
        "Sampling retains the last requested supported date");
    assertTrue(LocalDate.parse(dates.getFirst()).isBefore(LocalDate.parse(dates.getLast())));

    for (tools.jackson.databind.JsonNode series : history.get("series")) {
      for (java.lang.String name : List.of("mean", "lower", "upper"))
        assertEquals(
            dates.size(), series.get(name).size(), series.get("component").asString() + "." + name);
      if (series.get("component").asString().equals("FI"))
        for (tools.jackson.databind.JsonNode value : series.get("mean"))
          assertTrue(value.isNull(), "Unsupported dates stay null");
    }
    assertEquals(dates.size(), history.get("coveragePeriodByDate").size());
    assertEquals(List.of("coverage_period_start"), field(history.get("boundaries"), "kind"));
    assertFalse(
        history.get("change30d").get("available").asBoolean(),
        "A 30-day change is unavailable when its comparison date is unsupported");
    assertTrue(history.get("change30d").get("change").isNull());
  }

  @Test
  void matchesPollJsonCsvAndTheirFiltersAgainstThePinnedSnapshot() throws Exception {
    final tools.jackson.databind.JsonNode polls = read("examples/polls.json");
    final java.util.ArrayList<java.util.Map<java.lang.String, java.lang.String>> rows =
        new ArrayList<Map<String, String>>();
    try (final org.apache.commons.csv.CSVParser csv =
        CSVFormat.RFC4180
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get()
            .parse(new java.io.StringReader(readText("examples/polls.csv")))) {
      for (org.apache.commons.csv.CSVRecord record : csv) rows.add(record.toMap());
    }
    assertEquals(polls.get("polls").size(), rows.size());

    final tools.jackson.databind.JsonNode surfaces = read("contract.json").get("surfaces");
    final tools.jackson.databind.JsonNode table = surfaces.get(SURFACES.indexOf("polls"));
    final tools.jackson.databind.JsonNode download = surfaces.get(SURFACES.indexOf("polls-csv"));
    assertEquals(
        table.get("query"),
        download.get("query"),
        "The CSV download applies the poll table's filters");
    assertTrue(field(table.get("query"), "name").containsAll(polls.get("filters").propertyNames()));

    final byte[] bytes;
    try (final java.io.InputStream input = getClass().getResourceAsStream("/polls/audit.csv")) {
      bytes = input.readAllBytes();
    }
    final java.util.Map<java.lang.String, se.swedishpolls.source.PollCsv.Poll> source =
        PollCsv.parse(bytes).stream()
            .filter(PollCsv.Poll::eligible)
            .collect(
                java.util.stream.Collectors.toMap(
                    poll -> poll.institute() + "/" + poll.publicationDate(),
                    poll -> poll,
                    (first, second) -> first));

    for (int index = 0; index < rows.size(); index++) {
      final java.util.Map<java.lang.String, java.lang.String> row = rows.get(index);
      final tools.jackson.databind.JsonNode json = polls.get("polls").get(index);
      final se.swedishpolls.source.PollCsv.Poll poll =
          source.get(row.get("institute") + "/" + row.get("publication_date"));
      assertNotNull(poll, "The example must quote an eligible poll from the pinned snapshot");
      assertEquals(poll.collectionFrom().toString(), row.get("collection_from"));
      assertEquals(poll.collectionTo().toString(), row.get("collection_to"));
      assertEquals(poll.collectionTo().toString(), json.get("collectionTo").asString());
      assertEquals(poll.sampleSize().toPlainString(), row.get("sample_size"));
      assertEquals(poll.denominatorNote(), row.get("denominator_note"));
      assertEquals(poll.surveyType(), row.get("survey_type"));
      for (java.lang.String party : concat(ROSTER, "FI")) {
        final java.math.BigDecimal share = poll.shares().get(party);
        final tools.jackson.databind.JsonNode reported = json.get("shares").get(party);
        // Source precision survives both representations without rounding, filling or a zero for a
        // missing share.
        assertEquals(share == null ? "" : share.toPlainString(), row.get(party));
        assertEquals(
            share == null ? "" : share.toPlainString(),
            reported.isNull() ? "" : reported.asString());
      }
      assertEquals(poll.remainder(), new BigDecimal(row.get("other")));
      assertEquals("eight_party_2010", row.get("coverage_period"));
    }
  }

  @Test
  void separatesPointSeatsFromPosteriorMeansAndKeepsTheTenApprovedCoalitions() throws Exception {
    final tools.jackson.databind.JsonNode seats = read("examples/seats.json");
    assertEquals(349, seats.get("totalSeats").asInt());
    int allocated = 0;
    for (tools.jackson.databind.JsonNode party : seats.get("parties")) {
      assertTrue(party.get("pointSeats").isInt());
      allocated += party.get("pointSeats").asInt();
      assertTrue(
          party.get("meanSeats").isDouble(),
          "Posterior mean seats stay distinct from the point allocation");
      assertEquals(2, party.get("seatInterval").size());
      assertTrue(party.get("thresholdProbability").asDouble() <= 1.0);
    }
    assertEquals(349, allocated);
    assertFalse(
        field(seats.get("parties"), "component").contains("OTHER"),
        "OTHER receives no seats as a group");
    assertEquals(1.2, seats.get("allocationRule").get("firstDivisor").asDouble());
    assertEquals(4.0, seats.get("allocationRule").get("thresholdPercent").asDouble());
    assertTrue(
        seats.get("sensitivity").asString().contains("percentage points"),
        "A disclosed movement is part of the frozen seats shape");

    final tools.jackson.databind.JsonNode coalitions = read("examples/coalitions.json");
    assertEquals(175, coalitions.get("majoritySeats").asInt());
    assertEquals(
        seats.get("sensitivity").asString(),
        coalitions.get("sensitivity").asString(),
        "Both pages read the same headline probabilities, so they disclose the same movement");
    assertEquals(
        List.of("tido", "opposition", "left", "s_m"), texts(coalitions.get("overviewDefaults")));
    final java.util.LinkedHashMap<java.lang.String, java.util.List<java.lang.String>> memberships =
        new LinkedHashMap<String, List<String>>();
    for (tools.jackson.databind.JsonNode coalition : coalitions.get("coalitions")) {
      memberships.put(coalition.get("id").asString(), texts(coalition.get("parties")));
      assertTrue(coalition.get("majorityProbability").asDouble() <= 1.0);
    }
    assertEquals(
        Map.ofEntries(
            Map.entry("left", List.of("S", "V", "MP")),
            Map.entry("right", List.of("M", "L", "C", "KD")),
            Map.entry("tido", List.of("M", "L", "KD", "SD")),
            Map.entry("opposition", List.of("S", "C", "V", "MP")),
            Map.entry("s_c_mp", List.of("S", "C", "MP")),
            Map.entry("s_m", List.of("S", "M")),
            Map.entry("c_l_mp_s", List.of("C", "L", "MP", "S")),
            Map.entry("m_kd_sd", List.of("M", "KD", "SD")),
            Map.entry("s_sd", List.of("S", "SD")),
            Map.entry("m_sd", List.of("M", "SD"))),
        memberships);
  }

  @Test
  void exposesInstituteHouseEffectsAndElectionReferencesWithTheirOwnGrouping() throws Exception {
    for (tools.jackson.databind.JsonNode institute :
        read("examples/institutes.json").get("institutes")) {
      assertTrue(institute.has("methodEras"));
      for (tools.jackson.databind.JsonNode effect : institute.get("houseEffects"))
        assertTrue(
            effect.has("electionCycle") && effect.has("component") && effect.has("mean"),
            "House effects are relative to the institute ensemble within one election cycle");
    }

    final tools.jackson.databind.JsonNode latest =
        read("examples/elections.json").get("elections").get(0);
    assertEquals("2022-09-11", latest.get("electionDate").asString());
    assertEquals(6477970, latest.get("validVotes").asLong());
    assertEquals(3157, latest.get("results").get("FI").get("votes").asLong());
    assertEquals(0, latest.get("results").get("FI").get("officialSeats").asInt());
    assertEquals(
        349,
        latest.get("results").values().stream()
            .mapToInt(party -> party.get("officialSeats").asInt())
            .sum());
    assertEquals(
        "eight_party_2010", latest.get("comparableGrouping").get("coveragePeriod").asString());
  }

  @Test
  void documentsEveryFrozenSurfaceInTheContractDocument() throws Exception {
    final java.lang.String doc = Files.readString(Path.of("docs/api-contract.md"));
    for (tools.jackson.databind.JsonNode surface : read("contract.json").get("surfaces")) {
      assertTrue(
          doc.contains(surface.get("path").asString()),
          surface.get("path").asString() + " is undocumented");
      assertTrue(
          doc.contains(surface.get("example").asString()),
          surface.get("example").asString() + " is unreferenced");
    }
  }

  private static List<String> concat(List<String> values, String extra) {
    final java.util.ArrayList<java.lang.String> all = new ArrayList<>(values);
    all.add(extra);
    return List.copyOf(all);
  }
}
