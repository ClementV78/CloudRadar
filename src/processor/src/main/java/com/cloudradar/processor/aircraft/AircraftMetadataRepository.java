package com.cloudradar.processor.aircraft;

import java.util.Optional;

/** Repository contract for aircraft reference metadata lookups by ICAO24. */
public interface AircraftMetadataRepository {
  /**
   * Returns aircraft metadata for a given ICAO24 identifier.
   *
   * @param icao24 aircraft ICAO24 (hex, case-insensitive)
   * @return matching metadata when found
   */
  Optional<AircraftMetadata> findByIcao24(String icao24);
}
