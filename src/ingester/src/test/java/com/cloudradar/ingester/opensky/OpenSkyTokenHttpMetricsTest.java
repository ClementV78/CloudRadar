package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenSkyTokenHttpMetricsTest {

  @Test
  void recordsTokenResponsesAndExceptions() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OpenSkyTokenHttpMetrics metrics = new OpenSkyTokenHttpMetrics(registry);
    long startNs = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(10);

    metrics.recordResponse(startNs, 200);
    metrics.recordResponse(startNs, 401);
    metrics.recordResponse(startNs, 503);

    assertThat(counter(registry, "success")).isEqualTo(1.0);
    assertThat(counter(registry, "client_error")).isEqualTo(1.0);
    assertThat(counter(registry, "server_error")).isEqualTo(1.0);
    assertThat(registry.get("ingester.opensky.token.http.duration").timer().count()).isEqualTo(3L);

    metrics.recordException(startNs, false);
    assertThat(counter(registry, "exception")).isEqualTo(1.0);
    assertThat(registry.get("ingester.opensky.token.http.duration").timer().count()).isEqualTo(4L);

    metrics.recordException(startNs, true);
    assertThat(counter(registry, "exception")).isEqualTo(2.0);
    assertThat(registry.get("ingester.opensky.token.http.duration").timer().count()).isEqualTo(4L);

    metrics.incrementException();
    assertThat(counter(registry, "exception")).isEqualTo(3.0);
  }

  private double counter(SimpleMeterRegistry registry, String outcome) {
    return registry.get("ingester.opensky.token.http.requests.total")
        .tag("outcome", outcome)
        .counter()
        .count();
  }
}
