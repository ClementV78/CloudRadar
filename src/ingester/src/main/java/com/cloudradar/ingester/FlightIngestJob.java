package com.cloudradar.ingester;

import com.cloudradar.ingester.config.IngesterProperties;
import com.cloudradar.ingester.opensky.FetchResult;
import com.cloudradar.ingester.opensky.FlightState;
import com.cloudradar.ingester.opensky.OpenSkyClient;
import com.cloudradar.ingester.redis.RedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FlightIngestJob {
  private static final Logger log = LoggerFactory.getLogger(FlightIngestJob.class);

  private final OpenSkyClient openSkyClient;
  private final RedisPublisher redisPublisher;
  private final FlightEventMapper eventMapper;
  private final OpenSkyRateLimitTracker rateLimitTracker;
  private final IngestionBackoffController backoffController;
  private final IngesterMetrics metrics;
  private final IngesterProperties properties;

  @Autowired
  public FlightIngestJob(
      OpenSkyClient openSkyClient,
      RedisPublisher redisPublisher,
      MeterRegistry meterRegistry,
      IngesterProperties properties) {
    this(
        openSkyClient,
        redisPublisher,
        new FlightEventMapper(),
        new OpenSkyRateLimitTracker(properties),
        new IngestionBackoffController(),
        properties,
        meterRegistry);
  }

  FlightIngestJob(
      OpenSkyClient openSkyClient,
      RedisPublisher redisPublisher,
      FlightEventMapper eventMapper,
      OpenSkyRateLimitTracker rateLimitTracker,
      IngestionBackoffController backoffController,
      IngesterProperties properties,
      MeterRegistry meterRegistry) {
    this.openSkyClient = openSkyClient;
    this.redisPublisher = redisPublisher;
    this.eventMapper = eventMapper;
    this.rateLimitTracker = rateLimitTracker;
    this.backoffController = backoffController;
    this.properties = properties;
    this.metrics =
        new IngesterMetrics(meterRegistry, properties, this.rateLimitTracker, this.backoffController);
  }

  @PostConstruct
  public void logBboxConfig() {
    IngesterProperties.Bbox bbox = properties.bbox();
    log.info(
        "OpenSky bbox configured: latMin={}, latMax={}, lonMin={}, lonMax={}",
        bbox.latMin(),
        bbox.latMax(),
        bbox.lonMin(),
        bbox.lonMax());
  }

  @Scheduled(fixedDelayString = "${ingester.refresh-ms}")
  public void ingest() {
    long now = System.currentTimeMillis();
    if (backoffController.shouldSkipCycle(now)) {
      return;
    }

    try {
      FetchResult result = openSkyClient.fetchStates();
      List<FlightState> states = result.states();
      rateLimitTracker.recordFetch(states.size());
      metrics.recordFetch(states.size());

      long openskyFetchEpoch = System.currentTimeMillis() / 1000;
      List<Map<String, Object>> payloads = eventMapper.toEvents(states, openskyFetchEpoch);

      int pushed = redisPublisher.pushEvents(payloads);
      metrics.recordPush(pushed);
      log.info("Fetched {} states, pushed {} events", states.size(), pushed);

      rateLimitTracker.recordSuccessfulCycle(
          result.remainingCredits(),
          result.creditLimit(),
          result.resetAtEpochSeconds());
      backoffController.recordSuccess(System.currentTimeMillis(), rateLimitTracker.currentDelayMs());
    } catch (Exception ex) {
      metrics.recordError();
      log.error("Ingestion cycle failed", ex);
      IngestionBackoffController.FailureDecision decision =
          backoffController.recordFailure(System.currentTimeMillis());
      if (decision.disabled()) {
        log.error(
            "OpenSky fetch failed {} times. Disabling ingestion until restart.",
            decision.failureCount());
        return;
      }
      log.warn(
          "OpenSky fetch failed (attempt {}), backing off for {} ms",
          decision.failureCount(),
          decision.backoffMs());
    }
  }
}
