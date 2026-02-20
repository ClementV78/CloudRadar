package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.cloudradar.dashboard.aircraft.AircraftMetadata;
import com.cloudradar.dashboard.aircraft.AircraftMetadataRepository;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightDetailResponse;
import com.cloudradar.dashboard.model.FlightListResponse;
import com.cloudradar.dashboard.model.FlightMapItem;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class FlightQueryServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private HashOperations<String, Object, Object> hashOperations;
  @Mock private HyperLogLogOperations<String, String> hyperLogLogOperations;
  @Mock private ListOperations<String, String> listOperations;
  @Mock private AircraftMetadataRepository aircraftRepository;

  private DashboardProperties properties;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    properties = new DashboardProperties();
    properties.getApi().getBbox().setDefaultValue("0.0,45.0,10.0,55.0");
    properties.getApi().getBbox().setAllowedLonMin(-20.0);
    properties.getApi().getBbox().setAllowedLonMax(20.0);
    properties.getApi().getBbox().setAllowedLatMin(40.0);
    properties.getApi().getBbox().setAllowedLatMax(60.0);
    properties.getApi().getBbox().setMaxAreaDeg2(200.0);
    properties.getApi().setDefaultLimit(200);
    properties.getApi().setMaxLimit(1000);
    properties.getRedis().setLastPositionsKey("cloudradar:aircraft:last");
    properties.getRedis().setTrackKeyPrefix("cloudradar:aircraft:track:");

    objectMapper = new ObjectMapper();

    lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    lenient().when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
    lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
  }

  @Test
  void listFlights_usesRedisScanAndReturnsSortedLimitedItems() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.empty(), Optional.empty());

    List<Map.Entry<Object, Object>> entries = List.of(
        Map.entry("abc001", eventJson("abc001", 1700000001L, 120.0, 1000.0, false)),
        Map.entry("abc002", eventJson("abc002", 1700000003L, 180.0, 2000.0, false)),
        Map.entry("abc003", eventJson("abc003", 1700000002L, 90.0, 1500.0, false))
    );
    Cursor<Map.Entry<Object, Object>> cursor = cursorOf(entries);
    when(hashOperations.scan(anyString(), any())).thenReturn(cursor);

    FlightListResponse response =
        service.listFlights(null, null, "2", "lastSeen", "desc", null, null, null, null, null);

    assertEquals(2, response.count());
    assertEquals(3, response.totalMatched());
    assertNull(response.latestOpenSkyBatchEpoch());
    List<FlightMapItem> items = response.items();
    assertEquals("abc002", items.get(0).icao24());
    assertEquals("abc003", items.get(1).icao24());

    verify(hashOperations).scan(eq("cloudradar:aircraft:last"), any());
    verify(hashOperations, never()).values(anyString());
  }

  @Test
  void listFlights_keepsOnlyLatestEventPerNormalizedIcao24() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.empty(), Optional.empty());

    List<Map.Entry<Object, Object>> entries = List.of(
        Map.entry("abc123", eventJson("abc123", 1700000001L, 120.0, 1000.0, false)),
        Map.entry("ABC123 ", eventJson("ABC123 ", 1700000005L, 180.0, 2000.0, false)),
        Map.entry("def456", eventJson("def456", 1700000003L, 90.0, 1500.0, false))
    );
    Cursor<Map.Entry<Object, Object>> cursor = cursorOf(entries);
    when(hashOperations.scan(anyString(), any())).thenReturn(cursor);

    FlightListResponse response =
        service.listFlights(null, null, "10", "lastSeen", "desc", null, null, null, null, null);

    assertEquals(2, response.count());
    assertEquals(2, response.totalMatched());
    assertNull(response.latestOpenSkyBatchEpoch());
    assertEquals("abc123", response.items().get(0).icao24());
    assertEquals(1700000005L, response.items().get(0).lastSeen());
    assertEquals("def456", response.items().get(1).icao24());
  }

  @Test
  void listFlights_keepsLatestAndTwoPreviousOpenSkyBatches() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.empty(), Optional.empty());

    List<Map.Entry<Object, Object>> entries = List.of(
        Map.entry("abc001", eventJson("abc001", 1700000001L, 120.0, 1000.0, false, 101L)),
        Map.entry("abc002", eventJson("abc002", 1700000002L, 180.0, 2000.0, false, 101L)),
        Map.entry("abc003", eventJson("abc003", 1700000003L, 90.0, 1500.0, false, 100L)),
        Map.entry("abc002-old", eventJson("abc002", 1700099999L, 90.0, 1500.0, false, 100L)),
        Map.entry("abc004", eventJson("abc004", 1700000004L, 90.0, 1500.0, false, 99L)),
        Map.entry("abc005", eventJson("abc005", 1700000005L, 90.0, 1500.0, false, 98L))
    );
    Cursor<Map.Entry<Object, Object>> cursor = cursorOf(entries);
    when(hashOperations.scan(anyString(), any())).thenReturn(cursor);

    FlightListResponse response =
        service.listFlights(null, null, "10", "lastSeen", "desc", null, null, null, null, null);

    assertEquals(4, response.count());
    assertEquals(4, response.totalMatched());
    assertEquals(101L, response.latestOpenSkyBatchEpoch());

    Map<String, FlightMapItem> byIcao = response.items().stream()
        .collect(java.util.stream.Collectors.toMap(FlightMapItem::icao24, item -> item));

    assertNotNull(byIcao.get("abc001"));
    assertNotNull(byIcao.get("abc002"));
    assertNotNull(byIcao.get("abc003"));
    assertNotNull(byIcao.get("abc004"));
    assertNull(byIcao.get("abc005"));

    // Same ICAO appears in 101 and 100; newer batch must win even if older batch has newer lastSeen.
    assertEquals(1700000002L, byIcao.get("abc002").lastSeen());
  }

  @Test
  void listFlights_enrichesMapItemsOnReadWhenAircraftDbIsAvailable() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.of(aircraftRepository), Optional.empty());

    List<Map.Entry<Object, Object>> entries = List.of(
        Map.entry("abc123", eventJson("abc123", 1700000003L, 180.0, 2000.0, false))
    );
    Cursor<Map.Entry<Object, Object>> cursor = cursorOf(entries);
    when(hashOperations.scan(anyString(), any())).thenReturn(cursor);
    when(aircraftRepository.findByIcao24("abc123"))
        .thenReturn(Optional.of(new AircraftMetadata(
            "abc123",
            "France",
            "Commercial",
            "L2",
            "AIRBUS",
            "Airbus",
            "A320",
            "F-GKXA",
            "A320",
            false,
            2011,
            "Air France")));

    FlightListResponse response =
        service.listFlights(null, null, "10", "lastSeen", "desc", null, null, null, null, null);

    assertEquals(1, response.count());
    FlightMapItem item = response.items().get(0);
    assertEquals("abc123", item.icao24());
    assertEquals(false, item.militaryHint());
    assertEquals("airplane", item.airframeType());
    assertEquals("commercial", item.fleetType());
    assertEquals("large", item.aircraftSize());
    verify(aircraftRepository).findByIcao24("abc123");
  }

  @Test
  void listFlights_classifiesRescueHelicopterFromMetadataAndCallsign() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.of(aircraftRepository), Optional.empty());

    List<Map.Entry<Object, Object>> entries = List.of(
        Map.entry("39ac17", eventJson("39ac17", 1700000003L, 133.0, 495.3, false, null, "SAMUIDF"))
    );
    Cursor<Map.Entry<Object, Object>> cursor = cursorOf(entries);
    when(hashOperations.scan(anyString(), any())).thenReturn(cursor);
    when(aircraftRepository.findByIcao24("39ac17"))
        .thenReturn(Optional.of(new AircraftMetadata(
            "39ac17",
            "France",
            "H2T",
            "L2",
            "AIRBUS",
            "Airbus Helicopters",
            "EC145",
            "F-HLAX",
            "EC45",
            false,
            null,
            "SAMU IDF")));

    FlightListResponse response =
        service.listFlights(null, null, "10", "lastSeen", "desc", null, null, null, null, null);

    assertEquals(1, response.count());
    FlightMapItem item = response.items().get(0);
    assertEquals("39ac17", item.icao24());
    assertEquals("helicopter", item.airframeType());
    assertEquals("rescue", item.fleetType());
  }

  @Test
  void getFlightDetail_returnsTrackAndMetadataWhenRequested() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.of(aircraftRepository), Optional.empty());

    when(hashOperations.get("cloudradar:aircraft:last", "abc123"))
        .thenReturn(eventJson("abc123", 1700000000L, 210.0, 3200.0, false));
    when(listOperations.range("cloudradar:aircraft:track:abc123", 0, 119))
        .thenReturn(List.of(eventJson("abc123", 1699999998L, 200.0, 3000.0, false)));
    when(aircraftRepository.findByIcao24("abc123"))
        .thenReturn(Optional.of(new AircraftMetadata(
            "abc123",
            "France",
            "Commercial",
            "L2",
            "AIRBUS",
            "Airbus",
            "A320",
            "F-GKXA",
            "A320",
            false,
            2011,
            "Air France")));

    FlightDetailResponse response = service.getFlightDetail("abc123", "track");

    assertEquals("abc123", response.icao24());
    assertEquals("F-GKXA", response.registration());
    assertEquals("Airbus", response.manufacturer());
    assertEquals("A320", response.typecode());
    assertNotNull(response.recentTrack());
    assertEquals(1, response.recentTrack().size());
  }

  @Test
  void getFlightsMetrics_aggregatesExpectedKpis() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.of(aircraftRepository), Optional.empty());

    long now = Instant.now().getEpochSecond();
    List<Map.Entry<Object, Object>> entries = List.of(
        Map.entry("mil001", eventJson("mil001", now - 300, 250.0, 5000.0, false)),
        Map.entry("pri001", eventJson("pri001", now - 200, 180.0, 3500.0, false)),
        Map.entry("unk001", eventJson("unk001", now - 100, 160.0, 2700.0, false))
    );
    Cursor<Map.Entry<Object, Object>> cursor = cursorOf(entries);
    when(hashOperations.scan(anyString(), any())).thenReturn(cursor);

    when(aircraftRepository.findByIcao24("mil001"))
        .thenReturn(Optional.of(new AircraftMetadata(
            "mil001",
            "France",
            "Fighter",
            "L2",
            null,
            null,
            "F16",
            null,
            "F16",
            true,
            null,
            "Air Force")));
    when(aircraftRepository.findByIcao24("pri001"))
        .thenReturn(Optional.of(new AircraftMetadata(
            "pri001",
            "France",
            "Business",
            "L2",
            null,
            null,
            "CJ2",
            null,
            "C25A",
            false,
            null,
            "Private Owner")));
    when(aircraftRepository.findByIcao24("unk001")).thenReturn(Optional.empty());
    when(hashOperations.entries(anyString())).thenReturn(Map.of(
        "events_total", "10",
        "events_military", "2"));

    FlightsMetricsResponse response = service.getFlightsMetrics(null, "24h");

    assertEquals(3, response.activeAircraft());
    assertEquals(33.33, response.militarySharePercent());
    assertEquals(33.33, response.defenseActivityScore());
    assertTrue(response.trafficDensityPer10kKm2() > 0.0);

    Map<String, Integer> fleetCounts = response.fleetBreakdown().stream()
        .collect(java.util.stream.Collectors.toMap(
            FlightsMetricsResponse.TypeBreakdownItem::key,
            FlightsMetricsResponse.TypeBreakdownItem::count));
    assertEquals(1, fleetCounts.get("military"));
    assertEquals(1, fleetCounts.get("private"));
    assertEquals(1, fleetCounts.get("unknown"));

    assertNotNull(response.estimates());
    assertNull(response.estimates().takeoffsWindow());
    assertEquals("planned_v1_1", response.estimates().notes().get("takeoffsLandings"));
    assertFalse(response.activitySeries().isEmpty());
    assertTrue(response.activitySeries().stream().anyMatch(bucket -> bucket.eventsTotal() > 0));
    assertEquals(86400L, response.activityWindowSeconds());
    assertTrue(response.activityBucketSeconds() > 0);
  }

  @Test
  void getFlightsMetrics_aggregatesMinuteBucketsIntoDisplayBuckets() {
    FlightQueryService service =
        new FlightQueryService(redisTemplate, objectMapper, properties, Optional.empty(), Optional.empty());
    properties.getApi().setMetricsBucketCount(12);

    Cursor<Map.Entry<Object, Object>> cursor = cursorOf(List.of());
    when(hashOperations.scan(anyString(), any())).thenReturn(cursor);

    long now = Instant.now().getEpochSecond();
    long windowSeconds = Duration.ofMinutes(30).getSeconds();
    int bucketCount = 12;
    long bucketWidth = windowSeconds / bucketCount;
    long start = now - windowSeconds;
    long bucketStartA = start + (2L * bucketWidth);
    long bucketStartB = start + (7L * bucketWidth);

    List<Long> bucketAMinutes = new ArrayList<>();
    for (long minute = ((bucketStartA + 59) / 60) * 60; minute < bucketStartA + bucketWidth; minute += 60) {
      if (minute >= start && minute <= now) {
        bucketAMinutes.add(minute);
      }
    }
    assertTrue(bucketAMinutes.size() >= 2);
    long minuteA1 = bucketAMinutes.get(0);
    long minuteA2 = bucketAMinutes.get(1);

    long computedMinuteB = ((bucketStartB + 59) / 60) * 60;
    if (computedMinuteB > now) {
      computedMinuteB -= 60;
    }
    final long minuteB = computedMinuteB;

    String prefix = properties.getRedis().getActivityBucketKeyPrefix();
    when(hashOperations.entries(anyString())).thenAnswer(invocation -> {
      String key = invocation.getArgument(0);
      if (!key.startsWith(prefix)) {
        return Map.of();
      }
      long epoch = Long.parseLong(key.substring(prefix.length()));
      if (epoch == minuteA1) {
        return Map.of("events_total", "5", "events_military", "2");
      }
      if (epoch == minuteA2) {
        return Map.of("events_total", "3", "events_military", "1");
      }
      if (epoch == minuteB) {
        return Map.of("events_total", "4", "events_military", "1");
      }
      return Map.of();
    });
    when(hyperLogLogOperations.size(anyString())).thenAnswer(invocation -> {
      String key = invocation.getArgument(0);
      if (!key.startsWith(prefix)) {
        return 0L;
      }
      int suffixSeparator = key.indexOf(':', prefix.length());
      long epoch = Long.parseLong(key.substring(prefix.length(), suffixSeparator));
      if (key.endsWith(":aircraft_hll")) {
        if (epoch == minuteA1) {
          return 4L;
        }
        if (epoch == minuteA2) {
          return 2L;
        }
        if (epoch == minuteB) {
          return 3L;
        }
      }
      if (key.endsWith(":aircraft_military_hll")) {
        if (epoch == minuteA1) {
          return 1L;
        }
        if (epoch == minuteA2) {
          return 1L;
        }
        if (epoch == minuteB) {
          return 1L;
        }
      }
      return 0L;
    });

    FlightsMetricsResponse response = service.getFlightsMetrics(null, "30m");

    assertEquals(12, response.activitySeries().size());
    assertTrue(response.activitySeries().stream().anyMatch(bucket ->
        bucket.eventsTotal() == 8
            && bucket.eventsMilitary() == 3
            && bucket.aircraftTotal() == 6
            && bucket.aircraftMilitary() == 2
            && bucket.militarySharePercent() == 33.33
            && bucket.hasData()));
    assertTrue(response.activitySeries().stream().anyMatch(bucket ->
        bucket.eventsTotal() == 4
            && bucket.eventsMilitary() == 1
            && bucket.aircraftTotal() == 3
            && bucket.aircraftMilitary() == 1
            && bucket.militarySharePercent() == 33.33
            && bucket.hasData()));
  }

  private Cursor<Map.Entry<Object, Object>> cursorOf(List<Map.Entry<Object, Object>> entries) {
    return new ListBackedCursor(entries);
  }

  private String eventJson(String icao24, long lastContact, double velocity, double altitude, boolean onGround) {
    return eventJson(icao24, lastContact, velocity, altitude, onGround, null, icao24.toUpperCase());
  }

  private String eventJson(
      String icao24,
      long lastContact,
      double velocity,
      double altitude,
      boolean onGround,
      Long openskyFetchEpoch) {
    return eventJson(icao24, lastContact, velocity, altitude, onGround, openskyFetchEpoch, icao24.toUpperCase());
  }

  private String eventJson(
      String icao24,
      long lastContact,
      double velocity,
      double altitude,
      boolean onGround,
      Long openskyFetchEpoch,
      String callsign) {
    String fetchEpochField = openskyFetchEpoch == null ? "" : ",\"opensky_fetch_epoch\":" + openskyFetchEpoch;
    return "{" +
        "\"icao24\":\"" + icao24 + "\"," +
        "\"callsign\":\"" + callsign + "\"," +
        "\"lat\":48.8566," +
        "\"lon\":2.3522," +
        "\"heading\":90.0," +
        "\"velocity\":" + velocity + "," +
        "\"geo_altitude\":" + altitude + "," +
        "\"baro_altitude\":" + altitude + "," +
        "\"on_ground\":" + onGround + "," +
        "\"time_position\":" + (lastContact - 2) + "," +
        "\"last_contact\":" + lastContact +
        fetchEpochField +
        "}";
  }

  private static final class ListBackedCursor implements Cursor<Map.Entry<Object, Object>> {
    private final List<Map.Entry<Object, Object>> entries;
    private int index = 0;
    private boolean closed = false;

    private ListBackedCursor(List<Map.Entry<Object, Object>> entries) {
      this.entries = entries;
    }

    @Override
    public long getPosition() {
      return index;
    }

    @Override
    public CursorId getId() {
      return CursorId.of(index);
    }

    @Override
    public long getCursorId() {
      return index;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public boolean hasNext() {
      return !closed && index < entries.size();
    }

    @Override
    public Map.Entry<Object, Object> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return entries.get(index++);
    }
  }
}
