package com.cloudradar.dashboard.aircraft;

import java.util.Optional;

/**
 * Read-only repository contract for aircraft metadata lookups.
 */
public interface AircraftMetadataRepository {
  /**
   * Finds metadata for a given ICAO24 identifier.
   *
   * @param icao24 aircraft identifier
   * @return metadata when available
   */
  Optional<AircraftMetadata> findByIcao24(String icao24);
}
