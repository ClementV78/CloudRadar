package com.cloudradar.processor.service;

import com.cloudradar.processor.aircraft.AircraftMetadata;
import com.cloudradar.processor.aircraft.AircraftMetadataRepository;
import com.cloudradar.processor.config.ProcessorProperties;
import com.cloudradar.processor.service.ActivityBucketKeyResolver.BucketKey;
import com.cloudradar.processor.service.BboxClassifier.BboxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Core event-processing pipeline.
 *
 * <p>Orchestrates: validation, Redis writes, geo-fence classification,
 * activity bucket recording, metadata enrichment, and metric recording.
 */
class EventProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(EventProcessor.class);

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final ProcessorProperties properties;
  private final ProcessorMetrics metrics;
  private final BboxClassifier bboxClassifier;
  private final ActivityBucketKeyResolver bucketKeyResolver;
  private final Optional<AircraftMetadataRepository> aircraftRepo;
  private final LastPositionSnapshotWriter snapshotWriter;

  EventProcessor(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      ProcessorProperties properties,
      ProcessorMetrics metrics,
      BboxClassifier bboxClassifier,
      ActivityBucketKeyResolver bucketKeyResolver,
      Optional<AircraftMetadataRepository> aircraftRepo,
      LastPositionSnapshotWriter snapshotWriter) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.metrics = metrics;
    this.bboxClassifier = bboxClassifier;
    this.bucketKeyResolver = bucketKeyResolver;
    this.aircraftRepo = aircraftRepo;
    this.snapshotWriter = snapshotWriter;
  }

  /** Polls Redis for a payload and processes it if present. Also refreshes queue depth. */
  void pollAndProcess(String inputKey, Duration timeout) {
    String payload = redisTemplate.opsForList().rightPop(inputKey, timeout);
    if (payload != null) {
      process(payload);
    }
    refreshQueueDepth(inputKey);
  }

  /** Handles uncaught exceptions from the main loop. */
  void handleLoopError(Exception ex) {
    metrics.incrementError();
    LOGGER.warn("Processor loop error", ex);
  }

  /**
   * Processes a single JSON payload through the full pipeline:
   * parse → validate icao24 → write last-position hash → append track →
   * classify bbox → enrich from aircraft DB → record activity bucket → update metrics.
   */
  void process(String payload) {
    PositionEvent event;
    try {
      event = objectMapper.readValue(payload, PositionEvent.class);
    } catch (Exception ex) {
      metrics.incrementError();
      LOGGER.debug("Failed to parse payload", ex);
      return;
    }

    if (event.icao24() == null || event.icao24().isBlank()) {
      metrics.incrementError();
      return;
    }

    String redisIcao = event.icao24().trim();
    snapshotWriter.writeLatest(redisIcao, payload);

    if (properties.getTrackLength() > 0) {
      String trackKey = properties.getRedis().getTrackKeyPrefix() + redisIcao;
      long trackEndIndex = (long) properties.getTrackLength() - 1L;
      redisTemplate.opsForList().leftPush(trackKey, payload);
      redisTemplate.opsForList().trim(trackKey, 0L, trackEndIndex);
    }

    updateBboxState(event, redisIcao);

    Optional<AircraftMetadata> metadata = Optional.empty();
    if (aircraftRepo.isPresent()) {
      metadata = aircraftRepo.get().findByIcao24(redisIcao);
      recordAircraftMetrics(metadata);
    }

    long nowEpoch = System.currentTimeMillis() / 1000;
    recordActivityBucket(nowEpoch, redisIcao, metadata);
    metrics.incrementProcessed();
    metrics.updateLastProcessedEpoch(nowEpoch);
  }

  private void updateBboxState(PositionEvent event, String redisIcao) {
    BboxResult result = bboxClassifier.classify(event.lat(), event.lon(), properties.getBbox());
    if (result == BboxResult.UNKNOWN) {
      return;
    }
    if (result == BboxResult.INSIDE) {
      redisTemplate.opsForSet().add(properties.getRedis().getBboxSetKey(), redisIcao);
    } else {
      redisTemplate.opsForSet().remove(properties.getRedis().getBboxSetKey(), redisIcao);
    }
    Long count = redisTemplate.opsForSet().size(properties.getRedis().getBboxSetKey());
    if (count != null) {
      metrics.updateBboxCount(count.intValue());
    }
  }

  private void recordAircraftMetrics(Optional<AircraftMetadata> metadata) {
    metrics.recordCategory(metadata.map(AircraftMetadata::categoryOrFallback).orElse(null));
    metrics.recordCountry(metadata.map(AircraftMetadata::country).orElse(null));

    String militaryLabel = metadata.map(AircraftMetadata::militaryLabel).orElse(null);
    metrics.recordMilitary(militaryLabel);
    if ("true".equals(militaryLabel)) {
      metrics.recordTypecode(metadata.map(AircraftMetadata::typecode).orElse(null));
    }

    metrics.recordEnrichmentCoverage(
        "year_built",
        metadata.map(m -> m.yearBuilt() != null).orElse(false));
    metrics.recordEnrichmentCoverage(
        "owner_operator",
        metadata.map(m -> m.ownerOperator() != null && !m.ownerOperator().isBlank()).orElse(false));
  }

  private void recordActivityBucket(long epochSeconds, String icao24, Optional<AircraftMetadata> metadata) {
    try {
      BucketKey key = bucketKeyResolver.resolve(
          epochSeconds,
          properties.getActivityBucketSeconds(),
          properties.getActivityBucketRetentionSeconds(),
          properties.getRedis().getActivityBucketKeyPrefix());

      redisTemplate.opsForHash().increment(key.hashKey(), "events_total", 1L);
      redisTemplate.opsForHyperLogLog().add(key.hllKey(), icao24);

      if (metadata.map(AircraftMetadata::militaryHint).orElse(false)) {
        redisTemplate.opsForHash().increment(key.hashKey(), "events_military", 1L);
        redisTemplate.opsForHyperLogLog().add(key.militaryHllKey(), icao24);
      }

      Duration ttl = Duration.ofSeconds(key.ttlSeconds());
      redisTemplate.expire(key.hashKey(), ttl);
      redisTemplate.expire(key.hllKey(), ttl);
      redisTemplate.expire(key.militaryHllKey(), ttl);
    } catch (Exception ex) {
      LOGGER.debug("Failed to update activity bucket", ex);
    }
  }

  private void refreshQueueDepth(String inputKey) {
    try {
      Long size = redisTemplate.opsForList().size(inputKey);
      if (size != null) {
        metrics.updateQueueDepth(size);
      }
    } catch (Exception ignored) {
      // ignore errors to avoid impacting the processing loop
    }
  }
}
