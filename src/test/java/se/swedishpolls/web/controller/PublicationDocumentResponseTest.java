package se.swedishpolls.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PublicationDocumentResponseTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void latestExampleRoundTrips() throws Exception {
    roundTrip("estimates-latest.json", LatestResponse.class);
  }

  @Test
  void electionsExampleRoundTrips() throws Exception {
    roundTrip("elections.json", ElectionsResponse.class);
  }

  @Test
  void seatsExampleRoundTrips() throws Exception {
    roundTrip("seats.json", SeatsResponse.class);
  }

  @Test
  void missingRequiredFieldFails() throws Exception {
    final ObjectNode incomplete = (ObjectNode) example("estimates-latest.json");
    incomplete.remove("lastFieldworkDate");

    assertThrows(
        JacksonException.class, () -> JSON.readerFor(LatestResponse.class).readValue(incomplete));
  }

  private static void roundTrip(String name, Class<?> type) throws Exception {
    final JsonNode expected = example(name);
    final Object response = JSON.readerFor(type).readValue(expected);
    assertEquals(expected, JSON.readTree(JSON.writeValueAsString(response)));
  }

  private static JsonNode example(String name) throws Exception {
    try (final InputStream input =
        PublicationDocumentResponseTest.class.getResourceAsStream("/api/v1/examples/" + name)) {
      assertNotNull(input);
      return JSON.readTree(input);
    }
  }
}
