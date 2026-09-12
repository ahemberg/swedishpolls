package se.swedishpolls.web.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    final ObjectNode body = JSON.createObjectNode().put("publicationId", HEADER.publicationId());
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
        .andExpect(jsonPath("$.invalid[1].allowed", hasSize(3)));

    mvc.perform(get("/api/v1/estimates/latest?language=de"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(ApiErrors.INVALID_FILTER));
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
}
