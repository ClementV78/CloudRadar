package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

final class FlightActivitySeriesReader {
  private static final String AIRCRAFT_HLL_SUFFIX = ":aircraft_hll";
  private static final String AIRCRAFT_MILITARY_HLL_SUFFIX = ":aircraft_military_hll";

  private final StringRedisTemplate redisTemplate;
  private final DashboardProperties properties;
  private final Timer activitySeriesReadTimer;
  private final Counter activitySeriesRedisReadsCounter;
  private final Counter activitySeriesEmptyBucketsCounter;

  FlightActivitySeriesReader(StringRedisTemplate redisTemplate, DashboardProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
    this.activitySeriesReadTimer = Timer.builder("dashboard.activity.series.read.duration")
        .description("Time spent aggregating activity bucket series from Redis")
        .register(Metrics.globalRegistry);
    this.activitySeriesRedisReadsCounter = Counter.builder("dashboard.activity.series.redis.reads.total")
        .description("Number of Redis hash reads during activity series aggregation")
        .register(Metrics.globalRegistry);
    this.activitySeriesEmptyBucketsCounter = Counter.builder("dashboard.activity.series.empty.buckets.total")
        .description("Number of empty activity buckets seen during aggregation")
        .register(Metrics.globalRegistry);
  }

  List<FlightsMetricsResponse.TimeBucket> read(Duration window, int bucketCount) {
    Timer.Sample readSample = Timer.start(Metrics.globalRegistry);
    int redisReads = 0;
    int emptyBuckets = 0;

    long now = Instant.now().getEpochSecond();
    long windowSeconds = Math.max(1, window.getSeconds());
    long bucketWidth = Math.max(1, windowSeconds / bucketCount);
    long start = now - windowSeconds;
    long minuteWidth = 60L;
    long minuteStart = (start / minuteWidth) * minuteWidth;

    int[] totalByBucket = new int[bucketCount];
    int[] militaryByBucket = new int[bucketCount];
    int[] aircraftByBucket = new int[bucketCount];
    int[] aircraftMilitaryByBucket = new int[bucketCount];
    int[] observedByBucket = new int[bucketCount];

    HashOperations<String, Object, Object> hashOps = redisTemplate.opsForHash();
    for (long minuteEpoch = minuteStart; minuteEpoch <= now; minuteEpoch += minuteWidth) {
      String keyPrefix = properties.getRedis().getActivityBucketKeyPrefix() + minuteEpoch;
      redisReads++;
      Map<Object, Object> raw = hashOps.entries(keyPrefix);

      int total = parseBucketCount(raw == null ? null : raw.get("events_total"));
      int military = parseBucketCount(raw == null ? null : raw.get("events_military"));
      int aircraftTotal = toInt(redisTemplate.opsForHyperLogLog().size(keyPrefix + AIRCRAFT_HLL_SUFFIX));
      int aircraftMilitary = toInt(redisTemplate.opsForHyperLogLog().size(keyPrefix + AIRCRAFT_MILITARY_HLL_SUFFIX));

      if (total <= 0 && military <= 0 && aircraftTotal <= 0 && aircraftMilitary <= 0) {
        emptyBuckets++;
        continue;
      }

      long offset = minuteEpoch - start;
      int index = (int) Math.min(bucketCount - 1L, Math.max(0L, offset / bucketWidth));
      totalByBucket[index] += total;
      militaryByBucket[index] += military;
      aircraftByBucket[index] += aircraftTotal;
      aircraftMilitaryByBucket[index] += aircraftMilitary;
      observedByBucket[index] += 1;
    }

    List<FlightsMetricsResponse.TimeBucket> series = new ArrayList<>(bucketCount);
    for (int i = 0; i < bucketCount; i++) {
      long bucketStart = start + (i * bucketWidth);
      int total = totalByBucket[i];
      int military = militaryByBucket[i];
      int aircraftTotal = aircraftByBucket[i];
      int aircraftMilitary = aircraftMilitaryByBucket[i];
      series.add(new FlightsMetricsResponse.TimeBucket(
          bucketStart,
          total,
          military,
          aircraftTotal,
          aircraftMilitary,
          FlightMetricsSupport.round2(FlightMetricsSupport.pct(aircraftMilitary, aircraftTotal)),
          observedByBucket[i] > 0));
    }

    activitySeriesRedisReadsCounter.increment(redisReads);
    activitySeriesEmptyBucketsCounter.increment(emptyBuckets);
    readSample.stop(activitySeriesReadTimer);
    return series;
  }

  private int parseBucketCount(Object value) {
    if (value == null) {
      return 0;
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (NumberFormatException ex) {
      return 0;
    }
  }

  private int toInt(Long value) {
    if (value == null || value <= 0) {
      return 0;
    }
    return Math.toIntExact(value);
  }
}
