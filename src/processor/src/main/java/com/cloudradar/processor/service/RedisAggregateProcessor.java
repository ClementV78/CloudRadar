package com.cloudradar.processor.service;

import com.cloudradar.processor.aircraft.AircraftMetadataRepository;
import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Lifecycle manager for the Redis event-processing loop. */
@Component
public class RedisAggregateProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisAggregateProcessor.class);

  private final ProcessorProperties properties;
  private final ExecutorService executor;
  private final EventProcessor eventProcessor;

  public RedisAggregateProcessor(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProcessorProperties properties,
      MeterRegistry meterRegistry,
      Optional<AircraftMetadataRepository> aircraftRepo) {
    this.properties = properties;
    this.executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "processor-loop");
      thread.setDaemon(true);
      return thread;
    });
    ProcessorMetrics metrics = new ProcessorMetrics(meterRegistry, properties);
    this.eventProcessor = new EventProcessor(
        redisTemplate, objectMapper, properties, metrics,
        new BboxClassifier(),
        new ActivityBucketKeyResolver(),
        aircraftRepo,
        new LastPositionSnapshotWriter(redisTemplate, objectMapper, properties));
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
        eventProcessor.pollAndProcess(properties.getRedis().getInputKey(), timeout);
      } catch (Exception ex) {
        if (isInterruptedShutdown(ex)) {
          Thread.currentThread().interrupt();
          LOGGER.debug("Processor loop interrupted during shutdown");
          return;
        }
        eventProcessor.handleLoopError(ex);
      }
    }
  }

  static boolean isInterruptedShutdown(Throwable ex) {
    Throwable current = ex;
    while (current != null) {
      if (current instanceof InterruptedException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
