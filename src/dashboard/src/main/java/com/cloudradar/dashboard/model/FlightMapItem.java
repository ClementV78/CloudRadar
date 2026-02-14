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
 * @param fleetType inferred fleet profile (`commercial|military|private|unknown`)
 * @param aircraftSize inferred size profile (`small|medium|large|heavy|unknown`)
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
    String aircraftSize) {}
