package com.cloudradar.dashboard.model;

/**
 * Track point representation used in detailed aircraft responses.
 *
 * @param lat latitude
 * @param lon longitude
 * @param heading heading in degrees
 * @param altitude resolved altitude
 * @param groundSpeed speed when available
 * @param lastSeen point timestamp in epoch seconds
 * @param onGround ground-state flag
 */
public record FlightTrackPoint(
    Double lat,
    Double lon,
    Double heading,
    Double altitude,
    Double groundSpeed,
    Long lastSeen,
    Boolean onGround) {}
