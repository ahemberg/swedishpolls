package se.swedishpolls.source.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThan;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import se.swedishpolls.estimation.DailyStateSpace;
import se.swedishpolls.estimation.PollObservations;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.repository.CoveragePeriodRepository;
import se.swedishpolls.testsupport.PollCsvFixtures;
import se.swedishpolls.testsupport.TestDatabase;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "logging.level.WireMock=warn",
      "polls.ingest.enabled=false",
      "polls.source-url=${wiremock.server.baseUrl}/polls.csv",
      "spring.docker.compose.enabled=false",
      "spring.flyway.clean-disabled=false"
    })
@Import(TestDatabase.Configuration.class)
@EnableWireMock
class SnapshotIngestIT {
  @Autowired private JdbcClient db;
  @Autowired private Flyway flyway;
  @Autowired private SnapshotIngest ingest;
  @Autowired private PlatformTransactionManager transactions;
  @InjectWireMock private WireMockServer wireMock;

  private byte[] body = PollCsvFixtures.csv(PollCsvFixtures.ROW);
  private int status = 200;
  private String etag = "\"first\"";
  private StubMapping stub;

  @BeforeEach
  void reset() {
    wireMock.resetAll();
    flyway.clean();
    flyway.migrate();
    body = PollCsvFixtures.csv(PollCsvFixtures.ROW);
    status = 200;
    etag = "\"first\"";
    stub = null;
  }

  private SnapshotIngest.Result check() {
    return check(ingest);
  }

  private SnapshotIngest.Result check(SnapshotIngest target) {
    if (stub != null) wireMock.removeStub(stub);
    final com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response =
        aResponse().withStatus(status).withHeader("Last-Modified", "Mon, 07 Sep 2026 05:09:49 GMT");
    if (etag != null) response.withHeader("ETag", etag);
    if (status != 304) response.withBody(body);
    stub = wireMock.stubFor(get(urlEqualTo("/polls.csv")).willReturn(response));
    return target.check();
  }

  @Test
  void completeSnapshotsReplaceMembershipAndRemainReadable() {
    final java.lang.String original = PollCsvFixtures.ROW.replace("2020-01-20", "NA");
    body = PollCsvFixtures.csv(original + PollCsvFixtures.ROW.replace("Ipsos", "Novus"));
    assertEquals(SnapshotIngest.Result.CHANGED, check());
    final se.swedishpolls.source.Snapshot first = ingest.activeSnapshot().orElseThrow();
    assertArrayEquals(body, ingest.rawCsv(first.id()));
    assertEquals(2, ingest.polls(first.id()).size());

    // A corrected share, changed sample-size key and deleted Novus row replace the whole active
    // set.
    etag = "\"second\"";
    body = PollCsvFixtures.csv(original.replace("20.123", "20.124").replace(",1000,", ",1001,"));
    assertEquals(SnapshotIngest.Result.CHANGED, check());
    final se.swedishpolls.source.Snapshot second = ingest.activeSnapshot().orElseThrow();
    assertNotEquals(first.id(), second.id());
    assertEquals(1, ingest.polls(second.id()).size());
    assertEquals("1001", ingest.polls(second.id()).getFirst().raw().get("n"));
    assertEquals("20.124", ingest.polls(second.id()).getFirst().raw().get("M"));
    assertTrue(ingest.polls(second.id()).getFirst().eligible());
    assertFalse(ingest.polls(second.id()).getFirst().publicationTimeEligible());
    assertEquals(2, ingest.polls(first.id()).size());
    assertEquals(first, ingest.snapshot(first.id()));
    assertArrayEquals(
        PollCsvFixtures.csv(original + PollCsvFixtures.ROW.replace("Ipsos", "Novus")),
        ingest.rawCsv(first.id()));
    assertEquals(second.id(), ingest.activeSnapshot().orElseThrow().id());
  }

  @Test
  void conditionalRequestsAndHashesAvoidReimportAndCanRestoreAnEarlierSnapshot() {
    assertEquals(SnapshotIngest.Result.CHANGED, check());
    wireMock.verify(
        1,
        getRequestedFor(urlEqualTo("/polls.csv"))
            .withoutHeader("If-None-Match")
            .withoutHeader("If-Modified-Since"));
    final se.swedishpolls.source.Snapshot first = ingest.activeSnapshot().orElseThrow();
    status = 304;
    assertEquals(SnapshotIngest.Result.UNCHANGED, check());
    wireMock.verify(
        1,
        getRequestedFor(urlEqualTo("/polls.csv"))
            .withHeader("If-None-Match", equalTo("\"first\""))
            .withHeader("If-Modified-Since", equalTo("Mon, 07 Sep 2026 05:09:49 GMT")));
    status = 200;
    etag = "\"new-validator-same-bytes\"";
    assertEquals(SnapshotIngest.Result.UNCHANGED, check());
    assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
    body = PollCsvFixtures.csv(PollCsvFixtures.ROW.replace("20.123", "20.125"));
    assertEquals(SnapshotIngest.Result.CHANGED, check());
    body = ingest.rawCsv(first.id());
    assertEquals(SnapshotIngest.Result.CHANGED, check());
    assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
    assertEquals(1, ingest.polls(first.id()).size());
  }

