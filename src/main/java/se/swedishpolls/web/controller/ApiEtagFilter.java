package se.swedishpolls.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** Adds content ETags after Spring has rendered an API response. */
@Component
public final class ApiEtagFilter extends ShallowEtagHeaderFilter {
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    final String path = request.getRequestURI();
    return !path.equals("/api/v1") && !path.startsWith("/api/v1/");
  }
}
