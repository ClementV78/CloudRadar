package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.api.BadRequestException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

final class QueryCommonParser {
  private static final Set<String> SORTS = Set.of("lastSeen", "speed", "altitude");
  private static final Set<String> ORDERS = Set.of("asc", "desc");

  private QueryCommonParser() {}

  static Long parseSince(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }

    String value = raw.trim();
    if (value.chars().allMatch(Character::isDigit)) {
      long epoch = Long.parseLong(value);
      return epoch > 10_000_000_000L ? epoch / 1000 : epoch;
    }

    try {
      return OffsetDateTime.parse(value).toEpochSecond();
    } catch (DateTimeParseException ex) {
      throw new BadRequestException("since must be epoch seconds/ms or ISO8601");
    }
  }

  static int parseLimit(String raw, int defaultLimit, int maxLimit) {
    if (raw == null || raw.isBlank()) {
      return defaultLimit;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(raw);
    } catch (NumberFormatException ex) {
      throw new BadRequestException("limit must be an integer");
    }
    if (parsed <= 0) {
      throw new BadRequestException("limit must be > 0");
    }
    return Math.min(parsed, maxLimit);
  }

  static String parseSort(String raw, String defaultSort) {
    if (raw == null || raw.isBlank()) {
      return defaultSort;
    }
    if (!SORTS.contains(raw)) {
      throw new BadRequestException("sort must be one of: lastSeen,speed,altitude");
    }
    return raw;
  }

  static String parseOrder(String raw, String defaultOrder) {
    if (raw == null || raw.isBlank()) {
      return defaultOrder;
    }
    String normalized = raw.toLowerCase(Locale.ROOT);
    if (!ORDERS.contains(normalized)) {
      throw new BadRequestException("order must be one of: asc,desc");
    }
    return normalized;
  }
}
