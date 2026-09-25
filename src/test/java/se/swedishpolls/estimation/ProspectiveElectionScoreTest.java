package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProspectiveElectionScoreTest {
  private static final String RESULT =
      """
      {
        "rakningstillfalle": "slutlig",
        "antalValdistriktRaknade": 2,
        "antalValdistriktSomSkaRaknas": 2,
        "rosterPaverkaMandat": {"partiroster": [
          {"partiforkortning":"S","andelRoster":30.33},
          {"partiforkortning":"M","andelRoster":19.10},
          {"partiforkortning":"SD","andelRoster":20.54},
          {"partiforkortning":"V","andelRoster":6.75},
          {"partiforkortning":"C","andelRoster":6.71},
          {"partiforkortning":"KD","andelRoster":5.34},
          {"partiforkortning":"L","andelRoster":4.61},
          {"partiforkortning":"MP","andelRoster":5.08},
          {"partibeteckning":"Independent","andelRoster":0.01}
        ]},
        "partiMandat": [
          {"partiforkortning":"S","antalMandat":107},
          {"partiforkortning":"M","antalMandat":68},
          {"partiforkortning":"SD","antalMandat":73},
          {"partiforkortning":"V","antalMandat":24},
          {"partiforkortning":"C","antalMandat":24},
          {"partiforkortning":"KD","antalMandat":19},
          {"partiforkortning":"L","antalMandat":16},
          {"partiforkortning":"MP","antalMandat":18}
        ]
      }
      """;

  @Test
  void readsOnlyACompleteFinalNationalResult() {
    final ProspectiveElectionScore.Outcome outcome =
        ProspectiveElectionScore.outcome(RESULT.getBytes(StandardCharsets.UTF_8));

    assertEquals("1.54", outcome.shares().get("OTHER"));
    assertEquals(349, outcome.seats().values().stream().mapToInt(Integer::intValue).sum());
    assertEquals(107, outcome.seats().get("S"));
    final ProspectiveElectionScore.Outcome newParty =
        ProspectiveElectionScore.outcome(
            RESULT
                .replace(
                    "{\"partiforkortning\":\"MP\",\"antalMandat\":18}",
                    "{\"partiforkortning\":\"MP\",\"antalMandat\":17},"
                        + "{\"partiforkortning\":\"X\",\"antalMandat\":1}")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(1, newParty.seats().get("X"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProspectiveElectionScore.outcome(
                RESULT.replace("\"slutlig\"", "\"preliminar\"").getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        Map.of("S", "30.33", "M", "19.10"),
        Map.of("S", outcome.shares().get("S"), "M", outcome.shares().get("M")));
  }
}
