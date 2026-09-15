package se.swedishpolls.source;

import java.time.Instant;

/** One archived source snapshot: its row identity and its content hash. */
public record Snapshot(long id, String sha256, Instant capturedAt) {}
