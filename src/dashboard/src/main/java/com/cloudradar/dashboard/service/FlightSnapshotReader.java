package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.model.Bbox;
import com.cloudradar.dashboard.model.FlightTrackPoint;
import com.cloudradar.dashboard.model.PositionEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FlightSnapshotReader {
  private final FlightSnapshotCandidateCollector candidateCollector;
  private final FlightSnapshotDeduplicator deduplicator;
  private final FlightSnapshotEnricher enricher;
  private final FlightTrackReader trackReader;

  FlightSnapshotReader(
      FlightSnapshotCandidateCollector candidateCollector,
      FlightSnapshotDeduplicator deduplicator,
      FlightSnapshotEnricher enricher,
      FlightTrackReader trackReader) {
    this.candidateCollector = candidateCollector;
    this.deduplicator = deduplicator;
    this.enricher = enricher;
    this.trackReader = trackReader;
  }

  List<FlightSnapshot> loadSnapshots(
      Bbox bbox, Long since, boolean includeMetadata, boolean includeOwnerOperator) {
    List<Map.Entry<String, PositionEvent>> candidates = candidateCollector.collect(bbox, since);
    Map<String, PositionEvent> latestByIcao = deduplicator.deduplicate(candidates);
    return enricher.enrich(latestByIcao, includeMetadata, includeOwnerOperator);
  }

  Optional<PositionEvent> loadLatestEvent(String icao24) {
    return trackReader.loadLatestEvent(icao24);
  }

  List<FlightTrackPoint> loadTrack(String icao24) {
    return trackReader.loadTrack(icao24);
  }
}
