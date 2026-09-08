package se.swedishpolls;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RosterTest {
    private static final Roster.CoveragePeriod EIGHT = new Roster.CoveragePeriod("eight_party_2010",
            LocalDate.of(2010, 1, 1), null, PollCsv.PARTIES, false, true, "https://example.invalid/decision");
    private static final Roster.CoveragePeriod FI_SEGMENT = new Roster.CoveragePeriod("fi_candidate_2014_2018",
            LocalDate.of(2014, 4, 9), LocalDate.of(2018, 9, 7),
            java.util.stream.Stream.concat(PollCsv.PARTIES.stream(), java.util.stream.Stream.of("FI")).toList(),
            true, false, "https://example.invalid/decision");

    private static PollCsv.Poll poll(String row) { return PollCsv.parse(PollCsvTest.csv(row)).getFirst(); }

    @Test
    void groupsFiIntoOtherOutsideAnFiSegmentAndSeparatesItInside() {
        var row = PollCsvTest.ROW.replace("2020-01-20", "2016-06-20").replace("2020-01-01", "2016-06-01")
                .replace("2020-01-19", "2016-06-19");
        var outside = Roster.compose(EIGHT, poll(row));
        assertEquals(List.of("M", "L", "C", "KD", "S", "V", "MP", "SD", "OTHER"), List.copyOf(outside.components().keySet()));
        assertEquals(new BigDecimal("1.877"), outside.components().get("OTHER"));
        assertTrue(outside.complete());

        var inside = Roster.compose(FI_SEGMENT, poll(row));
        assertEquals(List.of("M", "L", "C", "KD", "S", "V", "MP", "SD", "FI", "RESIDUAL"), List.copyOf(inside.components().keySet()));
        assertEquals(new BigDecimal("1"), inside.components().get("FI"));
        assertEquals(new BigDecimal("0.877"), inside.components().get("RESIDUAL"));
        // Subtracting FI exactly once keeps support outside the fixed eight identical across rosters.
        assertEquals(0, outside.comparableRemainder().compareTo(inside.comparableRemainder()));
    }

    @Test
    void excludesPollsThatCannotFormThePeriodComposition() {
        var window = PollCsvTest.ROW.replace("2020-01-20", "2016-06-20").replace("2020-01-01", "2016-06-01")
                .replace("2020-01-19", "2016-06-19");
        var missingFi = poll(window.replace(",17,1,10,", ",17,NA,10,"));
        assertTrue(missingFi.eligible());
        assertTrue(Roster.compose(EIGHT, missingFi).complete());
        assertEquals(List.of("missing_share:FI"), Roster.compose(FI_SEGMENT, missingFi).exclusionReasons());
        assertTrue(Roster.compose(FI_SEGMENT, missingFi).components().isEmpty());

        var largeFi = poll(window.replace(",17,1,10,", ",17,3,10,"));
        assertEquals(List.of("negative_residual"), Roster.compose(FI_SEGMENT, largeFi).exclusionReasons());

        var beforeSegment = poll(PollCsvTest.ROW.replace("2020", "2013"));
        assertEquals(List.of("outside_coverage_period:fi_candidate_2014_2018"),
                Roster.compose(FI_SEGMENT, beforeSegment).exclusionReasons());
        assertTrue(Roster.compose(EIGHT, beforeSegment).complete());

        var ineligible = poll(PollCsvTest.ROW.replace("Ipsos", "Demoskop valdag").replace("2020-01-20", "2016-06-20")
                .replace("2020-01-01", "2016-06-01").replace("2020-01-19", "2016-06-19"));
        assertEquals(List.of("exit_or_election_day"), Roster.compose(FI_SEGMENT, ineligible).exclusionReasons());
    }

    @Test
    void documentsCandidateFiBoundariesFromEligiblePollsInThePinnedSnapshot() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/polls/audit.csv")) { bytes = input.readAllBytes(); }
        var polls = PollCsv.parse(bytes);
        var inSegment = polls.stream().map(poll -> Roster.compose(FI_SEGMENT, poll)).filter(Roster.Composition::complete).toList();
        assertEquals(388, inSegment.size());
        var window = polls.stream().filter(PollCsv.Poll::eligible).filter(FI_SEGMENT::covers).toList();
        assertEquals(439, window.size());
        assertEquals(51, window.stream().filter(poll -> !Roster.compose(FI_SEGMENT, poll).complete()).count());
        assertTrue(window.stream().map(poll -> Roster.compose(FI_SEGMENT, poll))
                .filter(c -> !c.complete()).allMatch(c -> c.exclusionReasons().equals(List.of("missing_share:FI"))));

        // The candidate boundaries are the outermost collection dates of those eligible FI polls.
        var withFi = window.stream().filter(poll -> poll.shares().get("FI") != null).toList();
        assertEquals(LocalDate.of(2014, 4, 9), withFi.stream().map(PollCsv.Poll::collectionFrom).min(LocalDate::compareTo).orElseThrow());
        assertEquals(LocalDate.of(2018, 9, 7), withFi.stream().map(PollCsv.Poll::collectionTo).max(LocalDate::compareTo).orElseThrow());
        assertEquals(10, withFi.stream().map(PollCsv.Poll::institute).distinct().count());
        // Merging the collection windows in start order leaves one gap wider than a month, in July 2016.
        long widestGap = 0;
        var covered = LocalDate.of(2014, 4, 9);
        for (var poll : withFi.stream().sorted(java.util.Comparator.comparing(PollCsv.Poll::collectionFrom)).toList()) {
            widestGap = Math.max(widestGap, java.time.temporal.ChronoUnit.DAYS.between(covered, poll.collectionFrom()));
            if (poll.collectionTo().isAfter(covered)) covered = poll.collectionTo();
        }
        assertEquals(27, widestGap);

        // The two isolated 2022 observations stay archived and never join the candidate segment.
        var later = polls.stream().filter(PollCsv.Poll::eligible)
                .filter(poll -> poll.shares().get("FI") != null && poll.collectionFrom().isAfter(LocalDate.of(2018, 9, 7))).toList();
        assertEquals(List.of(LocalDate.of(2022, 6, 21), LocalDate.of(2022, 8, 29)),
                later.stream().map(PollCsv.Poll::collectionTo).sorted().toList());
        assertEquals(List.of("Sentio"), later.stream().map(PollCsv.Poll::institute).distinct().toList());
        assertTrue(later.stream().noneMatch(FI_SEGMENT::covers));
        assertTrue(later.stream().map(poll -> Roster.compose(EIGHT, poll)).allMatch(Roster.Composition::complete));
    }
}
