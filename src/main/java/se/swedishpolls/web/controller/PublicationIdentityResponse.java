package se.swedishpolls.web.controller;

/** The publication identity repeated by every dependent API response. */
record PublicationIdentityResponse(String publicationId, String runId, long snapshotId) {}
