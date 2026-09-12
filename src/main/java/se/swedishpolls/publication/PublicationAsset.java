package se.swedishpolls.publication;

/** One immutable asset version. Its bytes live on the durable publication volume. */
public record PublicationAsset(
    String publicationId,
    String kind,
    String language,
    int version,
    String rendererVersion,
    String mediaType,
    int byteCount,
    String sha256) {
  /** One newly rendered version, described by the bytes it was rendered as. */
  public static PublicationAsset of(
      String publicationId,
      String kind,
      String language,
      int version,
      String rendererVersion,
      String mediaType,
      byte[] bytes) {
    return new PublicationAsset(
        publicationId,
        kind,
        language,
        version,
        rendererVersion,
        mediaType,
        bytes.length,
        Digest.sha256(bytes));
  }

  /** The file this version is stored as, under the publication's own directory. */
  public String fileName() {
    return kind + "-" + language + "-" + version + ".png";
  }

  /** The permanent path of this version. A renderer change adds a version, never a rewrite. */
  public String path() {
    return "/assets/" + publicationId + "/" + fileName();
  }
}
