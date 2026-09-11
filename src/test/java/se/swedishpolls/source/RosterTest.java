package se.swedishpolls.source;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.swedishpolls.testsupport.PollCsvFixtures;

class RosterTest {
  private static final Roster.CoveragePeriod EIGHT =
      new Roster.CoveragePeriod(
          "eight_party_2010",
          LocalDate.of(2010, 1, 1),
          null,
          PollCsv.PARTIES,
          false,
          true,
          "https://example.invalid/decision");
  private static final Roster.CoveragePeriod FI_SEGMENT =
      new Roster.CoveragePeriod(
          "fi_candidate_2014_2018",
          LocalDate.of(2014, 4, 9),
          LocalDate.of(2018, 9, 7),
          Stream.concat(PollCsv.PARTIES.stream(), Stream.of("FI")).toList(),
          true,
          false,
          "https://example.invalid/decision");

  private static PollCsv.Poll poll(String row) {
    return PollCsv.parse(PollCsvFixtures.csv(row)).getFirst();
  }

  @Test
  void groupsFiIntoOtherOutsideAnFiSegmentAndSeparatesItInside() {
    final se.swedishpolls.source.Roster.Composition outside =
        Roster.compose(EIGHT, poll(PollCsvFixtures.SEGMENT_ROW));
    assertEquals(
        List.of("M", "L", "C", "KD", "S", "V", "MP", "SD", "OTHER"),
        List.copyOf(outside.components().keySet()));
    assertEquals(new BigDecimal("1.877"), outside.components().get("OTHER"));
    assertTrue(outside.complete());

    final se.swedishpolls.source.Roster.Composition inside =
        Roster.compose(FI_SEGMENT, poll(PollCsvFixtures.SEGMENT_ROW));
    assertEquals(
        List.of("M", "L", "C", "KD", "S", "V", "MP", "SD", "FI", "RESIDUAL"),
        List.copyOf(inside.components().keySet()));
    assertEquals(new BigDecimal("1"), inside.components().get("FI"));
    assertEquals(new BigDecimal("0.877"), inside.components().get("RESIDUAL"));
    // Subtracting FI exactly once keeps support outside the fixed eight identical across rosters.
    assertEquals(0, outside.comparableRemainder().compareTo(inside.comparableRemainder()));
  }

  @Test
  void excludesPollsThatCannotFormThePeriodComposition() {
    final se.swedishpolls.source.PollCsv.Poll missingFi =
        poll(PollCsvFixtures.SEGMENT_ROW.replace(",17,1,10,", ",17,NA,10,"));
    assertTrue(missingFi.eligible());
    assertTrue(Roster.compose(EIGHT, missingFi).complete());
    assertEquals(
        List.of("missing_share:FI"), Roster.compose(FI_SEGMENT, missingFi).exclusionReasons());
    assertTrue(Roster.compose(FI_SEGMENT, missingFi).components().isEmpty());

    final se.swedishpolls.source.PollCsv.Poll largeFi =
        poll(PollCsvFixtures.SEGMENT_ROW.replace(",17,1,10,", ",17,3,10,"));
    assertEquals(
        List.of("negative_residual"), Roster.compose(FI_SEGMENT, largeFi).exclusionReasons());

    // A collection window is inside a period only as a whole, so a straddling poll cannot compose.
    final se.swedishpolls.source.PollCsv.Poll straddling =
        poll(
            PollCsvFixtures.SEGMENT_ROW
                .replace("2016-06-01", "2014-04-08")
                .replace("2016-06-19", "2014-04-20")
                .replace("2016-06-20", "2014-04-25"));
    assertEquals(
        List.of("outside_coverage_period:fi_candidate_2014_2018"),
        Roster.compose(FI_SEGMENT, straddling).exclusionReasons());
    assertTrue(Roster.compose(EIGHT, straddling).complete());

    final se.swedishpolls.source.PollCsv.Poll ineligible =
        poll(PollCsvFixtures.SEGMENT_ROW.replace("Ipsos", "Demoskop valdag"));
    assertEquals(
        List.of("exit_or_election_day"), Roster.compose(FI_SEGMENT, ineligible).exclusionReasons());
  }

