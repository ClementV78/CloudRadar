package com.cloudradar.dashboard.service;

import java.util.Locale;

final class FlightQueryValues {
  private FlightQueryValues() {}

  static String normalizeOptional(String raw, boolean lower, boolean upper) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    if (lower) {
      return trimmed.toLowerCase(Locale.ROOT);
    }
    if (upper) {
      return trimmed.toUpperCase(Locale.ROOT);
    }
    return trimmed;
  }

  static String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
