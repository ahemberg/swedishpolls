package se.swedishpolls.publication.service;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import se.swedishpolls.publication.ModelFreeze;
import se.swedishpolls.publication.PublicationAsset;
import se.swedishpolls.publication.PublicationDocuments;
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
  /** One publication selected for a request, and whether the caller pinned it. */
  public record Resolved(PublicationHeader header, boolean permanent) {
    public String publicationId() {
      return header.publicationId();
    }
  }

  private final PublicationStore store;
  private final ModelFreeze freeze;

  public Publications(PublicationStore store) {
    this.store = store;
    this.freeze = ModelFreeze.load();
  }

  /** The pinned publication when named, otherwise the current publication. */
  public Optional<Resolved> resolve(String publicationId) {
    if (publicationId != null) {
      return store.header(publicationId).map(header -> new Resolved(header, true));
    }
    return store
        .current()
        .flatMap(current -> store.header(current.publicationId()))
        .map(header -> new Resolved(header, false));
  }

  public Optional<String> latest(PublicationHeader publication, String period, String language) {
    return document(publication, PublicationDocuments.latestSurface(period), language);
  }

  public Optional<String> history(PublicationHeader publication, String language) {
    return document(publication, PublicationDocuments.HISTORY_SURFACE, language);
  }

  public Optional<String> coalitionHistory(PublicationHeader publication) {
    return document(
        publication,
        se.swedishpolls.publication.CoalitionHistoryDocument.SURFACE,
        Translations.SWEDISH);
  }

  public Optional<String> institutes(PublicationHeader publication, String language) {
    return document(publication, PublicationDocuments.INSTITUTES_SURFACE, language);
  }

  public Optional<String> elections(PublicationHeader publication, String period, String language) {
    return document(publication, PublicationDocuments.electionsSurface(period), language);
  }

  public Optional<String> seats(PublicationHeader publication, int electionYear, String language) {
    return document(publication, PublicationDocuments.seatsSurface(electionYear), language);
  }

  public Optional<String> coalitions(
      PublicationHeader publication, int electionYear, String language) {
    return document(publication, PublicationDocuments.coalitionsSurface(electionYear), language);
  }

  /** When the source was last read successfully, whether or not a publication followed. */
  public Optional<Instant> lastSuccessfulCheck() {
    return store.lastSuccessfulCheck();
  }

  /**
   * The shipped estimator contract this deployment runs under: its protocol versions, the release
   * verdict and the frozen values a page that explains the method presents. It is deployment-wide
   * rather than per publication, so it is read once here rather than out of a stored document.
   */
  public ModelFreeze freeze() {
    return freeze;
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

  private Optional<String> document(
      PublicationHeader publication, String surface, String language) {
    return store.document(publication.publicationId(), surface, language);
  }
}
