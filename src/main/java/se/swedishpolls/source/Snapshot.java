package se.swedishpolls.source;

/** One archived source snapshot: its row identity and its content hash. */
public record Snapshot(long id, String sha256) {}
