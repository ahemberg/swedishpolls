package se.swedishpolls;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fixture a publication test publishes from: the pinned audit snapshot cut to a short window,
 * and the frozen model reduced to a draw count a test can afford. Nothing else about the freeze
 * changes, so the pipeline under test is the production one.
 */
final class TestPublication {
  /** The path the WireMock source is stubbed on. */
  static final String SOURCE_PATH = "/polls.csv";

  /** The first fieldwork date the trimmed fixture keeps. */
  static final String FROM = "2019-01-01";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private TestPublication() {}

  /** The audit snapshot, keeping only rows whose fieldwork starts on or after {@link #FROM}. */
  static byte[] polls(String from) {
    try (final InputStream input = TestPublication.class.getResourceAsStream("/polls/audit.csv")) {
      final List<String> lines =
          List.of(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
      final List<String> kept = new ArrayList<>();
      kept.add(lines.getFirst());
      final int column = List.of(lines.getFirst().split(",", -1)).indexOf("collectPeriodFrom");
      for (final String line : lines.subList(1, lines.size())) {
        if (line.isBlank()) {
          continue;
        }
        final String[] values = line.split(",", -1);
        if (values.length > column && values[column].compareTo(from) >= 0) {
          kept.add(line);
        }
      }
      return String.join("\n", kept).concat("\n").getBytes(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Serves the fixture over HTTP, so a test exercises the production source client. */
  static StubMapping serve(WireMockServer wireMock, byte[] body) {
    return wireMock.stubFor(
        get(urlEqualTo(SOURCE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("ETag", "\"" + PublicationStore.sha256(body) + "\"")
                    .withHeader("Last-Modified", "Mon, 07 Sep 2026 05:09:49 GMT")
                    .withBody(body)));
  }

  /** The shipped freeze, released, at a draw count a test can run in seconds. */
  static ModelFreeze released(int draws) {
    return ModelFreeze.parse(adjusted(draws, "released", true));
  }

  /** The shipped freeze as it actually stands: a blocked release publishes nothing. */
  static ModelFreeze blocked(int draws) {
    return ModelFreeze.parse(adjusted(draws, "blocked", false));
  }

  private static tools.jackson.databind.JsonNode adjusted(
      int draws, String status, boolean clearGates) {
    try (final InputStream input = ModelFreeze.class.getResourceAsStream(ModelFreeze.RESOURCE)) {
      final tools.jackson.databind.node.ObjectNode root =
          (tools.jackson.databind.node.ObjectNode) JSON.readTree(input.readAllBytes());
      root.put("draws", draws);
      final tools.jackson.databind.node.ObjectNode release =
          (tools.jackson.databind.node.ObjectNode) root.get("release");
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
