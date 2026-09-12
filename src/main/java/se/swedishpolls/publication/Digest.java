package se.swedishpolls.publication;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The digest a publication records its stored bytes under. Documents and image bytes are written,
 * read back and compared through this one operation, so what was staged, what was promoted and what
 * is served later are all the same comparison.
 */
public final class Digest {
  private Digest() {}

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }
}
