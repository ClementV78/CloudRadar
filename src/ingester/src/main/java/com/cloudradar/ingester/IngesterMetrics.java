package com.cloudradar.ingester;

import com.cloudradar.ingester.config.IngesterProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

class IngesterMetrics {
  private final Counter fetchCounter;
  private final Counter requestCounter;
  private final Counter pushCounter;
  private final Counter errorCounter;
  private final IngesterProperties properties;

  IngesterMetrics(
      MeterRegistry meterRegistry,
      IngesterProperties properties,
      OpenSkyRateLimitTracker rateLimitTracker,
      IngestionBackoffController backoffController) {
    this.fetchCounter = meterRegistry.counter("ingester.fetch.total");
    this.requestCounter = meterRegistry.counter("ingester.fetch.requests.total");
    this.pushCounter = meterRegistry.counter("ingester.push.total");
    this.errorCounter = meterRegistry.counter("ingester.errors.total");
    this.properties = properties;

    meterRegistry.gauge(
        "ingester.opensky.credits.remaining",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.REMAINING_CREDITS));
    meterRegistry.gauge(
        "ingester.opensky.credits.limit",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.CREDIT_LIMIT));
    meterRegistry.gauge(
        "ingester.opensky.requests.since_reset",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.REQUESTS_SINCE_RESET));
    meterRegistry.gauge(
        "ingester.opensky.credits.used.since_reset",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.CREDITS_USED_SINCE_RESET));
    meterRegistry.gauge(
        "ingester.opensky.credits.consumed.percent",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.CONSUMED_PERCENT));
    meterRegistry.gauge(
        "ingester.opensky.events.since_reset",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.EVENTS_SINCE_RESET));
    meterRegistry.gauge(
        "ingester.opensky.states.last_count",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.LAST_STATES_COUNT));
    meterRegistry.gauge(
        "ingester.opensky.credits.avg_per_request",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.AVERAGE_CREDITS_PER_REQUEST));
    meterRegistry.gauge(
        "ingester.opensky.events.avg_per_request",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.AVERAGE_EVENTS_PER_REQUEST));
    meterRegistry.gauge("ingester.opensky.bbox.area.square_degrees", this, IngesterMetrics::bboxAreaSquareDegrees);
    meterRegistry.gauge("ingester.opensky.bbox.area.km2", this, IngesterMetrics::bboxAreaKm2);
    meterRegistry.gauge(
        "ingester.opensky.reset.eta_seconds",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.RESET_ETA_SECONDS));
    meterRegistry.gauge(
        "ingester.opensky.quota",
        rateLimitTracker,
        tracker -> tracker.metric(OpenSkyRateLimitTracker.Metric.QUOTA));
    meterRegistry.gauge(
        "ingester.opensky.threshold.warn50",
        rateLimitTracker,
        tracker -> tracker.thresholdPercent(OpenSkyRateLimitTracker.Threshold.WARN50));
    meterRegistry.gauge(
        "ingester.opensky.threshold.warn80",
        rateLimitTracker,
        tracker -> tracker.thresholdPercent(OpenSkyRateLimitTracker.Threshold.WARN80));
    meterRegistry.gauge(
        "ingester.opensky.threshold.warn95",
        rateLimitTracker,
        tracker -> tracker.thresholdPercent(OpenSkyRateLimitTracker.Threshold.WARN95));
    meterRegistry.gauge(
        "ingester.opensky.backoff.seconds",
        backoffController,
        controller -> (double) controller.currentBackoffSeconds());
    meterRegistry.gauge(
        "ingester.opensky.disabled",
        backoffController,
        controller -> (double) controller.disabledGaugeValue());
  }

  void recordFetch(int statesCount) {
    fetchCounter.increment(statesCount);
    requestCounter.increment();
  }

  void recordPush(int pushedCount) {
    pushCounter.increment(pushedCount);
  }

  void recordError() {
    errorCounter.increment();
  }

  private double bboxAreaSquareDegrees() {
    IngesterProperties.Bbox bbox = properties.bbox();
    return Math.max(0.0, (bbox.latMax() - bbox.latMin()) * (bbox.lonMax() - bbox.lonMin()));
  }

  private double bboxAreaKm2() {
    IngesterProperties.Bbox bbox = properties.bbox();
    double latMin = bbox.latMin();
    double latMax = bbox.latMax();
    double lonMin = bbox.lonMin();
    double lonMax = bbox.lonMax();

    double dLat = Math.max(0.0, latMax - latMin);
    double dLon = Math.max(0.0, lonMax - lonMin);

    double latMidRad = Math.toRadians((latMin + latMax) / 2.0);
    double heightKm = dLat * 110.574;
    double widthKm = dLon * 111.320 * Math.cos(latMidRad);
    return Math.max(0.0, widthKm * heightKm);
  }
}
