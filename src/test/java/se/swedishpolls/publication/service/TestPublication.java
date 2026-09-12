package se.swedishpolls.publication.service;

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
import se.swedishpolls.publication.Digest;
import se.swedishpolls.publication.TestFreeze;
import se.swedishpolls.publication.repository.PublicationLock;
import se.swedishpolls.publication.repository.PublicationStore;
import se.swedishpolls.source.service.ElectionReferenceService;
import se.swedishpolls.source.service.NationalAllocationRuleService;
import se.swedishpolls.source.service.PollQueryService;
import se.swedishpolls.source.service.SnapshotIngest;

/**
 * The fixture a publication test publishes from: the pinned audit snapshot cut to a short window,
 * and the frozen model reduced to a draw count a test can afford. Nothing else about the freeze
 * changes, so the pipeline under test is the production one.
 *
 * <p>The worker is built here rather than in each test, so the alternate constructor stays
 * package-private and a web test never reaches into the publication worker's own seams.
 */
public final class TestPublication {
  /** The path the WireMock source is stubbed on. */
  public static final String SOURCE_PATH = "/polls.csv";

  /** The first fieldwork date the trimmed fixture keeps. */
  public static final String FROM = "2019-01-01";

  private TestPublication() {}

  /** A worker at a released freeze and a draw count a test can run in seconds. */
  public static Publisher released(
      PublicationLock lock,
      SnapshotIngest ingest,
      PollQueryService queries,
      ElectionReferenceService electionReferences,
      NationalAllocationRuleService allocationRules,
      PublicationStore store,
      int draws) {
    return new Publisher(
        lock,
        ingest,
        queries,
        electionReferences,
        allocationRules,
        store,
        TestFreeze.released(draws));
  }

  /** A worker at the blocked release verdict the repository actually ships. */
  public static Publisher blocked(
      PublicationLock lock,
      SnapshotIngest ingest,
      PollQueryService queries,
      ElectionReferenceService electionReferences,
      NationalAllocationRuleService allocationRules,
      PublicationStore store,
      int draws) {
    return new Publisher(
        lock,
        ingest,
        queries,
        electionReferences,
        allocationRules,
        store,
        TestFreeze.blocked(draws));
  }

  /** The audit snapshot, keeping only rows whose fieldwork starts on or after {@link #FROM}. */
  public static byte[] polls(String from) {
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
  public static StubMapping serve(WireMockServer wireMock, byte[] body) {
    return wireMock.stubFor(
        get(urlEqualTo(SOURCE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("ETag", "\"" + Digest.sha256(body) + "\"")
                    .withHeader("Last-Modified", "Mon, 07 Sep 2026 05:09:49 GMT")
                    .withBody(body)));
  }
}
