package com.cloudradar.dashboard.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Normalized telemetry payload deserialized from Redis.
 *
 * <p>Unknown fields are ignored to remain resilient to upstream schema changes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionEvent(
    @JsonProperty("icao24") String icao24,
    @JsonProperty("callsign") String callsign,
    @JsonProperty("lat") Double lat,
    @JsonProperty("lon") Double lon,
    @JsonProperty("heading") Double heading,
    @JsonProperty("velocity") Double velocity,
    @JsonProperty("geo_altitude") Double geoAltitude,
    @JsonProperty("baro_altitude") Double baroAltitude,
    @JsonProperty("vertical_rate") Double verticalRate,
    @JsonProperty("on_ground") Boolean onGround,
    @JsonProperty("time_position") Long timePosition,
    @JsonProperty("last_contact") Long lastContact,
    @JsonProperty("ingested_at") String ingestedAt) {

  /**
   * Returns the best-effort altitude field for UI usage.
   *
   * @return geometric altitude when available, otherwise barometric altitude
   */
  public Double altitude() {
    return geoAltitude != null ? geoAltitude : baroAltitude;
  }
}