  @Test
  void failedOrPartialResponsesKeepTheLastSnapshotAndValidators() {
    status = 304;
    assertThrows(IllegalStateException.class, this::check);
    assertTrue(ingest.activeSnapshot().isEmpty());
    status = 200;
    check();
    final se.swedishpolls.source.Snapshot first = ingest.activeSnapshot().orElseThrow();
    etag = "\"bad\"";
    for (int code : new int[] {500, 206, 404}) {
      status = code;
      assertThrows(IllegalStateException.class, this::check);
      assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
    }
    status = 200;
    for (byte[] invalid :
        new byte[][] {
          new byte[0],
          PollCsvFixtures.HEADER.getBytes(StandardCharsets.UTF_8),
          PollCsvFixtures.csv("short,row\n"),
          new byte[] {(byte) 0xc3, (byte) 0x28}
        }) {
      body = invalid;
      assertThrows(IllegalArgumentException.class, this::check);
      assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
    }
    wireMock.verify(
        moreThan(0),
        getRequestedFor(urlEqualTo("/polls.csv"))
            .withHeader("If-None-Match", equalTo("\"first\"")));
    body = ingest.rawCsv(first.id());
    assertEquals(SnapshotIngest.Result.UNCHANGED, check());
  }

  @Test
  void aFailedRowWriteRollsBackTheArchiveAndPointerTogether() {
    check();
    final se.swedishpolls.source.Snapshot first = ingest.activeSnapshot().orElseThrow();
    db.sql(
            "ALTER TABLE snapshot_poll ADD CONSTRAINT simulated_disk_failure CHECK (poll->>'company' <> 'Broken')")
        .update();
    body =
        PollCsvFixtures.csv(PollCsvFixtures.ROW + PollCsvFixtures.ROW.replace("Ipsos", "Broken"));
    assertThrows(org.springframework.dao.DataAccessException.class, this::check);
    assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
    db.sql("ALTER TABLE snapshot_poll DROP CONSTRAINT simulated_disk_failure").update();
    assertEquals(SnapshotIngest.Result.CHANGED, check());
    assertEquals(2, ingest.polls(ingest.activeSnapshot().orElseThrow().id()).size());
  }

