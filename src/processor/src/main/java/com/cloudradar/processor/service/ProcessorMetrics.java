package com.cloudradar.processor.service;

import com.cloudradar.processor.config.ProcessorProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages all Micrometer counters and gauges for the processor service.
 *
 * <p>Encapsulates counter maps and gauge atoms so that the main processor
 * component stays focused on lifecycle orchestration.
 */
class ProcessorMetrics {

  private static final String UNKNOWN = "unknown";

  private final MeterRegistry meterRegistry;
  private final Counter processedCounter;
  private final Counter errorCounter;
  private final ConcurrentHashMap<String, Counter> categoryCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> militaryCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> countryCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> militaryTypecodeCounters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> enrichmentCounters = new ConcurrentHashMap<>();
  private final AtomicInteger bboxCount;
  private final AtomicLong lastProcessedEpoch;
  private final AtomicLong queueDepth;

  ProcessorMetrics(MeterRegistry meterRegistry, ProcessorProperties properties) {
    this.meterRegistry = meterRegistry;
    this.processedCounter = meterRegistry.counter("processor.events.processed");
    this.errorCounter = meterRegistry.counter("processor.events.errors");
    this.bboxCount = meterRegistry.gauge("processor.bbox.count", new AtomicInteger(0));
    this.lastProcessedEpoch = meterRegistry.gauge("processor.last_processed_epoch", new AtomicLong(0));
    this.queueDepth = meterRegistry.gauge("processor.queue.depth", new AtomicLong(0));
    meterRegistry.gauge(
        "processor.aircraft_db.enabled",
        properties.getAircraftDb(),
        db -> db.isEnabled() ? 1 : 0);
  }

  void incrementProcessed() {
    processedCounter.increment();
  }

  void incrementError() {
    errorCounter.increment();
  }

  void updateLastProcessedEpoch(long epochSeconds) {
    lastProcessedEpoch.set(epochSeconds);
  }

  void updateQueueDepth(long depth) {
    queueDepth.set(depth);
  }

  void updateBboxCount(int count) {
    bboxCount.set(count);
  }

  void recordCategory(String category) {
    String safe = (category != null && !category.isBlank()) ? category : UNKNOWN;
    Counter counter = categoryCounters.computeIfAbsent(
        safe,
        c -> meterRegistry.counter("processor.aircraft.category.events", "category", c));
    counter.increment();
  }

  void recordCountry(String country) {
    String safe = (country != null && !country.isBlank()) ? country.trim() : UNKNOWN;
    Counter counter = countryCounters.computeIfAbsent(
        safe,
        c -> meterRegistry.counter("processor.aircraft.country.events", "country", c));
    counter.increment();
  }

  void recordMilitary(String militaryLabel) {
    String safe = (militaryLabel != null) ? militaryLabel : UNKNOWN;
    Counter counter = militaryCounters.computeIfAbsent(
        safe,
        label -> meterRegistry.counter("processor.aircraft.military.events", "military", label));
    counter.increment();
  }

  void recordTypecode(String typecode) {
    String safe = sanitizeTypecode(typecode);
    Counter counter = militaryTypecodeCounters.computeIfAbsent(
        safe,
        code -> meterRegistry.counter("processor.aircraft.military.typecode.events", "typecode", code));
    counter.increment();
  }

  void recordEnrichmentCoverage(String field, boolean present) {
    String status = present ? "present" : "missing";
    String key = field + ":" + status;
    Counter counter = enrichmentCounters.computeIfAbsent(
        key,
        ignored -> meterRegistry.counter(
            "processor.aircraft.enrichment.events",
            "field", field,
            "status", status));
    counter.increment();
  }

  static String sanitizeTypecode(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    String trimmed = raw.trim().toUpperCase();
    return trimmed.length() <= 8 ? trimmed : UNKNOWN;
  }
}
