package com.cloudradar.processor.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Normalized telemetry payload consumed by the processor from Redis.
 *
 * <p>Unknown JSON attributes are ignored to keep ingestion resilient to upstream schema drift.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionEvent(
  @JsonProperty("icao24") String icao24,
  @JsonProperty("lat") Double lat,
  @JsonProperty("lon") Double lon,
  @JsonProperty("callsign") String callsign,
  @JsonProperty("heading") Double heading,
  @JsonProperty("velocity") Double velocity,
  @JsonProperty("baro_altitude") Double baroAltitude,
  @JsonProperty("geo_altitude") Double geoAltitude,
  @JsonProperty("on_ground") Boolean onGround,
  @JsonProperty("time_position") Long timePosition,
  @JsonProperty("last_contact") Long lastContact,
  @JsonProperty("ingested_at") String ingestedAt
) {}
