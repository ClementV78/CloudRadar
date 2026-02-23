package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.aircraft.AircraftMetadata;
import com.cloudradar.dashboard.aircraft.AircraftMetadataRepository;
import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.api.NotFoundException;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import com.cloudradar.dashboard.model.FlightDetailResponse;
import com.cloudradar.dashboard.model.FlightListResponse;
import com.cloudradar.dashboard.model.FlightMapItem;
import com.cloudradar.dashboard.model.FlightPhoto;
import com.cloudradar.dashboard.model.FlightTrackPoint;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import com.cloudradar.dashboard.model.PositionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Core query service for dashboard flight endpoints.
 *
 * <p>This service reads snapshots from Redis, enriches with optional SQLite metadata, and builds
 * either map-focused payloads or aggregated KPI payloads.
 */
@Service
public class FlightQueryService {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
  private static final Set<String> SUPPORTED_INCLUDES = Set.of("track", "enrichment");
  private static final long REDIS_SCAN_COUNT = 1000L;
  private static final int MAP_CONTINUITY_BATCH_WINDOW = 3;
  private static final String AIRCRAFT_HLL_SUFFIX = ":aircraft_hll";
  private static final String AIRCRAFT_MILITARY_HLL_SUFFIX = ":aircraft_military_hll";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final DashboardProperties properties;
  private final Optional<AircraftMetadataRepository> aircraftRepo;
  private final Optional<PrometheusMetricsService> prometheusMetricsService;
  private final Optional<PlanespottersPhotoService> planespottersPhotoService;
  private final Timer activitySeriesReadTimer;
  private final Counter activitySeriesRedisReadsCounter;
  private final Counter activitySeriesEmptyBucketsCounter;

