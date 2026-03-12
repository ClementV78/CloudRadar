package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.aircraft.AircraftMetadata;
import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.api.NotFoundException;
import com.cloudradar.dashboard.model.FlightDetailResponse;
import com.cloudradar.dashboard.model.FlightPhoto;
import com.cloudradar.dashboard.model.FlightTrackPoint;
import com.cloudradar.dashboard.model.PositionEvent;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class FlightDetailQueryHandler {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
  private static final Set<String> SUPPORTED_INCLUDES = Set.of("track", "enrichment");

  private final FlightSnapshotReader snapshotReader;
  private final FlightSnapshotEnricher snapshotEnricher;
  private final Optional<PlanespottersPhotoService> planespottersPhotoService;

  FlightDetailQueryHandler(
      FlightSnapshotReader snapshotReader,
      FlightSnapshotEnricher snapshotEnricher,
      Optional<PlanespottersPhotoService> planespottersPhotoService) {
    this.snapshotReader = snapshotReader;
    this.snapshotEnricher = snapshotEnricher;
    this.planespottersPhotoService = planespottersPhotoService;
  }

  FlightDetailResponse getFlightDetail(String icao24Raw, String includeRaw) {
    String icao24 = FlightQueryValues.normalizeOptional(icao24Raw, true, false);
    if (icao24 == null || !icao24.matches("^[a-f0-9]{6}$")) {
      throw new BadRequestException("icao24 must be a 6-char hexadecimal identifier");
    }

    Set<String> include = FlightDetailIncludeParser.parse(includeRaw, SUPPORTED_INCLUDES);
    PositionEvent event =
        snapshotReader
            .loadLatestEvent(icao24)
            .orElseThrow(() -> new NotFoundException("flight not found for icao24=" + icao24));

    Optional<AircraftMetadata> metadata = snapshotEnricher.resolveMetadata(icao24);
    List<FlightTrackPoint> track =
        include.contains("track") ? snapshotReader.loadTrack(icao24) : Collections.emptyList();
    FlightPhoto photo =
        planespottersPhotoService
            .map(
                service ->
                    service.resolvePhoto(
                        icao24, metadata.map(AircraftMetadata::registration).orElse(null)))
            .orElse(null);

    return new FlightDetailResponse(
        icao24,
        FlightQueryValues.trimToNull(event.callsign()),
        metadata.map(AircraftMetadata::registration).orElse(null),
        metadata
            .map(
                value ->
                    FlightQueryValues.trimToNull(value.manufacturerName()) != null
                        ? value.manufacturerName()
                        : value.manufacturerIcao())
            .orElse(null),
        metadata.map(AircraftMetadata::model).orElse(null),
        metadata
            .map(AircraftMetadata::typecode)
            .map(value -> FlightQueryValues.normalizeOptional(value, false, true))
            .orElse(null),
        metadata.map(AircraftMetadata::categoryOrFallback).orElse(null),
        event.lat(),
        event.lon(),
        event.heading(),
        event.altitude(),
        event.velocity(),
        event.verticalRate(),
        event.lastContact(),
        event.onGround(),
        metadata.map(AircraftMetadata::country).orElse(null),
        metadata.map(AircraftMetadata::militaryHint).orElse(null),
        metadata.map(AircraftMetadata::yearBuilt).orElse(null),
        metadata.map(AircraftMetadata::ownerOperator).orElse(null),
        photo,
        track,
        ISO.format(Instant.now()));
  }
}
