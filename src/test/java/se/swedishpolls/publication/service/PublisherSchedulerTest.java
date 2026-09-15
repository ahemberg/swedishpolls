package se.swedishpolls.publication.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class PublisherSchedulerTest {
  @Test
  void aFailedFreshInstallationCheckDoesNotStopApplicationStartup() {
    final Publisher publisher = mock(Publisher.class);
    doThrow(new IllegalStateException("source unavailable"))
        .when(publisher)
        .refreshAtStartup(false);

    final PublisherScheduler scheduler = new PublisherScheduler(publisher, false);

    assertDoesNotThrow(scheduler::startup);
  }

  @Test
  void anUpstreamFailureDoesNotStopTheNightlySchedule() {
    final Publisher publisher = mock(Publisher.class);
    doThrow(new IllegalStateException("source unavailable")).when(publisher).refresh(false);

    final PublisherScheduler scheduler = new PublisherScheduler(publisher, false);

    assertDoesNotThrow(scheduler::refresh);
  }
}
