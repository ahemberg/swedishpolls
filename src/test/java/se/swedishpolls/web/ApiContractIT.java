package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Array;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.testsupport.PollCsvFixtures;
import se.swedishpolls.testsupport.TestDatabase;
import tools.jackson.databind.JsonNode;

/**
 * The frozen examples must describe the stored coverage periods, election references and allocation
 * rules.
 */
class ApiContractIT {
  private static JsonNode read(String name) throws IOException {
    return ApiContractTest.read("examples/" + name);
  }

  @Test
  void describesTheStoredCoveragePeriodsElectionReferencesAndAllocationRule() throws Exception {
    final java.lang.String schema = "contract_" + UUID.randomUUID().toString().replace("-", "");
    final org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
        TestDatabase.dataSource(schema);
    final org.flywaydb.core.Flyway flyway =
        Flyway.configure().dataSource(dataSource).schemas(schema).cleanDisabled(false).load();
    try {
      flyway.migrate();
      final org.springframework.jdbc.core.simple.JdbcClient db = JdbcClient.create(dataSource);

      final tools.jackson.databind.JsonNode described =
          read("estimates-latest.json").get("coveragePeriods");
      final java.util.List<se.swedishpolls.source.Roster.CoveragePeriod> stored =
          new CoveragePeriodRepository(db).periods();
      assertEquals(stored.size(), described.size());
      for (int index = 0; index < stored.size(); index++) {
        final se.swedishpolls.source.Roster.CoveragePeriod period = stored.get(index);
        final tools.jackson.databind.JsonNode example = described.get(index);
        assertEquals(period.id(), example.get("id").asString());
        assertEquals(period.effectiveFrom(), LocalDate.parse(example.get("from").asString()));
        assertEquals(
            period.effectiveTo(),
            example.get("to").isNull() ? null : LocalDate.parse(example.get("to").asString()));
        assertEquals(period.roster(), texts(example.get("roster")));
        assertEquals(period.individualFi(), example.get("individualFi").asBoolean());
        assertEquals(period.supportValidated(), example.get("supportValidated").asBoolean());
        assertEquals(period.decisionUrl(), example.get("decision").asString());
        // OTHER holds FI exactly when the roster does not.
        assertEquals(!period.individualFi(), texts(example.get("otherMembers")).contains("FI"));
      }

      final tools.jackson.databind.JsonNode election =
          read("elections.json").get("elections").get(0);
      final java.time.LocalDate date = LocalDate.parse(election.get("electionDate").asString());
      assertEquals(
          db.sql("SELECT valid_votes FROM election_reference WHERE election_date = ?")
              .param(date)
              .query(Long.class)
              .single(),
          election.get("validVotes").asLong());
      final java.util.LinkedHashMap<java.lang.String, java.lang.Long> votes =
          new LinkedHashMap<String, Long>();
      final java.util.LinkedHashMap<java.lang.String, java.lang.Integer> seats =
          new LinkedHashMap<String, Integer>();
      for (java.util.Map.Entry<java.lang.String, tools.jackson.databind.JsonNode> entry :
          election.get("results").properties()) {
        votes.put(entry.getKey(), entry.getValue().get("votes").asLong());
        seats.put(entry.getKey(), entry.getValue().get("officialSeats").asInt());
      }
      assertEquals(
          db
              .sql("SELECT component, votes FROM election_party_reference WHERE election_date = ?")
              .param(date)
              .query()
              .listOfRows()
              .stream()
              .collect(
                  Collectors.toMap(
                      row -> (String) row.get("component"),
                      row -> ((Number) row.get("votes")).longValue())),
          votes);
      assertEquals(
          db
              .sql(
                  "SELECT component, official_seats FROM election_party_reference WHERE election_date = ?")
              .param(date)
              .query()
              .listOfRows()
              .stream()
              .collect(
                  Collectors.toMap(
                      row -> (String) row.get("component"),
                      row -> (Integer) row.get("official_seats"))),
          seats);

      final tools.jackson.databind.JsonNode rule = read("seats.json").get("allocationRule");
      final java.util.Map<java.lang.String, java.lang.@org.jspecify.annotations.Nullable Object>
          storedRule =
              db.sql("SELECT * FROM national_allocation_rule WHERE election_year = ?")
                  .param(rule.get("electionYear").asInt())
                  .query()
                  .singleRow();
      assertEquals(((Number) storedRule.get("seats")).intValue(), rule.get("seats").asInt());
      assertEquals(
          0,
          ((BigDecimal) storedRule.get("first_divisor"))
              .compareTo(BigDecimal.valueOf(rule.get("firstDivisor").asDouble())));
      assertEquals(
          0,
          ((BigDecimal) storedRule.get("national_threshold_percent"))
              .compareTo(BigDecimal.valueOf(rule.get("thresholdPercent").asDouble())));
      assertEquals(
          storedRule.get("threshold_inclusive"), rule.get("thresholdInclusive").asBoolean());
      assertEquals(
          storedRule.get("other_receives_seats"), rule.get("otherReceivesSeats").asBoolean());
      assertEquals(
          storedRule.get("constituency_exceptions_included"),
          rule.get("constituencyExceptionsIncluded").asBoolean());
      assertEquals(
          storedRule.get("subsequent_divisor_formula"),
          rule.get("subsequentDivisorFormula").asString());
      assertEquals(storedRule.get("official_tie_rule"), rule.get("officialTieRule").asString());
      assertEquals(
          List.of((String[]) ((Array) storedRule.get("tie_order")).getArray()),
          texts(rule.get("tieOrder")));

      // Only parties of the validated roster can be allocated seats or joined into a preset
      // coalition.
      final java.util.List<java.lang.String> roster =
          Roster.supportedPeriod(
                  new CoveragePeriodRepository(db).periods(),
                  PollCsv.parse(PollCsvFixtures.csv(PollCsvFixtures.ROW)).getFirst())
              .roster();
      for (tools.jackson.databind.JsonNode party : read("seats.json").get("parties"))
        assertTrue(
            roster.contains(party.get("component").asString()), party.get("component").asString());
      for (tools.jackson.databind.JsonNode coalition : read("coalitions.json").get("coalitions"))
        assertTrue(
            roster.containsAll(texts(coalition.get("parties"))), coalition.get("id").asString());
    } finally {
      flyway.clean();
    }
  }

  private static List<String> texts(JsonNode array) {
    return ApiContractTest.texts(array);
  }
}
