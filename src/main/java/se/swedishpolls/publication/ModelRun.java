package se.swedishpolls.publication;

/** The model run behind a publication. */
public record ModelRun(
    String runId,
    String protocolVersion,
    long seed,
    int draws,
    String parameters,
    String codeVersion,
    String estimatorVersion,
    String runtime,
    String numericalLibrary) {}
