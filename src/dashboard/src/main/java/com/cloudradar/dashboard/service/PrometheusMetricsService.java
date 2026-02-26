package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Lightweight Prometheus query helper used for derived dashboard KPIs.
 */
@Service
public class PrometheusMetricsService {
  private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsService.class);
  private static final String CREDITS_PER_REQUEST_24H_QUERY =
      "clamp_min(increase(ingester_opensky_credits_used_since_reset[24h]), 0)"
          + " / clamp_min(increase(ingester_opensky_requests_since_reset[24h]), 1)";

  private final DashboardProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public PrometheusMetricsService(DashboardProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newHttpClient());
  }

  PrometheusMetricsService(
      DashboardProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  /**
   * Returns OpenSky credits/request over the last 24h from Prometheus.
   *
   * @return parsed KPI value when available, empty otherwise
   */
  public Optional<Double> queryOpenSkyCreditsPerRequest24h() {
    DashboardProperties.Prometheus prometheus = properties.getPrometheus();
    if (!prometheus.isEnabled() || !StringUtils.hasText(prometheus.getBaseUrl())) {
      return Optional.empty();
    }

    try {
      String baseUrl = trimTrailingSlashes(prometheus.getBaseUrl());
      String query = URLEncoder.encode(CREDITS_PER_REQUEST_24H_QUERY, StandardCharsets.UTF_8);
      URI uri = URI.create(baseUrl + "/api/v1/query?query=" + query);

      HttpRequest request = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofMillis(Math.max(200, prometheus.getQueryTimeoutMs())))
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        log.debug("Prometheus query failed with status {}", response.statusCode());
        return Optional.empty();
      }

      JsonNode root = objectMapper.readTree(response.body());
      if (!"success".equals(root.path("status").asText())) {
        return Optional.empty();
      }

      JsonNode result = root.path("data").path("result");
      if (!result.isArray() || result.isEmpty()) {
        return Optional.empty();
      }

      JsonNode valueNode = result.get(0).path("value");
      if (!valueNode.isArray() || valueNode.size() < 2) {
        return Optional.empty();
      }

      String raw = valueNode.get(1).asText();
      if (!StringUtils.hasText(raw)) {
        return Optional.empty();
      }
      return Optional.of(Double.parseDouble(raw));
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.debug("Prometheus query interrupted", ex);
      return Optional.empty();
    } catch (Exception ex) {
      log.debug("Unable to query Prometheus OpenSky credits/request 24h", ex);
      return Optional.empty();
    }
  }

  private String trimTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }
}
