package com.cloudradar.processor.aircraft;

public record AircraftMetadata(
    String icao24,
    String country,
    String categoryDescription,
    String icaoAircraftClass,
    String manufacturerIcao,
    String manufacturerName,
    String model,
    String registration,
    String typecode) {

  public String categoryOrFallback() {
    if (categoryDescription != null && !categoryDescription.isBlank()) {
      return categoryDescription;
    }
    if (icaoAircraftClass != null && !icaoAircraftClass.isBlank()) {
      return icaoAircraftClass;
    }
    return "unknown";
  }
}

