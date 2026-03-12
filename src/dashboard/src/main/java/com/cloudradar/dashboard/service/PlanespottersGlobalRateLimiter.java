package com.cloudradar.dashboard.service;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

final class PlanespottersGlobalRateLimiter {
  private final StringRedisTemplate redisTemplate;
  private final String redisKeyPrefix;
  private final int limitRps;

  PlanespottersGlobalRateLimiter(
      StringRedisTemplate redisTemplate, String redisKeyPrefix, int limitRps) {
    this.redisTemplate = redisTemplate;
    this.redisKeyPrefix = redisKeyPrefix;
    this.limitRps = Math.max(1, limitRps);
  }

  boolean tryAcquire() {
    long epochSecond = java.time.Instant.now().getEpochSecond();
    String key = redisKeyPrefix + "ratelimit:sec:" + epochSecond;
    Long count = redisTemplate.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redisTemplate.expire(key, Duration.ofSeconds(2));
    }
    return count != null && count <= limitRps;
  }

  int limitRps() {
    return limitRps;
  }
}
