package com.cloudradar.ingester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudradar.ingester.config.IngesterProperties;
import com.cloudradar.ingester.opensky.FetchResult;
import com.cloudradar.ingester.opensky.OpenSkyClient;
import com.cloudradar.ingester.redis.RedisPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlightIngestJobTest {

  @Test
  void ingestUsesOpenSkyLimitHeaderForThrottlingWhenAvailable() {
    OpenSkyClient openSkyClient = mock(OpenSkyClient.class);
    RedisPublisher redisPublisher = mock(RedisPublisher.class);
    when(openSkyClient.fetchStates()).thenReturn(new FetchResult(List.of(), 399, 400, null));
    when(redisPublisher.pushEvents(anyList())).thenReturn(0);

    FlightIngestJob job = new FlightIngestJob(
        openSkyClient,
        redisPublisher,
        new SimpleMeterRegistry(),
        buildProperties());

    job.ingest();

    assertThat(readLongField(job, "currentDelayMs")).isEqualTo(10_000L);
  }

  @Test
  void ingestFallsBackToConfiguredQuotaWhenLimitHeaderMissing() {
    OpenSkyClient openSkyClient = mock(OpenSkyClient.class);
    RedisPublisher redisPublisher = mock(RedisPublisher.class);
    when(openSkyClient.fetchStates()).thenReturn(new FetchResult(List.of(), 399, null, null));
    when(redisPublisher.pushEvents(anyList())).thenReturn(0);

    FlightIngestJob job = new FlightIngestJob(
        openSkyClient,
        redisPublisher,
        new SimpleMeterRegistry(),
        buildProperties());

    job.ingest();

    assertThat(readLongField(job, "currentDelayMs")).isEqualTo(30_000L);
  }

  private IngesterProperties buildProperties() {
    return new IngesterProperties(
        10_000L,
        new IngesterProperties.Redis("cloudradar:ingest:queue"),
        new IngesterProperties.Bbox(46.0, 50.0, 2.0, 4.0),
        new IngesterProperties.RateLimit(4000, 50, 80, 95, 30_000L, 300_000L),
        new IngesterProperties.BboxBoost("cloudradar:opensky:bbox:boost:active", 1.0));
  }

  private long readLongField(Object target, String fieldName) {
    try {
      Field field = FlightIngestJob.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.getLong(target);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Unable to read test field: " + fieldName, ex);
    }
  }
}
