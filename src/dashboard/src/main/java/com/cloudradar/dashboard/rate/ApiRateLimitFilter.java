package com.cloudradar.dashboard.rate;

import com.cloudradar.dashboard.config.DashboardProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Servlet filter applying in-memory rate limiting to dashboard API routes.
 *
 * <p>The filter only targets paths under {@code /api/} and returns HTTP 429 when limits are
 * exceeded.
 */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
  private final DashboardProperties properties;
  private final InMemoryRateLimiter limiter;

  /**
   * Creates the filter with configuration and limiter dependencies.
   *
   * @param properties typed dashboard properties
   * @param limiter sliding-window limiter implementation
   */
  public ApiRateLimitFilter(DashboardProperties properties, InMemoryRateLimiter limiter) {
    this.properties = properties;
    this.limiter = limiter;
  }

  /**
   * Applies rate limiting only to API routes.
   *
   * @param request current HTTP request
   * @return {@code true} when filtering should be skipped
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path == null || !path.startsWith("/api/");
  }

  /**
   * Enforces per-client request limits using a sliding window strategy.
   *
   * @param request current HTTP request
   * @param response current HTTP response
   * @param filterChain downstream filter chain
   * @throws ServletException if servlet processing fails
   * @throws IOException if response writing fails
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    String client = extractClientKey(request);
    boolean allowed = limiter.allow(
        client,
        properties.getApi().getRateLimit().getWindowSeconds(),
        properties.getApi().getRateLimit().getMaxRequests());

    if (!allowed) {
      response.setStatus(429);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
      return;
    }

    filterChain.doFilter(request, response);
  }

  private static String extractClientKey(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
  }
}
