package com.cloudradar.ingester.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudradar.ingester.config.IngesterProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisPublisherIntegrationTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private StringRedisTemplate redisTemplate;

  @BeforeAll
  static void setupRedis() {
    RedisStandaloneConfiguration config =
        new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory = new LettuceConnectionFactory(config);
    connectionFactory.afterPropertiesSet();
  }

  @AfterAll
  static void shutdownRedis() {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
  }

  @BeforeEach
  void clearRedis() {
    redisTemplate = new StringRedisTemplate(connectionFactory);
    redisTemplate.afterPropertiesSet();
    try (RedisConnection connection = connectionFactory.getConnection()) {
      connection.serverCommands().flushAll();
    }
  }

  @Test
  void pushEvents_writesExpectedJsonContractToConfiguredRedisList() throws Exception {
    IngesterProperties properties =
        new IngesterProperties(
            10_000,
            new IngesterProperties.Redis("cloudradar:ingest:queue"),
            new IngesterProperties.Bbox(47.0, 49.0, 1.0, 3.0),
            new IngesterProperties.RateLimit(4_000, 50, 80, 95, 10_000, 30_000),
            new IngesterProperties.BboxBoost("cloudradar:opensky:bbox:boost:active", 1.5));

    RedisPublisher publisher = new RedisPublisher(redisTemplate, objectMapper, properties);

    Map<String, Object> event =
        Map.of(
            "icao24", "abc123",
            "callsign", "AFR123",
            "lat", 48.8566,
            "lon", 2.3522,
            "last_contact", 1_706_000_001L,
            "opensky_fetch_epoch", 1_706_000_000L);

    int pushed = publisher.pushEvents(List.of(event));

    assertEquals(1, pushed);

    String payload = redisTemplate.opsForList().rightPop("cloudradar:ingest:queue");
    assertNotNull(payload);

    Map<String, Object> asMap = objectMapper.readValue(payload, new TypeReference<>() {});
    assertEquals("abc123", asMap.get("icao24"));
    assertEquals("AFR123", asMap.get("callsign"));
    assertEquals(1_706_000_001, ((Number) asMap.get("last_contact")).longValue());
    assertEquals(1_706_000_000, ((Number) asMap.get("opensky_fetch_epoch")).longValue());

    Object ingestedAt = asMap.get("ingested_at");
    assertNotNull(ingestedAt, "ingested_at must be added by ingester before Redis publish");
    assertTrue(Instant.parse(ingestedAt.toString()).isBefore(Instant.now().plusSeconds(5)));
  }
}
