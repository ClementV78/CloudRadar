package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.aircraft.AircraftMetadata;
import com.cloudradar.dashboard.aircraft.AircraftMetadataRepository;
import com.cloudradar.dashboard.model.PositionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;

final class FlightSnapshotEnricher {
  private final Optional<AircraftMetadataRepository> aircraftRepo;
  private final FlightTaxonomy taxonomy;

  FlightSnapshotEnricher(
      Optional<AircraftMetadataRepository> aircraftRepo,
      FlightTaxonomy taxonomy) {
    this.aircraftRepo = aircraftRepo;
    this.taxonomy = taxonomy;
  }

  List<FlightSnapshot> enrich(
      Map<String, PositionEvent> latestByIcao,
      boolean includeMetadata,
      boolean includeOwnerOperator) {
    if (latestByIcao.isEmpty()) {
      return List.of();
    }

    List<FlightSnapshot> snapshots = new ArrayList<>(latestByIcao.size());
    for (Entry<String, PositionEvent> latest : latestByIcao.entrySet()) {
      String icao24 = latest.getKey();
      PositionEvent event = latest.getValue();

      Optional<AircraftMetadata> metadata = includeMetadata ? resolveMetadata(icao24) : Optional.empty();
      String category = metadata.map(AircraftMetadata::categoryOrFallback).orElse(null);
      String country = metadata.map(AircraftMetadata::country).orElse(null);
      String typecode = metadata.map(AircraftMetadata::typecode)
          .map(value -> FlightQueryValues.normalizeOptional(value, false, true))
          .orElse(null);
      Boolean militaryHint = metadata.map(AircraftMetadata::militaryHint).orElse(null);
      String ownerOperator = includeOwnerOperator ? metadata.map(AircraftMetadata::ownerOperator).orElse(null) : null;

      snapshots.add(
          new FlightSnapshot(
              icao24,
              event,
              category,
              country,
              typecode,
              militaryHint,
              taxonomy.inferAirframeType(category, typecode),
              ownerOperator));
    }
    return snapshots;
  }

  Optional<AircraftMetadata> resolveMetadata(String icao24) {
    if (aircraftRepo.isEmpty()) {
      return Optional.empty();
    }
    return aircraftRepo.get().findByIcao24(icao24);
  }
}
