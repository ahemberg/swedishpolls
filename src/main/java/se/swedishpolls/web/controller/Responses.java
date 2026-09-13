package se.swedishpolls.web.controller;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The two cache classes every response falls into and the content ETag both of them carry. A
 * permanently pinned publication is immutable for a year; the current one is revalidated. The
 * digest is taken over the bytes that are about to be sent, so it is the response's own identity
 * and owes nothing to how a publication is stored.
 */
final class Responses {
  private static final long CURRENT_MAX_AGE_SECONDS = 300;
  private static final long IMMUTABLE_MAX_AGE_SECONDS = 31_536_000;

  private Responses() {}

  static ResponseEntity<byte[]> respond(
      byte[] body, MediaType mediaType, boolean permanent, String ifNoneMatch) {
    final String etag = "\"" + sha256(body) + "\"";
    final CacheControl cache = cache(permanent);
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
    }
    return ResponseEntity.ok().eTag(etag).cacheControl(cache).contentType(mediaType).body(body);
  }

  static <T> ResponseEntity<T> render(T body, boolean permanent) {
    return render(body, MediaType.APPLICATION_JSON, permanent);
  }

  static <T> ResponseEntity<T> render(T body, MediaType mediaType, boolean permanent) {
    return ResponseEntity.ok().cacheControl(cache(permanent)).contentType(mediaType).body(body);
  }

  private static CacheControl cache(boolean permanent) {
    return permanent
        ? CacheControl.maxAge(Duration.ofSeconds(IMMUTABLE_MAX_AGE_SECONDS))
            .cachePublic()
            .immutable()
        : CacheControl.maxAge(Duration.ofSeconds(CURRENT_MAX_AGE_SECONDS)).cachePublic();
  }

  private static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required", e);
    }
  }
}