  @Test
  void anotherWorkerCannotFetchWhileTheDatabaseLockIsHeld() {
    final org.springframework.transaction.support.TransactionTemplate transaction =
        new TransactionTemplate(transactions);
    transaction.executeWithoutResult(
        ignored -> {
          db.sql("SELECT pg_advisory_xact_lock(1717001)").query().singleRow();
          try (final java.util.concurrent.ExecutorService executor =
              java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertEquals(
                SnapshotIngest.Result.BUSY,
                executor
                    .submit((java.util.concurrent.Callable<SnapshotIngest.Result>) this::check)
                    .get());
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });
    wireMock.verify(0, getRequestedFor(urlEqualTo("/polls.csv")));
    assertTrue(ingest.activeSnapshot().isEmpty());
    assertEquals(SnapshotIngest.Result.CHANGED, check());
  }

  @Test
  void archivesThePinnedAuditIncludingExcludedRowsAndExactAssessments() throws Exception {
    try (final java.io.InputStream input = getClass().getResourceAsStream("/polls/audit.csv")) {
      body = input.readAllBytes();
    }
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> expected = PollCsv.parse(body);
    assertEquals(SnapshotIngest.Result.CHANGED, check());
    final se.swedishpolls.source.Snapshot snapshot = ingest.activeSnapshot().orElseThrow();
    assertEquals(
        "27012c05d1e948133a4a2558ec841df62c518b9122117a461ca1f8f6aa9d1608", snapshot.sha256());
    assertArrayEquals(body, ingest.rawCsv(snapshot.id()));
    assertEquals(expected, ingest.polls(snapshot.id()));
    assertEquals(
        4, db.sql("SELECT count(*) FROM election_reference").query(Integer.class).single());
    // Only pre-2022 development inputs enter the numerical checks. Official outcomes stay in their
    // own tables.
    final java.util.List<se.swedishpolls.source.PollCsv.Poll> development =
        ingest.polls(snapshot.id()).stream()
            .filter(
                poll ->
                    poll.collectionTo() != null
                        && poll.collectionTo().isBefore(java.time.LocalDate.of(2022, 1, 1)))
            .toList();
    final java.util.List<se.swedishpolls.source.Roster.CoveragePeriod> periods =
        new CoveragePeriodRepository(db).periods();
    final java.util.List<java.time.LocalDate> elections =
        db.sql("SELECT election_date FROM election_reference ORDER BY election_date")
            .query(java.time.LocalDate.class)
            .list();
    final se.swedishpolls.estimation.PollObservations.Batch eight =
        PollObservations.prepare(periods.getFirst(), development);
    final se.swedishpolls.estimation.PollObservations.Batch fi =
        PollObservations.prepare(periods.get(1), development);
    assertEquals(388, fi.observations().size());
    assertTrue(eight.observations().size() > fi.observations().size());
    for (se.swedishpolls.estimation.PollObservations.Batch batch : java.util.List.of(eight, fi)) {
      assertEquals(development.size(), batch.observations().size() + batch.exclusions().size());
      assertTrue(
          batch.observations().stream()
              .allMatch(o -> o.poll().surveyType().equals("voting_intention")));
      assertEquals(
          batch.observations().size(),
          batch.observations().stream().map(o -> o.poll().rowNumber()).distinct().count());
      assertTrue(
          batch.observations().stream()
              .allMatch(o -> !o.ilr().hasUncountable() && !o.covariance().hasUncountable()));
      final se.swedishpolls.estimation.DailyStateSpace.Fit fit =
          DailyStateSpace.fit(batch, elections, new DailyStateSpace.Parameters(0.0001, 0.05, 1.5));
      assertTrue(Double.isFinite(fit.logLikelihood()));
      assertEquals(batch.period().effectiveFrom(), fit.days().getFirst().date());
      assertEquals(
          batch.observations().stream()
              .map(PollObservations.Observation::midpoint)
              .max(java.time.LocalDate::compareTo)
              .orElseThrow(),
          fit.days().getLast().date());
      assertTrue(
          fit.days().stream()
              .allMatch(
                  day ->
                      !day.filteredMean().hasUncountable()
                          && !day.smoothedMean().hasUncountable()
                          && !day.smoothedCovariance().hasUncountable()));
      // House effects reset on every election day the fitted span contains and center over its
      // active institutes.
      assertEquals(
          elections.stream()
              .filter(
                  date ->
                      date.isAfter(batch.period().effectiveFrom())
                          && !date.isAfter(fit.days().getLast().date()))
              .toList(),
          fit.cycles().stream().skip(1).map(DailyStateSpace.Cycle::start).toList());
      for (se.swedishpolls.estimation.DailyStateSpace.Cycle cycle : fit.cycles()) {
        assertEquals(1, cycle.weights().stream().mapToDouble(Double::doubleValue).sum(), 1e-12);
        assertEquals(
            cycle.effects().size() * (batch.components().size() - 1),
            cycle.smoothedMean().getNumRows());
        assertFalse(
            cycle.smoothedMean().hasUncountable() || cycle.smoothedCovariance().hasUncountable());
        final int dimension = batch.components().size() - 1;
        final org.ejml.simple.SimpleMatrix weighted =
            new org.ejml.simple.SimpleMatrix(dimension, 1);
        for (int effect = 0; effect < cycle.effects().size(); effect++)
          weighted.setTo(
              weighted.plus(
                  cycle
                      .smoothedMean()
                      .extractMatrix(effect * dimension, (effect + 1) * dimension, 0, 1)
                      .scale(cycle.weights().get(effect))));
        assertTrue(
            weighted.elementMaxAbs() < 1e-12,
            () -> "Uncentered ensemble: " + weighted.elementMaxAbs());
      }
      // Ingestion documents Demoskop's method break, so its two eras keep separate effect
      // identities.
      assertTrue(
          fit.cycles()
              .getLast()
              .effects()
              .containsAll(java.util.List.of("demoskop_before_2019_11", "inizio_continuation")));
    }
    assertFalse(fi.period().supportValidated());
    assertEquals(expected, ingest.polls(snapshot.id()));
    assertEquals(SnapshotIngest.Result.UNCHANGED, check());
    assertEquals(snapshot.id(), ingest.activeSnapshot().orElseThrow().id());
  }

  @Test
  void oversizedDownloadsAreStoppedByTheHttpClientAndRetainTheArchive() {
    check();
    final se.swedishpolls.source.Snapshot first = ingest.activeSnapshot().orElseThrow();
    body = new byte[16 * 1024 * 1024 + 1];
    assertThrows(IllegalStateException.class, this::check);
    assertEquals(first.id(), ingest.activeSnapshot().orElseThrow().id());
  }
}
