package com.cloudradar.dashboard.model;

/**
 * Lightweight aircraft projection returned by {@code GET /api/flights}.
 *
 * @param icao24 aircraft identifier
 * @param callsign callsign when available
 * @param lat latitude
 * @param lon longitude
 * @param heading heading in degrees
 * @param lastSeen last contact epoch (seconds)
 * @param speed ground speed when available
 * @param altitude resolved altitude when available
 * @param militaryHint military hint metadata
 * @param airframeType inferred airframe type (`airplane|helicopter|unknown`)
 * @param fleetType inferred fleet profile (`commercial|military|rescue|private|unknown`)
 * @param aircraftSize inferred size profile (`small|medium|large|heavy|unknown`)
 * @param prevLat previous latitude used for bootstrap motion (optional)
 * @param prevLon previous longitude used for bootstrap motion (optional)
 * @param prevHeading previous heading used for bootstrap motion (optional)
 * @param prevSpeed previous speed used for bootstrap motion (optional)
 * @param prevAltitude previous altitude used for bootstrap motion (optional)
 * @param prevLastSeen previous last-contact epoch used for bootstrap motion (optional)
 */
public record FlightMapItem(
    String icao24,
    String callsign,
    Double lat,
    Double lon,
    Double heading,
    Long lastSeen,
    Double speed,
    Double altitude,
    Boolean militaryHint,
    String airframeType,
    String fleetType,
    String aircraftSize,
    Double prevLat,
    Double prevLon,
    Double prevHeading,
    Double prevSpeed,
    Double prevAltitude,
    Long prevLastSeen) {}
