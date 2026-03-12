package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FlightMetricsQueryHandler {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  private final DashboardProperties properties;
  private final FlightSnapshotReader snapshotReader;
  private final FlightTaxonomy taxonomy;
  private final FlightMetricsSupport metricsSupport;
  private final Optional<PrometheusMetricsService> prometheusMetricsService;

  FlightMetricsQueryHandler(
      DashboardProperties properties,
      FlightSnapshotReader snapshotReader,
      FlightTaxonomy taxonomy,
      FlightMetricsSupport metricsSupport,
      Optional<PrometheusMetricsService> prometheusMetricsService) {
    this.properties = properties;
    this.snapshotReader = snapshotReader;
    this.taxonomy = taxonomy;
    this.metricsSupport = metricsSupport;
    this.prometheusMetricsService = prometheusMetricsService;
  }

  FlightsMetricsResponse getFlightsMetrics(String bboxRaw, String windowRaw) {
    Bbox bbox = QueryParser.parseBboxOrDefault(bboxRaw, properties);
    Duration window =
        QueryParser.parseWindow(
            windowRaw,
            properties.getApi().getMetricsWindowDefault(),
            properties.getApi().getMetricsWindowMax());
    long cutoff = QueryParser.cutoffEpoch(window);

    List<FlightSnapshot> snapshots = snapshotReader.loadSnapshots(bbox, cutoff, true, true);
    int active = snapshots.size();

    long militaryCount = snapshots.stream().filter(s -> Boolean.TRUE.equals(s.militaryHint())).count();
    double militaryShare = FlightMetricsSupport.pct(militaryCount, active);

    double density = 0.0;
    double areaKm2 = bbox.areaKm2();
    if (areaKm2 > 0.0) {
      density = FlightMetricsSupport.round2((active / areaKm2) * 10_000.0);
    }

    List<FlightsMetricsResponse.TypeBreakdownItem> fleetBreakdown =
        metricsSupport.breakdown(
            snapshots,
            taxonomy::fleetType,
            List.of("commercial", "military", "rescue", "private", "unknown"));

    List<FlightsMetricsResponse.TypeBreakdownItem> aircraftSizes =
        metricsSupport.breakdown(
            snapshots,
            taxonomy::aircraftSize,
            List.of("small", "medium", "large", "heavy", "unknown"));

    List<FlightsMetricsResponse.TypeBreakdownItem> aircraftTypes =
        metricsSupport.topBreakdown(snapshots, taxonomy::aircraftTypeLabel, 8);

    int bucketCount = Math.max(12, properties.getApi().getMetricsBucketCount());
    List<FlightsMetricsResponse.TimeBucket> activitySeries =
        metricsSupport.activitySeriesFromEventBuckets(window, bucketCount);
    int activityBucketSeconds =
        activitySeries.size() < 2
            ? (int) Math.max(1L, window.getSeconds() / Math.max(1, bucketCount))
            : (int)
                Math.max(1L, activitySeries.get(1).epoch() - activitySeries.get(0).epoch());

    FlightsMetricsResponse.Estimates estimates =
        new FlightsMetricsResponse.Estimates(
            null,
            null,
            null,
            Map.of(
                "takeoffsLandings", "planned_v1_1",
                "noiseProxyIndex", "planned_v1_1_heuristic",
                "alerts", "out_of_scope_issue_129"));

    Double openSkyCreditsPerRequest24h =
        prometheusMetricsService
            .flatMap(PrometheusMetricsService::queryOpenSkyCreditsPerRequest24h)
            .map(FlightMetricsSupport::round2)
            .orElse(null);

    return new FlightsMetricsResponse(
        active,
        density,
        FlightMetricsSupport.round2(militaryShare),
        FlightMetricsSupport.round2(militaryShare),
        fleetBreakdown,
        aircraftSizes,
        aircraftTypes,
        activitySeries,
        activityBucketSeconds,
        Math.max(1L, window.getSeconds()),
        estimates,
        openSkyCreditsPerRequest24h,
        ISO.format(Instant.now()));
  }
}
