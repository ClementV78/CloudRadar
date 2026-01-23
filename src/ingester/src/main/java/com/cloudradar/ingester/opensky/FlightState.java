package com.cloudradar.ingester.opensky;

public record FlightState(
    String icao24,
    String callsign,
    Double latitude,
    Double longitude,
    Double velocity,
    Double heading,
    Double geoAltitude,
    Double baroAltitude,
    Boolean onGround,
    Long timePosition,
    Long lastContact) {}
