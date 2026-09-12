package se.swedishpolls.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The compiled frontend entry, read once from the Vite manifest the Maven build bundles.
 *
 * <p>A build without the manifest serves no script and no stylesheet. That is the page every
 * shareable route has to work as anyway: the basic HTML carries the headline, the date and the
 * results table on its own.
 */
@Component
public final class SiteAssets {
  private static final String MANIFEST = "static/.vite/manifest.json";
  private static final String ENTRY = "src/main.tsx";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String script;
  private final List<String> stylesheets;

  public SiteAssets() {
    final ClassPathResource manifest = new ClassPathResource(MANIFEST);
    if (!manifest.exists()) {
      this.script = null;
      this.stylesheets = List.of();
      return;
    }
    final JsonNode entry = read(manifest).get(ENTRY);
    if (entry == null) {
      throw new IllegalStateException(MANIFEST + " has no entry for " + ENTRY);
    }
    this.script = "/" + entry.get("file").asString();
    final List<String> css = new ArrayList<>();
    final JsonNode declared = entry.get("css");
    if (declared != null) {
      for (final JsonNode file : declared) {
        css.add("/" + file.asString());
      }
    }
    this.stylesheets = List.copyOf(css);
  }

  /** The hashed module entry, or null when this build bundles no compiled frontend. */
  public String script() {
    return script;
  }

  /** The hashed stylesheets the entry pulls in, in manifest order. */
  public List<String> stylesheets() {
    return stylesheets;
  }

  private static JsonNode read(ClassPathResource manifest) {
    try (final InputStream input = manifest.getInputStream()) {
      return JSON.readTree(input);
    } catch (IOException e) {
      throw new UncheckedIOException("Unreadable " + MANIFEST, e);
    }
  }
}
