package se.swedishpolls;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a publication's image bytes. Every link names its publication, card, language and asset
 * version, so it is immutable: a renderer change publishes a new version beside this one.
 */
@RestController
public class AssetController {
  private static final Pattern NAME =
      Pattern.compile("^(?<kind>[a-z]+)-(?<language>sv|en)-(?<version>[1-9][0-9]*)\\.png$");

  private final PublicationStore store;

  public AssetController(PublicationStore store) {
    this.store = store;
  }

  @GetMapping("/assets/{publicationId}/{file}")
  public ResponseEntity<byte[]> asset(
      @PathVariable String publicationId,
      @PathVariable String file,
      @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    final Matcher name = NAME.matcher(file);
    if (!name.matches()) {
      throw ApiErrors.unknownRoute();
    }
    final Optional<PublicationStore.Asset> asset =
        store.asset(
            publicationId,
            name.group("kind"),
            name.group("language"),
            Integer.parseInt(name.group("version")));
    if (asset.isEmpty()) {
      throw ApiErrors.unknownRoute();
    }
    return ApiV1Controller.respond(
        store.bytes(asset.get()), MediaType.IMAGE_PNG, true, ifNoneMatch);
  }
}
