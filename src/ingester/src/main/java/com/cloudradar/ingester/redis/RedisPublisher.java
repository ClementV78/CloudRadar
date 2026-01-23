package com.cloudradar.ingester.redis;

import com.cloudradar.ingester.config.IngesterProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPublisher {
  private static final Logger log = LoggerFactory.getLogger(RedisPublisher.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final IngesterProperties properties;

  public RedisPublisher(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      IngesterProperties properties) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
  }

  public int pushEvents(List<Map<String, Object>> events) {
    int pushed = 0;
    for (Map<String, Object> event : events) {
      try {
        // Add ingest timestamp and push JSON payload into the Redis List.
        Map<String, Object> payload = new HashMap<>(event);
        payload.put("ingested_at", Instant.now().toString());
        redisTemplate.opsForList().rightPush(properties.redis().key(), objectMapper.writeValueAsString(payload));
        pushed++;
      } catch (JsonProcessingException ex) {
        log.warn("Failed to serialize event", ex);
      }
    }
    return pushed;
  }
}
