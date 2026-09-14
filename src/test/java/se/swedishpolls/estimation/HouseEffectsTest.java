package se.swedishpolls.estimation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.Roster;
import se.swedishpolls.testsupport.PollCsvFixtures;

/**
 * The published {@code shrunk} flag says the model applies its zero-centred shrinkage prior to this
 * effect. It is not a claim about how much evidence an institute has: the interval carries that.
 */
class HouseEffectsTest {
  private static final LocalDate START = LocalDate.of(2018, 6, 1);
  private static final List<LocalDate> ELECTIONS = List.of(LocalDate.of(2014, 9, 14));
  private static final DailyStateSpace.Parameters PARAMETERS =
      new DailyStateSpace.Parameters(0.003, 0.5, 1.5);

  private static String row(String institute, int from, int to, String m) {
    return "2018-06,"
        + institute
        + ","
        + m
        + ",5,8,5,25,8,5,12,1,10,1000,"
        + START.plusDays(to)
        + ","
        + institute
        + ","
        + START.plusDays(from)
        + ","
        + START.plusDays(to)
        + ",FALSE\n";
  }

  /** One institute polls every week of the period; the other polls once. */
  private static List<HouseEffects.Effect> effects() {
    final StringBuilder rows = new StringBuilder();
    for (int week = 0; week < 20; week++) {
      rows.append(row("Sifo", week * 7, week * 7 + 1, "24"));
    }
    rows.append(row("Novus", 70, 71, "16"));
    final Roster.CoveragePeriod period =
        new Roster.CoveragePeriod(
            "test",
            START,
            START.plusDays(200),
            PollCsv.PARTIES,
            false,
            false,
            "https://example.invalid");
    final DailyStateSpace.Fit fit =
        DailyStateSpace.fit(
            PollObservations.prepare(period, PollCsv.parse(PollCsvFixtures.csv(rows.toString()))),
            ELECTIONS,
            PARAMETERS);
    return HouseEffects.of(fit, period.id(), ELECTIONS, 0.95, 400, 20260908);
  }

  @Test
  void everyModelledEffectIsShrunkWhetherTheInstituteIsSparseOrWellObserved() {
    final List<HouseEffects.Effect> effects = effects();

    assertTrue(effects.stream().anyMatch(effect -> "Sifo".equals(effect.effect())));
    assertTrue(effects.stream().anyMatch(effect -> "Novus".equals(effect.effect())));
    for (final HouseEffects.Effect effect : effects) {
      assertTrue(
          effect.shrunk(),
          () -> effect.effect() + " " + effect.component() + " is fitted under the prior");
    }
  }

  /**
   * Why a poll count cannot be read off the flag: here the one-poll institute carries the narrower
   * interval of the two, because its effect stays near the prior the ensemble is centred on.
   */
  @Test
  void aSparseInstituteIsNotTheOneWithTheWiderInterval() {
    final List<HouseEffects.Effect> effects = effects();

    assertTrue(width(effects, "Novus", "M") < width(effects, "Sifo", "M"));
  }

  private static double width(
      List<HouseEffects.Effect> effects, String institute, String component) {
    return effects.stream()
        .filter(effect -> institute.equals(effect.effect()))
        .filter(effect -> component.equals(effect.component()))
        .map(effect -> effect.upper() - effect.lower())
        .findFirst()
        .orElseThrow();
  }
}
