package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import com.cloudradar.dashboard.model.FlightListResponse;
import com.cloudradar.dashboard.model.FlightMapItem;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class FlightListQueryHandler {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

  private final DashboardProperties properties;
  private final FlightSnapshotReader snapshotReader;
  private final FlightTaxonomy taxonomy;

  FlightListQueryHandler(
      DashboardProperties properties,
      FlightSnapshotReader snapshotReader,
      FlightTaxonomy taxonomy) {
    this.properties = properties;
    this.snapshotReader = snapshotReader;
    this.taxonomy = taxonomy;
  }

  FlightListResponse listFlights(
      String bboxRaw,
      String sinceRaw,
      String limitRaw,
      String sortRaw,
      String orderRaw,
      String militaryHintRaw,
      String airframeTypeRaw,
      String categoryRaw,
      String countryRaw,
      String typecodeRaw) {
    Bbox bbox = QueryParser.parseBboxOrDefault(bboxRaw, properties);
    Long since = QueryParser.parseSince(sinceRaw);
    int limit =
        QueryParser.parseLimit(
            limitRaw, properties.getApi().getDefaultLimit(), properties.getApi().getMaxLimit());
    String sort = QueryParser.parseSort(sortRaw, properties.getApi().getDefaultSort());
    String order = QueryParser.parseOrder(orderRaw, properties.getApi().getDefaultOrder());

    String militaryHint = parseMilitaryHintFilter(militaryHintRaw);
    String airframeType = parseAirframeTypeFilter(airframeTypeRaw);
    String category = FlightQueryValues.normalizeOptional(categoryRaw, true, false);
    String country = FlightQueryValues.normalizeOptional(countryRaw, true, false);
    String typecode = FlightQueryValues.normalizeOptional(typecodeRaw, false, true);

    List<FlightSnapshot> snapshots = snapshotReader.loadSnapshots(bbox, since, true, false);
    List<FlightSnapshot> filtered =
        snapshots.stream()
            .filter(snapshot -> taxonomy.matchesMilitary(snapshot, militaryHint))
            .filter(snapshot -> taxonomy.matchesAirframe(snapshot, airframeType))
            .filter(snapshot -> taxonomy.matchesString(snapshot.category(), category, true))
            .filter(snapshot -> taxonomy.matchesString(snapshot.country(), country, true))
            .filter(snapshot -> taxonomy.matchesString(snapshot.typecode(), typecode, false))
            .toList();

    Comparator<FlightSnapshot> comparator = comparatorForSort(sort);
    if ("desc".equals(order)) {
      comparator = comparator.reversed();
    }

    List<FlightMapItem> items =
        filtered.stream().sorted(comparator).limit(limit).map(taxonomy::toMapItem).toList();

    Long latestOpenSkyBatchEpoch =
        filtered.stream()
            .map(snapshot -> snapshot.event().openskyFetchEpoch())
            .filter(Objects::nonNull)
            .max(Long::compareTo)
            .orElse(null);

    Map<String, Double> bboxPayload =
        Map.of(
            "minLon", bbox.minLon(),
            "minLat", bbox.minLat(),
            "maxLon", bbox.maxLon(),
            "maxLat", bbox.maxLat());

    return new FlightListResponse(
        items,
        items.size(),
        filtered.size(),
        limit,
        bboxPayload,
        latestOpenSkyBatchEpoch,
        ISO.format(Instant.now()));
  }

  private static Comparator<FlightSnapshot> comparatorForSort(String sort) {
    return switch (sort) {
      case "speed" -> Comparator.comparing(snapshot -> FlightMetricsSupport.nullSafeDouble(snapshot.event().velocity()));
      case "altitude" ->
          Comparator.comparing(snapshot -> FlightMetricsSupport.nullSafeDouble(snapshot.event().altitude()));
      case "lastSeen" ->
          Comparator.comparing(snapshot -> FlightMetricsSupport.nullSafeLong(snapshot.event().lastContact()));
      default -> throw new BadRequestException("unsupported sort");
    };
  }

  private static String parseMilitaryHintFilter(String raw) {
    String normalized = FlightQueryValues.normalizeOptional(raw, true, false);
    if (normalized != null && !Set.of("true", "false", "unknown").contains(normalized)) {
      throw new BadRequestException("militaryHint must be one of: true,false,unknown");
    }
    return normalized;
  }

  private static String parseAirframeTypeFilter(String raw) {
    String normalized = FlightQueryValues.normalizeOptional(raw, true, false);
    if (normalized != null && !Set.of("airplane", "helicopter", "unknown").contains(normalized)) {
      throw new BadRequestException("airframeType must be one of: airplane,helicopter,unknown");
    }
    return normalized;
  }
}
