package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;

final class QueryBboxParser {
  private QueryBboxParser() {}

  static Bbox parseBboxOrDefault(String raw, DashboardProperties properties) {
    String candidate = raw == null || raw.isBlank() ? properties.getApi().getBbox().getDefaultValue() : raw;
    Bbox bbox = parseBbox(candidate);
    validateBboxBoundaries(bbox, properties);
    return bbox;
  }

  static Bbox parseBbox(String raw) {
    String[] chunks = raw.split(",");
    if (chunks.length != 4) {
      throw new BadRequestException("bbox must be minLon,minLat,maxLon,maxLat");
    }

    double minLon = parseCoordinate(chunks[0]);
    double minLat = parseCoordinate(chunks[1]);
    double maxLon = parseCoordinate(chunks[2]);
    double maxLat = parseCoordinate(chunks[3]);

    validateLongitudeRange(minLon, maxLon);
    validateLatitudeRange(minLat, maxLat);
    validateBoundsOrder(minLon, minLat, maxLon, maxLat);
    return new Bbox(minLon, minLat, maxLon, maxLat);
  }

  static void validateBboxBoundaries(Bbox bbox, DashboardProperties properties) {
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

  private static double parseCoordinate(String raw) {
    try {
      return Double.parseDouble(raw.trim());
    } catch (NumberFormatException ex) {
      throw new BadRequestException("bbox values must be numeric");
    }
  }

  private static void validateLongitudeRange(double minLon, double maxLon) {
    if (minLon < -180 || maxLon > 180) {
      throw new BadRequestException("longitude must be within [-180,180]");
    }
  }

  private static void validateLatitudeRange(double minLat, double maxLat) {
    if (minLat < -90 || maxLat > 90) {
      throw new BadRequestException("latitude must be within [-90,90]");
    }
  }

  private static void validateBoundsOrder(
      double minLon, double minLat, double maxLon, double maxLat) {
    if (minLon >= maxLon || minLat >= maxLat) {
      throw new BadRequestException("bbox must satisfy minLon < maxLon and minLat < maxLat");
    }
  }
}
