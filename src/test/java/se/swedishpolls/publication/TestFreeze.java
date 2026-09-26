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

  /**
   * Isolated host benchmark: only the release verdict changes, every numerical rule stays frozen.
   */
  public static ModelFreeze benchmark() {
    try (final InputStream input = ModelFreeze.class.getResourceAsStream(ModelFreeze.RESOURCE)) {
      final ObjectNode root = (ObjectNode) JSON.readTree(input.readAllBytes());
      final ObjectNode release = (ObjectNode) root.get("release");
      release.put("status", "released");
      release.putArray("failedBlockingGates");
      root.put("authorizedLevel", "calibrated");
      return ModelFreeze.parse(root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static JsonNode adjusted(int draws, String status, boolean clearGates) {
    try (final InputStream input = ModelFreeze.class.getResourceAsStream(ModelFreeze.RESOURCE)) {
      final ObjectNode root = (ObjectNode) JSON.readTree(input.readAllBytes());
      root.put("draws", draws);
      // Pipeline fixtures use few draws and do not claim production Monte Carlo precision.
      // The real error rule is checked separately by CoalitionPrecisionTest and its registered
      // study.
      final ObjectNode precision = (ObjectNode) root.get("coalitionHistoryPrecision");
      precision.put("draws", draws);
      precision.put("referenceDraws", draws * 4);
      precision.put("maxMeanErrorPoints", 100);
      precision.put("maxEndpointErrorPoints", 100);
      final ObjectNode release = (ObjectNode) root.get("release");
      release.put("status", status);
      root.put("authorizedLevel", clearGates ? "calibrated" : "none");
      if (clearGates) {
        release.putArray("failedBlockingGates");
      }
      return root;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
