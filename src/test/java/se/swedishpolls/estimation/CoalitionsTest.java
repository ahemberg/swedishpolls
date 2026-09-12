package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoalitionsTest {
  private static final List<String> COMPONENTS =
      List.of("S", "M", "SD", "V", "C", "KD", "L", "MP", "OTHER");

  @Test
  void theCatalogueKeepsAllTenApprovedMembershipsAndTheOverviewDefaults() {
    final Map<String, List<String>> memberships = new LinkedHashMap<>();
    for (final Coalitions.Preset preset : Coalitions.PRESETS) {
      memberships.put(preset.id(), preset.parties());
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
    assertEquals(10, Coalitions.PRESETS.size());
    assertEquals(List.of("tido", "opposition", "left", "s_m"), Coalitions.OVERVIEW_DEFAULTS);
    assertTrue(
        memberships.keySet().containsAll(Coalitions.OVERVIEW_DEFAULTS),
        "Every overview default is a preset of the catalogue");
  }

  @Test
  void oppositionAndClMpSAreDistinctPresets() {
    final List<String> opposition = Coalitions.preset("opposition").parties();
    final List<String> broad = Coalitions.preset("c_l_mp_s").parties();
    assertNotEquals(opposition, broad);
    assertTrue(opposition.contains("V") && !opposition.contains("L"));
    assertTrue(broad.contains("L") && !broad.contains("V"));
    assertThrows(IllegalArgumentException.class, () -> Coalitions.preset("custom"));
  }

  @Test
  void everyPresetIsSummarizedFromTheSameDrawsAndTheMajorityLineIncludes175() {
    final Coalitions.Result result = summarize();
    assertEquals(175, result.majoritySeats());
    assertEquals(10, result.coalitions().size());
    assertEquals(3, result.draws());
    for (final Coalitions.Seats coalition : result.coalitions()) {
      assertTrue(coalition.lowerSeats() <= coalition.meanSeats());
      assertTrue(coalition.meanSeats() <= coalition.upperSeats());
      assertTrue(coalition.majorityProbability() >= 0 && coalition.majorityProbability() <= 1);
    }
    // The first draw puts the opposition bloc on exactly 175, which the majority line includes.
    assertEquals(175, result.coalition("opposition").lowerSeats());
    assertEquals(1.0, result.coalition("opposition").majorityProbability(), 1e-12);
    assertEquals(0.0, result.coalition("m_sd").majorityProbability(), 1e-12);
  }

  @Test
  void anExactTieBetweenTwoComparedCoalitionsCountsForNeitherSide() {
    final Coalitions.Result result = summarize();
    assertEquals(45, result.comparison().size(), "Every pair of the catalogue is comparable");
    assertEquals(Coalitions.TIE_OUTCOME, result.tie());
    for (final Coalitions.Comparison pair : result.comparison()) {
      assertEquals(1.0, pair.leftLeads() + pair.rightLeads() + pair.tied(), 1e-12);
    }
    final Coalitions.Comparison level =
        result.comparison().stream()
            .filter(pair -> pair.left().equals("tido") && pair.right().equals("s_m"))
            .findFirst()
            .orElseThrow();
    // The third draw puts both blocs on 170 seats, which credits neither of them.
    assertEquals(1.0 / 3, level.tied(), 1e-12);
    assertEquals(1.0 / 3, level.leftLeads(), 1e-12);
    assertEquals(1.0 / 3, level.rightLeads(), 1e-12);
  }

  private static Coalitions.Result summarize() {
    return Coalitions.summarize(
        NationalSeats.allocateDraws(
            "eight_party_2010",
            LocalDate.of(2021, 9, 20),
            COMPONENTS,
            new double[][] {
              {28.0, 20.0, 18.0, 9.0, 8.0, 6.0, 5.0, 4.5, 1.5},
              {30.0, 19.0, 17.0, 10.0, 7.0, 6.0, 5.0, 4.5, 1.5},
              {28.95, 19.05, 18.0, 9.0, 8.0, 6.0, 5.0, 4.5, 1.5}
            },
            1L,
            NationalSeatsTest.rules(2026, 1.2)),
        0.95);
  }
}
