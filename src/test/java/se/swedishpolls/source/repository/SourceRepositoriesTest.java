package se.swedishpolls.source.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import se.swedishpolls.model.ElectionReference;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.source.Roster;
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

  @Test
  void flywayDataIsReadThroughTheProductionRepositories() {
    final List<Roster.CoveragePeriod> periods = coveragePeriods.periods();
    assertEquals(
        List.of("eight_party_2010", "fi_candidate_2014_2018"),
        periods.stream().map(Roster.CoveragePeriod::id).toList());
    assertFalse(periods.getLast().supportValidated());

    final List<ElectionReference> references = elections.all();
    assertEquals(
        List.of(2010, 2014, 2018, 2022),
        references.stream().map(ElectionReference::electionYear).toList());
    assertEquals(6_477_970, references.getLast().validVotes());

    final List<NationalAllocationRule> rules = allocationRules.all();
    assertEquals(
        List.of(2010, 2014, 2018, 2022, 2026),
        rules.stream().map(NationalAllocationRule::electionYear).toList());
    assertEquals(2026, allocationRules.approximatedElection(2025));
  }
}
