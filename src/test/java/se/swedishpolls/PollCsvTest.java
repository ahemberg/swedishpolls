package se.swedishpolls;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PollCsvTest {
    static final String HEADER = "PublYearMonth,Company,M,L,C,KD,S,V,MP,SD,FI,Uncertain,n,PublDate,house,collectPeriodFrom,collectPeriodTo,approxPeriod\n";
    static final String ROW = "2020-01,Ipsos,20.123,5,8,5,30,8,5,17,1,10,1000,2020-01-20,Ipsos,2020-01-01,2020-01-19,FALSE\n";
    static final String SEGMENT_ROW = ROW.replace("2020-01-20", "2016-06-20").replace("2020-01-01", "2016-06-01").replace("2020-01-19", "2016-06-19");

    static byte[] csv(String rows) { return (HEADER + rows).getBytes(StandardCharsets.UTF_8); }

    @Test
    void preservesPrecisionAndMissingnessWithoutMixingUncertainIntoComposition() {
        var poll = PollCsv.parse(csv(ROW.replace("2020-01-20", "NA"))).getFirst();
        assertEquals(new BigDecimal("20.123"), poll.shares().get("M"));
        assertEquals(new BigDecimal("1.877"), poll.remainder());
        assertEquals("10", poll.raw().get("Uncertain"));
        assertNull(poll.publicationDate());
        assertTrue(poll.eligible());
        assertFalse(poll.publicationTimeEligible());
        assertEquals("Ipsos", poll.company());
        assertEquals("Ipsos", poll.institute());
        assertNull(poll.methodEra());
        assertEquals("respondents; upstream Ipsos normalization may apply", poll.denominatorNote());
    }
    @Test
    void archivesIneligibleRowsWithReasonsAndKeepsMissingSharesNull() {
        var rows = PollCsv.parse(csv(
                ROW.replace(",17,1,10,", ",NA,1,10,")
                + ROW.replace("Ipsos", "Demoskop valdag")
                + ROW.replace("20.123", "25")
                + ROW.replace("2020-01-20", "2020-01-02")
                + ROW.replace("2020", "2009")));
        assertNull(rows.get(0).shares().get("SD"));
        assertNull(rows.get(0).remainder());
        assertTrue(rows.get(0).exclusionReasons().contains("missing_share:SD"));
        assertTrue(rows.get(1).exclusionReasons().contains("exit_or_election_day"));
        assertTrue(rows.get(2).exclusionReasons().contains("negative_remainder"));
        assertTrue(rows.get(3).exclusionReasons().contains("publication_before_collection_end"));
        assertTrue(rows.get(4).exclusionReasons().contains("outside_supported_history"));
        assertTrue(rows.stream().noneMatch(PollCsv.Poll::eligible));
    }
    @Test
    void rejectsIncompleteDocumentsAndQuarantinesDuplicateKeysIncludingNullPublication() {
        for (var body : java.util.List.of("", HEADER, HEADER + "short,row\n", HEADER + "\"unterminated", HEADER.replace("Company", "house") + ROW))
            assertThrows(IllegalArgumentException.class, () -> PollCsv.parse(body.getBytes(StandardCharsets.UTF_8)));
        var rows = PollCsv.parse(csv(ROW.replace("2020-01-20", "NA")
                + ROW.replace("2020-01-20", "NA").replace("20.123", "20.124")));
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(p -> p.exclusionReasons().contains("duplicate_natural_key")));
    }

    @Test
    void recordsDocumentedMethodBreakWithoutRenamingSourceIdentities() {
        var rows = PollCsv.parse(csv(ROW.replace("Ipsos", "Demoskop").replace("2020", "2018")
                + ROW.replace("Ipsos", "Demoskop") + ROW.replace("Ipsos", "Inizio")));
        assertEquals("demoskop_before_2019_11", rows.get(0).methodEra());
        assertEquals("inizio_continuation", rows.get(1).methodEra());
        assertEquals("inizio_continuation", rows.get(2).methodEra());
        assertEquals("Inizio", rows.get(2).institute());
        assertTrue(rows.get(0).methodEvidence().contains("f0390c05854d87bbf21db9d31c6431ffa0f07f7e"));
    }

    @Test
    void agreesWithPinnedDataAuditWithoutRoundingOrRescaling() throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/polls/audit.csv")) { bytes = input.readAllBytes(); }
        assertEquals("27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var rows = PollCsv.parse(bytes);
        assertEquals(2650, rows.size());
        var retained = rows.stream().filter(p -> p.collectionFrom() != null && p.collectionTo() != null
                && p.sampleSize() != null && !p.collectionTo().isBefore(java.time.LocalDate.of(2006, 9, 1))).toList();
        assertEquals(1665, retained.size());
        assertEquals(33, retained.stream().filter(p -> p.shares().get("SD") == null).count());
        assertEquals(20, retained.stream().filter(p -> p.publicationDate() == null).count());
        assertEquals(7, retained.stream().filter(p -> java.util.List.of("M", "L", "C", "KD", "S", "V", "MP", "SD")
                .stream().anyMatch(party -> p.shares().get(party) != null && p.shares().get(party).scale() > 2)).count());
        assertEquals(5, retained.stream().filter(p -> p.remainder() != null && p.remainder().signum() == 0).count());
        assertTrue(retained.stream().noneMatch(p -> p.remainder() != null && p.remainder().signum() < 0));
        assertTrue(rows.stream().filter(p -> p.surveyType().equals("exit_or_election_day")).noneMatch(PollCsv.Poll::eligible));
        assertTrue(rows.stream().anyMatch(p -> p.eligible() && p.publicationDate() == null));
    }
}
