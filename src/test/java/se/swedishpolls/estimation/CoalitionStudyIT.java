package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import se.swedishpolls.publication.Digest;
import se.swedishpolls.publication.ModelFreeze;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.testsupport.TestDatabase;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Opt-in numerical study using development data only, not the reserved release audit. */
class CoalitionStudyIT {
  @Test
  @EnabledIfSystemProperty(named = "coalition.study", matches = "true")
  void allSubsetsMeetTheRegisteredPrecisionBudgetOnBothRosters() throws Exception {
    final String schema = "coalition_study_" + UUID.randomUUID().toString().replace("-", "");
    final DriverManagerDataSource source = TestDatabase.dataSource(schema);
    final Flyway flyway =
        Flyway.configure().dataSource(source).schemas(schema).cleanDisabled(false).load();
    final ModelFreeze freeze = ModelFreeze.load();
    final byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/polls/audit.csv"));
    final JsonMapper json = JsonMapper.builder().build();
    final ObjectNode evidence = json.createObjectNode();
    evidence.put("protocolVersion", "coalition-history-1");
    evidence.put("sourceSha256", Digest.sha256(bytes));
    evidence.put("developmentEnd", freeze.developmentCoverage().developmentThrough().toString());
    final ArrayNode reports = evidence.putArray("periods");
    boolean passed = true;
    final long start = System.nanoTime();
    try {
      flyway.migrate();
      final JdbcClient db = JdbcClient.create(source);
      final List<LocalDate> elections =
          db.sql(
                  "SELECT election_date FROM election_reference WHERE election_date <= :cutoff ORDER BY election_date")
              .param("cutoff", freeze.developmentCoverage().developmentThrough())
              .query(LocalDate.class)
              .list();
      final List<PollCsv.Poll> polls = PollCsv.parse(bytes);
      for (final Roster.CoveragePeriod period : new CoveragePeriodRepository(db).periods()) {
        if (!List.of("eight_party_2010", "fi_candidate_2014_2018").contains(period.id())) continue;
        final EstimateHistory.Fitted fitted =
            EstimateHistory.fitted(
                period,
                polls,
                elections,
                freeze.period(period.id()).parameters(),
                freeze.developmentCoverage());
        final CoalitionPrecision.Report report =
            CoalitionPrecision.evaluate(fitted, period.id(), freeze.coalitionPrecision());
        final ObjectNode row = reports.addObject();
        row.put("periodId", period.id());
        row.set("report", json.valueToTree(report));
        passed &= report.passed();
      }
    } finally {
      evidence.put("seconds", (System.nanoTime() - start) / 1_000_000_000.0);
      evidence.put("passed", passed && reports.size() == 2);
      Files.writeString(
          Path.of("target/coalition-precision-study.json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
      flyway.clean();
    }
    assertTrue(passed && reports.size() == 2, evidence.toPrettyString());
  }
}
