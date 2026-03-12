package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import com.cloudradar.dashboard.model.PositionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

final class FlightSnapshotCandidateCollector {
  private static final long REDIS_SCAN_COUNT = 1000L;

  private final StringRedisTemplate redisTemplate;
  private final DashboardProperties properties;
  private final FlightEventParser eventParser;

  FlightSnapshotCandidateCollector(
      StringRedisTemplate redisTemplate,
      DashboardProperties properties,
      FlightEventParser eventParser) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
    this.eventParser = eventParser;
  }

  List<Entry<String, PositionEvent>> collect(Bbox bbox, Long since) {
    HashOperations<String, Object, Object> hashOps = redisTemplate.opsForHash();
    ScanOptions scanOptions = ScanOptions.scanOptions().count(REDIS_SCAN_COUNT).build();
    List<Entry<String, PositionEvent>> candidates = new ArrayList<>();

    try (Cursor<Entry<Object, Object>> cursor = hashOps.scan(properties.getRedis().getLastPositionsKey(), scanOptions)) {
      while (cursor.hasNext()) {
        Entry<Object, Object> entry = cursor.next();
        Object payloadObj = entry.getValue();
        if (payloadObj == null) {
          continue;
        }

        eventParser.parse(payloadObj.toString()).ifPresent(event -> {
          String icao24 = FlightQueryValues.normalizeOptional(event.icao24(), true, false);
          if (isEligible(icao24, event, bbox, since)) {
            candidates.add(Map.entry(icao24, event));
          }
        });
      }
    }

    return candidates;
  }

  private static boolean isEligible(String icao24, PositionEvent event, Bbox bbox, Long since) {
    if (icao24 == null || event.lat() == null || event.lon() == null) {
      return false;
    }
    if (!bbox.contains(event.lat(), event.lon())) {
      return false;
    }
    if (since == null) {
      return true;
    }
    Long lastSeen = event.lastContact();
    return lastSeen != null && lastSeen >= since;
  }
}
