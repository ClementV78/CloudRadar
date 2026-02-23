package com.cloudradar.dashboard.model;

import java.util.List;

/**
 * Detailed aircraft view returned by {@code GET /api/flights/{icao24}}.
 *
 * @param icao24 aircraft identifier
 * @param callsign callsign when available
 * @param registration aircraft registration from enrichment
 * @param manufacturer manufacturer name or code
 * @param model model from enrichment
 * @param typecode ICAO type code
 * @param category dashboard-friendly category
 * @param lat latitude
 * @param lon longitude
 * @param heading heading in degrees
 * @param altitude resolved altitude
 * @param groundSpeed speed value when available
 * @param verticalRate vertical speed when available
 * @param lastSeen last contact epoch
 * @param onGround ground-state flag
 * @param country aircraft country metadata
 * @param militaryHint military hint metadata
 * @param yearBuilt optional aircraft year built
 * @param ownerOperator optional operator metadata
 * @param photo optional aircraft photo metadata
 * @param recentTrack optional recent track points
 * @param timestamp response generation timestamp
 */
public record FlightDetailResponse(
    String icao24,
    String callsign,
    String registration,
    String manufacturer,
    String model,
    String typecode,
    String category,
    Double lat,
    Double lon,
    Double heading,
    Double altitude,
    Double groundSpeed,
    Double verticalRate,
    Long lastSeen,
    Boolean onGround,
    String country,
    Boolean militaryHint,
    Integer yearBuilt,
    String ownerOperator,
    FlightPhoto photo,
    List<FlightTrackPoint> recentTrack,
    String timestamp) {}
