package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightPhoto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

final class PlanespottersPhotoCache {
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final DashboardProperties.Planespotters properties;

  PlanespottersPhotoCache(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties.Planespotters properties) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  String cacheKeyForIcao(String icao24) {
    return properties.getRedisKeyPrefix() + "icao24:" + icao24.toLowerCase(Locale.ROOT);
  }

  FlightPhoto read(String cacheKey) {
    String payload = redisTemplate.opsForValue().get(cacheKey);
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(payload, FlightPhoto.class);
    } catch (IOException ex) {
      return null;
    }
  }

  boolean write(String cacheKey, FlightPhoto photo) {
    if (photo == null) {
      return false;
    }
    long ttl = ttlForStatus(photo.status());
    try {
      String payload = objectMapper.writeValueAsString(photo);
      redisTemplate.opsForValue().set(cacheKey, payload, ttl, TimeUnit.SECONDS);
      return true;
    } catch (IOException ex) {
      return false;
    }
  }

  private long ttlForStatus(String status) {
    return switch (status) {
      case "available" -> Math.max(60L, properties.getCacheTtlSeconds());
      case "not_found" -> Math.max(30L, properties.getNegativeCacheTtlSeconds());
      case "rate_limited" -> Math.max(1L, properties.getRateLimitedCacheTtlSeconds());
      default -> Math.max(5L, properties.getErrorCacheTtlSeconds());
    };
  }
}
