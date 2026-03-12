package com.cloudradar.processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.cloudradar.processor.aircraft.AircraftMetadata;
import com.cloudradar.processor.aircraft.AircraftMetadataRepository;
import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@SuppressWarnings("unchecked")
class EventProcessorTest {

  private StringRedisTemplate redisTemplate;
  private HashOperations<String, Object, Object> hashOps;
  private ListOperations<String, String> listOps;
  private SetOperations<String, String> setOps;
  private HyperLogLogOperations<String, String> hllOps;
  private ProcessorProperties properties;
  private SimpleMeterRegistry meterRegistry;
  private ProcessorMetrics metrics;
  private EventProcessor processor;

  @BeforeEach
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    hashOps = mock(HashOperations.class);
    listOps = mock(ListOperations.class);
    setOps = mock(SetOperations.class);
    hllOps = mock(HyperLogLogOperations.class);
    when(redisTemplate.opsForHash()).thenReturn(hashOps);
    when(redisTemplate.opsForList()).thenReturn(listOps);
    when(redisTemplate.opsForSet()).thenReturn(setOps);
    when(redisTemplate.opsForHyperLogLog()).thenReturn(hllOps);
    when(setOps.size(anyString())).thenReturn(1L);

    properties = new ProcessorProperties();
    properties.setTrackLength(5);

    meterRegistry = new SimpleMeterRegistry();
    metrics = new ProcessorMetrics(meterRegistry, properties);

