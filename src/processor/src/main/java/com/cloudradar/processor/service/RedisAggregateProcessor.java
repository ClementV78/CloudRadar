package com.cloudradar.processor.service;

import com.cloudradar.processor.aircraft.AircraftMetadataRepository;
import com.cloudradar.processor.aircraft.AircraftMetadata;
import com.cloudradar.processor.config.ProcessorProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Main processing loop for Redis-backed event aggregation.
 *
 * <p>This component:
 * <ul>
 *   <li>consumes telemetry payloads from the Redis input list</li>
 *   <li>updates last-position / track / bbox aggregates</li>
 *   <li>emits low-cardinality operational and enrichment metrics</li>
 * </ul>
 */
@Component
public class RedisAggregateProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisAggregateProcessor.class);
  private static final String UNKNOWN = "unknown";
  private static final String AIRCRAFT_HLL_SUFFIX = ":aircraft_hll";
  private static final String AIRCRAFT_MILITARY_HLL_SUFFIX = ":aircraft_military_hll";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ProcessorProperties properties;
  private final ExecutorService executor;
  private final MeterRegistry meterRegistry;
  private final Optional<AircraftMetadataRepository> aircraftRepo;
  private final Counter processedCounter;
  private final Counter errorCounter;
  private final ConcurrentHashMap<String, Counter> categoryCounters;
  private final ConcurrentHashMap<String, Counter> militaryCounters;
  private final ConcurrentHashMap<String, Counter> countryCounters;
  private final ConcurrentHashMap<String, Counter> militaryTypecodeCounters;
  private final ConcurrentHashMap<String, Counter> enrichmentCounters;
  private final AtomicInteger bboxCount;
  private final AtomicLong lastProcessedEpoch;
  private final AtomicLong queueDepth;

  public RedisAggregateProcessor(
    StringRedisTemplate redisTemplate,
    ObjectMapper objectMapper,
    ProcessorProperties properties,
    MeterRegistry meterRegistry,
    Optional<AircraftMetadataRepository> aircraftRepo
  ) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.executor = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "processor-loop");
      thread.setDaemon(true);
      return thread;
    });
    this.meterRegistry = meterRegistry;
    this.aircraftRepo = aircraftRepo;
    this.processedCounter = meterRegistry.counter("processor.events.processed");
    this.errorCounter = meterRegistry.counter("processor.events.errors");
    this.categoryCounters = new ConcurrentHashMap<>();
    this.militaryCounters = new ConcurrentHashMap<>();
    this.countryCounters = new ConcurrentHashMap<>();
    this.militaryTypecodeCounters = new ConcurrentHashMap<>();
    this.enrichmentCounters = new ConcurrentHashMap<>();
    this.bboxCount = meterRegistry.gauge("processor.bbox.count", new AtomicInteger(0));
    this.lastProcessedEpoch = meterRegistry.gauge("processor.last_processed_epoch", new AtomicLong(0));
    this.queueDepth = meterRegistry.gauge("processor.queue.depth", new AtomicLong(0));
    meterRegistry.gauge(
        "processor.aircraft_db.enabled",
        properties.getAircraftDb(),
        db -> db.isEnabled() ? 1 : 0
    );
  }

  /** Starts the background processing loop after Spring context initialization. */
  @jakarta.annotation.PostConstruct
  public void start() {
    executor.submit(this::runLoop);
  }

  /** Stops the processing loop and waits briefly for a clean shutdown. */
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
          refreshQueueDepth();
        } else {
          processPayload(payload);
          refreshQueueDepth();
        }
      } catch (Exception ex) {
        if (isInterruptedShutdown(ex)) {
          Thread.currentThread().interrupt();
          LOGGER.debug("Processor loop interrupted during shutdown");
          return;
        }
        errorCounter.increment();
        LOGGER.warn("Processor loop error", ex);
      }
    }
  }

  private static boolean isInterruptedShutdown(Throwable ex) {
    Throwable current = ex;
    while (current != null) {
      if (current instanceof InterruptedException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
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
      long trackEndIndex = (long) properties.getTrackLength() - 1L;
      redisTemplate.opsForList().leftPush(trackKey, payload);
      redisTemplate.opsForList().trim(trackKey, 0L, trackEndIndex);
    }

    updateBboxState(event, redisIcao);
    Optional<AircraftMetadata> metadata = Optional.empty();
    if (aircraftRepo.isPresent()) {
      metadata = resolveMetadata(redisIcao);
      recordAircraftCategory(metadata);
      recordAircraftCountry(metadata);
      recordAircraftEnrichment(metadata);
    }
    recordActivityBucket(System.currentTimeMillis() / 1000, redisIcao, metadata);
    processedCounter.increment();
    lastProcessedEpoch.set(System.currentTimeMillis() / 1000);
  }

  private void recordActivityBucket(long epochSeconds, String icao24, Optional<AircraftMetadata> metadata) {
    try {
      long bucketSeconds = Math.max(1L, properties.getActivityBucketSeconds());
      long bucketStart = (epochSeconds / bucketSeconds) * bucketSeconds;
      String bucketKeyPrefix = properties.getRedis().getActivityBucketKeyPrefix() + bucketStart;
      redisTemplate.opsForHash().increment(bucketKeyPrefix, "events_total", 1L);
      redisTemplate.opsForHyperLogLog().add(bucketKeyPrefix + AIRCRAFT_HLL_SUFFIX, icao24);

      if (metadata.map(AircraftMetadata::militaryHint).orElse(false)) {
        redisTemplate.opsForHash().increment(bucketKeyPrefix, "events_military", 1L);
        redisTemplate.opsForHyperLogLog().add(bucketKeyPrefix + AIRCRAFT_MILITARY_HLL_SUFFIX, icao24);
      }

      long ttlSeconds = Math.max(
          bucketSeconds,
          properties.getActivityBucketRetentionSeconds() + bucketSeconds
      );
      Duration ttl = Duration.ofSeconds(ttlSeconds);
      redisTemplate.expire(bucketKeyPrefix, ttl);
      redisTemplate.expire(bucketKeyPrefix + AIRCRAFT_HLL_SUFFIX, ttl);
      redisTemplate.expire(bucketKeyPrefix + AIRCRAFT_MILITARY_HLL_SUFFIX, ttl);
    } catch (Exception ex) {
      LOGGER.debug("Failed to update activity bucket", ex);
    }
  }

  private Optional<AircraftMetadata> resolveMetadata(String icao24) {
    if (aircraftRepo.isEmpty()) {
      return Optional.empty();
    }
    return aircraftRepo.get().findByIcao24(icao24);
  }

  private void recordAircraftCategory(Optional<AircraftMetadata> metadata) {
    String category = metadata
        .map(meta -> meta.categoryOrFallback())
        .orElse(UNKNOWN);

    // Low-cardinality label value intended for Top-N dashboard panels.
    Counter counter = categoryCounters.computeIfAbsent(
        category,
        c -> meterRegistry.counter("processor.aircraft.category.events", "category", c)
    );
    counter.increment();
  }

  private void recordAircraftCountry(Optional<AircraftMetadata> metadata) {
    String country = metadata
        .map(AircraftMetadata::country)
        .filter(v -> v != null && !v.isBlank())
        .map(String::trim)
        .orElse(UNKNOWN);
    Counter counter = countryCounters.computeIfAbsent(
        country,
        c -> meterRegistry.counter("processor.aircraft.country.events", "country", c)
    );
    counter.increment();
  }

  private void recordAircraftEnrichment(Optional<AircraftMetadata> metadata) {
    String militaryLabel = metadata
        .map(AircraftMetadata::militaryLabel)
        .orElse(UNKNOWN);
    Counter militaryCounter = militaryCounters.computeIfAbsent(
        militaryLabel,
        label -> meterRegistry.counter("processor.aircraft.military.events", "military", label)
    );
    militaryCounter.increment();
    if ("true".equals(militaryLabel)) {
      String typecode = metadata
          .map(AircraftMetadata::typecode)
          .filter(v -> v != null && !v.isBlank())
          .map(String::trim)
          .map(String::toUpperCase)
          .filter(v -> v.length() <= 8)
          .orElse(UNKNOWN);
      Counter typeCounter = militaryTypecodeCounters.computeIfAbsent(
          typecode,
          code -> meterRegistry.counter("processor.aircraft.military.typecode.events", "typecode", code)
      );
      typeCounter.increment();
    }

    recordEnrichmentCoverage(
        "year_built",
        metadata.map(m -> m.yearBuilt() != null).orElse(false)
    );
    recordEnrichmentCoverage(
        "owner_operator",
        metadata.map(m -> m.ownerOperator() != null && !m.ownerOperator().isBlank()).orElse(false)
    );
  }

  private void recordEnrichmentCoverage(String field, boolean present) {
    String status = present ? "present" : "missing";
    String key = field + ":" + status;
    Counter counter = enrichmentCounters.computeIfAbsent(
        key,
        ignored -> meterRegistry.counter(
            "processor.aircraft.enrichment.events",
            "field", field,
            "status", status
        )
    );
    counter.increment();
  }

  private void refreshQueueDepth() {
    try {
      Long size = redisTemplate.opsForList().size(properties.getRedis().getInputKey());
      if (size != null) {
        queueDepth.set(size);
      }
    } catch (Exception ignored) {
      // ignore errors to avoid impacting the processing loop
    }
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
