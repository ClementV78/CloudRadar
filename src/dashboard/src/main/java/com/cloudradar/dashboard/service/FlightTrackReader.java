package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightTrackPoint;
import com.cloudradar.dashboard.model.PositionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

final class FlightTrackReader {
  private final StringRedisTemplate redisTemplate;
  private final DashboardProperties properties;
  private final FlightEventParser eventParser;

  FlightTrackReader(
      StringRedisTemplate redisTemplate,
      DashboardProperties properties,
      FlightEventParser eventParser) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
    this.eventParser = eventParser;
  }

  Optional<PositionEvent> loadLatestEvent(String icao24) {
    Object rawPayload = redisTemplate.opsForHash().get(properties.getRedis().getLastPositionsKey(), icao24);
    if (rawPayload == null) {
      return Optional.empty();
    }
    return eventParser.parse(rawPayload.toString());
  }

  List<FlightTrackPoint> loadTrack(String icao24) {
    String trackKey = properties.getRedis().getTrackKeyPrefix() + icao24;
    List<String> payloads = redisTemplate.opsForList().range(trackKey, 0, 119);
    if (payloads == null || payloads.isEmpty()) {
      return Collections.emptyList();
    }

    List<FlightTrackPoint> points = new ArrayList<>(payloads.size());
    for (String payload : payloads) {
      eventParser.parse(payload).ifPresent(event -> points.add(toTrackPoint(event)));
    }
    return points;
  }

  private static FlightTrackPoint toTrackPoint(PositionEvent event) {
    return new FlightTrackPoint(
        event.lat(),
        event.lon(),
        event.heading(),
        event.altitude(),
        event.velocity(),
        event.lastContact(),
        event.onGround());
  }
}
