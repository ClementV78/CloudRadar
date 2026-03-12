package com.cloudradar.processor.service;

import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Writes the latest per-aircraft payload and injects previous snapshot fields when available.
 */
final class LastPositionSnapshotWriter {
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ProcessorProperties properties;

  LastPositionSnapshotWriter(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProcessorProperties properties) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  void writeLatest(String icao24, String payload) {
    String lastPositionsKey = properties.getRedis().getLastPositionsKey();
    Object previousPayloadRaw = redisTemplate.opsForHash().get(lastPositionsKey, icao24);
    if (!(previousPayloadRaw instanceof String previousPayload) || previousPayload.isBlank()) {
      redisTemplate.opsForHash().put(lastPositionsKey, icao24, payload);
      return;
    }

    String payloadWithPrevious = mergePreviousSnapshot(payload, previousPayload);
    redisTemplate.opsForHash().put(lastPositionsKey, icao24, payloadWithPrevious);
  }

  private String mergePreviousSnapshot(String currentPayload, String previousPayload) {
    try {
      JsonNode currentNode = objectMapper.readTree(currentPayload);
      JsonNode previousNode = objectMapper.readTree(previousPayload);
      if (!(currentNode instanceof ObjectNode currentObject) || !(previousNode instanceof ObjectNode previousObject)) {
        return currentPayload;
      }

      boolean hasPreviousSnapshot = false;
      hasPreviousSnapshot |= copyNumberField(previousObject, currentObject, "lat", "prev_lat");
      hasPreviousSnapshot |= copyNumberField(previousObject, currentObject, "lon", "prev_lon");
      hasPreviousSnapshot |= copyNumberField(previousObject, currentObject, "heading", "prev_heading");
      hasPreviousSnapshot |= copyNumberField(previousObject, currentObject, "velocity", "prev_velocity");
      hasPreviousSnapshot |= copyAltitudeField(previousObject, currentObject);
      hasPreviousSnapshot |= copyLongField(previousObject, currentObject, "last_contact", "prev_last_contact");

      if (!hasPreviousSnapshot) {
        return currentPayload;
      }
      return objectMapper.writeValueAsString(currentObject);
    } catch (Exception ex) {
      return currentPayload;
    }
  }

  private static boolean copyNumberField(
      ObjectNode source, ObjectNode target, String sourceField, String targetField) {
    Double value = doubleValue(source, sourceField);
    if (value == null) {
      return false;
    }
    target.put(targetField, value);
    return true;
  }

  private static boolean copyAltitudeField(ObjectNode source, ObjectNode target) {
    Double altitude =
        firstPresent(doubleValue(source, "geo_altitude"), doubleValue(source, "baro_altitude"));
    if (altitude == null) {
      return false;
    }
    target.put("prev_altitude", altitude);
    return true;
  }

  private static boolean copyLongField(
      ObjectNode source, ObjectNode target, String sourceField, String targetField) {
    Long value = longValue(source, sourceField);
    if (value == null) {
      return false;
    }
    target.put(targetField, value);
    return true;
  }

  private static Double firstPresent(Double first, Double second) {
    if (first != null) {
      return first;
    }
    return second;
  }

  private static Double doubleValue(ObjectNode source, String fieldName) {
    JsonNode node = source.get(fieldName);
    if (node == null || node.isNull() || !node.isNumber()) {
      return null;
    }
    return node.doubleValue();
  }

  private static Long longValue(ObjectNode source, String fieldName) {
    JsonNode node = source.get(fieldName);
    if (node == null || node.isNull() || !node.isNumber()) {
      return null;
    }
    return node.longValue();
  }
}
