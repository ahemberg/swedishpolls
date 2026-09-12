package se.swedishpolls.publication;

import java.time.Instant;

/** The publication a request without a pin reads, and whether a failed update kept it. */
public record CurrentPublication(String publicationId, boolean stale, Instant staleSince) {}
