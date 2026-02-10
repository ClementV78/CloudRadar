package com.cloudradar.processor.aircraft;

import java.util.Optional;

public interface AircraftMetadataRepository {
  Optional<AircraftMetadata> findByIcao24(String icao24);
}

