package se.swedishpolls.publication;

import java.time.Instant;
import java.time.LocalDate;

/** One publication's immutable header. */
public record PublicationHeader(
    String publicationId,
    String runId,
    long snapshotId,
    LocalDate lastFieldworkDate,
    Instant sourceCheckedAt,
    Instant publishedAt,
    String history,
    String headlinePeriod,
    int approximatedElection,
    String state) {}