  @Test
  void documentsCandidateFiBoundariesFromEligiblePollsInThePinnedSnapshot() throws Exception {
    final byte[] bytes;
    try (final java.io.InputStream input = getClass().getResourceAsStream("/polls/audit.csv")) {
      bytes = input.readAllBytes();
    }
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> polls = PollCsv.parse(bytes);
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> eligible =
        polls.stream().filter(PollCsv.Poll::eligible).toList();
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> window =
        eligible.stream().filter(FI_SEGMENT::covers).toList();
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> withFi =
        window.stream()
            .filter(poll -> poll.shares().get("FI") != null)
            .sorted(Comparator.comparing(PollCsv.Poll::collectionFrom))
            .toList();
    assertEquals(439, window.size());
    assertEquals(388, withFi.size());
    assertEquals(
        388,
        polls.stream()
            .map(poll -> Roster.compose(FI_SEGMENT, poll))
            .filter(Roster.Composition::complete)
            .count());

    // The boundaries are the outermost collection dates of those eligible FI polls.
    assertEquals(LocalDate.of(2014, 4, 9), withFi.getFirst().collectionFrom());
    assertEquals(
        LocalDate.of(2018, 9, 7),
        withFi.stream().map(PollCsv.Poll::collectionTo).max(LocalDate::compareTo).orElseThrow());
    assertEquals(10, withFi.stream().map(PollCsv.Poll::institute).distinct().count());
    assertEquals(
        5,
        eligible.stream()
            .filter(
                poll ->
                    poll.collectionFrom().isBefore(FI_SEGMENT.effectiveFrom())
                        && !poll.collectionTo().isBefore(FI_SEGMENT.effectiveFrom()))
            .peek(poll -> assertNull(poll.shares().get("FI")))
            .count());
    assertEquals(
        Map.of(2014, 74L, 2015, 81L, 2016, 80L, 2017, 78L, 2018, 75L),
        byCollectionYear(withFi, PollCsv.Poll::institute).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> (long) entry.getValue().size())));
    assertEquals(
        List.of(10L, 8L, 8L, 8L, 7L),
        byCollectionYear(withFi, PollCsv.Poll::institute).values().stream()
            .map(institutes -> institutes.stream().distinct().count())
            .toList());

    final java.util.List<java.math.BigDecimal> shares =
        withFi.stream().map(poll -> poll.shares().get("FI")).sorted().toList();
    assertEquals(
        List.of(new BigDecimal("0.6"), new BigDecimal("4.4")),
        List.of(shares.getFirst(), shares.getLast()));
    assertEquals(new BigDecimal("2.1"), shares.get(shares.size() / 2));
    assertEquals(
        5, shares.stream().filter(share -> share.compareTo(new BigDecimal("4")) >= 0).count());

    // Merging the collection windows in start order leaves one break far wider than the rest.
    final java.util.ArrayList<java.lang.Long> breaks = new java.util.ArrayList<Long>();
    java.time.LocalDate covered = FI_SEGMENT.effectiveFrom();
    for (se.swedishpolls.source.PollCsv.Poll poll : withFi) {
      breaks.add(ChronoUnit.DAYS.between(covered, poll.collectionFrom()));
      if (poll.collectionTo().isAfter(covered)) covered = poll.collectionTo();
    }
    final java.util.List<java.lang.Long> widest =
        breaks.stream().sorted(Comparator.reverseOrder()).limit(2).toList();
    assertEquals(List.of(27L, 15L), widest);
    assertEquals(LocalDate.of(2016, 8, 1), withFi.get(breaks.indexOf(27L)).collectionFrom());

    final java.util.List<se.swedishpolls.source.Roster.Composition> excluded =
        window.stream()
            .map(poll -> Roster.compose(FI_SEGMENT, poll))
            .filter(composition -> !composition.complete())
            .toList();
    assertEquals(51, excluded.size());
    assertTrue(
        excluded.stream()
            .allMatch(
                composition -> composition.exclusionReasons().equals(List.of("missing_share:FI"))));
    assertEquals(
        Map.of(
            "Novus",
            17L,
            "Inizio",
            10L,
            "Sentio",
            9L,
            "SCB",
            8L,
            "Ipsos",
            4L,
            "Sifo",
            2L,
            "Demoskop",
            1L),
        window.stream()
            .filter(poll -> poll.shares().get("FI") == null)
            .collect(Collectors.groupingBy(PollCsv.Poll::institute, Collectors.counting())));

    // The two isolated 2022 observations stay archived and never join the candidate segment.
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> later =
        eligible.stream()
            .filter(
                poll ->
                    poll.shares().get("FI") != null
                        && poll.collectionFrom().isAfter(FI_SEGMENT.effectiveTo()))
            .toList();
    assertEquals(
        List.of(LocalDate.of(2022, 6, 21), LocalDate.of(2022, 8, 29)),
        later.stream().map(PollCsv.Poll::collectionTo).sorted().toList());
    assertEquals(
        List.of("Sentio"), later.stream().map(PollCsv.Poll::institute).distinct().toList());
    assertTrue(later.stream().noneMatch(FI_SEGMENT::covers));
    assertTrue(
        later.stream()
            .map(poll -> Roster.compose(EIGHT, poll))
            .allMatch(Roster.Composition::complete));
  }

  private static <T> Map<Integer, List<T>> byCollectionYear(
      List<PollCsv.Poll> polls, java.util.function.Function<PollCsv.Poll, T> field) {
    return polls.stream()
        .collect(
            Collectors.groupingBy(
                poll -> poll.collectionTo().getYear(),
                TreeMap::new,
                Collectors.mapping(field, Collectors.toList())));
  }
}
