package se.swedishpolls.publication.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.support.CronExpression;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import se.swedishpolls.source.Snapshot;
import se.swedishpolls.source.service.SnapshotIngest;
import se.swedishpolls.testsupport.TestDatabase;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "logging.level.WireMock=warn",
      "polls.ingest.enabled=true",
      "publication.enabled=false",
      "polls.source-url=${wiremock.server.baseUrl}/polls.csv",
      "spring.docker.compose.enabled=false"
    })
@Import(TestDatabase.Configuration.class)
@EnableWireMock(@ConfigureWireMock(filesUnderClasspath = "scheduling-wiremock"))
class RefreshSchedulingIT {
  private static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

  @Autowired private SnapshotIngest ingest;
  @Autowired private PublisherScheduler scheduler;
  @Autowired private ScheduledAnnotationBeanPostProcessor scheduledTasks;
  @Autowired private JdbcClient db;
  @InjectWireMock private WireMockServer wireMock;

  @Test
  void anEmptyArchiveIsCollectedAtStartup() {
    await().until(() -> ingest.activeSnapshot().isPresent());
  }

  @Test
  void unchangedInputKeepsTheSnapshotAndOnlyAdvancesTheSuccessfulCheck() {
    await().until(() -> ingest.activeSnapshot().isPresent());
    final Snapshot before = ingest.activeSnapshot().orElseThrow();
    final Instant checkedBefore = lastSuccessfulCheck();

    scheduler.refresh();

    final Snapshot after = ingest.activeSnapshot().orElseThrow();
    assertEquals(before, after);
    assertTrue(lastSuccessfulCheck().isAfter(checkedBefore));
  }

  @Test
  void anUpstreamFailureRetainsTheSnapshotAndSuccessfulCheckTime() {
    await().until(() -> ingest.activeSnapshot().isPresent());
    final Snapshot before = ingest.activeSnapshot().orElseThrow();
    final Instant checkedBefore = lastSuccessfulCheck();
    final com.github.tomakehurst.wiremock.stubbing.StubMapping failure =
        wireMock.stubFor(
            get(urlEqualTo("/polls.csv")).atPriority(1).willReturn(aResponse().withStatus(500)));

    try {
      scheduler.refresh();
    } finally {
      wireMock.removeStub(failure);
    }

    assertEquals(before, ingest.activeSnapshot().orElseThrow());
    assertEquals(checkedBefore, lastSuccessfulCheck());
  }

  @Test
  void theSingleRefreshRunsAtThreeInStockholmAcrossDaylightSavingChanges()
      throws ReflectiveOperationException {
    final Set<ScheduledTask> tasks = scheduledTasks.getScheduledTasks();
    assertEquals(1, tasks.size());
    final CronTask task = assertInstanceOf(CronTask.class, tasks.iterator().next().getTask());
    assertEquals("0 0 3 * * *", task.getExpression());

    final Method refresh = PublisherScheduler.class.getDeclaredMethod("refresh");
    final Scheduled scheduled = refresh.getAnnotation(Scheduled.class);
    assertEquals("Europe/Stockholm", scheduled.zone());

    final CronExpression cron = CronExpression.parse(task.getExpression());
    final ZonedDateTime spring = cron.next(ZonedDateTime.of(2026, 3, 28, 3, 0, 0, 0, STOCKHOLM));
    final ZonedDateTime autumn = cron.next(ZonedDateTime.of(2026, 10, 24, 3, 0, 0, 0, STOCKHOLM));
    assertTrue(spring != null && autumn != null);
    assertEquals(LocalDate.of(2026, 3, 29), spring.toLocalDate());
    assertEquals(LocalDate.of(2026, 10, 25), autumn.toLocalDate());
    assertEquals(3, spring.getHour());
    assertEquals(3, autumn.getHour());
  }

  private Instant lastSuccessfulCheck() {
    return db.sql("SELECT last_successful_check_at FROM poll_source WHERE source_url = ?")
        .param(wireMock.baseUrl() + "/polls.csv")
        .query((result, row) -> result.getTimestamp(1).toInstant())
        .single();
  }
}
