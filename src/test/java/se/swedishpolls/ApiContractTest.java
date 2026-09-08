package se.swedishpolls;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** The frozen v1 contract: every surface, its example and the rules a later publication must keep. */
class ApiContractTest {
    private static final List<String> SURFACES = List.of("publication", "estimates-latest", "estimates-history",
            "polls", "polls-csv", "institutes", "elections", "seats", "coalitions");
    private static final List<String> ROSTER = List.of("S", "M", "SD", "V", "C", "KD", "L", "MP");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode read(String name) throws IOException {
        try (var input = ApiContractTest.class.getResourceAsStream("/api/v1/" + name)) {
            assertNotNull(input, name + " is missing from the frozen contract");
            return JSON.readTree(input);
        }
    }

    private static String readText(String name) throws IOException {
        try (var input = ApiContractTest.class.getResourceAsStream("/api/v1/" + name)) {
            assertNotNull(input, name + " is missing from the frozen contract");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }

    private static List<String> field(JsonNode array, String name) {
        return StreamSupport.stream(array.spliterator(), false).map(element -> element.get(name).asString()).toList();
    }

    @Test
    void freezesEveryV1SurfaceWithOneExampleAndSharedRequestRules() throws Exception {
        var contract = read("contract.json");
        assertEquals("v1", contract.get("version").asString());
        assertEquals("/api/v1", contract.get("basePath").asString());

        var surfaces = contract.get("surfaces");
        assertEquals(SURFACES, field(surfaces, "id"));
        for (var surface : surfaces) {
            var id = surface.get("id").asString();
            assertTrue(surface.get("path").asString().startsWith("/api/v1"), id + " must live under the versioned base path");
            assertTrue(List.of("current", "immutable").contains(surface.get("cache").asString()), id);
            assertFalse(readText(surface.get("example").asString()).isBlank(), id);
            // A page resolves the publication once and pins every dependent request to it.
            if (!id.equals("publication"))
                assertTrue(field(surface.get("query"), "name").contains("publication"), id);
        }

        var rules = contract.get("rules");
        assertEquals(List.of(1, 3, 7), texts(rules.get("displaySteps")).stream().map(Integer::valueOf).toList());
        assertEquals(300, rules.get("currentMaxAgeSeconds").asInt());
        assertEquals("inclusive", rules.get("dateBounds").asString());
        assertEquals("null", rules.get("missingValue").asString());
        assertTrue(rules.get("immutableMaxAgeSeconds").asInt() >= 31536000);
        assertTrue(rules.get("etag").asString().contains("content"));

        var errors = new LinkedHashMap<String, Integer>();
        for (var error : contract.get("errors")) errors.put(error.get("code").asString(), error.get("status").asInt());
        assertEquals(Map.of("unknown_version", 404, "unknown_route", 404, "unknown_publication", 404,
                "invalid_filter", 400, "estimates_unavailable", 503), errors);
        assertFalse(read("examples/errors.json").get("estimates_unavailable").get("body").has("estimates"),
                "A request before the first publication returns unavailability, never zero estimates");
    }

    @Test
    void identifiesThePublicationAndModelRunOnEveryDependentResponse() throws Exception {
        var publication = read("examples/publication.json");
        for (var name : List.of("publicationId", "publishedAt", "sourceCheckedAt", "lastFieldworkDate", "stale", "permalink"))
            assertTrue(publication.has(name), name);
        for (var name : List.of("runId", "codeVersion", "estimatorVersion", "seed"))
            assertTrue(publication.get("modelRun").has(name), name);
        for (var name : List.of("snapshotId", "sha256", "sourceUrl", "capturedAt"))
            assertTrue(publication.get("snapshot").has(name), name);
        // The estimate's fieldwork date, the source check and the publication time are three distinct instants.
        assertEquals(3, List.of(publication.get("publishedAt").asString(), publication.get("sourceCheckedAt").asString(),
                publication.get("lastFieldworkDate").asString()).stream().distinct().count());

        var identity = Map.of("publicationId", publication.get("publicationId").asString(),
                "runId", publication.get("modelRun").get("runId").asString(),
                "snapshotId", publication.get("snapshot").get("snapshotId").asString());
        for (var name : List.of("examples/estimates-latest.json", "examples/estimates-history.json", "examples/polls.json",
                "examples/institutes.json", "examples/elections.json", "examples/seats.json", "examples/coalitions.json")) {
            var dependent = read(name).get("publication");
            assertEquals(identity, dependent.properties().stream()
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().asString())),
                    name + " must identify its publication and model run");
        }
    }

    @Test
    void statesCoveragePeriodsRosterAndOtherMembershipWithUnsupportedValuesNull() throws Exception {
        var latest = read("examples/estimates-latest.json");
        var periods = latest.get("coveragePeriods");
        assertEquals(List.of("eight_party_2010", "fi_candidate_2014_2018"), field(periods, "id"));

        var current = periods.get(0);
        assertEquals("2010-01-01", current.get("from").asString());
        assertTrue(current.get("to").isNull());
        assertEquals(ROSTER, texts(current.get("roster")));
        assertTrue(texts(current.get("otherMembers")).contains("FI"), "OTHER membership must be explicit");
        assertTrue(current.get("supportValidated").asBoolean());

        var candidate = periods.get(1);
        assertEquals(List.of("2014-04-09", "2018-09-07"), List.of(candidate.get("from").asString(), candidate.get("to").asString()));
        assertTrue(texts(candidate.get("roster")).contains("FI"));
        assertFalse(candidate.get("supportValidated").asBoolean(), "No individual FI estimate is enabled yet");
        assertFalse(texts(candidate.get("otherMembers")).contains("FI"));

        var components = latest.get("components");
        assertEquals(concat(ROSTER, "OTHER"), field(components, "component"));
        for (var component : components) {
            assertTrue(component.get("lower").asDouble() <= component.get("mean").asDouble());
            assertTrue(component.get("mean").asDouble() <= component.get("upper").asDouble());
        }
        assertEquals("eight_party_2010", latest.get("coveragePeriod").asString());
        assertTrue(latest.get("unavailable").get("FI").get("mean").isNull(),
                "An unsupported individual estimate is null, never zero");
        assertTrue(latest.get("comparableRemainder").get("mean").isDouble(),
                "The comparable remainder includes FI in every period");
    }

    @Test
    void usesOneColumnarHistoryWithInclusiveRangesAndBoundedDisplaySampling() throws Exception {
        var history = read("examples/estimates-history.json");
        var range = history.get("range");
        assertTrue(range.get("inclusive").asBoolean());
        assertEquals(3, range.get("step").asInt());
        var dates = texts(history.get("dates"));
        assertEquals(range.get("from").asString(), dates.getFirst());
        assertEquals(range.get("to").asString(), dates.getLast(), "Sampling retains the last requested supported date");
        assertTrue(LocalDate.parse(dates.getFirst()).isBefore(LocalDate.parse(dates.getLast())));

        for (var series : history.get("series")) {
            for (var name : List.of("mean", "lower", "upper"))
                assertEquals(dates.size(), series.get(name).size(), series.get("component").asString() + "." + name);
            if (series.get("component").asString().equals("FI"))
                for (var value : series.get("mean")) assertTrue(value.isNull(), "Unsupported dates stay null");
        }
        assertEquals(dates.size(), history.get("coveragePeriodByDate").size());
        assertEquals(List.of("coverage_period_start"), field(history.get("boundaries"), "kind"));
        assertFalse(history.get("change30d").get("available").asBoolean(),
                "A 30-day change is unavailable when its comparison date is unsupported");
        assertTrue(history.get("change30d").get("change").isNull());
    }

    @Test
    void matchesPollJsonCsvAndTheirFiltersAgainstThePinnedSnapshot() throws Exception {
        var polls = read("examples/polls.json");
        var rows = new ArrayList<Map<String, String>>();
        try (var csv = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
                .parse(new java.io.StringReader(readText("examples/polls.csv")))) {
            for (var record : csv) rows.add(record.toMap());
        }
        assertEquals(polls.get("polls").size(), rows.size());

        var surfaces = read("contract.json").get("surfaces");
        var table = surfaces.get(SURFACES.indexOf("polls"));
        var download = surfaces.get(SURFACES.indexOf("polls-csv"));
        assertEquals(table.get("query"), download.get("query"), "The CSV download applies the poll table's filters");
        assertTrue(field(table.get("query"), "name").containsAll(polls.get("filters").propertyNames()));

        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/polls/audit.csv")) { bytes = input.readAllBytes(); }
        var source = PollCsv.parse(bytes).stream().filter(PollCsv.Poll::eligible).collect(java.util.stream.Collectors
                .toMap(poll -> poll.institute() + "/" + poll.publicationDate(), poll -> poll, (first, second) -> first));

        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var json = polls.get("polls").get(index);
            var poll = source.get(row.get("institute") + "/" + row.get("publication_date"));
            assertNotNull(poll, "The example must quote an eligible poll from the pinned snapshot");
            assertEquals(poll.collectionFrom().toString(), row.get("collection_from"));
            assertEquals(poll.collectionTo().toString(), row.get("collection_to"));
            assertEquals(poll.collectionTo().toString(), json.get("collectionTo").asString());
            assertEquals(poll.sampleSize().toPlainString(), row.get("sample_size"));
            assertEquals(poll.denominatorNote(), row.get("denominator_note"));
            assertEquals(poll.surveyType(), row.get("survey_type"));
            for (var party : concat(ROSTER, "FI")) {
                var share = poll.shares().get(party);
                var reported = json.get("shares").get(party);
                // Source precision survives both representations without rounding, filling or a zero for a missing share.
                assertEquals(share == null ? "" : share.toPlainString(), row.get(party));
                assertEquals(share == null ? "" : share.toPlainString(), reported.isNull() ? "" : reported.asString());
            }
            assertEquals(poll.remainder(), new BigDecimal(row.get("other")));
            assertEquals("eight_party_2010", row.get("coverage_period"));
        }
    }

    @Test
    void separatesPointSeatsFromPosteriorMeansAndKeepsTheTenApprovedCoalitions() throws Exception {
        var seats = read("examples/seats.json");
        assertEquals(349, seats.get("totalSeats").asInt());
        var allocated = 0;
        for (var party : seats.get("parties")) {
            assertTrue(party.get("pointSeats").isInt());
            allocated += party.get("pointSeats").asInt();
            assertTrue(party.get("meanSeats").isDouble(), "Posterior mean seats stay distinct from the point allocation");
            assertEquals(2, party.get("seatInterval").size());
            assertTrue(party.get("thresholdProbability").asDouble() <= 1.0);
        }
        assertEquals(349, allocated);
        assertFalse(field(seats.get("parties"), "component").contains("OTHER"), "OTHER receives no seats as a group");
        assertEquals(1.2, seats.get("allocationRule").get("firstDivisor").asDouble());
        assertEquals(4.0, seats.get("allocationRule").get("thresholdPercent").asDouble());

        var coalitions = read("examples/coalitions.json");
        assertEquals(175, coalitions.get("majoritySeats").asInt());
        assertEquals(List.of("tido", "opposition", "left", "s_m"), texts(coalitions.get("overviewDefaults")));
        var memberships = new LinkedHashMap<String, List<String>>();
        for (var coalition : coalitions.get("coalitions")) {
            memberships.put(coalition.get("id").asString(), texts(coalition.get("parties")));
            assertTrue(coalition.get("majorityProbability").asDouble() <= 1.0);
        }
        assertEquals(Map.ofEntries(Map.entry("left", List.of("S", "V", "MP")), Map.entry("right", List.of("M", "L", "C", "KD")),
                Map.entry("tido", List.of("M", "L", "KD", "SD")), Map.entry("opposition", List.of("S", "C", "V", "MP")),
                Map.entry("s_c_mp", List.of("S", "C", "MP")), Map.entry("s_m", List.of("S", "M")),
                Map.entry("c_l_mp_s", List.of("C", "L", "MP", "S")), Map.entry("m_kd_sd", List.of("M", "KD", "SD")),
                Map.entry("s_sd", List.of("S", "SD")), Map.entry("m_sd", List.of("M", "SD"))), memberships);
    }

    @Test
    void exposesInstituteHouseEffectsAndElectionReferencesWithTheirOwnGrouping() throws Exception {
        for (var institute : read("examples/institutes.json").get("institutes")) {
            assertTrue(institute.has("methodEras"));
            for (var effect : institute.get("houseEffects"))
                assertTrue(effect.has("electionCycle") && effect.has("component") && effect.has("mean"),
                        "House effects are relative to the institute ensemble within one election cycle");
        }

        var latest = read("examples/elections.json").get("elections").get(0);
        assertEquals("2022-09-11", latest.get("electionDate").asString());
        assertEquals(6477970, latest.get("validVotes").asLong());
        assertEquals(3157, latest.get("results").get("FI").get("votes").asLong());
        assertEquals(0, latest.get("results").get("FI").get("officialSeats").asInt());
        assertEquals(349, latest.get("results").values().stream()
                .mapToInt(party -> party.get("officialSeats").asInt()).sum());
        assertEquals("eight_party_2010", latest.get("comparableGrouping").get("coveragePeriod").asString());
    }

    @Test
    void documentsEveryFrozenSurfaceInTheContractDocument() throws Exception {
        var doc = Files.readString(Path.of("docs/api-contract.md"));
        for (var surface : read("contract.json").get("surfaces")) {
            assertTrue(doc.contains(surface.get("path").asString()), surface.get("path").asString() + " is undocumented");
            assertTrue(doc.contains(surface.get("example").asString()), surface.get("example").asString() + " is unreferenced");
        }
    }

    private static List<String> concat(List<String> values, String extra) {
        var all = new ArrayList<>(values);
        all.add(extra);
        return List.copyOf(all);
    }
}
