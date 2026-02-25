package com.cloudradar.processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.cloudradar.processor.aircraft.AircraftMetadata;
import com.cloudradar.processor.aircraft.AircraftMetadataRepository;
import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
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
      LockSupport.parkNanos(Duration.ofMillis(1200).toNanos());
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

  @Test
  void processor_recordsUnknownCountersWhenMetadataRepositoryReturnsEmpty() throws Exception {
    ProcessorProperties properties = new ProcessorProperties();
    properties.setPollTimeoutSeconds(1);
    properties.setTrackLength(1);

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    AircraftMetadataRepository repository = icao24 -> Optional.empty();
    RedisAggregateProcessor processor =
        new RedisAggregateProcessor(
            redisTemplate,
            objectMapper,
            properties,
            meterRegistry,
            Optional.of(repository));

    Map<String, Object> event =
        Map.ofEntries(
            Map.entry("icao24", "def456"),
            Map.entry("callsign", "AFR456"),
            Map.entry("lat", 48.7),
            Map.entry("lon", 2.1),
            Map.entry("time_position", 1_706_100_000L),
            Map.entry("last_contact", 1_706_100_001L),
            Map.entry("ingested_at", "2026-02-24T11:00:00Z"),
            Map.entry("opensky_fetch_epoch", 1_706_100_000L));

    String payload = objectMapper.writeValueAsString(event);

    processor.start();
    try {
      redisTemplate.opsForList().rightPush(properties.getRedis().getInputKey(), payload);

      waitUntil(
          () -> counterValue(meterRegistry, "processor.aircraft.category.events", "category", "unknown") >= 1.0,
          Duration.ofSeconds(8),
          "processor did not record unknown category");
      waitUntil(
          () -> counterValue(meterRegistry, "processor.aircraft.country.events", "country", "unknown") >= 1.0,
          Duration.ofSeconds(8),
          "processor did not record unknown country");
      waitUntil(
          () -> counterValue(meterRegistry, "processor.aircraft.military.events", "military", "unknown") >= 1.0,
          Duration.ofSeconds(8),
          "processor did not record unknown military label");
    } finally {
      processor.stop();
    }
  }

  @Test
  void processor_recordsUnknownMilitaryTypecodeWhenMetadataTypecodeIsInvalid() throws Exception {
    ProcessorProperties properties = new ProcessorProperties();
    properties.setPollTimeoutSeconds(1);
    properties.setTrackLength(1);

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    AircraftMetadataRepository repository =
        icao24 ->
            Optional.of(
                new AircraftMetadata(
                    icao24,
                    "France",
                    "Military",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "TOO_LONG_TYPECODE",
                    true,
                    1999,
                    "Air Force"));
    RedisAggregateProcessor processor =
        new RedisAggregateProcessor(
            redisTemplate,
            objectMapper,
            properties,
            meterRegistry,
            Optional.of(repository));

    Map<String, Object> event =
        Map.ofEntries(
            Map.entry("icao24", "fed789"),
            Map.entry("callsign", "FRA789"),
            Map.entry("lat", 47.9),
            Map.entry("lon", 1.9),
            Map.entry("time_position", 1_706_200_000L),
            Map.entry("last_contact", 1_706_200_001L),
            Map.entry("ingested_at", "2026-02-24T12:00:00Z"),
            Map.entry("opensky_fetch_epoch", 1_706_200_000L));

    String payload = objectMapper.writeValueAsString(event);

    processor.start();
    try {
      redisTemplate.opsForList().rightPush(properties.getRedis().getInputKey(), payload);

      waitUntil(
          () ->
              counterValue(
                      meterRegistry,
                      "processor.aircraft.military.typecode.events",
                      "typecode",
                      "unknown")
                  >= 1.0,
          Duration.ofSeconds(8),
          "processor did not record unknown military typecode");
    } finally {
      processor.stop();
    }
  }

  @Test
  void isInterruptedShutdown_detectsInterruptedCauseChain() throws Exception {
    Throwable interrupted = new RuntimeException(new IllegalStateException(new InterruptedException("stop")));
    Throwable nonInterrupted = new RuntimeException(new IllegalStateException("no interruption"));

    assertTrue(invokeIsInterruptedShutdown(interrupted));
    assertFalse(invokeIsInterruptedShutdown(nonInterrupted));
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

  private static double counterValue(
      SimpleMeterRegistry meterRegistry, String name, String tagKey, String tagValue) {
    try {
      return meterRegistry.get(name).tag(tagKey, tagValue).counter().count();
    } catch (Exception ignored) {
      return 0.0;
    }
  }

  private static boolean invokeIsInterruptedShutdown(Throwable ex) throws Exception {
    Method method =
        RedisAggregateProcessor.class.getDeclaredMethod("isInterruptedShutdown", Throwable.class);
    method.setAccessible(true);
    return (boolean) method.invoke(null, ex);
  }
}
