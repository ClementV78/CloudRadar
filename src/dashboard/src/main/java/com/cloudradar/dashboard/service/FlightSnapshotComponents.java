package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.aircraft.AircraftMetadataRepository;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

final class FlightSnapshotComponents {
  private final FlightTaxonomy taxonomy;
  private final FlightSnapshotReader snapshotReader;
  private final FlightSnapshotEnricher snapshotEnricher;
  private final FlightMetricsSupport metricsSupport;

  private FlightSnapshotComponents(
      FlightTaxonomy taxonomy,
      FlightSnapshotReader snapshotReader,
      FlightSnapshotEnricher snapshotEnricher,
      FlightMetricsSupport metricsSupport) {
    this.taxonomy = taxonomy;
    this.snapshotReader = snapshotReader;
    this.snapshotEnricher = snapshotEnricher;
    this.metricsSupport = metricsSupport;
  }

  static FlightSnapshotComponents build(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties properties,
      Optional<AircraftMetadataRepository> aircraftRepo) {
    FlightTaxonomy taxonomy = new FlightTaxonomy();
    FlightEventParser eventParser = new FlightEventParser(objectMapper);
    FlightSnapshotCandidateCollector candidateCollector =
        new FlightSnapshotCandidateCollector(redisTemplate, properties, eventParser);
    FlightSnapshotDeduplicator deduplicator = new FlightSnapshotDeduplicator();
    FlightSnapshotEnricher snapshotEnricher = new FlightSnapshotEnricher(aircraftRepo, taxonomy);
    FlightTrackReader trackReader = new FlightTrackReader(redisTemplate, properties, eventParser);
    FlightSnapshotReader snapshotReader =
        new FlightSnapshotReader(candidateCollector, deduplicator, snapshotEnricher, trackReader);
    FlightMetricsSupport metricsSupport =
        new FlightMetricsSupport(new FlightActivitySeriesReader(redisTemplate, properties));
    return new FlightSnapshotComponents(taxonomy, snapshotReader, snapshotEnricher, metricsSupport);
  }

  FlightTaxonomy taxonomy() {
    return taxonomy;
  }

  FlightSnapshotReader snapshotReader() {
    return snapshotReader;
  }

  FlightSnapshotEnricher snapshotEnricher() {
    return snapshotEnricher;
  }

  FlightMetricsSupport metricsSupport() {
    return metricsSupport;
  }
}
