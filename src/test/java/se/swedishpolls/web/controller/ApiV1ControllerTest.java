package se.swedishpolls.web.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import se.swedishpolls.publication.PublicationHeader;
import se.swedishpolls.publication.service.Publications;
import se.swedishpolls.source.PollCsv;
import se.swedishpolls.source.PollQuery;
import se.swedishpolls.source.service.PollQueryService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@WebMvcTest(ApiV1Controller.class)
class ApiV1ControllerTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final PublicationHeader HEADER =
      new PublicationHeader(
          "pub_20260912T120000Z",
          "run-1",
          42,
          LocalDate.of(2026, 9, 10),
          Instant.parse("2026-09-11T12:00:00Z"),
          Instant.parse("2026-09-12T12:00:00Z"),
          "corrected",
          "eight_party_2010",
          2026,
          "published");

  @Autowired private MockMvc mvc;
  @MockitoBean private Publications publications;
  @MockitoBean private PollQueryService queries;

  @BeforeEach
  void noPublication() {
    when(publications.resolve(org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenReturn(Optional.empty());
    when(publications.lastSuccessfulCheck()).thenReturn(Optional.empty());
  }

  @Test
  void everySurfaceThatNeedsAPublicationReturnsExplicitUnavailability() throws Exception {
    for (final String path :
        List.of(
            "/api/v1/publication",
            "/api/v1/estimates/latest",
            "/api/v1/estimates/history",
            "/api/v1/polls",
            "/api/v1/polls.csv",
            "/api/v1/institutes",
            "/api/v1/elections",
            "/api/v1/seats",
            "/api/v1/coalitions")) {
      mvc.perform(get(path))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value(ApiErrors.ESTIMATES_UNAVAILABLE))
          .andExpect(jsonPath("$.components").doesNotExist())
          .andExpect(jsonPath("$.parties").doesNotExist())
          .andExpect(jsonPath("$.sourceCheckedAt").hasJsonPath());
    }
  }

  @Test
  void aPermanentLinkStillFailsAsUnknownRatherThanAsUnavailable() throws Exception {
    mvc.perform(get("/api/v1/publications/pub_00000000T000000Z"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ApiErrors.UNKNOWN_PUBLICATION));
  }

  @Test
  void currentAndPermanentResponsesCarryTheirCachePolicyAndContentEtag() throws Exception {
    final ObjectNode body =
        (ObjectNode)
            JSON.readTree(
                "{\"publicationId\":\"pub_20260912T120000Z\","
                    + "\"publishedAt\":\"2026-09-12T12:00:00Z\","
                    + "\"sourceCheckedAt\":\"2026-09-11T12:00:00Z\","
                    + "\"lastFieldworkDate\":\"2026-09-10\",\"stale\":false,"
                    + "\"staleSince\":null,"
                    + "\"permalink\":\"/api/v1/publications/pub_20260912T120000Z\","
                    + "\"modelRun\":{\"runId\":\"run-1\",\"codeVersion\":\"abc123\","
                    + "\"estimatorVersion\":\"1\",\"seed\":7,\"runtime\":\"java\","
                    + "\"numericalLibrary\":\"commons-math\"},"
                    + "\"snapshot\":{\"snapshotId\":42,\"sha256\":\"digest\","
                    + "\"sourceUrl\":\"https://example.test/polls.csv\","
                    + "\"capturedAt\":\"2026-09-11T11:00:00Z\"},"
                    + "\"assets\":{\"note\":\"note\"},\"history\":\"published\"}");
    when(publications.resolve(isNull()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, false)));
    when(publications.resolve(HEADER.publicationId()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, true)));
    when(publications.metadata(
            org.mockito.ArgumentMatchers.eq(HEADER), org.mockito.ArgumentMatchers.any()))
        .thenReturn(body);

    final MvcResult current =
        mvc.perform(get("/api/v1/publication"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andExpect(content().string(body.toString()))
            .andReturn();
    final String etag = current.getResponse().getHeader(HttpHeaders.ETAG);
    mvc.perform(get("/api/v1/publication").header(HttpHeaders.IF_NONE_MATCH, etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string(HttpHeaders.ETAG, etag));

    mvc.perform(get("/api/v1/publications/{publicationId}", HEADER.publicationId()))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"));
  }

  @Test
  void unfilteredFrozenPublicationDocumentsAreServedByteForByte() throws Exception {
    final String stored = "{\n  \"publication\": {\"publicationId\": \"pub_1\"}\n}\n";
    when(publications.resolve(isNull()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, false)));
    when(publications.latest(HEADER, HEADER.headlinePeriod(), "sv"))
        .thenReturn(Optional.of(stored));
    when(publications.elections(HEADER, HEADER.headlinePeriod(), "sv"))
        .thenReturn(Optional.of(stored));
    when(publications.seats(HEADER, HEADER.approximatedElection(), "sv"))
        .thenReturn(Optional.of(stored));
    when(publications.coalitions(HEADER, HEADER.approximatedElection(), "sv"))
        .thenReturn(Optional.of(stored));

    for (final String path :
        List.of(
            "/api/v1/estimates/latest",
            "/api/v1/elections",
            "/api/v1/seats",
            "/api/v1/coalitions")) {
      final MvcResult result =
          mvc.perform(get(path))
              .andExpect(status().isOk())
              .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
              .andExpect(header().exists(HttpHeaders.ETAG))
              .andReturn();
      assertEquals(
          sha256(stored.getBytes(StandardCharsets.UTF_8)),
          sha256(result.getResponse().getContentAsByteArray()),
          path);
    }
  }

  @Test
  void invalidFiltersReportEveryRejectedValue() throws Exception {
    when(publications.resolve(isNull()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, false)));
    when(publications.history(HEADER, "sv"))
        .thenReturn(Optional.of("{\"publication\":{},\"dates\":[],\"series\":[]}"));

    mvc.perform(get("/api/v1/estimates/history?from=2026-13-01&step=5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ApiErrors.INVALID_FILTER))
        .andExpect(jsonPath("$.invalid", hasSize(2)))
        .andExpect(jsonPath("$.invalid[0].reason").value("not_a_date"))
        .andExpect(jsonPath("$.invalid[1].reason").value("unsupported_step"))
        .andExpect(jsonPath("$.invalid[1].allowed", hasSize(3)))
        .andExpect(
            content()
                .string(
                    "{\"code\":\"invalid_filter\",\"message\":\"Invalid query parameters.\","
                        + "\"invalid\":[{\"name\":\"from\",\"reason\":\"not_a_date\"},"
                        + "{\"name\":\"step\",\"reason\":\"unsupported_step\","
                        + "\"allowed\":[1,3,7]}]}"));

    mvc.perform(get("/api/v1/estimates/latest?language=de"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ApiErrors.INVALID_FILTER));
  }

  @Test
  void assembledDocumentResponsesKeepTheirJsonShapeAndHeaders() throws Exception {
    final String identity =
        "{\"publicationId\":\"pub_20260912T120000Z\",\"runId\":\"run-1\",\"snapshotId\":42}";
    when(publications.resolve(isNull()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, false)));
    when(publications.history(HEADER, "sv"))
        .thenReturn(
            Optional.of(
                "{\"publication\":"
                    + identity
                    + ",\"intervalLevel\":0.95,"
                    + "\"range\":{\"from\":\"2026-09-10\",\"to\":\"2026-09-10\","
                    + "\"step\":1,\"inclusive\":true,\"requestedTo\":\"2026-09-10\"},"
                    + "\"dates\":[\"2026-09-10\"],"
                    + "\"coveragePeriodByDate\":[\"eight_party_2010\"],\"series\":[],"
                    + "\"boundaries\":[]}"));
    when(publications.institutes(HEADER, "sv"))
        .thenReturn(
            Optional.of(
                "{\"publication\":"
                    + identity
                    + ",\"reference\":\"reference\",\"electionCycles\":[\"2022-2026\"],"
                    + "\"institutes\":[]}"));
    when(publications.coalitions(HEADER, HEADER.approximatedElection(), "sv"))
        .thenReturn(
            Optional.of(
                "{\"publication\":"
                    + identity
                    + ",\"lastFieldworkDate\":\"2026-09-10\",\"majoritySeats\":175,"
                    + "\"intervalLevel\":0.95,\"electionYear\":2026,\"overviewDefaults\":[],"
                    + "\"note\":\"note\",\"coalitions\":[],\"comparison\":{\"pairs\":[],"
                    + "\"tie\":\"tie\"}}"));

    mvc.perform(get("/api/v1/estimates/history"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
        .andExpect(header().exists(HttpHeaders.ETAG))
        .andExpect(
            content()
                .string(
                    "{\"publication\":"
                        + identity
                        + ",\"intervalLevel\":0.95,\"range\":{\"from\":\"2026-09-10\","
                        + "\"to\":\"2026-09-10\",\"step\":1,\"inclusive\":true,"
                        + "\"requestedTo\":\"2026-09-10\"},\"dates\":[\"2026-09-10\"],"
                        + "\"coveragePeriodByDate\":[\"eight_party_2010\"],\"series\":[],"
                        + "\"boundaries\":[],\"change30d\":{\"comparisonDate\":\"2026-08-11\","
                        + "\"available\":false,\"change\":null,"
                        + "\"reason\":\"comparison_date_outside_supported_history\"}}"));
    mvc.perform(get("/api/v1/institutes"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
        .andExpect(header().exists(HttpHeaders.ETAG))
        .andExpect(
            content()
                .string(
                    "{\"publication\":"
                        + identity
                        + ",\"reference\":\"reference\","
                        + "\"electionCycles\":[\"2022-2026\"],\"institutes\":[],"
                        + "\"electionCycle\":\"2022-2026\"}"));
    mvc.perform(get("/api/v1/coalitions?coalition=tido"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
        .andExpect(header().exists(HttpHeaders.ETAG))
        .andExpect(
            content()
                .string(
                    "{\"publication\":"
                        + identity
                        + ",\"lastFieldworkDate\":\"2026-09-10\",\"majoritySeats\":175,"
                        + "\"intervalLevel\":0.95,\"electionYear\":2026,\"overviewDefaults\":[],"
                        + "\"note\":\"note\",\"coalitions\":[],"
                        + "\"comparison\":{\"pairs\":[],\"tie\":\"tie\"}}"));

    mvc.perform(get("/api/v1/institutes?electionCycle=unknown"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.invalid[0].name").value("electionCycle"));
    mvc.perform(get("/api/v1/coalitions?coalition=unknown"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.invalid[0].name").value("coalition"));
  }

  @Test
  void filteredPollsAreRenderedAsAJsonResponse() throws Exception {
    final PollQuery.Filters filters =
        new PollQuery.Filters(null, null, List.of(), List.of("S", "M"), null, false);
    final Map<String, BigDecimal> shares = new LinkedHashMap<>();
    shares.put("S", new BigDecimal("31.20"));
    shares.put("M", null);
    final PollCsv.Poll poll =
        new PollCsv.Poll(
            1,
            Map.of(),
            "Company",
            "Institute",
            "method-1",
            "evidence",
            "phone",
            null,
            LocalDate.of(2026, 9, 11),
            LocalDate.of(2026, 9, 8),
            null,
            new BigDecimal("1234.0"),
            shares,
            new BigDecimal("1.25"),
            List.of("excluded"));
    final PollQuery.Row row =
        new PollQuery.Row("42:1", poll, "eight_party_2010", true, new BigDecimal("2.30"));
    when(publications.resolve(isNull()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, false)));
    when(publications.latest(HEADER, HEADER.headlinePeriod(), "sv"))
        .thenReturn(
            Optional.of(
                "{\"coveragePeriods\":[{\"id\":\"eight_party_2010\","
                    + "\"from\":\"2010-01-01\",\"to\":null,\"roster\":[\"S\"],"
                    + "\"individualFi\":false,\"supportValidated\":true,"
                    + "\"decision\":\"https://example.test/decision\"}]}"));
    when(queries.query(eq(42L), anyList(), eq(filters), eq(1), eq(50)))
        .thenReturn(new PollQuery.Result(List.of(row), List.of(row), 1, 1, 50));

    mvc.perform(get("/api/v1/polls?party=S,M"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
        .andExpect(header().exists(HttpHeaders.ETAG))
        .andExpect(jsonPath("$.publication.publicationId").value(HEADER.publicationId()))
        .andExpect(jsonPath("$.filters.party", hasSize(2)))
        .andExpect(jsonPath("$.polls", hasSize(1)))
        .andExpect(
            content()
                .string(
                    "{\"publication\":{\"publicationId\":\"pub_20260912T120000Z\","
                        + "\"runId\":\"run-1\",\"snapshotId\":42},"
                        + "\"filters\":{\"from\":null,\"to\":null,\"institute\":[],"
                        + "\"party\":[\"S\",\"M\"],\"includeExcluded\":false,"
                        + "\"coveragePeriod\":null},\"coveragePeriod\":\"eight_party_2010\","
                        + "\"total\":1,\"page\":1,\"pageSize\":50,"
                        + "\"csv\":\"/api/v1/polls.csv?publication=pub_20260912T120000Z"
                        + "&party=S,M\","
                        + "\"labels\":{\"S\":\"Socialdemokraterna\","
                        + "\"M\":\"Moderaterna\"},\"polls\":[{\"pollId\":\"42:1\","
                        + "\"institute\":\"Institute\",\"company\":\"Company\","
                        + "\"methodEra\":\"method-1\",\"methodEvidence\":\"evidence\","
                        + "\"surveyType\":\"phone\",\"publicationDate\":\"2026-09-11\","
                        + "\"collectionFrom\":\"2026-09-08\",\"collectionTo\":null,"
                        + "\"approximatePeriod\":true,\"sampleSize\":1234.0,"
                        + "\"denominatorNote\":null,\"shares\":{\"S\":31.20,\"M\":null},"
                        + "\"other\":1.25,\"uncertain\":2.30,"
                        + "\"coveragePeriod\":\"eight_party_2010\",\"eligible\":false,"
                        + "\"exclusionReasons\":[\"excluded\"]}]}"));
  }

  @Test
  void unknownVersionsAndRoutesUseTheirOwnCodes() throws Exception {
    mvc.perform(get("/api/v2/estimates/latest"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ApiErrors.UNKNOWN_VERSION));
    mvc.perform(get("/api/v1/estimates/trend"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ApiErrors.UNKNOWN_ROUTE));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
