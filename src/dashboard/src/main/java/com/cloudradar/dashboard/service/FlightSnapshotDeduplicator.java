package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.model.PositionEvent;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class FlightSnapshotDeduplicator {
  private static final int MAP_CONTINUITY_BATCH_WINDOW = 3;

  Map<String, PositionEvent> deduplicate(List<Entry<String, PositionEvent>> candidates) {
    TreeMap<Long, Boolean> batchEpochsDesc = new TreeMap<>(Comparator.reverseOrder());
    for (Entry<String, PositionEvent> candidate : candidates) {
      Long batchEpoch = candidate.getValue().openskyFetchEpoch();
      if (batchEpoch != null) {
        batchEpochsDesc.put(batchEpoch, Boolean.TRUE);
      }
    }

    Set<Long> continuityBatchEpochs =
        batchEpochsDesc.keySet().stream().limit(MAP_CONTINUITY_BATCH_WINDOW).collect(Collectors.toSet());

    Map<String, PositionEvent> latestEventsByIcao = new LinkedHashMap<>();
    for (Entry<String, PositionEvent> candidate : candidates) {
      PositionEvent event = candidate.getValue();
      if (!continuityBatchEpochs.isEmpty()) {
        Long batchEpoch = event.openskyFetchEpoch();
        if (batchEpoch == null || !continuityBatchEpochs.contains(batchEpoch)) {
          continue;
        }
      }

      String icao24 = candidate.getKey();
      PositionEvent existing = latestEventsByIcao.get(icao24);
      if (existing != null && !isPreferredCandidate(event, existing)) {
        continue;
      }
      latestEventsByIcao.put(icao24, event);
    }

    return latestEventsByIcao;
  }

  private static boolean isPreferredCandidate(PositionEvent candidate, PositionEvent current) {
    Long candidateBatch = candidate.openskyFetchEpoch();
    Long currentBatch = current.openskyFetchEpoch();

    if (candidateBatch != null && currentBatch != null && !Objects.equals(candidateBatch, currentBatch)) {
      return candidateBatch > currentBatch;
    }
    if (candidateBatch != null && currentBatch == null) {
      return true;
    }
    if (candidateBatch == null && currentBatch != null) {
      return false;
    }
    return isMoreRecent(candidate.lastContact(), current.lastContact());
  }

  private static boolean isMoreRecent(Long candidateLastSeen, Long currentLastSeen) {
    if (candidateLastSeen == null) {
      return false;
    }
    if (currentLastSeen == null) {
      return true;
    }
    return candidateLastSeen >= currentLastSeen;
  }
}
