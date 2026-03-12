package com.cloudradar.dashboard.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PlanespottersEndpointBuilder {
  private final String baseUrl;

  PlanespottersEndpointBuilder(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  String byHexPath(String icao24) {
    return "hex/" + urlEncode(icao24);
  }

  String byRegistrationPath(String registration) {
    return "reg/" + urlEncode(registration);
  }

  String buildUrl(String path) {
    return trimTrailingSlashes(baseUrl) + "/" + path;
  }

  String normalizeRegistration(String registration) {
    if (registration == null) {
      return null;
    }
    String trimmed = registration.trim();
    return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  static String trimTrailingSlashes(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