  /**
   * Creates the query service.
   *
   * @param redisTemplate Redis access template
   * @param objectMapper JSON mapper for event payloads
   * @param properties typed dashboard configuration
   * @param aircraftRepo optional aircraft metadata repository
   * @param prometheusMetricsService optional Prometheus query helper
   * @param planespottersPhotoService optional Planespotters integration service
   */
  public FlightQueryService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties properties,
      Optional<AircraftMetadataRepository> aircraftRepo,
      Optional<PrometheusMetricsService> prometheusMetricsService,
      Optional<PlanespottersPhotoService> planespottersPhotoService) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.aircraftRepo = aircraftRepo;
    this.prometheusMetricsService = prometheusMetricsService;
    this.planespottersPhotoService = planespottersPhotoService;
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

  /**
   * Builds the lightweight map response for {@code GET /api/flights}.
   *
   * @param bboxRaw optional bbox query value
   * @param sinceRaw optional time lower-bound query value
   * @param limitRaw optional limit query value
   * @param sortRaw optional sort field query value
   * @param orderRaw optional order query value
   * @param militaryHintRaw optional military filter
   * @param airframeTypeRaw optional airframe filter
   * @param categoryRaw optional category filter
   * @param countryRaw optional country filter
   * @param typecodeRaw optional typecode filter
   * @return map response payload
   */
  public FlightListResponse listFlights(
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
    int limit = QueryParser.parseLimit(limitRaw, properties.getApi().getDefaultLimit(), properties.getApi().getMaxLimit());
    String sort = QueryParser.parseSort(sortRaw, properties.getApi().getDefaultSort());
    String order = QueryParser.parseOrder(orderRaw, properties.getApi().getDefaultOrder());

    String militaryHint = normalizeOptional(militaryHintRaw, true, false);
    if (militaryHint != null && !Set.of("true", "false", "unknown").contains(militaryHint)) {
      throw new BadRequestException("militaryHint must be one of: true,false,unknown");
    }

    String airframeType = normalizeOptional(airframeTypeRaw, true, false);
    if (airframeType != null && !Set.of("airplane", "helicopter", "unknown").contains(airframeType)) {
      throw new BadRequestException("airframeType must be one of: airplane,helicopter,unknown");
    }

    String category = normalizeOptional(categoryRaw, true, false);
    String country = normalizeOptional(countryRaw, true, false);
    String typecode = normalizeOptional(typecodeRaw, false, true);

    // Map payload must be enriched on read so UI marker typing does not depend on Redis persistence.
    List<FlightSnapshot> snapshots = loadSnapshots(bbox, since, true, false);

    List<FlightSnapshot> filtered = snapshots.stream()
        .filter(snapshot -> matchesMilitary(snapshot, militaryHint))
        .filter(snapshot -> matchesAirframe(snapshot, airframeType))
        .filter(snapshot -> matchesString(snapshot.category(), category, true))
        .filter(snapshot -> matchesString(snapshot.country(), country, true))
        .filter(snapshot -> matchesString(snapshot.typecode(), typecode, false))
        .toList();

    Comparator<FlightSnapshot> comparator = switch (sort) {
      case "speed" -> Comparator.comparing(s -> nullSafeDouble(s.event().velocity()));
      case "altitude" -> Comparator.comparing(s -> nullSafeDouble(s.event().altitude()));
      case "lastSeen" -> Comparator.comparing(s -> nullSafeLong(s.event().lastContact()));
      default -> throw new BadRequestException("unsupported sort");
    };

    if ("desc".equals(order)) {
      comparator = comparator.reversed();
    }

    List<FlightMapItem> items = filtered.stream()
        .sorted(comparator)
        .limit(limit)
        .map(this::toMapItem)
        .toList();

    Long latestOpenSkyBatchEpoch = filtered.stream()
        .map(snapshot -> snapshot.event().openskyFetchEpoch())
        .filter(Objects::nonNull)
        .max(Long::compareTo)
        .orElse(null);

    Map<String, Double> bboxPayload = Map.of(
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

  /**
   * Builds the enriched detail response for a single aircraft.
   *
   * @param icao24Raw path variable identifying the aircraft
   * @param includeRaw optional include list (track/enrichment)
   * @return detail response payload
   */
  public FlightDetailResponse getFlightDetail(String icao24Raw, String includeRaw) {
    String icao24 = normalizeOptional(icao24Raw, true, false);
    if (icao24 == null || !icao24.matches("^[a-f0-9]{6}$")) {
      throw new BadRequestException("icao24 must be a 6-char hexadecimal identifier");
    }

    Set<String> include = parseInclude(includeRaw);
    String rawPayload = (String) redisTemplate.opsForHash().get(properties.getRedis().getLastPositionsKey(), icao24);
    if (rawPayload == null) {
      throw new NotFoundException("flight not found for icao24=" + icao24);
    }

    PositionEvent event = parseEvent(rawPayload).orElseThrow(
        () -> new NotFoundException("flight payload unavailable for icao24=" + icao24));

    Optional<AircraftMetadata> metadata = resolveMetadata(icao24);
    List<FlightTrackPoint> track = include.contains("track") ? loadTrack(icao24) : Collections.emptyList();
    FlightPhoto photo = planespottersPhotoService
        .map(service -> service.resolvePhoto(icao24, metadata.map(AircraftMetadata::registration).orElse(null)))
        .orElse(null);

    return new FlightDetailResponse(
        icao24,
        trimToNull(event.callsign()),
        metadata.map(AircraftMetadata::registration).orElse(null),
        metadata.map(this::manufacturer).orElse(null),
        metadata.map(AircraftMetadata::model).orElse(null),
        metadata.map(AircraftMetadata::typecode).map(this::upperOrNull).orElse(null),
        metadata.map(AircraftMetadata::categoryOrFallback).orElse(null),
        event.lat(),
        event.lon(),
        event.heading(),
        event.altitude(),
        event.velocity(),
        event.verticalRate(),
        event.lastContact(),
        event.onGround(),
        metadata.map(AircraftMetadata::country).orElse(null),
        metadata.map(AircraftMetadata::militaryHint).orElse(null),
        metadata.map(AircraftMetadata::yearBuilt).orElse(null),
        metadata.map(AircraftMetadata::ownerOperator).orElse(null),
        photo,
        track,
        ISO.format(Instant.now()));
  }

  /**
   * Builds aggregated KPI payload for dashboard cards/charts.
   *
   * @param bboxRaw optional bbox query value
   * @param windowRaw optional duration window query value
   * @return aggregated metrics response
   */
  public FlightsMetricsResponse getFlightsMetrics(String bboxRaw, String windowRaw) {
    Bbox bbox = QueryParser.parseBboxOrDefault(bboxRaw, properties);
    Duration window = QueryParser.parseWindow(
        windowRaw,
        properties.getApi().getMetricsWindowDefault(),
        properties.getApi().getMetricsWindowMax());
    long cutoff = QueryParser.cutoffEpoch(window);

    List<FlightSnapshot> snapshots = loadSnapshots(bbox, cutoff, true, true);
    int active = snapshots.size();

    long militaryCount = snapshots.stream().filter(s -> Boolean.TRUE.equals(s.militaryHint())).count();
    double militaryShare = pct(militaryCount, active);

    double density = 0.0;
    double areaKm2 = bbox.areaKm2();
    if (areaKm2 > 0.0) {
      density = round2((active / areaKm2) * 10_000.0);
    }

    List<FlightsMetricsResponse.TypeBreakdownItem> fleetBreakdown = breakdown(
        snapshots,
        this::fleetType,
        List.of("commercial", "military", "rescue", "private", "unknown"));

    List<FlightsMetricsResponse.TypeBreakdownItem> aircraftSizes = breakdown(
        snapshots,
        this::aircraftSize,
        List.of("small", "medium", "large", "heavy", "unknown"));

    List<FlightsMetricsResponse.TypeBreakdownItem> aircraftTypes = topBreakdown(
        snapshots,
        this::aircraftTypeLabel,
        8);

    int bucketCount = Math.max(12, properties.getApi().getMetricsBucketCount());
    List<FlightsMetricsResponse.TimeBucket> activitySeries = activitySeriesFromEventBuckets(window, bucketCount);
    int activityBucketSeconds =
        activitySeries.size() < 2
            ? (int) Math.max(1L, window.getSeconds() / Math.max(1, bucketCount))
            : (int) Math.max(1L, activitySeries.get(1).epoch() - activitySeries.get(0).epoch());

    FlightsMetricsResponse.Estimates estimates = new FlightsMetricsResponse.Estimates(
        null,
        null,
        null,
        Map.of(
            "takeoffsLandings", "planned_v1_1",
            "noiseProxyIndex", "planned_v1_1_heuristic",
            "alerts", "out_of_scope_issue_129"));

    Double openSkyCreditsPerRequest24h = prometheusMetricsService
        .flatMap(PrometheusMetricsService::queryOpenSkyCreditsPerRequest24h)
        .map(FlightQueryService::round2)
        .orElse(null);

    return new FlightsMetricsResponse(
        active,
        density,
        round2(militaryShare),
        round2(militaryShare),
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

  private List<FlightSnapshot> loadSnapshots(
      Bbox bbox,
      Long since,
      boolean includeMetadata,
      boolean includeOwnerOperator) {
    HashOperations<String, Object, Object> hashOps = redisTemplate.opsForHash();
    ScanOptions scanOptions = ScanOptions.scanOptions().count(REDIS_SCAN_COUNT).build();
    List<Entry<String, PositionEvent>> candidates = new ArrayList<>();
    TreeMap<Long, Boolean> batchEpochsDesc = new TreeMap<>(Comparator.reverseOrder());
    Map<String, PositionEvent> latestEventsByIcao = new LinkedHashMap<>();
    List<FlightSnapshot> snapshots = new ArrayList<>();

    try (Cursor<Entry<Object, Object>> cursor = hashOps.scan(properties.getRedis().getLastPositionsKey(), scanOptions)) {
      while (cursor.hasNext()) {
        Entry<Object, Object> entry = cursor.next();
        Object payloadObj = entry.getValue();
        if (payloadObj == null) {
          continue;
        }

        Optional<PositionEvent> maybeEvent = parseEvent(payloadObj.toString());
        if (maybeEvent.isEmpty()) {
          continue;
        }

        PositionEvent event = maybeEvent.get();
        String icao24 = normalizeOptional(event.icao24(), true, false);
        if (icao24 == null || event.lat() == null || event.lon() == null) {
          continue;
        }
        if (!bbox.contains(event.lat(), event.lon())) {
          continue;
        }
        if (since != null) {
          Long lastSeen = event.lastContact();
          if (lastSeen == null || lastSeen < since) {
            continue;
          }
        }
        candidates.add(Map.entry(icao24, event));
        Long batchEpoch = event.openskyFetchEpoch();
        if (batchEpoch != null) {
          batchEpochsDesc.put(batchEpoch, Boolean.TRUE);
        }
      }
    }

    Set<Long> continuityBatchEpochs = batchEpochsDesc.keySet().stream()
        .limit(MAP_CONTINUITY_BATCH_WINDOW)
        .collect(Collectors.toSet());

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

    for (Entry<String, PositionEvent> latest : latestEventsByIcao.entrySet()) {
      String icao24 = latest.getKey();
      PositionEvent event = latest.getValue();

        String category = null;
        String country = null;
        String typecode = null;
        Boolean militaryHint = null;
        String ownerOperator = null;

        if (includeMetadata) {
          Optional<AircraftMetadata> metadata = resolveMetadata(icao24);
          category = metadata.map(AircraftMetadata::categoryOrFallback).orElse(null);
          country = metadata.map(AircraftMetadata::country).orElse(null);
          typecode = metadata.map(AircraftMetadata::typecode).map(this::upperOrNull).orElse(null);
          militaryHint = metadata.map(AircraftMetadata::militaryHint).orElse(null);
          if (includeOwnerOperator) {
            ownerOperator = metadata.map(AircraftMetadata::ownerOperator).orElse(null);
          }
        }

      snapshots.add(
          new FlightSnapshot(
              icao24,
              event,
              category,
              country,
              typecode,
              militaryHint,
              inferAirframeType(category, typecode),
              ownerOperator));
    }

    return snapshots.isEmpty() ? Collections.emptyList() : snapshots;
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

  private Optional<PositionEvent> parseEvent(String payload) {
    try {
      PositionEvent event = objectMapper.readValue(payload, PositionEvent.class);
      return Optional.of(event);
    } catch (Exception ex) {
      return Optional.empty();
    }
  }

  private Optional<AircraftMetadata> resolveMetadata(String icao24) {
    if (aircraftRepo.isEmpty()) {
      return Optional.empty();
    }
    return aircraftRepo.get().findByIcao24(icao24);
  }

  private FlightMapItem toMapItem(FlightSnapshot snapshot) {
    PositionEvent event = snapshot.event();
    return new FlightMapItem(
        snapshot.icao24(),
        trimToNull(event.callsign()),
        event.lat(),
        event.lon(),
        event.heading(),
        event.lastContact(),
        event.velocity(),
        event.altitude(),
        snapshot.militaryHint(),
        snapshot.airframeType() == null ? "unknown" : snapshot.airframeType(),
        fleetType(snapshot),
        aircraftSize(snapshot));
  }

  private List<FlightTrackPoint> loadTrack(String icao24) {
    String trackKey = properties.getRedis().getTrackKeyPrefix() + icao24;
    List<String> payloads = redisTemplate.opsForList().range(trackKey, 0, 119);
    if (payloads == null || payloads.isEmpty()) {
      return Collections.emptyList();
    }

    List<FlightTrackPoint> points = new ArrayList<>(payloads.size());
    for (String payload : payloads) {
      Optional<PositionEvent> eventOpt = parseEvent(payload);
      if (eventOpt.isEmpty()) {
        continue;
      }
      PositionEvent event = eventOpt.get();
      points.add(new FlightTrackPoint(
          event.lat(),
          event.lon(),
          event.heading(),
          event.altitude(),
          event.velocity(),
          event.lastContact(),
          event.onGround()));
    }
    return points;
  }

  private Set<String> parseInclude(String includeRaw) {
    if (includeRaw == null || includeRaw.isBlank()) {
      return Set.of("enrichment");
    }

    Set<String> includes = Arrays.stream(includeRaw.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toSet());

    List<String> invalid = includes.stream().filter(i -> !SUPPORTED_INCLUDES.contains(i)).toList();
    if (!invalid.isEmpty()) {
      throw new BadRequestException("include contains unsupported values: " + String.join(",", invalid));
    }

    if (!includes.contains("enrichment")) {
      includes = new java.util.HashSet<>(includes);
      includes.add("enrichment");
    }
    return includes;
  }

  private List<FlightsMetricsResponse.TypeBreakdownItem> breakdown(
      List<FlightSnapshot> snapshots,
      java.util.function.Function<FlightSnapshot, String> keyFn,
      List<String> canonicalOrder) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    canonicalOrder.forEach(key -> counts.put(key, 0));

    for (FlightSnapshot snapshot : snapshots) {
      String key = keyFn.apply(snapshot);
      counts.compute(key, (k, old) -> (old == null ? 0 : old) + 1);
    }

    int total = snapshots.size();
    List<FlightsMetricsResponse.TypeBreakdownItem> items = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      items.add(new FlightsMetricsResponse.TypeBreakdownItem(
          entry.getKey(),
          entry.getValue(),
          round2(pct(entry.getValue(), total))));
    }

    return items;
  }

  private List<FlightsMetricsResponse.TypeBreakdownItem> topBreakdown(
      List<FlightSnapshot> snapshots,
      java.util.function.Function<FlightSnapshot, String> keyFn,
      int topN) {
    Map<String, Integer> counts = new HashMap<>();
    for (FlightSnapshot snapshot : snapshots) {
      String key = keyFn.apply(snapshot);
      counts.compute(key, (k, old) -> (old == null ? 0 : old) + 1);
    }

    int total = snapshots.size();
    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .limit(topN)
        .map(e -> new FlightsMetricsResponse.TypeBreakdownItem(e.getKey(), e.getValue(), round2(pct(e.getValue(), total))))
        .toList();
  }

  private List<FlightsMetricsResponse.TimeBucket> activitySeriesFromEventBuckets(
      Duration window,
      int bucketCount) {
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
      int idx = (int) Math.min(bucketCount - 1L, Math.max(0L, offset / bucketWidth));
      totalByBucket[idx] += total;
      militaryByBucket[idx] += military;
      aircraftByBucket[idx] += aircraftTotal;
      aircraftMilitaryByBucket[idx] += aircraftMilitary;
      observedByBucket[idx] += 1;
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
          round2(pct(aircraftMilitary, aircraftTotal)),
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

  private boolean matchesMilitary(FlightSnapshot snapshot, String filter) {
    if (filter == null) {
      return true;
    }
    return switch (filter) {
      case "true" -> Boolean.TRUE.equals(snapshot.militaryHint());
      case "false" -> Boolean.FALSE.equals(snapshot.militaryHint());
      case "unknown" -> snapshot.militaryHint() == null;
      default -> true;
    };
  }

  private boolean matchesAirframe(FlightSnapshot snapshot, String filter) {
    if (filter == null) {
      return true;
    }
    return switch (filter) {
      case "unknown" -> snapshot.airframeType() == null;
      default -> Objects.equals(snapshot.airframeType(), filter);
    };
  }

  private boolean matchesString(String value, String filter, boolean lowercase) {
    if (filter == null) {
      return true;
    }
    if (value == null || value.isBlank()) {
      return false;
    }
    return lowercase
        ? value.trim().toLowerCase(Locale.ROOT).equals(filter)
        : value.trim().toUpperCase(Locale.ROOT).equals(filter);
  }

  private String fleetType(FlightSnapshot snapshot) {
    if (Boolean.TRUE.equals(snapshot.militaryHint())) {
      return "military";
    }

    if (isRescueFlight(snapshot)) {
      return "rescue";
    }

    String owner = normalizeOptional(snapshot.ownerOperator(), true, false);
    String category = normalizeOptional(snapshot.category(), true, false);
    if ((owner != null && (owner.contains("private") || owner.contains("charter")))
        || (category != null && (category.contains("private") || category.contains("business") || category.contains("general")))) {
      return "private";
    }

    if (category == null) {
      return "unknown";
    }
    return "commercial";
  }

  private String aircraftSize(FlightSnapshot snapshot) {
    String category = normalizeOptional(snapshot.category(), true, false);
    String typecode = normalizeOptional(snapshot.typecode(), false, true);

    if (category != null && category.contains("heavy")) {
      return "heavy";
    }
    if (category != null && (category.contains("large") || category.contains("wide"))) {
      return "large";
    }
    if (category != null && (category.contains("light") || category.contains("small") || category.contains("ultra"))) {
      return "small";
    }

    if (typecode != null) {
      if (typecode.startsWith("B74") || typecode.startsWith("B77") || typecode.startsWith("A38")) {
        return "heavy";
      }
      if (typecode.startsWith("A3") || typecode.startsWith("B7") || typecode.startsWith("B8")) {
        return "large";
      }
      if (typecode.startsWith("C1") || typecode.startsWith("P") || typecode.startsWith("E") || typecode.startsWith("H")) {
        return "small";
      }
      return "medium";
    }

    return "unknown";
  }

  private String aircraftTypeLabel(FlightSnapshot snapshot) {
    if (snapshot.typecode() != null && !snapshot.typecode().isBlank()) {
      return snapshot.typecode();
    }
    if (snapshot.category() != null && !snapshot.category().isBlank()) {
      return snapshot.category();
    }
    return "unknown";
  }

  private String inferAirframeType(String category, String typecode) {
    String cat = normalizeOptional(category, true, false);
    String tc = normalizeOptional(typecode, false, true);

    if (cat != null && (cat.contains("heli") || cat.contains("rotor") || cat.matches("^h\\d.*"))) {
      return "helicopter";
    }
    if (tc != null && (tc.startsWith("H")
        || tc.startsWith("EC")
        || tc.startsWith("AS")
        || tc.startsWith("SA")
        || tc.startsWith("AW")
        || tc.startsWith("BK")
        || tc.startsWith("MI")
        || tc.startsWith("KA")
        || tc.startsWith("UH")
        || tc.startsWith("CH"))) {
      return "helicopter";
    }
    if (cat != null || tc != null) {
      return "airplane";
    }
    return null;
  }

  private boolean isRescueFlight(FlightSnapshot snapshot) {
    String callsign = normalizeOptional(snapshot.event().callsign(), true, false);
    String owner = normalizeOptional(snapshot.ownerOperator(), true, false);
    String category = normalizeOptional(snapshot.category(), true, false);

    if (containsAny(owner, "samu", "secours", "rescue", "hems", "medevac", "civil security", "civil protection")) {
      return true;
    }
    if (containsAny(category, "rescue", "secours", "hems", "medevac", "medical", "ambulance")) {
      return true;
    }

    return containsAny(callsign, "samu", "rescue", "dragon", "hems", "lifeguard", "secours");
  }

  private static boolean containsAny(String value, String... needles) {
    if (value == null || value.isBlank()) {
      return false;
    }
    for (String needle : needles) {
      if (value.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private String manufacturer(AircraftMetadata metadata) {
    if (metadata.manufacturerName() != null && !metadata.manufacturerName().isBlank()) {
      return metadata.manufacturerName();
    }
    return metadata.manufacturerIcao();
  }

  private static double pct(long value, int total) {
    if (total <= 0) {
      return 0.0;
    }
    return (value * 100.0) / total;
  }

  private static double round2(double value) {
    return Math.round(value * 100.0) / 100.0;
  }

  private static double nullSafeDouble(Double value) {
    return value == null ? Double.NEGATIVE_INFINITY : value;
  }

  private static long nullSafeLong(Long value) {
    return value == null ? Long.MIN_VALUE : value;
  }

  private static String normalizeOptional(String raw, boolean lower, boolean upper) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    if (lower) {
      return trimmed.toLowerCase(Locale.ROOT);
    }
    if (upper) {
      return trimmed.toUpperCase(Locale.ROOT);
    }
    return trimmed;
  }

  private static String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String upperOrNull(String raw) {
    return normalizeOptional(raw, false, true);
  }

  private record FlightSnapshot(
      String icao24,
      PositionEvent event,
      String category,
      String country,
      String typecode,
      Boolean militaryHint,
      String airframeType,
      String ownerOperator) {}
}
