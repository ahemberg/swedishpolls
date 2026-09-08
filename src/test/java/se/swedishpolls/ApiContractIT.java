package se.swedishpolls;

import java.io.IOException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** The frozen examples must describe the stored coverage periods, election references and allocation rules. */
class ApiContractIT {
    private static JsonNode read(String name) throws IOException {
        try (var input = ApiContractIT.class.getResourceAsStream("/api/v1/examples/" + name)) {
            assertNotNull(input, name);
            return JsonMapper.builder().build().readTree(input);
        }
    }

    @Test
    void describesTheStoredCoveragePeriodsElectionReferencesAndAllocationRule() throws Exception {
        var schema = "contract_" + UUID.randomUUID().toString().replace("-", "");
        var url = System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/swedishpolls");
        var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv().getOrDefault("DATABASE_USER", "swedishpolls"), System.getenv("DATABASE_PASSWORD"));
        var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
        try {
            flyway.migrate();
            var db = JdbcClient.create(dataSource);

            var described = read("estimates-latest.json").get("coveragePeriods");
            var stored = new Roster(db).periods();
            assertEquals(stored.size(), described.size());
            for (var index = 0; index < stored.size(); index++) {
                var period = stored.get(index);
                var example = described.get(index);
                assertEquals(period.id(), example.get("id").asString());
                assertEquals(period.effectiveFrom(), LocalDate.parse(example.get("from").asString()));
                assertEquals(period.effectiveTo(), example.get("to").isNull() ? null : LocalDate.parse(example.get("to").asString()));
                assertEquals(period.roster(), texts(example.get("roster")));
                assertEquals(period.individualFi(), example.get("individualFi").asBoolean());
                assertEquals(period.supportValidated(), example.get("supportValidated").asBoolean());
                assertEquals(period.decisionUrl(), example.get("decision").asString());
                // OTHER holds FI exactly when the roster does not.
                assertEquals(!period.individualFi(), texts(example.get("otherMembers")).contains("FI"));
            }

            var election = read("elections.json").get("elections").get(0);
            var date = LocalDate.parse(election.get("electionDate").asString());
            assertEquals(db.sql("SELECT valid_votes FROM election_reference WHERE election_date = ?")
                    .param(date).query(Long.class).single(), election.get("validVotes").asLong());
            var votes = new LinkedHashMap<String, Long>();
            var seats = new LinkedHashMap<String, Integer>();
            for (var entry : election.get("results").properties()) {
                votes.put(entry.getKey(), entry.getValue().get("votes").asLong());
                seats.put(entry.getKey(), entry.getValue().get("officialSeats").asInt());
            }
            assertEquals(db.sql("SELECT component, votes FROM election_party_reference WHERE election_date = ?")
                    .param(date).query().listOfRows().stream()
                    .collect(java.util.stream.Collectors.toMap(row -> (String) row.get("component"),
                            row -> ((Number) row.get("votes")).longValue())), votes);
            assertEquals(db.sql("SELECT component, official_seats FROM election_party_reference WHERE election_date = ?")
                    .param(date).query().listOfRows().stream()
                    .collect(java.util.stream.Collectors.toMap(row -> (String) row.get("component"),
                            row -> (Integer) row.get("official_seats"))), seats);

            var rule = read("seats.json").get("allocationRule");
            var storedRule = db.sql("SELECT * FROM national_allocation_rule WHERE election_year = ?")
                    .param(rule.get("electionYear").asInt()).query().singleRow();
            assertEquals(((Number) storedRule.get("seats")).intValue(), rule.get("seats").asInt());
            assertEquals(0, ((java.math.BigDecimal) storedRule.get("first_divisor")).compareTo(
                    java.math.BigDecimal.valueOf(rule.get("firstDivisor").asDouble())));
            assertEquals(0, ((java.math.BigDecimal) storedRule.get("national_threshold_percent")).compareTo(
                    java.math.BigDecimal.valueOf(rule.get("thresholdPercent").asDouble())));
            assertEquals(storedRule.get("threshold_inclusive"), rule.get("thresholdInclusive").asBoolean());
            assertEquals(storedRule.get("other_receives_seats"), rule.get("otherReceivesSeats").asBoolean());
            assertEquals(storedRule.get("constituency_exceptions_included"), rule.get("constituencyExceptionsIncluded").asBoolean());
            assertEquals(storedRule.get("subsequent_divisor_formula"), rule.get("subsequentDivisorFormula").asString());
            assertEquals(storedRule.get("official_tie_rule"), rule.get("officialTieRule").asString());
            assertEquals(List.of((String[]) ((java.sql.Array) storedRule.get("tie_order")).getArray()),
                    texts(rule.get("tieOrder")));

            // Only parties of the validated roster can be allocated seats or joined into a preset coalition.
            var roster = Roster.supportedPeriod(new Roster(db).periods(),
                    PollCsv.parse(PollCsvTest.csv(PollCsvTest.ROW)).getFirst()).roster();
            for (var party : read("seats.json").get("parties"))
                assertTrue(roster.contains(party.get("component").asString()), party.get("component").asString());
            for (var coalition : read("coalitions.json").get("coalitions"))
                assertTrue(roster.containsAll(texts(coalition.get("parties"))), coalition.get("id").asString());
        } finally {
            flyway.clean();
        }
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }
}
