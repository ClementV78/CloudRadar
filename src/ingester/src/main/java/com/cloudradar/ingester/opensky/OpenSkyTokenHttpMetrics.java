package com.cloudradar.ingester.opensky;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
final class OpenSkyTokenHttpMetrics {
  private final Timer tokenRequestTimer;
  private final Counter tokenRequestSuccessCounter;
  private final Counter tokenRequestClientErrorCounter;
  private final Counter tokenRequestServerErrorCounter;
  private final Counter tokenRequestExceptionCounter;

  OpenSkyTokenHttpMetrics(MeterRegistry meterRegistry) {
    this.tokenRequestTimer = Timer.builder("ingester.opensky.token.http.duration")
        .description("OpenSky token HTTP request duration (seconds)")
        .publishPercentileHistogram(true)
        .register(meterRegistry);

    this.tokenRequestSuccessCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "success")
        .register(meterRegistry);
    this.tokenRequestClientErrorCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "client_error")
        .register(meterRegistry);
    this.tokenRequestServerErrorCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "server_error")
        .register(meterRegistry);
    this.tokenRequestExceptionCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "exception")
        .register(meterRegistry);
  }

  void recordResponse(long httpStartNs, int statusCode) {
    recordDuration(httpStartNs);
    if (statusCode == 200) {
      tokenRequestSuccessCounter.increment();
      return;
    }
    if (statusCode >= 500) {
      tokenRequestServerErrorCounter.increment();
      return;
    }
    tokenRequestClientErrorCounter.increment();
  }

  void recordException(long httpStartNs, boolean requestRecorded) {
    if (!requestRecorded && httpStartNs > 0) {
      recordDuration(httpStartNs);
    }
    tokenRequestExceptionCounter.increment();
  }

  void incrementException() {
    tokenRequestExceptionCounter.increment();
  }

  private void recordDuration(long httpStartNs) {
    tokenRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
  }
}
