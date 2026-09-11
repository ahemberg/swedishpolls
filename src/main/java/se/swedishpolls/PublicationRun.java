package se.swedishpolls;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
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
      NationalSeats.Rules allocationRule,
      List<NationalSeats.Rules> allocationRules,
      List<ElectionReferences.Election> elections,
      Map<String, PollQuery.Institute> institutes) {
    public Results {
      coveragePeriods = List.copyOf(coveragePeriods);
      periods = List.copyOf(periods);
      alternatives = List.copyOf(alternatives);
      allocationRules = List.copyOf(allocationRules);
      elections = List.copyOf(elections);
      institutes = Map.copyOf(institutes);
    }

    @Override
    public List<Alternative> alternatives() {
      return List.copyOf(alternatives);
    }
  }

  /**
   * Runs the frozen estimator over one snapshot's polls. Development evidence is cut at the
   * registered development end; a publication reads corrected history through its own last
   * fieldwork date at the same frozen parameters.
   */
  public static Results run(
      JdbcClient db,
      ModelFreeze freeze,
      long snapshotId,
      String snapshotSha256,
      List<PollCsv.Poll> polls,
      List<Roster.CoveragePeriod> coveragePeriods) {
    final List<ElectionReferences.Election> elections = new ElectionReferences(db).all();
    final List<ComparableRemainder.Reference> references =
        elections.stream().map(ElectionReferences.Election::reference).toList();
    final List<LocalDate> electionDates =
        elections.stream().map(ElectionReferences.Election::electionDate).toList();
    final LocalDate lastFieldworkDate = lastFieldworkDate(polls);
    final CoverageValidation.Rules coverage = freeze.coverageThrough(lastFieldworkDate);
    final NationalSeats seatRules = new NationalSeats(db);
    final NationalSeats.Rules allocation =
        seatRules.rules(approximatedElection(db, lastFieldworkDate));
    final List<NationalSeats.Rules> allocationRules =
        db
            .sql("SELECT election_year FROM national_allocation_rule ORDER BY election_year")
            .query(Integer.class)
            .list()
            .stream()
            .map(seatRules::rules)
            .toList();
    final double level = freeze.intervalLevel();

    final List<Period> periods = new ArrayList<>();
    for (final Roster.CoveragePeriod period : coveragePeriods) {
      if (!period.supportValidated() || !freeze.period(period.id()).supported()) {
        continue;
      }
      final DailyStateSpace.Parameters parameters = freeze.period(period.id()).parameters();
      final EstimateHistory.Estimated history =
          EstimateHistory.estimate(
              period, polls, electionDates, parameters, coverage, freeze.uncertainty());
      final ComparableRemainder.Estimated remainder =
          ComparableRemainder.estimate(
              period, polls, references, parameters, coverage, freeze.uncertainty());
      final JointUncertainty.Draws drawn =
          JointUncertainty.finalDay(
                  period, polls, electionDates, parameters, coverage, freeze.uncertainty())
              .draws();
      final EstimateHistory.Fitted fitted =
          EstimateHistory.fitted(period, polls, electionDates, parameters, coverage);
      periods.add(
          new Period(
              period,
              history,
              remainder,
              drawn,
              HouseEffects.of(
                  fitted.spans().getLast().fit(),
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

  /** The election this allocation approximates: the next one the stored rules reach. */
  private static int approximatedElection(JdbcClient db, LocalDate lastFieldworkDate) {
    return db.sql(
            "SELECT election_year FROM national_allocation_rule WHERE election_year >= ?"
                + " ORDER BY election_year LIMIT 1")
        .param(lastFieldworkDate.getYear())
        .query(Integer.class)
        .optional()
        .orElseGet(
            () ->
                db.sql("SELECT max(election_year) FROM national_allocation_rule")
                    .query(Integer.class)
                    .single());
  }
}
