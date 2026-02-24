package com.cloudradar.processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisAggregateProcessorIntegrationTest {

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
  void processor_consumesIngressListAndWritesExpectedAggregateContracts() throws Exception {
    ProcessorProperties properties = new ProcessorProperties();
    properties.setPollTimeoutSeconds(1);
    properties.setTrackLength(5);

    RedisAggregateProcessor processor =
        new RedisAggregateProcessor(
            redisTemplate,
            objectMapper,
            properties,
            new SimpleMeterRegistry(),
            Optional.empty());

    Map<String, Object> event =
        Map.ofEntries(
            Map.entry("icao24", "abc123"),
            Map.entry("callsign", "AFR123"),
            Map.entry("lat", 48.8566),
            Map.entry("lon", 2.3522),
            Map.entry("velocity", 210.0),
            Map.entry("heading", 190.0),
            Map.entry("geo_altitude", 1200.0),
            Map.entry("baro_altitude", 1150.0),
            Map.entry("on_ground", false),
            Map.entry("time_position", 1_706_000_000L),
            Map.entry("last_contact", 1_706_000_001L),
            Map.entry("ingested_at", "2026-02-24T10:00:00Z"),
            Map.entry("opensky_fetch_epoch", 1_706_000_000L));

    String payload = objectMapper.writeValueAsString(event);

    processor.start();
    try {
      redisTemplate.opsForList().rightPush(properties.getRedis().getInputKey(), payload);

      waitUntil(
          () -> redisTemplate.opsForHash().hasKey(properties.getRedis().getLastPositionsKey(), "abc123"),
          Duration.ofSeconds(8),
          "processor did not write cloudradar:aircraft:last contract");

      String savedPayload =
          (String) redisTemplate.opsForHash().get(properties.getRedis().getLastPositionsKey(), "abc123");
      assertNotNull(savedPayload);

      Map<String, Object> savedMap = objectMapper.readValue(savedPayload, new TypeReference<>() {});
      assertEquals("abc123", savedMap.get("icao24"));
      assertEquals(1_706_000_000, ((Number) savedMap.get("opensky_fetch_epoch")).longValue());

      String trackKey = properties.getRedis().getTrackKeyPrefix() + "abc123";
      waitUntil(
          () -> {
            Long size = redisTemplate.opsForList().size(trackKey);
            return size != null && size > 0;
          },
          Duration.ofSeconds(8),
          "processor did not write track list contract");

      String firstTrackPayload = redisTemplate.opsForList().index(trackKey, 0);
      assertNotNull(firstTrackPayload);
      Map<String, Object> trackMap = objectMapper.readValue(firstTrackPayload, new TypeReference<>() {});
      assertEquals("abc123", trackMap.get("icao24"));

      Boolean inBbox = redisTemplate.opsForSet().isMember(properties.getRedis().getBboxSetKey(), "abc123");
      assertTrue(Boolean.TRUE.equals(inBbox));

      waitUntil(
          () -> {
            Set<String> keys = redisTemplate.keys(properties.getRedis().getActivityBucketKeyPrefix() + "*");
            if (keys == null || keys.isEmpty()) {
              return false;
            }
            return keys.stream()
                .map(redisTemplate::type)
                .anyMatch(DataType.HASH::equals);
          },
          Duration.ofSeconds(8),
          "processor did not write activity bucket contract");

      Set<String> bucketKeys = redisTemplate.keys(properties.getRedis().getActivityBucketKeyPrefix() + "*");
      assertNotNull(bucketKeys);
      Set<String> bucketHashKeys =
          bucketKeys.stream()
              .filter(key -> DataType.HASH.equals(redisTemplate.type(key)))
              .collect(Collectors.toSet());
      assertFalse(bucketHashKeys.isEmpty());

      String bucketKey = bucketHashKeys.iterator().next();
      Object eventsTotal = redisTemplate.opsForHash().get(bucketKey, "events_total");
      assertEquals("1", String.valueOf(eventsTotal));

      Long uniqueAircraft = redisTemplate.opsForHyperLogLog().size(bucketKey + ":aircraft_hll");
      assertNotNull(uniqueAircraft);
      assertTrue(uniqueAircraft >= 1);
    } finally {
      processor.stop();
    }
  }

  private static void waitUntil(BooleanSupplier condition, Duration timeout, String failureMessage) {
    long deadline = System.nanoTime() + timeout.toNanos();
    long pollIntervalNanos = Duration.ofMillis(100).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      LockSupport.parkNanos(pollIntervalNanos);
    }
    fail(failureMessage);
  }
}
