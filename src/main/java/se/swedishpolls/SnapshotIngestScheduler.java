package se.swedishpolls;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "polls.ingest.enabled", havingValue = "true", matchIfMissing = true)
final class SnapshotIngestScheduler {
  private final SnapshotIngest ingest;

  SnapshotIngestScheduler(SnapshotIngest ingest) {
    this.ingest = ingest;
  }

  @Scheduled(fixedRateString = "${polls.ingest.interval:30m}")
  void check() {
    try {
      ingest.check();
    } catch (RuntimeException e) {
      LoggerFactory.getLogger(SnapshotIngestScheduler.class)
          .error("Poll source check failed; active snapshot retained", e);
    }
  }
}
