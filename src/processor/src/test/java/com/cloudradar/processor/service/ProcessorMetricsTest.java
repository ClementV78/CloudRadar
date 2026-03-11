package com.cloudradar.processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudradar.processor.config.ProcessorProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProcessorMetricsTest {

  private SimpleMeterRegistry registry;
  private ProcessorMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new ProcessorMetrics(registry, new ProcessorProperties());
  }

  @Test
  void incrementProcessed() {
    metrics.incrementProcessed();
    metrics.incrementProcessed();
    assertEquals(2.0, registry.get("processor.events.processed").counter().count());
  }

  @Test
  void incrementError() {
    metrics.incrementError();
    assertEquals(1.0, registry.get("processor.events.errors").counter().count());
  }

  @Test
  void recordCategoryWithValue() {
    metrics.recordCategory("A1");
    assertEquals(1.0, registry.get("processor.aircraft.category.events")
        .tag("category", "A1").counter().count());
  }

  @Test
  void recordCategoryNullFallsBackToUnknown() {
    metrics.recordCategory(null);
    assertEquals(1.0, registry.get("processor.aircraft.category.events")
        .tag("category", "unknown").counter().count());
  }

  @Test
  void recordCountryWithValue() {
    metrics.recordCountry("France");
    assertEquals(1.0, registry.get("processor.aircraft.country.events")
        .tag("country", "France").counter().count());
  }

  @Test
  void recordCountryBlankFallsBackToUnknown() {
    metrics.recordCountry("  ");
    assertEquals(1.0, registry.get("processor.aircraft.country.events")
        .tag("country", "unknown").counter().count());
  }

  @Test
  void recordMilitary() {
    metrics.recordMilitary("true");
    assertEquals(1.0, registry.get("processor.aircraft.military.events")
        .tag("military", "true").counter().count());
  }

  @Test
  void recordMilitaryNullFallsBackToUnknown() {
    metrics.recordMilitary(null);
    assertEquals(1.0, registry.get("processor.aircraft.military.events")
        .tag("military", "unknown").counter().count());
  }

  @Test
  void recordTypecodeValid() {
    metrics.recordTypecode("B738");
    assertEquals(1.0, registry.get("processor.aircraft.military.typecode.events")
        .tag("typecode", "B738").counter().count());
  }

  @Test
  void recordTypecodeTooLongFallsBackToUnknown() {
    metrics.recordTypecode("TOOLONGCODE123");
    assertEquals(1.0, registry.get("processor.aircraft.military.typecode.events")
        .tag("typecode", "unknown").counter().count());
  }

  @Test
  void recordTypecodeNullFallsBackToUnknown() {
    metrics.recordTypecode(null);
    assertEquals(1.0, registry.get("processor.aircraft.military.typecode.events")
        .tag("typecode", "unknown").counter().count());
  }

  @Test
  void recordEnrichmentCoveragePresent() {
    metrics.recordEnrichmentCoverage("year_built", true);
    assertEquals(1.0, registry.get("processor.aircraft.enrichment.events")
        .tag("field", "year_built").tag("status", "present").counter().count());
  }

  @Test
  void recordEnrichmentCoverageMissing() {
    metrics.recordEnrichmentCoverage("owner_operator", false);
    assertEquals(1.0, registry.get("processor.aircraft.enrichment.events")
        .tag("field", "owner_operator").tag("status", "missing").counter().count());
  }

  @Test
  void sanitizeTypecodeTrimsAndUppercases() {
    assertEquals("B738", ProcessorMetrics.sanitizeTypecode("  b738 "));
  }

  @Test
  void sanitizeTypecodeExactly8Chars() {
    assertEquals("ABCDEFGH", ProcessorMetrics.sanitizeTypecode("abcdefgh"));
  }

  @Test
  void sanitizeTypecodeOver8ReturnsUnknown() {
    assertEquals("unknown", ProcessorMetrics.sanitizeTypecode("ABCDEFGHI"));
  }
}
