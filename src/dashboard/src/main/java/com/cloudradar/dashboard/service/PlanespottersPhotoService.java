package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightPhoto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PlanespottersPhotoService {
  private static final Logger log = LoggerFactory.getLogger(PlanespottersPhotoService.class);

  private final DashboardProperties.Planespotters properties;
  private final HttpClient httpClient;
  private final PlanespottersEndpointBuilder endpointBuilder;
  private final PlanespottersPhotoPayloadParser payloadParser;
  private final PlanespottersPhotoCache cache;
  private final PlanespottersGlobalRateLimiter rateLimiter;
  private final Counter cacheHitCounter;
  private final Counter cacheMissCounter;
  private final Counter limiterRejectCounter;
  private final Counter upstreamSuccessCounter;
  private final Counter upstreamNotFoundCounter;
  private final Counter upstreamErrorCounter;
  private final Counter upstreamRateLimitedCounter;

  @Autowired
  public PlanespottersPhotoService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties dashboardProperties) {
    this(
        redisTemplate,
        objectMapper,
        dashboardProperties,
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofMillis(
                    Math.max(300, dashboardProperties.getPlanespotters().getTimeoutMs())))
            .build());
  }

  PlanespottersPhotoService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties dashboardProperties,
      HttpClient httpClient) {
    this.properties = dashboardProperties.getPlanespotters();
    this.httpClient = httpClient;
    this.endpointBuilder = new PlanespottersEndpointBuilder(properties.getBaseUrl());
    this.payloadParser = new PlanespottersPhotoPayloadParser(objectMapper);
    this.cache = new PlanespottersPhotoCache(redisTemplate, objectMapper, properties);
    this.rateLimiter =
        new PlanespottersGlobalRateLimiter(
            redisTemplate, properties.getRedisKeyPrefix(), properties.getGlobalRps());
    this.cacheHitCounter = counter("dashboard.planespotters.cache.hit.total", "Cache hits");
    this.cacheMissCounter = counter("dashboard.planespotters.cache.miss.total", "Cache misses");
    this.limiterRejectCounter =
        counter("dashboard.planespotters.limiter.reject.total", "Global limiter rejects");
    this.upstreamSuccessCounter =
        counter("dashboard.planespotters.upstream.success.total", "Upstream success");
    this.upstreamNotFoundCounter =
        counter("dashboard.planespotters.upstream.not_found.total", "Upstream not found");
    this.upstreamErrorCounter =
        counter("dashboard.planespotters.upstream.error.total", "Upstream errors");
    this.upstreamRateLimitedCounter =
        counter("dashboard.planespotters.upstream.rate_limited.total", "Upstream rate limited");
  }

  public FlightPhoto resolvePhoto(String icao24, String registration) {
    if (!properties.isEnabled()) {
      log.debug("Planespotters disabled, skipping photo lookup for icao24={}", icao24);
      return null;
    }

    String cacheKey = cache.cacheKeyForIcao(icao24);
    FlightPhoto cached = cache.read(cacheKey);
    if (cached != null) {
      cacheHitCounter.increment();
      log.debug("Planespotters cache hit key={} status={}", cacheKey, cached.status());
      return cached;
    }

    cacheMissCounter.increment();
    log.debug("Planespotters cache miss key={}", cacheKey);

    FlightPhoto resolved = fetchPhotoWithFallback(icao24, registration);
    if (!cache.write(cacheKey, resolved)) {
      log.warn("Failed to serialize Planespotters cache payload (key={})", cacheKey);
    } else {
      log.debug(
          "Planespotters cache write key={} status={}",
          cacheKey,
          resolved == null ? null : resolved.status());
    }
    return resolved;
  }

  private FlightPhoto fetchPhotoWithFallback(String icao24, String registration) {
    FlightPhoto fromHex = fetchFromEndpoint(endpointBuilder.byHexPath(icao24));
    if (!"not_found".equals(fromHex.status())) {
      return fromHex;
    }

    String normalizedRegistration = endpointBuilder.normalizeRegistration(registration);
    if (normalizedRegistration == null) {
      return fromHex;
    }

    return fetchFromEndpoint(endpointBuilder.byRegistrationPath(normalizedRegistration));
  }

  private FlightPhoto fetchFromEndpoint(String path) {
    if (!rateLimiter.tryAcquire()) {
      limiterRejectCounter.increment();
      log.warn(
          "Planespotters lookup rejected by global limiter (path={}, limitRps={})",
          path,
          rateLimiter.limitRps());
      return FlightPhoto.rateLimited();
    }

    String url = endpointBuilder.buildUrl(path);
    log.debug("Planespotters upstream request GET {}", url);

    HttpRequest request =
        HttpRequest.newBuilder()
            .GET()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(Math.max(300, properties.getTimeoutMs())))
            .header("Accept", "application/json")
            .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      log.debug("Planespotters upstream response status={} path={}", status, path);
      if (status == 429) {
        upstreamRateLimitedCounter.increment();
        log.warn("Planespotters upstream rate-limited request (path={})", path);
        return FlightPhoto.rateLimited();
      }
      if (status == 404) {
        upstreamNotFoundCounter.increment();
        log.debug("Planespotters upstream not found (path={})", path);
        return FlightPhoto.notFound();
      }
      if (status < 200 || status >= 300) {
        upstreamErrorCounter.increment();
        log.warn("Planespotters upstream error status={} (path={})", status, path);
        return FlightPhoto.error();
      }

      FlightPhoto photo = payloadParser.parse(response.body());
      registerParsedPayloadOutcome(path, photo);
      return photo;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      upstreamErrorCounter.increment();
      log.warn("Planespotters upstream request failed (path={})", path, ex);
      return FlightPhoto.error();
    } catch (IOException | RuntimeException ex) {
      upstreamErrorCounter.increment();
      if (ex instanceof JsonProcessingException) {
        log.warn("Planespotters upstream response parse failed (path={})", path);
        log.debug("Planespotters parse error details (path={})", path, ex);
      } else {
        log.warn("Planespotters upstream request failed (path={})", path, ex);
      }
      return FlightPhoto.error();
    }
  }

  private void registerParsedPayloadOutcome(String path, FlightPhoto photo) {
    if ("available".equals(photo.status())) {
      upstreamSuccessCounter.increment();
      return;
    }
    if ("not_found".equals(photo.status())) {
      upstreamNotFoundCounter.increment();
      log.debug("Planespotters payload returned no photos (path={})", path);
      return;
    }
    upstreamErrorCounter.increment();
    log.warn("Planespotters payload rejected due to missing/untrusted thumbnail or source URL");
  }

  private static Counter counter(String name, String description) {
    return Counter.builder(name).description(description).register(Metrics.globalRegistry);
  }
}
