package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing and validating dashboard query parameters.
 */
public final class QueryParser {
  private static final Set<String> SORTS = Set.of("lastSeen", "speed", "altitude");
  private static final Set<String> ORDERS = Set.of("asc", "desc");
  private static final Pattern WINDOW_PATTERN = Pattern.compile("^(\\d+)([mhd])$");

  private QueryParser() {}

  /**
   * Parses a bbox query parameter or falls back to configured defaults.
   *
   * @param raw raw bbox query value
   * @param properties typed dashboard properties
   * @return validated bbox
   */
  public static Bbox parseBboxOrDefault(String raw, DashboardProperties properties) {
    Bbox bbox;
    if (raw == null || raw.isBlank()) {
      bbox = parseBbox(properties.getApi().getBbox().getDefaultValue());
    } else {
      bbox = parseBbox(raw);
    }

    validateBboxBoundaries(bbox, properties);
    return bbox;
  }

  /**
   * Parses a bbox expression formatted as {@code minLon,minLat,maxLon,maxLat}.
   *
   * @param raw raw bbox value
   * @return parsed bbox
   */
  public static Bbox parseBbox(String raw) {
    String[] chunks = raw.split(",");
    if (chunks.length != 4) {
      throw new BadRequestException("bbox must be minLon,minLat,maxLon,maxLat");
    }

    try {
      double minLon = Double.parseDouble(chunks[0].trim());
      double minLat = Double.parseDouble(chunks[1].trim());
      double maxLon = Double.parseDouble(chunks[2].trim());
      double maxLat = Double.parseDouble(chunks[3].trim());

      if (minLon < -180 || maxLon > 180) {
        throw new BadRequestException("longitude must be within [-180,180]");
      }
      if (minLat < -90 || maxLat > 90) {
        throw new BadRequestException("latitude must be within [-90,90]");
      }
      if (minLon >= maxLon || minLat >= maxLat) {
        throw new BadRequestException("bbox must satisfy minLon < maxLon and minLat < maxLat");
      }
      return new Bbox(minLon, minLat, maxLon, maxLat);
    } catch (NumberFormatException ex) {
      throw new BadRequestException("bbox values must be numeric");
    }
  }

  /**
   * Validates a bbox against configured allowed bounds and max area.
   *
   * @param bbox parsed bbox
   * @param properties typed dashboard properties
   */
  public static void validateBboxBoundaries(Bbox bbox, DashboardProperties properties) {
    DashboardProperties.Bbox limits = properties.getApi().getBbox();
    if (bbox.minLat() < limits.getAllowedLatMin()
        || bbox.maxLat() > limits.getAllowedLatMax()
        || bbox.minLon() < limits.getAllowedLonMin()
        || bbox.maxLon() > limits.getAllowedLonMax()) {
      throw new BadRequestException("bbox is outside allowed boundaries");
    }

    if (bbox.areaDeg2() > limits.getMaxAreaDeg2()) {
      throw new BadRequestException("bbox area exceeds configured maximum");
    }
  }

  /**
   * Parses an optional time lower-bound from epoch or ISO-8601.
   *
   * @param raw raw query value
   * @return epoch seconds or {@code null} when absent
   */
  public static Long parseSince(String raw) {
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

  /**
   * Parses and clamps pagination limits.
   *
   * @param raw raw limit query value
   * @param defaultLimit default value when absent
   * @param maxLimit hard upper bound
   * @return effective limit
   */
  public static int parseLimit(String raw, int defaultLimit, int maxLimit) {
    if (raw == null || raw.isBlank()) {
      return defaultLimit;
    }
    try {
      int parsed = Integer.parseInt(raw);
      if (parsed <= 0) {
        throw new BadRequestException("limit must be > 0");
      }
      return Math.min(parsed, maxLimit);
    } catch (NumberFormatException ex) {
      throw new BadRequestException("limit must be an integer");
    }
  }

  /**
   * Parses the sort field used by flight listing.
   *
   * @param raw raw sort query value
   * @param defaultSort fallback sort field
   * @return validated sort field
   */
  public static String parseSort(String raw, String defaultSort) {
    if (raw == null || raw.isBlank()) {
      return defaultSort;
    }
    if (!SORTS.contains(raw)) {
      throw new BadRequestException("sort must be one of: lastSeen,speed,altitude");
    }
    return raw;
  }

  /**
   * Parses the sort order used by flight listing.
   *
   * @param raw raw order query value
   * @param defaultOrder fallback sort order
   * @return validated order value
   */
  public static String parseOrder(String raw, String defaultOrder) {
    if (raw == null || raw.isBlank()) {
      return defaultOrder;
    }
    String normalized = raw.toLowerCase(Locale.ROOT);
    if (!ORDERS.contains(normalized)) {
      throw new BadRequestException("order must be one of: asc,desc");
    }
    return normalized;
  }

  /**
   * Parses a duration window string (for example {@code 30m}, {@code 24h}, {@code 2d}).
   *
   * @param raw raw window query value
   * @param defaultValue fallback duration
   * @param maxValue hard maximum duration
   * @return validated window duration
   */
  public static Duration parseWindow(String raw, Duration defaultValue, Duration maxValue) {
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }

    Matcher matcher = WINDOW_PATTERN.matcher(raw.trim().toLowerCase(Locale.ROOT));
    if (!matcher.matches()) {
      throw new BadRequestException("window must use format like 30m,6h,24h,2d");
    }

    long amount = Long.parseLong(matcher.group(1));
    String unit = matcher.group(2);
    Duration parsed = switch (unit) {
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      case "d" -> Duration.ofDays(amount);
      default -> throw new BadRequestException("unsupported window unit");
    };

    if (parsed.isNegative() || parsed.isZero()) {
      throw new BadRequestException("window must be > 0");
    }
    if (parsed.compareTo(maxValue) > 0) {
      throw new BadRequestException("window exceeds maximum allowed duration");
    }
    return parsed;
  }

  /**
   * Computes a lower-bound epoch from a duration window relative to now.
   *
   * @param window duration window
   * @return cutoff epoch seconds
   */
  public static long cutoffEpoch(Duration window) {
    return Instant.now().minus(window).getEpochSecond();
  }
}
