package com.cloudradar.ingester;

import com.cloudradar.ingester.opensky.FlightState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class FlightEventMapper {

  List<Map<String, Object>> toEvents(List<FlightState> states, long openskyFetchEpoch) {
    return states.stream()
        .map(state -> toEvent(state, openskyFetchEpoch))
        .collect(Collectors.toList());
  }

  Map<String, Object> toEvent(FlightState state, long openskyFetchEpoch) {
    Map<String, Object> event = new HashMap<>();
    event.put("icao24", state.icao24());
    event.put("callsign", state.callsign());
    event.put("lat", state.latitude());
    event.put("lon", state.longitude());
    event.put("velocity", state.velocity());
    event.put("heading", state.heading());
    event.put("geo_altitude", state.geoAltitude());
    event.put("baro_altitude", state.baroAltitude());
    event.put("on_ground", state.onGround());
    event.put("time_position", state.timePosition());
    event.put("last_contact", state.lastContact());
    event.put("opensky_fetch_epoch", openskyFetchEpoch);
    return event;
  }
}
