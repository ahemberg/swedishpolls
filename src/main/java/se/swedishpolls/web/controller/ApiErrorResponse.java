package se.swedishpolls.web.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Typed bodies for the frozen error vocabulary of the versioned API. */
sealed interface ApiErrorResponse extends java.io.Serializable {
  String code();

  String message();

  record Basic(String code, String message) implements ApiErrorResponse {}

  record Version(String code, String message, List<String> supportedVersions)
      implements ApiErrorResponse {
    public Version {
      supportedVersions = List.copyOf(supportedVersions);
    }

    @Override
    public List<String> supportedVersions() {
      return List.copyOf(supportedVersions);
    }
  }

  record Invalid(String code, String message, List<Entry> invalid) implements ApiErrorResponse {
    public Invalid {
      invalid = List.copyOf(invalid);
    }

    @Override
    public List<Entry> invalid() {
      return List.copyOf(invalid);
    }
  }

  record Unavailable(String code, String message, String sourceCheckedAt)
      implements ApiErrorResponse {}

  record Entry(
      String name,
      String reason,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Integer> allowed) {
    public Entry {
      allowed = List.copyOf(allowed);
    }

    @Override
    public List<Integer> allowed() {
      return List.copyOf(allowed);
    }
  }
}
