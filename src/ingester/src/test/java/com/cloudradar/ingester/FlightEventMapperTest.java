package com.cloudradar.ingester;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudradar.ingester.opensky.FlightState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlightEventMapperTest {

  @Test
  void toEventMapsExpectedContractFields() {
    FlightEventMapper mapper = new FlightEventMapper();
    FlightState state = new FlightState(
        "abc123",
        "AFR123",
        48.8566,
        2.3522,
        230.5,
        180.0,
        11300.0,
        11000.0,
        false,
        1_700_000_001L,
        1_700_000_002L);

    Map<String, Object> event = mapper.toEvent(state, 1_700_000_000L);

    assertThat(event)
        .containsEntry("icao24", "abc123")
        .containsEntry("callsign", "AFR123")
        .containsEntry("lat", 48.8566)
        .containsEntry("lon", 2.3522)
        .containsEntry("velocity", 230.5)
        .containsEntry("heading", 180.0)
        .containsEntry("geo_altitude", 11300.0)
        .containsEntry("baro_altitude", 11000.0)
        .containsEntry("on_ground", false)
        .containsEntry("time_position", 1_700_000_001L)
        .containsEntry("last_contact", 1_700_000_002L)
        .containsEntry("opensky_fetch_epoch", 1_700_000_000L);
  }

  @Test
  void toEventsMapsAllStates() {
    FlightEventMapper mapper = new FlightEventMapper();
    List<FlightState> states = List.of(
        new FlightState("abc123", "AFR123", 48.0, 2.0, null, null, null, null, null, null, null),
        new FlightState("def456", "BAW987", 49.0, 3.0, null, null, null, null, null, null, null));

    List<Map<String, Object>> events = mapper.toEvents(states, 1_700_000_000L);

    assertThat(events).hasSize(2);
    assertThat(events.get(0)).containsEntry("icao24", "abc123");
    assertThat(events.get(1)).containsEntry("icao24", "def456");
  }
}
