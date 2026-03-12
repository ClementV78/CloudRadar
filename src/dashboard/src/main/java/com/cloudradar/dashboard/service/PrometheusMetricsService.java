package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PrometheusMetricsService {
  private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsService.class);
  private static final String CREDITS_PER_REQUEST_24H_QUERY =
      "clamp_min(increase(ingester_opensky_credits_used_since_reset[24h]), 0)"
          + " / clamp_min(increase(ingester_opensky_requests_since_reset[24h]), 1)";

  private final DashboardProperties properties;
  private final HttpClient httpClient;
  private final PrometheusQueryResponseParser responseParser;

  @Autowired
  public PrometheusMetricsService(DashboardProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newHttpClient());
  }

  PrometheusMetricsService(
      DashboardProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
    this.properties = properties;
    this.httpClient = httpClient;
    this.responseParser = new PrometheusQueryResponseParser(objectMapper);
  }

  public Optional<Double> queryOpenSkyCreditsPerRequest24h() {
    DashboardProperties.Prometheus prometheus = properties.getPrometheus();
    if (!prometheus.isEnabled() || !StringUtils.hasText(prometheus.getBaseUrl())) {
      return Optional.empty();
    }

    try {
      URI uri =
          new PrometheusQueryRequestBuilder(prometheus.getBaseUrl())
              .buildUri(CREDITS_PER_REQUEST_24H_QUERY);
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofMillis(Math.max(200, prometheus.getQueryTimeoutMs())))
              .GET()
              .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        log.debug("Prometheus query failed with status {}", response.statusCode());
        return Optional.empty();
      }
      return responseParser.parse(response.body());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.debug("Prometheus query interrupted", ex);
      return Optional.empty();
    } catch (Exception ex) {
      log.debug("Unable to query Prometheus OpenSky credits/request 24h", ex);
      return Optional.empty();
    }
  }
}
