package com.cloudradar.processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@SuppressWarnings("unchecked")
class LastPositionSnapshotWriterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private StringRedisTemplate redisTemplate;
  private HashOperations<String, Object, Object> hashOps;
  private ProcessorProperties properties;
  private LastPositionSnapshotWriter writer;

  @BeforeEach
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    hashOps = mock(HashOperations.class);
    when(redisTemplate.opsForHash()).thenReturn(hashOps);

    properties = new ProcessorProperties();
    writer = new LastPositionSnapshotWriter(redisTemplate, objectMapper, properties);
  }

  @Test
  void writeLatest_withoutExistingPayload_storesCurrentPayloadAsIs() {
    String payload = payload(48.0, 2.0, 100.0, 5000.0, 1_700_000_000L);
    when(hashOps.get("cloudradar:aircraft:last", "abc123")).thenReturn(null);

    writer.writeLatest("abc123", payload);

    verify(hashOps).put(eq("cloudradar:aircraft:last"), eq("abc123"), eq(payload));
  }

  @Test
  void writeLatest_withExistingPayload_injectsPreviousSnapshotFields() throws Exception {
    String previousPayload = payload(48.0, 2.0, 90.0, 4900.0, 1_700_000_000L);
    String currentPayload = payload(48.1, 2.1, 95.0, 4950.0, 1_700_000_010L);
    when(hashOps.get("cloudradar:aircraft:last", "abc123")).thenReturn(previousPayload);

    writer.writeLatest("abc123", currentPayload);

    String storedPayload = extractStoredPayload();
    Map<String, Object> saved = objectMapper.readValue(storedPayload, new TypeReference<>() {});
    assertEquals(48.1, ((Number) saved.get("lat")).doubleValue(), 0.0001);
    assertEquals(2.1, ((Number) saved.get("lon")).doubleValue(), 0.0001);
    assertEquals(48.0, ((Number) saved.get("prev_lat")).doubleValue(), 0.0001);
    assertEquals(2.0, ((Number) saved.get("prev_lon")).doubleValue(), 0.0001);
    assertEquals(90.0, ((Number) saved.get("prev_heading")).doubleValue(), 0.0001);
    assertEquals(120.0, ((Number) saved.get("prev_velocity")).doubleValue(), 0.0001);
    assertEquals(4900.0, ((Number) saved.get("prev_altitude")).doubleValue(), 0.0001);
    assertEquals(1_700_000_000L, ((Number) saved.get("prev_last_contact")).longValue());
  }

  @Test
  void writeLatest_withInvalidPreviousPayload_fallsBackToCurrentPayload() {
    String currentPayload = payload(48.1, 2.1, 95.0, 4950.0, 1_700_000_010L);
    when(hashOps.get("cloudradar:aircraft:last", "abc123")).thenReturn("{not-json");

    writer.writeLatest("abc123", currentPayload);

    String storedPayload = extractStoredPayload();
    assertEquals(currentPayload, storedPayload);
  }

  @Test
  void writeLatest_usesBarometricAltitudeWhenGeoAltitudeMissing() throws Exception {
    String previousPayload =
        "{\"icao24\":\"abc123\",\"lat\":48.0,\"lon\":2.0,\"heading\":90.0,"
            + "\"velocity\":100.0,\"geo_altitude\":null,\"baro_altitude\":4100.0,"
            + "\"last_contact\":1700000000}";
    String currentPayload = payload(48.1, 2.1, 95.0, 4950.0, 1_700_000_010L);
    when(hashOps.get("cloudradar:aircraft:last", "abc123")).thenReturn(previousPayload);

    writer.writeLatest("abc123", currentPayload);

    String storedPayload = extractStoredPayload();
    Map<String, Object> saved = objectMapper.readValue(storedPayload, new TypeReference<>() {});
    assertEquals(4100.0, ((Number) saved.get("prev_altitude")).doubleValue(), 0.0001);
    assertTrue(saved.containsKey("prev_lat"));
    assertFalse(saved.containsKey("prev_unknown"));
    assertNull(saved.get("prev_unknown"));
  }

  private String extractStoredPayload() {
    org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(hashOps).put(eq("cloudradar:aircraft:last"), eq("abc123"), payloadCaptor.capture());
    return payloadCaptor.getValue();
  }

  private static String payload(
      double lat, double lon, double heading, double geoAltitude, long lastContact) {
    return String.format(
        "{\"icao24\":\"abc123\",\"lat\":%s,\"lon\":%s,\"heading\":%s,"
            + "\"velocity\":120.0,\"geo_altitude\":%s,\"baro_altitude\":3900.0,"
            + "\"last_contact\":%d}",
        lat, lon, heading, geoAltitude, lastContact);
  }
}
