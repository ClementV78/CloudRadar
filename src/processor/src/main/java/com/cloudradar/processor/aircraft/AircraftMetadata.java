package com.cloudradar.processor.aircraft;

/**
 * Immutable aircraft metadata record loaded from the local reference SQLite DB.
 *
 * <p>Fields may be partially populated depending on source coverage and merge strategy.
 */
public record AircraftMetadata(
    String icao24,
    String country,
    String categoryDescription,
    String icaoAircraftClass,
    String manufacturerIcao,
    String manufacturerName,
    String model,
    String registration,
    String typecode,
    Boolean militaryHint,
    Integer yearBuilt,
    String ownerOperator) {

  /**
   * Returns a dashboard-friendly category with fallback order:
   * {@code categoryDescription -> icaoAircraftClass -> unknown}.
   *
   * @return resolved low-cardinality category
   */
  public String categoryOrFallback() {
    if (categoryDescription != null && !categoryDescription.isBlank()) {
      return categoryDescription;
    }
    if (icaoAircraftClass != null && !icaoAircraftClass.isBlank()) {
      return icaoAircraftClass;
    }
    return "unknown";
  }

  /**
   * Returns the military hint as a stable metric label value.
   *
   * @return {@code true}, {@code false}, or {@code unknown}
   */
  public String militaryLabel() {
    if (militaryHint == null) {
      return "unknown";
    }
    return militaryHint ? "true" : "false";
  }
}
