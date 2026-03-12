package com.cloudradar.ingester.opensky;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
final class OpenSkyStatesHttpMetrics {
  enum Outcome {
    SUCCESS,
    RATE_LIMITED,
    CLIENT_ERROR,
    SERVER_ERROR
  }

  private final Timer statesRequestTimer;
  private final Counter statesRequestSuccessCounter;
  private final Counter statesRequestRateLimitedCounter;
  private final Counter statesRequestClientErrorCounter;
  private final Counter statesRequestServerErrorCounter;
  private final Counter statesRequestExceptionCounter;
  private final AtomicInteger lastStatusCode = new AtomicInteger(0);

  OpenSkyStatesHttpMetrics(MeterRegistry meterRegistry) {
    this.statesRequestTimer = Timer.builder("ingester.opensky.states.http.duration")
        .description("OpenSky /states/all HTTP request duration (seconds)")
        .publishPercentileHistogram(true)
        .register(meterRegistry);

    this.statesRequestSuccessCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "success")
        .register(meterRegistry);
    this.statesRequestRateLimitedCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "rate_limited")
        .register(meterRegistry);
    this.statesRequestClientErrorCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "client_error")
        .register(meterRegistry);
    this.statesRequestServerErrorCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "server_error")
        .register(meterRegistry);
    this.statesRequestExceptionCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "exception")
        .register(meterRegistry);

    meterRegistry.gauge("ingester.opensky.states.http.last_status", lastStatusCode);
  }

  void recordResponse(long httpStartNs, int statusCode, Outcome outcome) {
    lastStatusCode.set(statusCode);
    recordDuration(httpStartNs);
    switch (outcome) {
      case SUCCESS -> statesRequestSuccessCounter.increment();
      case RATE_LIMITED -> statesRequestRateLimitedCounter.increment();
      case CLIENT_ERROR -> statesRequestClientErrorCounter.increment();
      case SERVER_ERROR -> statesRequestServerErrorCounter.increment();
    }
  }

  void recordException(long httpStartNs, boolean requestRecorded) {
    lastStatusCode.set(0);
    if (!requestRecorded && httpStartNs > 0) {
      recordDuration(httpStartNs);
    }
    statesRequestExceptionCounter.increment();
  }

  private void recordDuration(long httpStartNs) {
    statesRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
  }
}
