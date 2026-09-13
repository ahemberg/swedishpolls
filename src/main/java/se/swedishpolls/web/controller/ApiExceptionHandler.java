package se.swedishpolls.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every rejected request into the frozen error body its code names. */
@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(ApiErrors.ApiException.class)
  public ResponseEntity<ApiErrorResponse> api(ApiErrors.ApiException error) {
    return body(error);
  }

  /**
   * An unknown path inside a served version is an unknown resource; an unknown version is its own
   * error, so a caller can tell a typo from a version this deployment does not speak.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiErrorResponse> missing(HttpServletRequest request) {
    final String path = request.getRequestURI();
    if (path.startsWith("/api/") && !path.startsWith("/api/v1/") && !path.equals("/api/v1")) {
      return body(ApiErrors.unknownVersion());
    }
    return body(ApiErrors.unknownRoute());
  }

  private static ResponseEntity<ApiErrorResponse> body(ApiErrors.ApiException error) {
    return ResponseEntity.status(error.status())
        .contentType(MediaType.APPLICATION_JSON)
        .body(error.body());
  }
}
