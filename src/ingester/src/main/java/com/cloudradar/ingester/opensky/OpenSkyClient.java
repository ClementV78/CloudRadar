package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.IngesterProperties;
import com.cloudradar.ingester.config.OpenSkyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class OpenSkyClient {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyClient.class);

  private final OpenSkyEndpointProvider endpointProvider;
  private final IngesterProperties ingesterProperties;
  private final StringRedisTemplate redisTemplate;
  private final OpenSkyTokenService tokenService;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Timer statesRequestTimer;
  private final Counter statesRequestSuccessCounter;
  private final Counter statesRequestRateLimitedCounter;
  private final Counter statesRequestClientErrorCounter;
  private final Counter statesRequestServerErrorCounter;
  private final Counter statesRequestExceptionCounter;
  private final AtomicInteger lastStatusCode = new AtomicInteger(0);

  public OpenSkyClient(
      OpenSkyEndpointProvider endpointProvider,
      IngesterProperties ingesterProperties,
      StringRedisTemplate redisTemplate,
      OpenSkyTokenService tokenService,
      MeterRegistry meterRegistry,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this.endpointProvider = endpointProvider;
    this.ingesterProperties = ingesterProperties;
    this.redisTemplate = redisTemplate;
    this.tokenService = tokenService;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;

    this.statesRequestTimer = Timer.builder("ingester.opensky.states.http.duration")
        .description("OpenSky /states/all HTTP request duration (seconds)")
        .publishPercentileHistogram(true)
        .register(meterRegistry);

    // Keep cardinality low: a handful of outcomes, no URL labels.
    this.statesRequestSuccessCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "success")
        .register(meterRegistry);
    this.statesRequestRateLimitedCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "rate_limited")
        .register(meterRegistry);
    this.statesRequestClientErrorCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "client_error")
        .register(meterRegistry);
    this.statesRequestServerErrorCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "server_error")
        .register(meterRegistry);
    this.statesRequestExceptionCounter = Counter.builder("ingester.opensky.states.http.requests.total")
        .description("OpenSky /states/all HTTP requests (by outcome)")
        .tag("outcome", "exception")
        .register(meterRegistry);

    meterRegistry.gauge("ingester.opensky.states.http.last_status", lastStatusCode);
  }

  public FetchResult fetchStates() {
    long httpStartNs = -1L;
    boolean recorded = false;
    try {
      // Query OpenSky states endpoint within the configured bbox.
      IngesterProperties.Bbox bbox = resolveEffectiveBbox();
      String baseUrl = endpointProvider.baseUrl();
      if (baseUrl == null || baseUrl.isBlank()) {
        throw new IllegalStateException("OpenSky base URL is missing.");
      }
      String url = String.format(
          "%s/states/all?lamin=%s&lamax=%s&lomin=%s&lomax=%s",
          baseUrl, bbox.latMin(), bbox.latMax(), bbox.lonMin(), bbox.lonMax());

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Authorization", "Bearer " + tokenService.getToken())
          .GET()
          .build();

      httpStartNs = System.nanoTime();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      lastStatusCode.set(response.statusCode());
      statesRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      recorded = true;
      Integer remainingCredits = parseRemainingCredits(response.headers().firstValue("X-Rate-Limit-Remaining"));
      Integer creditLimit = parseIntHeader(response.headers().firstValue("X-Rate-Limit-Limit"));
      Long resetAtEpochSeconds = parseResetAtEpochSeconds(response.headers().firstValue("X-Rate-Limit-Reset"));

      if (response.statusCode() == 429) {
        statesRequestRateLimitedCounter.increment();
        log.warn("OpenSky rate limit hit (429)");
        return new FetchResult(List.of(), remainingCredits, creditLimit, resetAtEpochSeconds);
      }
      if (response.statusCode() >= 400) {
        if (response.statusCode() >= 500) {
          statesRequestServerErrorCounter.increment();
        } else {
          statesRequestClientErrorCounter.increment();
        }
        log.warn("OpenSky fetch failed: status={}", response.statusCode());
        return new FetchResult(List.of(), remainingCredits, creditLimit, resetAtEpochSeconds);
      }
      statesRequestSuccessCounter.increment();

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode states = root.path("states");
      if (!states.isArray()) {
        return new FetchResult(List.of(), remainingCredits, creditLimit, resetAtEpochSeconds);
      }

      // Each row is a fixed-position array defined by OpenSky; we map selected fields.
      List<FlightState> results = new ArrayList<>();
      for (JsonNode row : states) {
        if (!row.isArray()) {
          continue;
        }
        results.add(parseState(row));
      }
      return new FetchResult(results, remainingCredits, creditLimit, resetAtEpochSeconds);
    } catch (OpenSkyTokenService.TokenRefreshException ex) {
      lastStatusCode.set(0);
      if (!recorded && httpStartNs > 0) {
        statesRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      }
      statesRequestExceptionCounter.increment();
      log.error("Failed to fetch OpenSky states", ex);
      throw ex;
    } catch (InterruptedException ex) {
      lastStatusCode.set(0);
      if (!recorded && httpStartNs > 0) {
        statesRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      }
      statesRequestExceptionCounter.increment();
      Thread.currentThread().interrupt();
      log.error("Failed to fetch OpenSky states: request interrupted", ex);
      return new FetchResult(List.of(), null, null, null);
    } catch (Exception ex) {
      lastStatusCode.set(0);
      if (!recorded && httpStartNs > 0) {
        statesRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      }
      statesRequestExceptionCounter.increment();
      log.error("Failed to fetch OpenSky states", ex);
      return new FetchResult(List.of(), null, null, null);
    }
  }

  private FlightState parseState(JsonNode row) {
    return new FlightState(
        text(row, 0),
        text(row, 1),
        number(row, 6),
        number(row, 5),
        number(row, 9),
        number(row, 10),
        number(row, 13),
        number(row, 7),
        bool(row, 8),
        longNumber(row, 3),
        longNumber(row, 4));
  }

  private IngesterProperties.Bbox resolveEffectiveBbox() {
    IngesterProperties.Bbox base = ingesterProperties.bbox();
    IngesterProperties.BboxBoost boost = ingesterProperties.bboxBoost();
    if (boost == null || boost.factor() <= 1.0 || boost.redisKey() == null || boost.redisKey().isBlank()) {
      return base;
    }

    try {
      String active = redisTemplate.opsForValue().get(boost.redisKey());
      if (active == null || active.isBlank()) {
        return base;
      }
      return scaleBboxByArea(base, boost.factor());
    } catch (Exception ex) {
      log.debug("Unable to read bbox boost key from Redis, using base bbox", ex);
      return base;
    }
  }

  private IngesterProperties.Bbox scaleBboxByArea(IngesterProperties.Bbox bbox, double areaFactor) {
    double factor = Math.max(1.0, areaFactor);
    double linearScale = Math.sqrt(factor);

    double centerLat = (bbox.latMin() + bbox.latMax()) / 2.0;
    double centerLon = (bbox.lonMin() + bbox.lonMax()) / 2.0;
    double halfLat = (bbox.latMax() - bbox.latMin()) / 2.0 * linearScale;
    double halfLon = (bbox.lonMax() - bbox.lonMin()) / 2.0 * linearScale;

    return new IngesterProperties.Bbox(
        clamp(centerLat - halfLat, -90.0, 90.0),
        clamp(centerLat + halfLat, -90.0, 90.0),
        clamp(centerLon - halfLon, -180.0, 180.0),
        clamp(centerLon + halfLon, -180.0, 180.0));
  }

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private String text(JsonNode row, int idx) {
    JsonNode node = row.get(idx);
    return node == null || node.isNull() ? null : node.asText().trim();
  }

  private Double number(JsonNode row, int idx) {
    JsonNode node = row.get(idx);
    return node == null || node.isNull() ? null : node.asDouble();
  }

  private Long longNumber(JsonNode row, int idx) {
    JsonNode node = row.get(idx);
    return node == null || node.isNull() ? null : node.asLong();
  }

  private Boolean bool(JsonNode row, int idx) {
    JsonNode node = row.get(idx);
    return node == null || node.isNull() ? null : node.asBoolean();
  }

  private Integer parseRemainingCredits(Optional<String> header) {
    if (header.isEmpty()) {
      return null;
    }
    try {
      return Integer.parseInt(header.get());
    } catch (NumberFormatException ex) {
      log.debug("Unable to parse X-Rate-Limit-Remaining header value: {}", header.get());
      return null;
    }
  }

  private Integer parseIntHeader(Optional<String> header) {
    if (header.isEmpty()) {
      return null;
    }
    try {
      return Integer.parseInt(header.get());
    } catch (NumberFormatException ex) {
      log.debug("Unable to parse int header value: {}", header.get());
      return null;
    }
  }

  private Long parseResetAtEpochSeconds(Optional<String> header) {
    if (header.isEmpty()) {
      return null;
    }
    try {
      long value = Long.parseLong(header.get());
      long now = System.currentTimeMillis() / 1000;
      // Heuristic: if it's a small number, assume it's a delta in seconds; else assume it's epoch seconds.
      if (value > 0 && value < 1_000_000_000L) {
        return now + value;
      }
      // Sanity check: ignore obviously wrong values.
      if (value < now - 86400L || value > now + 7L * 86400L) {
        return null;
      }
      return value;
    } catch (NumberFormatException ex) {
      log.debug("Unable to parse X-Rate-Limit-Reset header value: {}", header.get());
      return null;
    }
  }
}
