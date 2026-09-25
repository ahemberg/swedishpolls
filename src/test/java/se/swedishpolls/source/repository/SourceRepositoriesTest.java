package se.swedishpolls.source.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import se.swedishpolls.model.ElectionReference;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.source.Roster;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.testsupport.TestDatabase;

@JdbcTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  TestDatabase.Configuration.class,
  CoveragePeriodRepository.class,
  ElectionReferenceRepository.class,
  NationalAllocationRuleRepository.class
})
class SourceRepositoriesTest {
  @Autowired private CoveragePeriodRepository coveragePeriods;
  @Autowired private ElectionReferenceRepository elections;
  @Autowired private NationalAllocationRuleRepository allocationRules;
  @Autowired private JdbcClient db;

  @Test
  void flywayDataIsReadThroughTheProductionRepositories() {
    final List<Roster.CoveragePeriod> periods = coveragePeriods.periods();
    assertEquals(
        List.of("eight_party_2010", "fi_candidate_2014_2018"),
        periods.stream().map(Roster.CoveragePeriod::id).toList());
    assertFalse(periods.getLast().supportValidated());

    final List<ElectionReference> references = elections.all();
    assertEquals(
        List.of(2010, 2014, 2018, 2022, 2026),
        references.stream().map(ElectionReference::electionYear).toList());
    assertEquals(6_767_429, references.getLast().validVotes());

    final List<NationalAllocationRule> rules = allocationRules.all();
    assertEquals(
        List.of(2010, 2014, 2018, 2022, 2026),
        rules.stream().map(NationalAllocationRule::electionYear).toList());
    assertEquals(2026, allocationRules.approximatedElection(2025));
  }

  @Test
  void theCommittedElectionReferencesRespectTheEmbargo() {
    new ElectionReferenceService(elections).requireEmbargo();
  }

  @Test
  void theLatestElectionIsSeenWithoutAnyPartyResult() {
    db.sql(
            """
            INSERT INTO election_reference (election_date, election_year, valid_votes, source_url,
                source_sha256, retrieved_on, official_seats_source_url)
            VALUES ('2026-09-14', 2026, 1000, 'https://example.invalid', repeat('0', 64),
                '2026-09-14', 'https://example.invalid')
            """)
        .update();
    assertEquals(Optional.of(LocalDate.of(2026, 9, 14)), elections.latestElectionDate());
  }
}
