package se.swedishpolls.publication;

import java.time.Instant;

/** The archived source snapshot a publication pinned. */
public record PinnedSnapshot(
    long snapshotId, String sha256, String sourceUrl, Instant capturedAt) {}
