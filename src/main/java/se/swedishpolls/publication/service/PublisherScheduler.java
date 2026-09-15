package se.swedishpolls.publication.service;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the single source refresh and, when enabled, the publication worker. */
@Component
@ConditionalOnProperty(name = "polls.ingest.enabled", havingValue = "true", matchIfMissing = true)
final class PublisherScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(PublisherScheduler.class);

  private final Publisher publisher;
  private final boolean publicationEnabled;

  PublisherScheduler(
      Publisher publisher, @Value("${publication.enabled:true}") boolean publicationEnabled) {
    this.publisher = publisher;
    this.publicationEnabled = publicationEnabled;
  }

  @EventListener(ApplicationReadyEvent.class)
  void startup() {
    try {
      publisher.refreshAtStartup(publicationEnabled);
    } catch (RuntimeException e) {
      LOG.error("Startup refresh failed; the nightly refresh will retry", e);
    }
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "Europe/Stockholm")
  void refresh() {
    try {
      final Publisher.@Nullable Attempt attempt = publisher.refresh(publicationEnabled);
      if (attempt == null) {
        LOG.info("The poll source was checked; publication is disabled");
        return;
      }
      // Every log line is a literal. The reason a run did not publish is recorded on
      // publication_attempt, where it cannot reach a log file at all.
      switch (attempt.outcome()) {
        case PUBLISHED -> LOG.info("A new publication is current");
        case UNCHANGED -> LOG.info("The source snapshot is unchanged");
        case BLOCKED -> LOG.warn("The release verdict blocks publication");
        case FAILED -> LOG.error("The update failed; the previous publication is retained");
        case BUSY -> LOG.info("Another worker holds the publication lock");
      }
    } catch (RuntimeException e) {
      LOG.error("Refresh failed; retained source data and publications remain available", e);
    }
  }
}
