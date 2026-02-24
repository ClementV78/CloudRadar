package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightDetailResponse;
import com.cloudradar.dashboard.model.FlightListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
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
class FlightQueryServiceRedisIntegrationTest {

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
  void dashboard_readsProcessorStylePayloadFromRedisContracts() throws Exception {
    DashboardProperties properties = new DashboardProperties();

    FlightQueryService service =
        new FlightQueryService(
            redisTemplate,
            objectMapper,
            properties,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    Map<String, Object> latestEvent =
        Map.ofEntries(
            Map.entry("icao24", "abc123"),
            Map.entry("callsign", "AFR123"),
            Map.entry("lat", 48.8566),
            Map.entry("lon", 2.3522),
            Map.entry("velocity", 215.0),
            Map.entry("heading", 182.0),
            Map.entry("geo_altitude", 3100.0),
            Map.entry("baro_altitude", 3000.0),
            Map.entry("on_ground", false),
            Map.entry("time_position", 1_706_000_010L),
            Map.entry("last_contact", 1_706_000_020L),
            Map.entry("ingested_at", "2026-02-24T10:00:00Z"),
            Map.entry("opensky_fetch_epoch", 1_706_000_000L));

    String latestPayload = objectMapper.writeValueAsString(latestEvent);
    redisTemplate.opsForHash().put(properties.getRedis().getLastPositionsKey(), "abc123", latestPayload);

    String trackKey = properties.getRedis().getTrackKeyPrefix() + "abc123";
    redisTemplate.opsForList().leftPush(trackKey, latestPayload);

    FlightListResponse list =
        service.listFlights(
            null,
            null,
            "10",
            "lastSeen",
            "desc",
            null,
            null,
            null,
            null,
            null);

    assertEquals(1, list.count());
    assertEquals(1, list.totalMatched());
    assertEquals(1_706_000_000L, list.latestOpenSkyBatchEpoch());
    assertEquals("abc123", list.items().get(0).icao24());

    FlightDetailResponse detail = service.getFlightDetail("abc123", "track");
    assertEquals("abc123", detail.icao24());
    assertEquals("AFR123", detail.callsign());
    assertEquals(48.8566, detail.lat());
    assertEquals(2.3522, detail.lon());
    assertNotNull(detail.recentTrack());
    assertFalse(detail.recentTrack().isEmpty());
    assertEquals(1_706_000_020L, detail.recentTrack().get(0).lastSeen());
  }
}
