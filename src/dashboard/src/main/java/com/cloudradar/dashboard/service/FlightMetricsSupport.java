package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class FlightMetricsSupport {
  private final FlightActivitySeriesReader activitySeriesReader;

  FlightMetricsSupport(FlightActivitySeriesReader activitySeriesReader) {
    this.activitySeriesReader = activitySeriesReader;
  }

  List<FlightsMetricsResponse.TypeBreakdownItem> breakdown(
      List<FlightSnapshot> snapshots,
      Function<FlightSnapshot, String> keyFn,
      List<String> canonicalOrder) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    canonicalOrder.forEach(key -> counts.put(key, 0));

    for (FlightSnapshot snapshot : snapshots) {
      String key = keyFn.apply(snapshot);
      counts.compute(key, (ignored, old) -> (old == null ? 0 : old) + 1);
    }

    int total = snapshots.size();
    List<FlightsMetricsResponse.TypeBreakdownItem> items = new ArrayList<>(counts.size());
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      items.add(new FlightsMetricsResponse.TypeBreakdownItem(
          entry.getKey(),
          entry.getValue(),
          round2(pct(entry.getValue(), total))));
    }
    return items;
  }

  List<FlightsMetricsResponse.TypeBreakdownItem> topBreakdown(
      List<FlightSnapshot> snapshots,
      Function<FlightSnapshot, String> keyFn,
      int topN) {
    Map<String, Integer> counts = new HashMap<>();
    for (FlightSnapshot snapshot : snapshots) {
      String key = keyFn.apply(snapshot);
      counts.compute(key, (ignored, old) -> (old == null ? 0 : old) + 1);
    }

    int total = snapshots.size();
    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(topN)
        .map(entry -> new FlightsMetricsResponse.TypeBreakdownItem(
            entry.getKey(),
            entry.getValue(),
            round2(pct(entry.getValue(), total))))
        .toList();
  }

  List<FlightsMetricsResponse.TimeBucket> activitySeriesFromEventBuckets(Duration window, int bucketCount) {
    return activitySeriesReader.read(window, bucketCount);
  }

  static double pct(long value, int total) {
    if (total <= 0) {
      return 0.0;
    }
    return (value * 100.0) / total;
  }

  static double round2(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  static double nullSafeDouble(Double value) {
    return value == null ? Double.NEGATIVE_INFINITY : value;
  }

  static long nullSafeLong(Long value) {
    return value == null ? Long.MIN_VALUE : value;
  }
}
