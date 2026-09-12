package se.swedishpolls.publication.service;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import se.swedishpolls.publication.CurrentPublication;
import se.swedishpolls.publication.PublicationAsset;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.Translations;
import se.swedishpolls.publication.repository.PublicationStore;
import tools.jackson.databind.node.ObjectNode;

/**
 * The published surface a request reads. Resolution, pinned reads and asset bytes are use cases of
 * the publication, so the HTTP layer asks for them here instead of holding a store: a request still
 * reads one publication, and nothing above this class knows how a publication is persisted.
 */
@Component
public class Publications {
  private final PublicationStore store;

  public Publications(PublicationStore store) {
    this.store = store;
  }

  /** The publication pinned by identifier, or empty when no published one carries it. */
  public Optional<PublicationHeader> header(String publicationId) {
    return store.header(publicationId);
  }

  /** The publication a request without a pin reads, and whether a failed update kept it. */
  public Optional<CurrentPublication> current() {
    return store.current();
  }

  /** The header of the publication a request without a pin reads. */
  public Optional<PublicationHeader> currentHeader() {
    return store.current().flatMap(current -> store.header(current.publicationId()));
  }

  /** One stored document of one publication, in one language. */
  public Optional<String> document(String publicationId, String surface, String language) {
    return store.document(publicationId, surface, language);
  }

  /** When the source was last read successfully, whether or not a publication followed. */
  public Optional<Instant> lastSuccessfulCheck() {
    return store.lastSuccessfulCheck();
  }

  /** One immutable asset version of one publication. */
  public Optional<PublicationAsset> asset(
      String publicationId, String kind, String language, int version) {
    return store.asset(publicationId, kind, language, version);
  }

  /** The stored bytes of an asset, verified against the digest recorded when they were staged. */
  public byte[] bytes(PublicationAsset asset) {
    return store.bytes(asset);
  }

  /** The identity a publication is published under, composed for the surfaces that show it. */
  public ObjectNode metadata(PublicationHeader header, Translations text) {
    return PublicationMetadata.of(store, header, text);
  }
}
