package com.cloudradar.ingester;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudradar.ingester.config.IngesterProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class IngesterMetricsTest {

  @Test
  void recordsCountersAndExposesDynamicGauges() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IngestionBackoffController backoffController = new IngestionBackoffController();
    OpenSkyRateLimitTracker rateLimitTracker = new OpenSkyRateLimitTracker(buildProperties());
    IngesterMetrics metrics =
        new IngesterMetrics(registry, buildProperties(), rateLimitTracker, backoffController);

    metrics.recordFetch(4);
    metrics.recordPush(3);
    metrics.recordError();
    rateLimitTracker.recordFetch(4);
    rateLimitTracker.recordSuccessfulCycle(390, 400, null);
    backoffController.recordFailure(System.currentTimeMillis());

    assertThat(registry.get("ingester.fetch.total").counter().count()).isEqualTo(4.0);
    assertThat(registry.get("ingester.fetch.requests.total").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("ingester.push.total").counter().count()).isEqualTo(3.0);
    assertThat(registry.get("ingester.errors.total").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("ingester.opensky.backoff.seconds").gauge().value()).isEqualTo(1.0);
    assertThat(registry.get("ingester.opensky.bbox.area.km2").gauge().value()).isGreaterThan(0.0);
  }

  private IngesterProperties buildProperties() {
    return new IngesterProperties(
        10_000L,
        new IngesterProperties.Redis("cloudradar:ingest:queue"),
        new IngesterProperties.Bbox(46.0, 50.0, 2.0, 4.0),
        new IngesterProperties.RateLimit(4_000L, 50, 80, 95, 30_000L, 300_000L),
        new IngesterProperties.BboxBoost("cloudradar:opensky:bbox:boost:active", 1.0));
  }
}
