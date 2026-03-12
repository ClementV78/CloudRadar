package com.cloudradar.ingester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudradar.ingester.config.IngesterProperties;
import com.cloudradar.ingester.opensky.FetchResult;
import com.cloudradar.ingester.opensky.FlightState;
import com.cloudradar.ingester.opensky.OpenSkyClient;
import com.cloudradar.ingester.redis.RedisPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlightIngestJobTest {

  @Test
  void ingestPublishesMappedEventsOnSuccessfulCycle() {
    OpenSkyClient openSkyClient = mock(OpenSkyClient.class);
    RedisPublisher redisPublisher = mock(RedisPublisher.class);
    when(openSkyClient.fetchStates()).thenReturn(new FetchResult(
        List.of(new FlightState("abc123", "AFR123", 48.0, 2.0, 230.0, 180.0, 11000.0, 10900.0, false, 1700L, 1701L)),
        399,
        400,
        2000L));
    when(redisPublisher.pushEvents(anyList())).thenReturn(1);

    IngestionBackoffController backoffController = new IngestionBackoffController();
    FlightIngestJob job = new FlightIngestJob(
        openSkyClient,
        redisPublisher,
        new FlightEventMapper(),
        new OpenSkyRateLimitTracker(buildProperties()),
        backoffController,
        buildProperties(),
        new SimpleMeterRegistry());

    job.ingest();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> payloadCaptor = ArgumentCaptor.forClass(List.class);
    verify(redisPublisher).pushEvents(payloadCaptor.capture());
    assertThat(payloadCaptor.getValue()).hasSize(1);
    assertThat(payloadCaptor.getValue().get(0))
        .containsEntry("icao24", "abc123")
        .containsEntry("callsign", "AFR123")
        .containsKey("opensky_fetch_epoch");
    assertThat(backoffController.currentBackoffSeconds()).isZero();
  }

  @Test
  void ingestAppliesBackoffWhenFetchThrows() {
    OpenSkyClient openSkyClient = mock(OpenSkyClient.class);
    RedisPublisher redisPublisher = mock(RedisPublisher.class);
    when(openSkyClient.fetchStates()).thenThrow(new RuntimeException("opensky down"));

    IngestionBackoffController backoffController = new IngestionBackoffController();
    FlightIngestJob job = new FlightIngestJob(
        openSkyClient,
        redisPublisher,
        new FlightEventMapper(),
        new OpenSkyRateLimitTracker(buildProperties()),
        backoffController,
        buildProperties(),
        new SimpleMeterRegistry());

    job.ingest();

    assertThat(backoffController.currentBackoffSeconds()).isEqualTo(1L);
    verify(redisPublisher, never()).pushEvents(anyList());
  }

  @Test
  void ingestSkipsWhenBackoffWindowIsActive() {
    OpenSkyClient openSkyClient = mock(OpenSkyClient.class);
    RedisPublisher redisPublisher = mock(RedisPublisher.class);

    IngestionBackoffController backoffController = new IngestionBackoffController();
    backoffController.recordFailure(System.currentTimeMillis());

    FlightIngestJob job = new FlightIngestJob(
        openSkyClient,
        redisPublisher,
        new FlightEventMapper(),
        new OpenSkyRateLimitTracker(buildProperties()),
        backoffController,
        buildProperties(),
        new SimpleMeterRegistry());

    job.ingest();

    verify(openSkyClient, never()).fetchStates();
  }

  @Test
  void ingestSkipsWhenBackoffControllerIsDisabled() {
    OpenSkyClient openSkyClient = mock(OpenSkyClient.class);
    RedisPublisher redisPublisher = mock(RedisPublisher.class);

    IngestionBackoffController backoffController = new IngestionBackoffController();
    for (int i = 0; i < 11; i++) {
      backoffController.recordFailure(System.currentTimeMillis());
    }

    FlightIngestJob job = new FlightIngestJob(
        openSkyClient,
        redisPublisher,
        new FlightEventMapper(),
        new OpenSkyRateLimitTracker(buildProperties()),
        backoffController,
        buildProperties(),
        new SimpleMeterRegistry());

    job.ingest();

    verify(openSkyClient, never()).fetchStates();
    assertThat(backoffController.disabledGaugeValue()).isEqualTo(1);
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
