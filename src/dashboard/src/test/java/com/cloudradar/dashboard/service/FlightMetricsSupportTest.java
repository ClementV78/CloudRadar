package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import com.cloudradar.dashboard.model.PositionEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class FlightMetricsSupportTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private HashOperations<String, Object, Object> hashOperations;
  @Mock private HyperLogLogOperations<String, String> hyperLogLogOperations;

  private FlightMetricsSupport support;

  @BeforeEach
  void setUp() {
    DashboardProperties properties = new DashboardProperties();
    properties.getRedis().setActivityBucketKeyPrefix("cloudradar:activity:");
    support =
        new FlightMetricsSupport(new FlightActivitySeriesReader(redisTemplate, properties));
  }

  @Test
  void breakdown_returnsCanonicalOrderWithPercentages() {
    List<FlightSnapshot> snapshots = List.of(
        snapshot("mil", true, "A320"),
        snapshot("unk", null, null));

    List<FlightsMetricsResponse.TypeBreakdownItem> result =
        support.breakdown(
            snapshots,
            snapshot -> Boolean.TRUE.equals(snapshot.militaryHint()) ? "military" : "unknown",
            List.of("military", "unknown"));

    assertEquals(2, result.size());
    assertEquals("military", result.get(0).key());
    assertEquals(1, result.get(0).count());
    assertEquals(50.0, result.get(0).percent());
    assertEquals(50.0, result.get(1).percent());
  }

  @Test
  void activitySeriesFromEventBuckets_returnsFixedBucketCountForEmptyWindow() {
    when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);
    when(hashOperations.entries(anyString())).thenReturn(Map.of());
    when(hyperLogLogOperations.size(anyString())).thenReturn(0L);

    List<FlightsMetricsResponse.TimeBucket> series =
        support.activitySeriesFromEventBuckets(Duration.ofMinutes(30), 12);

    assertEquals(12, series.size());
    assertTrue(series.stream().allMatch(bucket -> bucket.eventsTotal() == 0));
  }

  @Test
  void mathHelpers_roundAsExpected() {
    assertEquals(33.33, FlightMetricsSupport.round2(33.333));
    assertEquals(0.0, FlightMetricsSupport.pct(1, 0));
    assertEquals(25.0, FlightMetricsSupport.pct(1, 4));
  }

  private FlightSnapshot snapshot(String icao24, Boolean militaryHint, String typecode) {
    return new FlightSnapshot(
        icao24,
        new PositionEvent(
            icao24,
            "CALL",
            48.0,
            2.0,
            90.0,
            100.0,
            1000.0,
            1000.0,
            0.0,
            false,
            1L,
            2L,
            null,
            10L),
        null,
        null,
        typecode,
        militaryHint,
        "airplane",
        null);
  }
}
