package se.swedishpolls.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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
import se.swedishpolls.web.SiteBootstrap;
import se.swedishpolls.web.SiteHtml;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@WebMvcTest(PageController.class)
class PageControllerTest {
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
  private static final ObjectNode PAGE = JsonMapper.builder().build().createObjectNode();

  @Autowired private MockMvc mvc;
  @MockitoBean private SiteBootstrap bootstrap;
  @MockitoBean private SiteHtml html;
  @MockitoBean private Publications publications;
  @MockitoBean private PollQueryService queries;

  @BeforeEach
  void renderPage() {
    when(html.page(any())).thenReturn("<html lang=\"sv\">page</html>");
    when(bootstrap.page(any(), any())).thenReturn(PAGE);
    when(bootstrap.unavailable(any(), any())).thenReturn(PAGE);
    when(bootstrap.noSource(any())).thenReturn(PAGE);
    when(publications.lastSuccessfulCheck()).thenReturn(Optional.empty());
    when(queries.activeSnapshot()).thenReturn(Optional.empty());
  }

  @Test
  void currentAndPermanentPagesCarryTheirCachePolicyAndContentEtag() throws Exception {
    when(publications.resolve(isNull()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, false)));
    when(publications.resolve(HEADER.publicationId()))
        .thenReturn(Optional.of(new Publications.Resolved(HEADER, true)));

    final MvcResult current =
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("text/html;charset=UTF-8"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
            .andExpect(header().exists(HttpHeaders.ETAG))
            .andReturn();
    final String etag = current.getResponse().getHeader(HttpHeaders.ETAG);
    mvc.perform(get("/").header(HttpHeaders.IF_NONE_MATCH, etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string(HttpHeaders.ETAG, etag));

    mvc.perform(get("/?publication={publicationId}", HEADER.publicationId()))
        .andExpect(status().isOk())
        .andExpect(
            header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"));
  }

  @Test
  void anUnknownPublicationIsRefusedRatherThanFallingBackToCurrent() throws Exception {
    when(publications.resolve("missing")).thenReturn(Optional.empty());

    mvc.perform(get("/?publication=missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ApiErrors.UNKNOWN_PUBLICATION));
  }

  @Test
  void anUnavailablePageStillReturnsBasicHtml() throws Exception {
    when(publications.resolve(isNull())).thenReturn(Optional.empty());

    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string("<html lang=\"sv\">page</html>"));
  }

  @Test
  void anUnknownPartySlugReturnsNotFound() throws Exception {
    mvc.perform(get("/parti/okant"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ApiErrors.UNKNOWN_ROUTE));
  }
}
