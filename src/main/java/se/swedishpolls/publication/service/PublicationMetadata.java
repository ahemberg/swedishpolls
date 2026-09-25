package se.swedishpolls.publication.service;

import java.util.Optional;
import se.swedishpolls.publication.CurrentPublication;
import se.swedishpolls.publication.ModelRun;
import se.swedishpolls.publication.PinnedSnapshot;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.repository.PublicationStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The identity a publication is published under: which run produced it, which snapshot it pinned,
 * when it was checked and published, and whether a later update failed.
 *
 * <p>The API surface and the rendered page read this through one builder, so a page and the
 * requests it makes can never disagree about which publication they are showing.
 */
final class PublicationMetadata {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private PublicationMetadata() {}

  static ObjectNode of(PublicationStore store, PublicationHeader header) {
    final ModelRun run = store.run(header.runId());
    final PinnedSnapshot snapshot = store.snapshot(header.snapshotId());
    final Optional<CurrentPublication> current = store.current();
    final boolean isCurrent =
        current.isPresent() && current.get().publicationId().equals(header.publicationId());
    final ObjectNode node = JSON.createObjectNode();
    node.put("publicationId", header.publicationId());
    node.put("publishedAt", header.publishedAt().toString());
    node.put("sourceCheckedAt", header.sourceCheckedAt().toString());
    node.put("lastFieldworkDate", header.lastFieldworkDate().toString());
    node.put("stale", isCurrent && current.get().stale());
    if (isCurrent && current.get().stale()) {
      node.put("staleSince", current.get().staleSince().toString());
    } else {
      node.putNull("staleSince");
    }
    node.put("permalink", "/api/v1/publications/" + header.publicationId());
    final ObjectNode model = node.putObject("modelRun");
    model.put("runId", run.runId());
    model.put("codeVersion", run.codeVersion());
    model.put("estimatorVersion", run.estimatorVersion());
    model.put("seed", run.seed());
    model.put("runtime", run.runtime());
    model.put("numericalLibrary", run.numericalLibrary());
    final ObjectNode archived = node.putObject("snapshot");
    archived.put("snapshotId", snapshot.snapshotId());
    archived.put("sha256", snapshot.sha256());
    archived.put("sourceUrl", snapshot.sourceUrl());
    archived.put("capturedAt", snapshot.capturedAt().toString());
    node.put("history", header.history());
    store
        .coalitionHistoryMetadata(header.publicationId())
        .ifPresent(
            manifest -> {
              node.putObject("capabilities").put("customCoalitionHistory", 1);
              node.set("coalitionHistory", JSON.readTree(manifest));
            });

    return node;
  }
}
