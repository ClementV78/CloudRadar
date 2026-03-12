package com.cloudradar.dashboard.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class PrometheusQueryRequestBuilder {
  private final String baseUrl;

  PrometheusQueryRequestBuilder(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  URI buildUri(String promQlQuery) {
    String query = URLEncoder.encode(promQlQuery, StandardCharsets.UTF_8);
    return URI.create(trimTrailingSlashes(baseUrl) + "/api/v1/query?query=" + query);
  }

  static String trimTrailingSlashes(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }
}
