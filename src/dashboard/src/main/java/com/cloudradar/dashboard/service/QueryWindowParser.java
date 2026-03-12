package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.api.BadRequestException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QueryWindowParser {
  private static final Pattern WINDOW_PATTERN = Pattern.compile("^(\\d+)([mhd])$");

  private QueryWindowParser() {}

  static Duration parseWindow(String raw, Duration defaultValue, Duration maxValue) {
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }

    Matcher matcher = parseMatcher(raw);
    Duration parsed = toDuration(Long.parseLong(matcher.group(1)), matcher.group(2));
    validateRange(parsed, maxValue);
    return parsed;
  }

  static long cutoffEpoch(Duration window) {
    return Instant.now().minus(window).getEpochSecond();
  }

  private static Matcher parseMatcher(String raw) {
    Matcher matcher = WINDOW_PATTERN.matcher(raw.trim().toLowerCase(Locale.ROOT));
    if (!matcher.matches()) {
      throw new BadRequestException("window must use format like 30m,6h,24h,2d");
    }
    return matcher;
  }

  private static Duration toDuration(long amount, String unit) {
    return switch (unit) {
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      case "d" -> Duration.ofDays(amount);
      default -> throw new BadRequestException("unsupported window unit");
    };
  }

  private static void validateRange(Duration parsed, Duration maxValue) {
    if (parsed.isNegative() || parsed.isZero()) {
      throw new BadRequestException("window must be > 0");
    }
    if (parsed.compareTo(maxValue) > 0) {
      throw new BadRequestException("window exceeds maximum allowed duration");
    }
  }
}