    processor = new EventProcessor(
        redisTemplate,
        new ObjectMapper(),
        properties,
        metrics,
        new BboxClassifier(),
        new ActivityBucketKeyResolver(),
        Optional.empty(),
        new LastPositionSnapshotWriter(redisTemplate, new ObjectMapper(), properties));
  }

  @Test
  void validEvent_writesHashAndTrack() {
    String payload = validPayload("abc123", 48.0, 2.0);
    processor.process(payload);

    verify(hashOps).put(eq("cloudradar:aircraft:last"), eq("abc123"), eq(payload));
    verify(listOps).leftPush(eq("cloudradar:aircraft:track:abc123"), eq(payload));
    verify(listOps).trim(eq("cloudradar:aircraft:track:abc123"), eq(0L), eq(4L));
    assertEquals(1.0, meterRegistry.get("processor.events.processed").counter().count());
  }

  @Test
  void nullIcao24_skipsAndIncrementsError() {
    String payload = "{\"icao24\":null,\"lat\":48.0,\"lon\":2.0}";
    processor.process(payload);

    verify(hashOps, never()).put(anyString(), anyString(), anyString());
    assertEquals(1.0, meterRegistry.get("processor.events.errors").counter().count());
  }

  @Test
  void blankIcao24_skipsAndIncrementsError() {
    String payload = "{\"icao24\":\"  \",\"lat\":48.0,\"lon\":2.0}";
    processor.process(payload);

    verify(hashOps, never()).put(anyString(), anyString(), anyString());
    assertEquals(1.0, meterRegistry.get("processor.events.errors").counter().count());
  }

  @Test
  void malformedJson_incrementsError() {
    processor.process("{not valid json}");

    verify(hashOps, never()).put(anyString(), anyString(), anyString());
    assertEquals(1.0, meterRegistry.get("processor.events.errors").counter().count());
  }

  @Test
  void eventInsideBbox_addedToSet() {
    // Default bbox includes 48.0, 2.0
    processor.process(validPayload("abc123", 48.0, 2.0));

    verify(setOps).add(eq("cloudradar:aircraft:in_bbox"), eq("abc123"));
  }

  @Test
  void eventOutsideBbox_removedFromSet() {
    processor.process(validPayload("abc123", 90.0, 90.0));

    verify(setOps).remove(eq("cloudradar:aircraft:in_bbox"), eq("abc123"));
  }

  @Test
  void eventWithNullLatLon_noBboxOperation() {
    String payload = "{\"icao24\":\"abc123\",\"lat\":null,\"lon\":null}";
    processor.process(payload);

    verify(setOps, never()).add(anyString(), anyString());
    verify(setOps, never()).remove(anyString(), any());
  }

  @Test
  void trackLengthZero_noTrackWrite() {
    properties.setTrackLength(0);
    processor.process(validPayload("abc123", 48.0, 2.0));

    verify(listOps, never()).leftPush(anyString(), anyString());
  }

  @Test
  void withMetadataRepo_recordsEnrichmentMetrics() {
    AircraftMetadataRepository repo = icao24 -> Optional.of(
        new AircraftMetadata(icao24, "France", "Light", null, null, null,
            null, null, "B738", false, 2015, "AirFrance"));

    EventProcessor processorWithRepo = new EventProcessor(
        redisTemplate,
        new ObjectMapper(),
        properties,
        metrics,
        new BboxClassifier(),
        new ActivityBucketKeyResolver(),
        Optional.of(repo),
        new LastPositionSnapshotWriter(redisTemplate, new ObjectMapper(), properties));

    processorWithRepo.process(validPayload("abc123", 48.0, 2.0));

    assertEquals(1.0, meterRegistry.get("processor.aircraft.category.events")
        .tag("category", "Light").counter().count());
    assertEquals(1.0, meterRegistry.get("processor.aircraft.country.events")
        .tag("country", "France").counter().count());
    assertEquals(1.0, meterRegistry.get("processor.aircraft.military.events")
        .tag("military", "false").counter().count());
  }

  @Test
  void withoutMetadataRepo_noEnrichmentMetrics() {
    processor.process(validPayload("abc123", 48.0, 2.0));

    // No category/country/military counters should exist when repo is absent
    assertEquals(0.0, counterValue("processor.aircraft.category.events", "category", "unknown"));
    assertEquals(0.0, counterValue("processor.aircraft.country.events", "country", "unknown"));
  }

  @Test
  void activityBucketWritten() {
    processor.process(validPayload("abc123", 48.0, 2.0));

    // Verify hash increment for events_total
    verify(hashOps).increment(
        org.mockito.ArgumentMatchers.startsWith("cloudradar:activity:bucket:"),
        eq("events_total"),
        eq(1L));
    // Verify HLL add
    verify(hllOps).add(
        org.mockito.ArgumentMatchers.contains(":aircraft_hll"),
        eq("abc123"));
  }

  @Test
  void pollAndProcess_nullPayload_doesNotCallProcess() {
    String inputKey = "cloudradar:ingest:queue";
    when(listOps.rightPop(eq(inputKey), any(Duration.class))).thenReturn(null);
    when(listOps.size(inputKey)).thenReturn(0L);

    processor.pollAndProcess(inputKey, Duration.ofSeconds(2));

    // No event processed — hash should not be called
    verify(hashOps, never()).put(anyString(), anyString(), anyString());
    // Queue depth still refreshed
    verify(listOps).size(inputKey);
  }

  @Test
  void pollAndProcess_withPayload_delegatesToProcess() {
    String inputKey = "cloudradar:ingest:queue";
    String payload = validPayload("abc123", 48.0, 2.0);
    when(listOps.rightPop(eq(inputKey), any(Duration.class))).thenReturn(payload);
    when(listOps.size(inputKey)).thenReturn(5L);

    processor.pollAndProcess(inputKey, Duration.ofSeconds(2));

    verify(hashOps).put(eq("cloudradar:aircraft:last"), eq("abc123"), eq(payload));
    assertEquals(1.0, meterRegistry.get("processor.events.processed").counter().count());
  }

  @Test
  void handleLoopError_incrementsErrorCounter() {
    processor.handleLoopError(new RuntimeException("test"));

    assertEquals(1.0, meterRegistry.get("processor.events.errors").counter().count());
  }

  private static String validPayload(String icao24, double lat, double lon) {
    return String.format(
        "{\"icao24\":\"%s\",\"lat\":%s,\"lon\":%s,\"time_position\":1706000000,\"last_contact\":1706000001}",
        icao24, lat, lon);
  }

  private double counterValue(String name, String tagKey, String tagValue) {
    try {
      return meterRegistry.get(name).tag(tagKey, tagValue).counter().count();
    } catch (Exception e) {
      return 0.0;
    }
  }
}
