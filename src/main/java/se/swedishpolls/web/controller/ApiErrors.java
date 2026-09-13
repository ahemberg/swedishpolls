package se.swedishpolls.web.controller;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import se.swedishpolls.web.PollFilters;

/** The frozen error vocabulary of the versioned API. */
public final class ApiErrors {
  private ApiErrors() {}

  public static final String UNKNOWN_VERSION = "unknown_version";
  public static final String UNKNOWN_ROUTE = "unknown_route";
  public static final String UNKNOWN_PUBLICATION = "unknown_publication";
  public static final String INVALID_FILTER = "invalid_filter";
  public static final String ESTIMATES_UNAVAILABLE = "estimates_unavailable";

  /** An error response carrying its own status and body. */
  public static final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ApiErrorResponse body;
    private final HttpStatus status;

    ApiException(HttpStatus status, ApiErrorResponse body) {
      super(body.code());
      this.status = status;
      this.body = body;
    }

    public HttpStatus status() {
      return status;
    }

    /** The response body, already paired with the status that describes it. */
    public ApiErrorResponse body() {
      return body;
    }
  }

  public static ApiException unknownVersion() {
    return new ApiException(
        HttpStatus.NOT_FOUND,
        new ApiErrorResponse.Version(UNKNOWN_VERSION, "Unknown API version.", List.of("v1")));
  }

  public static ApiException unknownRoute() {
    return new ApiException(
        HttpStatus.NOT_FOUND, new ApiErrorResponse.Basic(UNKNOWN_ROUTE, "Unknown resource."));
  }

  public static ApiException unknownPublication() {
    return new ApiException(
        HttpStatus.NOT_FOUND,
        new ApiErrorResponse.Basic(
            UNKNOWN_PUBLICATION,
            "Unknown publication. A permanent link never falls back to the current publication."));
  }

  public static ApiException invalidFilter(List<PollFilters.Invalid> invalid) {
    final List<ApiErrorResponse.Entry> entries =
        invalid.stream()
            .map(entry -> new ApiErrorResponse.Entry(entry.name(), entry.reason(), entry.allowed()))
            .toList();
    return new ApiException(
        HttpStatus.BAD_REQUEST,
        new ApiErrorResponse.Invalid(INVALID_FILTER, "Invalid query parameters.", entries));
  }

  /** No validated publication exists yet, so there is nothing to serve and no estimate fields. */
  public static ApiException estimatesUnavailable(Instant sourceCheckedAt) {
    return new ApiException(
        HttpStatus.SERVICE_UNAVAILABLE,
        new ApiErrorResponse.Unavailable(
            ESTIMATES_UNAVAILABLE,
            "No validated publication exists yet.",
            sourceCheckedAt == null ? null : sourceCheckedAt.toString()));
  }
}
