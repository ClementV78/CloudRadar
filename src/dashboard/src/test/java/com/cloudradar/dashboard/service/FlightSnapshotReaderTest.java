package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.cloudradar.dashboard.aircraft.AircraftMetadataRepository;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class FlightSnapshotReaderTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private HashOperations<String, Object, Object> hashOperations;
  @Mock private AircraftMetadataRepository aircraftRepository;

  private FlightSnapshotReader reader;

  @BeforeEach
  void setUp() {
    DashboardProperties properties = new DashboardProperties();
    properties.getRedis().setLastPositionsKey("cloudradar:aircraft:last");
    properties.getRedis().setTrackKeyPrefix("cloudradar:aircraft:track:");
    properties.getApi().getBbox().setAllowedLonMin(-20.0);
    properties.getApi().getBbox().setAllowedLonMax(20.0);
    properties.getApi().getBbox().setAllowedLatMin(40.0);
    properties.getApi().getBbox().setAllowedLatMax(60.0);
    properties.getApi().getBbox().setMaxAreaDeg2(200.0);

    when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    FlightEventParser eventParser = new FlightEventParser(new ObjectMapper());
    FlightSnapshotCandidateCollector candidateCollector =
        new FlightSnapshotCandidateCollector(redisTemplate, properties, eventParser);
    FlightSnapshotDeduplicator deduplicator = new FlightSnapshotDeduplicator();
    FlightSnapshotEnricher snapshotEnricher =
        new FlightSnapshotEnricher(java.util.Optional.of(aircraftRepository), new FlightTaxonomy());
    FlightTrackReader trackReader = new FlightTrackReader(redisTemplate, properties, eventParser);
    reader = new FlightSnapshotReader(candidateCollector, deduplicator, snapshotEnricher, trackReader);
  }

  @Test
  void loadSnapshots_prefersNewestBatchForSameIcao() {
    List<Map.Entry<Object, Object>> entries =
        List.of(
            Map.entry("a", eventJson("abc123", 1700000020L, 100L)),
            Map.entry("b", eventJson("abc123", 1700000030L, 99L)));

    when(hashOperations.scan(anyString(), any())).thenReturn(new ListBackedCursor(entries));

    List<FlightSnapshot> snapshots =
        reader.loadSnapshots(new Bbox(0.0, 45.0, 10.0, 55.0), null, false, false);

    assertEquals(1, snapshots.size());
    assertEquals(100L, snapshots.get(0).event().openskyFetchEpoch());
    assertEquals(1700000020L, snapshots.get(0).event().lastContact());
  }

  @Test
  void loadLatestEvent_returnsEventWhenPayloadExists() {
    when(hashOperations.get("cloudradar:aircraft:last", "abc123"))
        .thenReturn(eventJson("abc123", 1700000000L, 101L));

    assertTrue(reader.loadLatestEvent("abc123").isPresent());
  }

  private String eventJson(String icao24, long lastContact, Long openskyFetchEpoch) {
    String fetchEpochField =
        openskyFetchEpoch == null ? "" : ",\"opensky_fetch_epoch\":" + openskyFetchEpoch;
    return "{"
        + "\"icao24\":\""
        + icao24
        + "\"," + "\"callsign\":\""
        + icao24.toUpperCase()
        + "\"," + "\"lat\":48.8566,"
        + "\"lon\":2.3522,"
        + "\"heading\":90.0,"
        + "\"velocity\":120.0,"
        + "\"geo_altitude\":1000.0,"
        + "\"baro_altitude\":1000.0,"
        + "\"on_ground\":false,"
        + "\"time_position\":"
        + (lastContact - 2)
        + ","
        + "\"last_contact\":"
        + lastContact
        + fetchEpochField
        + "}";
  }

  private static final class ListBackedCursor implements Cursor<Map.Entry<Object, Object>> {
    private final List<Map.Entry<Object, Object>> entries;
    private final Iterator<Map.Entry<Object, Object>> iterator;

    private ListBackedCursor(List<Map.Entry<Object, Object>> entries) {
      this.entries = entries;
      this.iterator = entries.iterator();
    }

    @Override
    public long getPosition() {
      return 0;
    }

    @Override
    public CursorId getId() {
      return CursorId.of(0L);
    }

    @Override
    public long getCursorId() {
      return 0;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public void close() {}

    @Override
    public boolean hasNext() {
      return iterator.hasNext();
    }

    @Override
    public Map.Entry<Object, Object> next() {
      return iterator.next();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("remove");
    }

  }
}
