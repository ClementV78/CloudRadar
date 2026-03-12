package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenSkyStatesHttpMetricsTest {

  @Test
  void recordsResponsesAndExceptionsByOutcome() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    OpenSkyStatesHttpMetrics metrics = new OpenSkyStatesHttpMetrics(registry);
    long startNs = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(10);

    metrics.recordResponse(startNs, 200, OpenSkyStatesHttpMetrics.Outcome.SUCCESS);
    metrics.recordResponse(startNs, 429, OpenSkyStatesHttpMetrics.Outcome.RATE_LIMITED);
    metrics.recordResponse(startNs, 404, OpenSkyStatesHttpMetrics.Outcome.CLIENT_ERROR);
    metrics.recordResponse(startNs, 503, OpenSkyStatesHttpMetrics.Outcome.SERVER_ERROR);

    assertThat(counter(registry, "success")).isEqualTo(1.0);
    assertThat(counter(registry, "rate_limited")).isEqualTo(1.0);
    assertThat(counter(registry, "client_error")).isEqualTo(1.0);
    assertThat(counter(registry, "server_error")).isEqualTo(1.0);
    assertThat(registry.get("ingester.opensky.states.http.last_status").gauge().value()).isEqualTo(503.0);
    assertThat(registry.get("ingester.opensky.states.http.duration").timer().count()).isEqualTo(4L);

    metrics.recordException(startNs, false);
    assertThat(counter(registry, "exception")).isEqualTo(1.0);
    assertThat(registry.get("ingester.opensky.states.http.last_status").gauge().value()).isEqualTo(0.0);
    assertThat(registry.get("ingester.opensky.states.http.duration").timer().count()).isEqualTo(5L);

    metrics.recordException(startNs, true);
    assertThat(counter(registry, "exception")).isEqualTo(2.0);
    assertThat(registry.get("ingester.opensky.states.http.duration").timer().count()).isEqualTo(5L);
  }

  private double counter(SimpleMeterRegistry registry, String outcome) {
    return registry.get("ingester.opensky.states.http.requests.total")
        .tag("outcome", outcome)
        .counter()
        .count();
  }
}
