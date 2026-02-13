package com.cloudradar.dashboard.aircraft;

/**
 * Immutable metadata view for one aircraft loaded from the local reference database.
 *
 * @param icao24 aircraft identifier
 * @param country country metadata
 * @param categoryDescription category description from source dataset
 * @param icaoAircraftClass ICAO aircraft class fallback
 * @param manufacturerIcao manufacturer ICAO code
 * @param manufacturerName manufacturer display name
 * @param model model designation
 * @param registration registration string
 * @param typecode ICAO type code
 * @param militaryHint optional military hint flag
 * @param yearBuilt optional production year
 * @param ownerOperator optional owner/operator label
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
   * Resolves the best available category label for dashboards.
   *
   * @return category description, ICAO class, or {@code null}
   */
  public String categoryOrFallback() {
    if (categoryDescription != null && !categoryDescription.isBlank()) {
      return categoryDescription;
    }
    if (icaoAircraftClass != null && !icaoAircraftClass.isBlank()) {
      return icaoAircraftClass;
    }
    return null;
  }
}
