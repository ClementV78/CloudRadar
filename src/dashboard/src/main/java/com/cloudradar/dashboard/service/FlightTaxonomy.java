package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.model.FlightMapItem;
import com.cloudradar.dashboard.model.PositionEvent;
import java.util.Objects;

final class FlightTaxonomy {

  boolean matchesMilitary(FlightSnapshot snapshot, String filter) {
    if (filter == null) {
      return true;
    }
    return switch (filter) {
      case "true" -> Boolean.TRUE.equals(snapshot.militaryHint());
      case "false" -> Boolean.FALSE.equals(snapshot.militaryHint());
      case "unknown" -> snapshot.militaryHint() == null;
      default -> true;
    };
  }

  boolean matchesAirframe(FlightSnapshot snapshot, String filter) {
    if (filter == null) {
      return true;
    }
    return switch (filter) {
      case "unknown" -> snapshot.airframeType() == null;
      default -> Objects.equals(snapshot.airframeType(), filter);
    };
  }

  boolean matchesString(String value, String filter, boolean lowercase) {
    if (filter == null) {
      return true;
    }
    if (value == null || value.isBlank()) {
      return false;
    }
    String normalized = FlightQueryValues.normalizeOptional(value, lowercase, !lowercase);
    return Objects.equals(normalized, filter);
  }

  FlightMapItem toMapItem(FlightSnapshot snapshot) {
    PositionEvent event = snapshot.event();
    return new FlightMapItem(
        snapshot.icao24(),
        FlightQueryValues.trimToNull(event.callsign()),
        event.lat(),
        event.lon(),
        event.heading(),
        event.lastContact(),
        event.velocity(),
        event.altitude(),
        snapshot.militaryHint(),
        snapshot.airframeType() == null ? "unknown" : snapshot.airframeType(),
        fleetType(snapshot),
        aircraftSize(snapshot),
        event.prevLat(),
        event.prevLon(),
        event.prevHeading(),
        event.prevVelocity(),
        event.prevAltitude(),
        event.prevLastContact());
  }

  String fleetType(FlightSnapshot snapshot) {
    if (Boolean.TRUE.equals(snapshot.militaryHint())) {
      return "military";
    }

    if (isRescueFlight(snapshot)) {
      return "rescue";
    }

    String owner = FlightQueryValues.normalizeOptional(snapshot.ownerOperator(), true, false);
    String category = FlightQueryValues.normalizeOptional(snapshot.category(), true, false);
    if ((owner != null && (owner.contains("private") || owner.contains("charter")))
        || (category != null
            && (category.contains("private")
                || category.contains("business")
                || category.contains("general")))) {
      return "private";
    }

    if (category == null) {
      return "unknown";
    }
    return "commercial";
  }

  String aircraftSize(FlightSnapshot snapshot) {
    String category = FlightQueryValues.normalizeOptional(snapshot.category(), true, false);
    String typecode = FlightQueryValues.normalizeOptional(snapshot.typecode(), false, true);

    String sizeFromCategory = sizeFromCategory(category);
    if (sizeFromCategory != null) {
      return sizeFromCategory;
    }
    String sizeFromTypecode = sizeFromTypecode(typecode);
    return sizeFromTypecode == null ? "unknown" : sizeFromTypecode;
  }

  String aircraftTypeLabel(FlightSnapshot snapshot) {
    if (snapshot.typecode() != null && !snapshot.typecode().isBlank()) {
      return snapshot.typecode();
    }
    if (snapshot.category() != null && !snapshot.category().isBlank()) {
      return snapshot.category();
    }
    return "unknown";
  }

  String inferAirframeType(String category, String typecode) {
    String normalizedCategory = FlightQueryValues.normalizeOptional(category, true, false);
    String normalizedTypecode = FlightQueryValues.normalizeOptional(typecode, false, true);

    if (isHelicopterCategory(normalizedCategory) || isHelicopterTypecode(normalizedTypecode)) {
      return "helicopter";
    }
    if (normalizedCategory != null || normalizedTypecode != null) {
      return "airplane";
    }
    return null;
  }

  private boolean isRescueFlight(FlightSnapshot snapshot) {
    String callsign = FlightQueryValues.normalizeOptional(snapshot.event().callsign(), true, false);
    String owner = FlightQueryValues.normalizeOptional(snapshot.ownerOperator(), true, false);
    String category = FlightQueryValues.normalizeOptional(snapshot.category(), true, false);

    if (containsAny(
        owner,
        "samu",
        "secours",
        "rescue",
        "hems",
        "medevac",
        "civil security",
        "civil protection")) {
      return true;
    }
    if (containsAny(category, "rescue", "secours", "hems", "medevac", "medical", "ambulance")) {
      return true;
    }
    return containsAny(callsign, "samu", "rescue", "dragon", "hems", "lifeguard", "secours");
  }

  private static String sizeFromCategory(String category) {
    if (category == null) {
      return null;
    }
    if (category.contains("heavy")) {
      return "heavy";
    }
    if (category.contains("large") || category.contains("wide")) {
      return "large";
    }
    if (category.contains("light") || category.contains("small") || category.contains("ultra")) {
      return "small";
    }
    return null;
  }

  private static String sizeFromTypecode(String typecode) {
    if (typecode == null) {
      return null;
    }
    if (startsWithAny(typecode, "B74", "B77", "A38")) {
      return "heavy";
    }
    if (startsWithAny(typecode, "A3", "B7", "B8")) {
      return "large";
    }
    if (startsWithAny(typecode, "C1", "P", "E", "H")) {
      return "small";
    }
    return "medium";
  }

  private static boolean isHelicopterCategory(String category) {
    return category != null
        && (category.contains("heli")
            || category.contains("rotor")
            || category.matches("^h\\d.*"));
  }

  private static boolean isHelicopterTypecode(String typecode) {
    return typecode != null
        && startsWithAny(typecode, "H", "EC", "AS", "SA", "AW", "BK", "MI", "KA", "UH", "CH");
  }

  private static boolean containsAny(String value, String... needles) {
    if (value == null || value.isBlank()) {
      return false;
    }
    for (String needle : needles) {
      if (value.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static boolean startsWithAny(String value, String... prefixes) {
    for (String prefix : prefixes) {
      if (value.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
