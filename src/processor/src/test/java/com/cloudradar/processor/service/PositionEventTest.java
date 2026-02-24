package com.cloudradar.processor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PositionEventTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parsesSnakeCasePayloadAndIgnoresUnknownFields() throws Exception {
    String payload = """
        {
          "icao24": "abc123",
          "lat": 48.8566,
          "lon": 2.3522,
          "callsign": "AFR123",
          "heading": 180.0,
          "velocity": 230.5,
          "baro_altitude": 11000.0,
          "geo_altitude": 11300.0,
          "on_ground": false,
          "time_position": 1700000001,
          "last_contact": 1700000002,
          "ingested_at": "2026-02-24T12:00:00Z",
          "opensky_fetch_epoch": 1700000000,
          "unexpected_field": "ignored"
        }
        """;

    PositionEvent event = objectMapper.readValue(payload, PositionEvent.class);

    assertThat(event.icao24()).isEqualTo("abc123");
    assertThat(event.lat()).isEqualTo(48.8566);
    assertThat(event.lon()).isEqualTo(2.3522);
    assertThat(event.callsign()).isEqualTo("AFR123");
    assertThat(event.heading()).isEqualTo(180.0);
    assertThat(event.velocity()).isEqualTo(230.5);
    assertThat(event.baroAltitude()).isEqualTo(11000.0);
    assertThat(event.geoAltitude()).isEqualTo(11300.0);
    assertThat(event.onGround()).isFalse();
    assertThat(event.timePosition()).isEqualTo(1700000001L);
    assertThat(event.lastContact()).isEqualTo(1700000002L);
    assertThat(event.ingestedAt()).isEqualTo("2026-02-24T12:00:00Z");
    assertThat(event.openskyFetchEpoch()).isEqualTo(1700000000L);
  }

  @Test
  void serializesUsingContractFieldNames() throws Exception {
    PositionEvent event = new PositionEvent(
        "abc123",
        48.8566,
        2.3522,
        "AFR123",
        180.0,
        230.5,
        11000.0,
        11300.0,
        false,
        1700000001L,
        1700000002L,
        "2026-02-24T12:00:00Z",
        1700000000L);

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));

    assertThat(json.has("baro_altitude")).isTrue();
    assertThat(json.has("geo_altitude")).isTrue();
    assertThat(json.has("time_position")).isTrue();
    assertThat(json.has("opensky_fetch_epoch")).isTrue();
    assertThat(json.has("baroAltitude")).isFalse();
    assertThat(json.has("openskyFetchEpoch")).isFalse();
  }
}
