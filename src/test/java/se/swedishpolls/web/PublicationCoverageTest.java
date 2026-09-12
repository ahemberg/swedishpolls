package se.swedishpolls.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.Roster;
import tools.jackson.databind.json.JsonMapper;

class PublicationCoverageTest {
  @Test
  void readsTheCoverageDefinitionStoredInThePublication() {
    final Roster.CoveragePeriod period =
        PublicationCoverage.from(
                JsonMapper.builder()
                    .build()
                    .readTree(
                        """
                        {"coveragePeriods":[{"id":"eight_party_2022","from":"2022-09-12",
                        "to":null,"roster":["S","M"],"individualFi":false,
                        "supportValidated":true,"decision":"https://example.test/decision"}]}
                        """))
            .getFirst();

    assertEquals("eight_party_2022", period.id());
    assertEquals(LocalDate.of(2022, 9, 12), period.effectiveFrom());
    assertNull(period.effectiveTo());
    assertEquals(List.of("S", "M"), period.roster());
    assertFalse(period.individualFi());
    assertTrue(period.supportValidated());
    assertEquals("https://example.test/decision", period.decisionUrl());
  }
}
