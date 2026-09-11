package se.swedishpolls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the single publication worker. Overlap is prevented by its advisory lock, not by timing. */
@Component
@ConditionalOnProperty(name = "publication.enabled", havingValue = "true", matchIfMissing = true)
final class PublisherScheduler {
  private static final Logger LOG = LoggerFactory.getLogger(PublisherScheduler.class);

  private final Publisher publisher;

  PublisherScheduler(Publisher publisher) {
    this.publisher = publisher;
  }

  @Scheduled(
      fixedRateString = "${publication.interval:30m}",
      initialDelayString = "${publication.initial-delay:1m}")
  void publish() {
    try {
      // Every log line is a literal. The reason a run did not publish is recorded on
      // publication_attempt, where it cannot reach a log file at all.
      final Publisher.Attempt attempt = publisher.publish();
      switch (attempt.outcome()) {
        case PUBLISHED -> LOG.info("A new publication is current");
        case UNCHANGED -> LOG.info("The source snapshot is unchanged");
        case BLOCKED -> LOG.warn("The release verdict blocks publication");
        case FAILED -> LOG.error("The update failed; the previous publication is retained");
        case BUSY -> LOG.info("Another worker holds the publication lock");
      }
    } catch (RuntimeException e) {
      LOG.error("Publication worker failed; the previous publication is retained", e);
    }
  }
}
