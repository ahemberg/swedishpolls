package se.swedishpolls.web.controller;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The frozen error vocabulary of the versioned API. */
public final class ApiErrors {
  private ApiErrors() {}

  public static final String UNKNOWN_VERSION = "unknown_version";
  public static final String UNKNOWN_ROUTE = "unknown_route";
  public static final String UNKNOWN_PUBLICATION = "unknown_publication";
  public static final String INVALID_FILTER = "invalid_filter";
  public static final String ESTIMATES_UNAVAILABLE = "estimates_unavailable";

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  /** One rejected query parameter and why it was rejected. */
  public record Invalid(String name, String reason, List<Integer> allowed) {
    public Invalid {
      allowed = List.copyOf(allowed);
    }

    public Invalid(String name, String reason) {
      this(name, reason, List.of());
    }

    @Override
    public List<Integer> allowed() {
      return List.copyOf(allowed);
    }
  }

  /** An error response carrying its own status and body. */
  public static final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String body;
    private final HttpStatus status;

    ApiException(HttpStatus status, ObjectNode body) {
      super(body.get("code").asString());
      this.status = status;
      this.body = body.toString();
    }

    public HttpStatus status() {
      return status;
    }

    /** The response body as JSON text, so the error carries nothing a reader has to rebuild. */
    public String body() {
      return body;
    }
  }

  public static ApiException unknownVersion() {
    final ObjectNode body = NODES.objectNode();
    body.put("code", UNKNOWN_VERSION);
    body.put("message", "Unknown API version.");
    body.putArray("supportedVersions").add("v1");
    return new ApiException(HttpStatus.NOT_FOUND, body);
  }

  public static ApiException unknownRoute() {
    final ObjectNode body = NODES.objectNode();
    body.put("code", UNKNOWN_ROUTE);
    body.put("message", "Unknown resource.");
    return new ApiException(HttpStatus.NOT_FOUND, body);
  }

  public static ApiException unknownPublication() {
    final ObjectNode body = NODES.objectNode();
    body.put("code", UNKNOWN_PUBLICATION);
    body.put(
        "message",
        "Unknown publication. A permanent link never falls back to the current publication.");
    return new ApiException(HttpStatus.NOT_FOUND, body);
  }

  public static ApiException invalidFilter(List<Invalid> invalid) {
    final ObjectNode body = NODES.objectNode();
    body.put("code", INVALID_FILTER);
    body.put("message", "Invalid query parameters.");
    final ArrayNode entries = body.putArray("invalid");
    for (final Invalid entry : invalid) {
      final ObjectNode node = entries.addObject();
      node.put("name", entry.name());
      node.put("reason", entry.reason());
      if (!entry.allowed().isEmpty()) {
        final ArrayNode allowed = node.putArray("allowed");
        entry.allowed().forEach(allowed::add);
      }
    }
    return new ApiException(HttpStatus.BAD_REQUEST, body);
  }

  /** No validated publication exists yet, so there is nothing to serve and no estimate fields. */
  public static ApiException estimatesUnavailable(Instant sourceCheckedAt) {
    final ObjectNode body = NODES.objectNode();
    body.put("code", ESTIMATES_UNAVAILABLE);
    body.put("message", "No validated publication exists yet.");
    if (sourceCheckedAt == null) {
      body.putNull("sourceCheckedAt");
    } else {
      body.put("sourceCheckedAt", sourceCheckedAt.toString());
    }
    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, body);
  }
}
