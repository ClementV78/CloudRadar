package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightPhoto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves aircraft photos from Planespotters API with Redis-backed caching and global rate
 * limiting.
 */
@Service
public class PlanespottersPhotoService {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);
  private static final Set<String> TRUSTED_HOSTS = Set.of(
      "cdn.planespotters.net",
      "www.planespotters.net",
      "planespotters.net");

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final DashboardProperties.Planespotters properties;
  private final HttpClient httpClient;
  private final Counter cacheHitCounter;
  private final Counter cacheMissCounter;
  private final Counter limiterRejectCounter;
  private final Counter upstreamSuccessCounter;
  private final Counter upstreamNotFoundCounter;
  private final Counter upstreamErrorCounter;
  private final Counter upstreamRateLimitedCounter;

  public PlanespottersPhotoService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties dashboardProperties) {
    this(redisTemplate, objectMapper, dashboardProperties, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.max(300, dashboardProperties.getPlanespotters().getTimeoutMs())))
        .build());
  }

  PlanespottersPhotoService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties dashboardProperties,
      HttpClient httpClient) {
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.properties = dashboardProperties.getPlanespotters();
    this.httpClient = httpClient;
    this.cacheHitCounter = counter("dashboard.planespotters.cache.hit.total", "Cache hits");
    this.cacheMissCounter = counter("dashboard.planespotters.cache.miss.total", "Cache misses");
    this.limiterRejectCounter = counter("dashboard.planespotters.limiter.reject.total", "Global limiter rejects");
    this.upstreamSuccessCounter = counter("dashboard.planespotters.upstream.success.total", "Upstream success");
    this.upstreamNotFoundCounter = counter("dashboard.planespotters.upstream.not_found.total", "Upstream not found");
    this.upstreamErrorCounter = counter("dashboard.planespotters.upstream.error.total", "Upstream errors");
    this.upstreamRateLimitedCounter =
        counter("dashboard.planespotters.upstream.rate_limited.total", "Upstream rate limited");
  }

  /**
   * Resolves photo metadata for one aircraft.
   *
   * @param icao24 normalized ICAO24 identifier
   * @param registration optional registration fallback
   * @return resolved photo metadata (available/not_found/rate_limited/error), or null when
   *     integration is disabled
   */
  public FlightPhoto resolvePhoto(String icao24, String registration) {
    if (!properties.isEnabled()) {
      return null;
    }

    String cacheKey = cacheKeyForIcao(icao24);
    FlightPhoto cached = readCached(cacheKey);
    if (cached != null) {
      cacheHitCounter.increment();
      return cached;
    }
    cacheMissCounter.increment();

    FlightPhoto resolved = fetchPhotoWithFallback(icao24, registration);
    cache(cacheKey, resolved);
    return resolved;
  }

  private FlightPhoto fetchPhotoWithFallback(String icao24, String registration) {
    FlightPhoto fromHex = fetchFromEndpoint("hex/" + urlEncode(icao24));
    if (!"not_found".equals(fromHex.status())) {
      return fromHex;
    }

    String normalizedRegistration = normalizeRegistration(registration);
    if (normalizedRegistration == null) {
      return fromHex;
    }

    return fetchFromEndpoint("reg/" + urlEncode(normalizedRegistration));
  }

  private FlightPhoto fetchFromEndpoint(String path) {
    if (!acquireGlobalToken()) {
      limiterRejectCounter.increment();
      return FlightPhoto.rateLimited();
    }

    String url = properties.getBaseUrl().replaceAll("/+$", "") + "/" + path;
    HttpRequest request = HttpRequest.newBuilder()
        .GET()
        .uri(URI.create(url))
        .timeout(Duration.ofMillis(Math.max(300, properties.getTimeoutMs())))
        .header("Accept", "application/json")
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 429) {
        upstreamRateLimitedCounter.increment();
        return FlightPhoto.rateLimited();
      }
      if (status == 404) {
        upstreamNotFoundCounter.increment();
        return FlightPhoto.notFound();
      }
      if (status < 200 || status >= 300) {
        upstreamErrorCounter.increment();
        return FlightPhoto.error();
      }
      return parsePhotoResponse(response.body());
    } catch (IOException | InterruptedException | RuntimeException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      upstreamErrorCounter.increment();
      return FlightPhoto.error();
    }
  }

  private FlightPhoto parsePhotoResponse(String body) throws IOException {
    JsonNode root = objectMapper.readTree(body);
    if (root.path("error").isTextual()) {
      upstreamErrorCounter.increment();
      return FlightPhoto.error();
    }

    JsonNode photos = root.path("photos");
    if (!photos.isArray() || photos.isEmpty()) {
      upstreamNotFoundCounter.increment();
      return FlightPhoto.notFound();
    }

    JsonNode first = photos.get(0);
    String thumbSrc = sanitizeUrl(first.path("thumbnail").path("src").asText(null));
    String thumbLargeSrc = sanitizeUrl(first.path("thumbnail_large").path("src").asText(null));
    String sourceLink = sanitizeUrl(first.path("link").asText(null));

    if (thumbSrc == null || thumbLargeSrc == null || sourceLink == null) {
      upstreamErrorCounter.increment();
      return FlightPhoto.error();
    }

    upstreamSuccessCounter.increment();
    return FlightPhoto.available(
        thumbSrc,
        toNullableInt(first.path("thumbnail").path("size").path("width")),
        toNullableInt(first.path("thumbnail").path("size").path("height")),
        thumbLargeSrc,
        toNullableInt(first.path("thumbnail_large").path("size").path("width")),
        toNullableInt(first.path("thumbnail_large").path("size").path("height")),
        trimToNull(first.path("photographer").asText(null)),
        sourceLink);
  }

  private FlightPhoto readCached(String cacheKey) {
    String payload = redisTemplate.opsForValue().get(cacheKey);
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(payload, FlightPhoto.class);
    } catch (IOException ex) {
      return null;
    }
  }

  private void cache(String cacheKey, FlightPhoto photo) {
    if (photo == null) {
      return;
    }
    long ttl = switch (photo.status()) {
      case "available" -> Math.max(60L, properties.getCacheTtlSeconds());
      case "not_found" -> Math.max(30L, properties.getNegativeCacheTtlSeconds());
      case "rate_limited" -> Math.max(1L, properties.getRateLimitedCacheTtlSeconds());
      default -> Math.max(5L, properties.getErrorCacheTtlSeconds());
    };
    try {
      String payload = objectMapper.writeValueAsString(photo);
      redisTemplate.opsForValue().set(cacheKey, payload, ttl, TimeUnit.SECONDS);
    } catch (IOException ignored) {
      // Non-blocking: photo integration must never fail core detail payload.
    }
  }

  private boolean acquireGlobalToken() {
    int limit = Math.max(1, properties.getGlobalRps());
    long epochSecond = Instant.now().getEpochSecond();
    String key = properties.getRedisKeyPrefix() + "ratelimit:sec:" + epochSecond;
    Long count = redisTemplate.opsForValue().increment(key);
    if (count != null && count == 1L) {
      redisTemplate.expire(key, Duration.ofSeconds(2));
    }
    return count != null && count <= limit;
  }

  private String cacheKeyForIcao(String icao24) {
    return properties.getRedisKeyPrefix() + "icao24:" + icao24.toLowerCase(Locale.ROOT);
  }

  private static String normalizeRegistration(String registration) {
    if (registration == null) {
      return null;
    }
    String trimmed = registration.trim();
    return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String sanitizeUrl(String value) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    try {
      URI uri = URI.create(trimmed);
      if (!"https".equalsIgnoreCase(uri.getScheme())) {
        return null;
      }
      String host = uri.getHost();
      if (host == null) {
        return null;
      }
      String normalizedHost = host.toLowerCase(Locale.ROOT);
      return TRUSTED_HOSTS.contains(normalizedHost) ? uri.toString() : null;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static Integer toNullableInt(JsonNode node) {
    return node != null && node.isNumber() ? node.asInt() : null;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static Counter counter(String name, String description) {
    return Counter.builder(name).description(description).register(Metrics.globalRegistry);
  }
}
