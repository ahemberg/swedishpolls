package se.swedishpolls.publication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.swedishpolls.estimation.ComparableRemainder;
import se.swedishpolls.estimation.CoverageValidation;
import se.swedishpolls.estimation.DailyStateSpace;
import se.swedishpolls.estimation.EstimateHistory;
import se.swedishpolls.estimation.HouseEffects;
import se.swedishpolls.estimation.JointUncertainty;
import se.swedishpolls.estimation.SeatOutcomes;
import se.swedishpolls.model.ElectionReference;
import se.swedishpolls.model.NationalAllocationRule;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.Roster;

/**
 * One model run over one source snapshot, at the frozen parameters. It produces every quantity a
 * publication exposes and nothing a request may vary: filters, ranges and sampling are applied to
 * this output later, so no request ever refits the model.
 */
public final class PublicationRun {
  private PublicationRun() {}

  /** One coverage period's published estimates, from one fit and one set of draws. */
  public record Period(
      Roster.CoveragePeriod period,
      EstimateHistory.Estimated history,
      ComparableRemainder.Estimated remainder,
      JointUncertainty.Draws draws,
      List<HouseEffects.Effect> houseEffects) {
    public Period {
      houseEffects = List.copyOf(houseEffects);
    }

    @Override
    public List<HouseEffects.Effect> houseEffects() {
      return List.copyOf(houseEffects);
    }
  }

  /**
   * One registered alternative fit of the headline period, drawn on the same final day as the
   * published run. The sensitivity requirement names it, and a movement it causes is disclosed
   * beside the number it moves rather than blocking the publication.
   */
  public record Alternative(String kind, String label, JointUncertainty.Draws draws) {}

  /** Everything one publication was computed from and everything it publishes. */
  public record Results(
      long snapshotId,
      String snapshotSha256,
      LocalDate lastFieldworkDate,
      double intervalLevel,
      List<Roster.CoveragePeriod> coveragePeriods,
      List<Period> periods,
      Period headline,
      List<Alternative> alternatives,
      NationalAllocationRule allocationRule,
      List<NationalAllocationRule> allocationRules,
      List<ElectionReference> elections,
      Map<String, PollQuery.Institute> institutes) {
    public Results {
      coveragePeriods = List.copyOf(coveragePeriods);
      periods = List.copyOf(periods);
      alternatives = List.copyOf(alternatives);
      allocationRules = List.copyOf(allocationRules);
      elections = List.copyOf(elections);
      institutes = Map.copyOf(institutes);
    }
  }

  /**
   * Runs the frozen estimator over one snapshot's polls. Development evidence is cut at the
   * registered development end; a publication reads corrected history through its own last
   * fieldwork date at the same frozen parameters.
   */
  public static Results run(
      ModelFreeze freeze,
      long snapshotId,
      String snapshotSha256,
      List<PollCsv.Poll> polls,
      List<Roster.CoveragePeriod> coveragePeriods,
      List<ElectionReference> elections,
      NationalAllocationRule allocation,
      List<NationalAllocationRule> allocationRules) {
    final List<ComparableRemainder.Reference> references =
        elections.stream().map(ComparableRemainder::reference).toList();
    final List<LocalDate> electionDates =
        elections.stream().map(ElectionReference::electionDate).toList();
    final LocalDate lastFieldworkDate = lastFieldworkDate(polls);
    final CoverageValidation.Rules coverage = freeze.coverageThrough(lastFieldworkDate);
    final double level = freeze.intervalLevel();

    final List<Period> periods = new ArrayList<>();
    for (final Roster.CoveragePeriod period : coveragePeriods) {
      if (!period.supportValidated() || !freeze.period(period.id()).supported()) {
        continue;
      }
      final DailyStateSpace.Parameters parameters = freeze.period(period.id()).parameters();
      // One fit serves this period's four summarizers; the seeded reproduction check refits
      // independently, because reproducing the input is its point.
      final EstimateHistory.Fitted fitted =
          EstimateHistory.fitted(period, polls, electionDates, parameters, coverage);
      final EstimateHistory.Estimated history =
          EstimateHistory.estimate(fitted, period, coverage, freeze.uncertainty());
      final ComparableRemainder.Estimated remainder =
          ComparableRemainder.estimate(fitted, period, references, coverage, freeze.uncertainty());
      final JointUncertainty.Draws drawn =
          JointUncertainty.finalDay(fitted, period, parameters, freeze.uncertainty()).draws();
      periods.add(
          new Period(
              period,
              history,
              remainder,
              drawn,
              HouseEffects.estimate(
                  fitted,
                  period.id(),
                  electionDates,
                  level,
                  freeze.uncertainty().draws(),
                  freeze.uncertainty().seed())));
    }
    if (periods.isEmpty()) {
      throw new IllegalStateException("No validated coverage period produced an estimate");
    }
    final Period headline = headline(periods, lastFieldworkDate);
    return new Results(
        snapshotId,
        snapshotSha256,
        lastFieldworkDate,
        level,
        coveragePeriods,
        periods,
        headline,
        alternatives(headline, polls, electionDates, freeze, coverage),
        allocation,
        allocationRules,
        elections,
        PollQuery.institutes(polls));
  }

  /**
   * The registered alternative fits of the headline period. Only the centering alternative is
   * refitted here; leave-one-institute-out stays in the release audit, where the whole registered
   * set runs against recorded evidence rather than on every publication.
   */
  private static List<Alternative> alternatives(
      Period headline,
      List<PollCsv.Poll> polls,
      List<LocalDate> electionDates,
      ModelFreeze freeze,
      CoverageValidation.Rules coverage) {
    return List.of(
        new Alternative(
            "centering",
            SeatOutcomes.POLL_COUNT,
            JointUncertainty.finalDayDraws(
                headline.period(),
                polls,
                electionDates,
                freeze.period(headline.period().id()).parameters(),
                coverage,
                freeze.uncertainty(),
                DailyStateSpace.Centering.POLL_COUNT)));
  }

  /** The last fieldwork date any eligible poll of the snapshot reaches. */
  public static LocalDate lastFieldworkDate(List<PollCsv.Poll> polls) {
    return polls.stream()
        .filter(PollCsv.Poll::eligible)
        .map(PollCsv.Poll::collectionTo)
        .filter(java.util.Objects::nonNull)
        .max(LocalDate::compareTo)
        .orElseThrow(
            () -> new IllegalStateException("The snapshot has no eligible fieldwork date"));
  }

  /** The period whose estimates the current-opinion surfaces read. */
  private static Period headline(List<Period> periods, LocalDate lastFieldworkDate) {
    return periods.stream()
        .filter(period -> period.period().effectiveFrom() != null)
        .filter(period -> !period.period().effectiveFrom().isAfter(lastFieldworkDate))
        .filter(
            period ->
                period.period().effectiveTo() == null
                    || !period.period().effectiveTo().isBefore(lastFieldworkDate))
        .reduce((first, second) -> second)
        .orElse(periods.getLast());
  }
}
