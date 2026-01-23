package com.cloudradar.ingester;

import com.cloudradar.ingester.config.IngesterProperties;
import com.cloudradar.ingester.opensky.FetchResult;
import com.cloudradar.ingester.opensky.FlightState;
import com.cloudradar.ingester.opensky.OpenSkyClient;
import com.cloudradar.ingester.redis.RedisPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FlightIngestJob {
  private static final Logger log = LoggerFactory.getLogger(FlightIngestJob.class);

  private enum RateLimitLevel {
    NORMAL,
    WARN_50,
    WARN_80,
    WARN_95
  }

  private final OpenSkyClient openSkyClient;
  private final RedisPublisher redisPublisher;
  private final Counter fetchCounter;
  private final Counter requestCounter;
  private final Counter pushCounter;
  private final Counter errorCounter;
  private final IngesterProperties properties;
  private volatile long currentDelayMs;
  private volatile long nextAllowedAtMs;
  private volatile RateLimitLevel lastRateLevel = RateLimitLevel.NORMAL;
  private final java.util.concurrent.atomic.AtomicLong remainingCredits = new java.util.concurrent.atomic.AtomicLong(-1);
  private final java.util.concurrent.atomic.AtomicLong lastRemainingCredits = new java.util.concurrent.atomic.AtomicLong(-1);
  private final java.util.concurrent.atomic.AtomicLong requestsSinceReset = new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong creditsUsedSinceReset = new java.util.concurrent.atomic.AtomicLong(0);
  private final java.util.concurrent.atomic.AtomicLong eventsSinceReset = new java.util.concurrent.atomic.AtomicLong(0);

  public FlightIngestJob(
      OpenSkyClient openSkyClient,
      RedisPublisher redisPublisher,
      MeterRegistry meterRegistry,
      IngesterProperties properties) {
    this.openSkyClient = openSkyClient;
    this.redisPublisher = redisPublisher;
    this.fetchCounter = meterRegistry.counter("ingester.fetch.total");
    this.requestCounter = meterRegistry.counter("ingester.fetch.requests.total");
    this.pushCounter = meterRegistry.counter("ingester.push.total");
    this.errorCounter = meterRegistry.counter("ingester.errors.total");
    this.properties = properties;
    this.currentDelayMs = properties.refreshMs();
    this.nextAllowedAtMs = 0;

    registerGauges(meterRegistry);
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
    if (now < nextAllowedAtMs) {
      return;
    }
    try {
      // Fetch current states inside the configured bbox, then push as JSON events.
      FetchResult result = openSkyClient.fetchStates();
      List<FlightState> states = result.states();
      requestCounter.increment();
      requestsSinceReset.incrementAndGet();
      eventsSinceReset.addAndGet(states.size());
      fetchCounter.increment(states.size());

      List<Map<String, Object>> payloads = states.stream()
          .map(this::toEvent)
          .collect(Collectors.toList());

      int pushed = redisPublisher.pushEvents(payloads);
      pushCounter.increment(pushed);
      log.info("Fetched {} states, pushed {} events", states.size(), pushed);

      updateCreditTracking(result.remainingCredits());
      updateRateLimit(result.remainingCredits());
    } catch (Exception ex) {
      // Keep the scheduler running even if a cycle fails.
      errorCounter.increment();
      log.error("Ingestion cycle failed", ex);
    } finally {
      nextAllowedAtMs = System.currentTimeMillis() + currentDelayMs;
    }
  }

  private Map<String, Object> toEvent(FlightState state) {
    Map<String, Object> event = new HashMap<>();
    event.put("icao24", state.icao24());
    event.put("callsign", state.callsign());
    event.put("lat", state.latitude());
    event.put("lon", state.longitude());
    event.put("velocity", state.velocity());
    event.put("heading", state.heading());
    event.put("geo_altitude", state.geoAltitude());
    event.put("baro_altitude", state.baroAltitude());
    event.put("on_ground", state.onGround());
    event.put("time_position", state.timePosition());
    event.put("last_contact", state.lastContact());
    return event;
  }

  private void registerGauges(MeterRegistry meterRegistry) {
    meterRegistry.gauge("ingester.opensky.credits.remaining", remainingCredits);
    meterRegistry.gauge("ingester.opensky.requests.since_reset", requestsSinceReset);
    meterRegistry.gauge("ingester.opensky.credits.used.since_reset", creditsUsedSinceReset);
    meterRegistry.gauge("ingester.opensky.events.since_reset", eventsSinceReset);
    meterRegistry.gauge("ingester.opensky.credits.avg_per_request", this, job -> job.averageCreditsPerRequest());
    meterRegistry.gauge("ingester.opensky.events.avg_per_request", this, job -> job.averageEventsPerRequest());
    meterRegistry.gauge("ingester.opensky.bbox.area.square_degrees", this, job -> job.bboxAreaSquareDegrees());
    meterRegistry.gauge("ingester.opensky.quota", this, job -> job.quotaOrDefault());
    meterRegistry.gauge("ingester.opensky.threshold.warn50", this, job -> job.thresholdPercent("warn50"));
    meterRegistry.gauge("ingester.opensky.threshold.warn80", this, job -> job.thresholdPercent("warn80"));
    meterRegistry.gauge("ingester.opensky.threshold.warn95", this, job -> job.thresholdPercent("warn95"));
  }

  private void updateCreditTracking(Integer remaining) {
    if (remaining == null) {
      return;
    }

    long previous = lastRemainingCredits.getAndSet(remaining);
    remainingCredits.set(remaining);

    if (previous < 0) {
      return;
    }

    if (remaining > previous) {
      // Credits replenished (daily reset). Reset per-period counters.
      requestsSinceReset.set(0);
      creditsUsedSinceReset.set(0);
      eventsSinceReset.set(0);
      return;
    }

    long delta = previous - remaining;
    if (delta > 0) {
      creditsUsedSinceReset.addAndGet(delta);
    }
  }

  private double averageCreditsPerRequest() {
    long requests = requestsSinceReset.get();
    if (requests == 0) {
      return 0.0;
    }
    return (double) creditsUsedSinceReset.get() / (double) requests;
  }

  private double averageEventsPerRequest() {
    long requests = requestsSinceReset.get();
    if (requests == 0) {
      return 0.0;
    }
    return (double) eventsSinceReset.get() / (double) requests;
  }

  private double bboxAreaSquareDegrees() {
    IngesterProperties.Bbox bbox = properties.bbox();
    return Math.max(0.0, (bbox.latMax() - bbox.latMin()) * (bbox.lonMax() - bbox.lonMin()));
  }

  private double quotaOrDefault() {
    IngesterProperties.RateLimit rateLimit = properties.rateLimit();
    return rateLimit == null ? 0.0 : rateLimit.quota();
  }

  private double thresholdPercent(String threshold) {
    IngesterProperties.RateLimit rateLimit = properties.rateLimit();
    if (rateLimit == null) {
      return 0.0;
    }
    return switch (threshold) {
      case "warn50" -> rateLimit.warn50();
      case "warn80" -> rateLimit.warn80();
      case "warn95" -> rateLimit.warn95();
      default -> 0.0;
    };
  }

  private void updateRateLimit(Integer remainingCredits) {
    IngesterProperties.RateLimit rateLimit = properties.rateLimit();
    if (rateLimit == null || remainingCredits == null || rateLimit.quota() <= 0) {
      return;
    }

    long quota = rateLimit.quota();
    long remaining = remainingCredits;
    double consumed = ((double) (quota - remaining) / (double) quota) * 100.0;
    if (consumed < 0) {
      consumed = 0;
    }
    if (consumed > 100) {
      consumed = 100;
    }

    RateLimitLevel level = resolveLevel(consumed, rateLimit);
    if (level != lastRateLevel) {
      log.warn(
          "OpenSky credits consumed {}% (remaining: {}). Threshold reached: {}%",
          String.format("%.1f", consumed),
          remaining,
          levelToPercent(level, rateLimit));
      lastRateLevel = level;
    }

    long newDelayMs = resolveDelay(level, rateLimit);
    if (newDelayMs != currentDelayMs) {
      log.info("Adjusting refresh interval to {}s based on OpenSky credit usage", newDelayMs / 1000);
      currentDelayMs = newDelayMs;
    }
  }

  private RateLimitLevel resolveLevel(double consumed, IngesterProperties.RateLimit rateLimit) {
    if (consumed >= rateLimit.warn95()) {
      return RateLimitLevel.WARN_95;
    }
    if (consumed >= rateLimit.warn80()) {
      return RateLimitLevel.WARN_80;
    }
    if (consumed >= rateLimit.warn50()) {
      return RateLimitLevel.WARN_50;
    }
    return RateLimitLevel.NORMAL;
  }

  private int levelToPercent(RateLimitLevel level, IngesterProperties.RateLimit rateLimit) {
    return switch (level) {
      case WARN_95 -> rateLimit.warn95();
      case WARN_80 -> rateLimit.warn80();
      case WARN_50 -> rateLimit.warn50();
      default -> 0;
    };
  }

  private long resolveDelay(RateLimitLevel level, IngesterProperties.RateLimit rateLimit) {
    if (level == RateLimitLevel.WARN_95) {
      return rateLimit.refreshCriticalMs();
    }
    if (level == RateLimitLevel.WARN_80) {
      return rateLimit.refreshWarnMs();
    }
    return properties.refreshMs();
  }
}
