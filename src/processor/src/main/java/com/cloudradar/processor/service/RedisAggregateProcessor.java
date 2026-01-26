package com.cloudradar.processor.service;

import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisAggregateProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisAggregateProcessor.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ProcessorProperties properties;
  private final ExecutorService executor;
  private final Counter processedCounter;
  private final Counter errorCounter;
  private final AtomicInteger bboxCount;
  private final AtomicLong lastProcessedEpoch;

  public RedisAggregateProcessor(
    StringRedisTemplate redisTemplate,
    ObjectMapper objectMapper,
    ProcessorProperties properties,
    MeterRegistry meterRegistry
  ) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "processor-loop");
      thread.setDaemon(true);
      return thread;
    });
    this.processedCounter = meterRegistry.counter("processor.events.processed");
    this.errorCounter = meterRegistry.counter("processor.events.errors");
    this.bboxCount = meterRegistry.gauge("processor.bbox.count", new AtomicInteger(0));
    this.lastProcessedEpoch = meterRegistry.gauge("processor.last_processed_epoch", new AtomicLong(0));
  }

  @jakarta.annotation.PostConstruct
  public void start() {
    executor.submit(this::runLoop);
  }

  @jakarta.annotation.PreDestroy
  public void stop() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void runLoop() {
    Duration timeout = Duration.ofSeconds(properties.getPollTimeoutSeconds());
    while (!Thread.currentThread().isInterrupted()) {
      try {
        String payload = redisTemplate.opsForList().rightPop(
          properties.getRedis().getInputKey(),
          timeout
        );
        if (payload == null) {
          continue;
        }
        processPayload(payload);
      } catch (Exception ex) {
        errorCounter.increment();
        LOGGER.warn("Processor loop error", ex);
      }
    }
  }

  private void processPayload(String payload) {
    PositionEvent event;
    try {
      event = objectMapper.readValue(payload, PositionEvent.class);
    } catch (Exception ex) {
      errorCounter.increment();
      LOGGER.debug("Failed to parse payload", ex);
      return;
    }

    if (event.icao24() == null || event.icao24().isBlank()) {
      errorCounter.increment();
      return;
    }

    String redisIcao = event.icao24().trim();
    redisTemplate.opsForHash().put(properties.getRedis().getLastPositionsKey(), redisIcao, payload);

    if (properties.getTrackLength() > 0) {
      String trackKey = properties.getRedis().getTrackKeyPrefix() + redisIcao;
      redisTemplate.opsForList().leftPush(trackKey, payload);
      redisTemplate.opsForList().trim(trackKey, 0, properties.getTrackLength() - 1);
    }

    updateBboxState(event, redisIcao);
    processedCounter.increment();
    lastProcessedEpoch.set(System.currentTimeMillis() / 1000);
  }

  private void updateBboxState(PositionEvent event, String redisIcao) {
    if (event.lat() == null || event.lon() == null) {
      return;
    }

    boolean inside = event.lat() >= properties.getBbox().getLatMin()
      && event.lat() <= properties.getBbox().getLatMax()
      && event.lon() >= properties.getBbox().getLonMin()
      && event.lon() <= properties.getBbox().getLonMax();

    if (inside) {
      redisTemplate.opsForSet().add(properties.getRedis().getBboxSetKey(), redisIcao);
    } else {
      redisTemplate.opsForSet().remove(properties.getRedis().getBboxSetKey(), redisIcao);
    }

    Long count = redisTemplate.opsForSet().size(properties.getRedis().getBboxSetKey());
    if (count != null) {
      bboxCount.set(count.intValue());
    }
  }
}
