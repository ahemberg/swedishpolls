package se.swedishpolls.publication;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shipped freeze, adjusted for a test. Parsing stays package-private to the publication
 * package, so this factory lives beside it rather than opening the production seam up.
 */
public final class TestFreeze {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private TestFreeze() {}

  /** The shipped freeze, released, at a draw count a test can run in seconds. */
  public static ModelFreeze released(int draws) {
    return ModelFreeze.parse(adjusted(draws, "released", true));
  }

  /** The shipped freeze as it actually stands: a blocked release publishes nothing. */
  public static ModelFreeze blocked(int draws) {
    return ModelFreeze.parse(adjusted(draws, "blocked", false));
  }

  private static JsonNode adjusted(int draws, String status, boolean clearGates) {
    try (final InputStream input = ModelFreeze.class.getResourceAsStream(ModelFreeze.RESOURCE)) {
      final ObjectNode root = (ObjectNode) JSON.readTree(input.readAllBytes());
      root.put("draws", draws);
      final ObjectNode release = (ObjectNode) root.get("release");
      release.put("status", status);
      if (clearGates) {
        release.putArray("failedBlockingGates");
      }
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
