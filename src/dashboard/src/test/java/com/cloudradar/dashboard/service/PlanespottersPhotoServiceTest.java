package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightPhoto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class PlanespottersPhotoServiceTest {
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  @Mock private HttpClient httpClient;
  @Mock private HttpResponse<String> httpResponse;

  private DashboardProperties properties;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    properties = new DashboardProperties();
    properties.getPlanespotters().setEnabled(true);
    properties.getPlanespotters().setGlobalRps(2);
    properties.getPlanespotters().setRedisKeyPrefix("cloudradar:photo:v1:");
    properties.getPlanespotters().setBaseUrl("https://api.planespotters.net/pub/photos");
    objectMapper = new ObjectMapper();
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @Test
  void resolvePhoto_returnsCachedPayload() throws Exception {
    String cacheKey = "cloudradar:photo:v1:icao24:abc123";
    String cachedJson = objectMapper.writeValueAsString(FlightPhoto.available(
        "https://cdn.planespotters.net/small.jpg",
        200,
        133,
        "https://cdn.planespotters.net/large.jpg",
        420,
        280,
        "Jane",
        "https://www.planespotters.net/photo/1"));
    when(valueOperations.get(cacheKey)).thenReturn(cachedJson);

    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", "F-GKXA");

    assertNotNull(photo);
    assertEquals("available", photo.status());
    assertEquals("https://cdn.planespotters.net/small.jpg", photo.thumbnailSrc());
    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void resolvePhoto_returnsRateLimitedWhenGlobalLimiterRejects() throws Exception {
    when(valueOperations.get("cloudradar:photo:v1:icao24:abc123")).thenReturn(null);
    when(valueOperations.increment(any(String.class))).thenReturn(3L);

    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", null);

    assertNotNull(photo);
    assertEquals("rate_limited", photo.status());
    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolvePhoto_fetchesAndCachesAvailablePhoto() throws Exception {
    String cacheKey = "cloudradar:photo:v1:icao24:abc123";
    when(valueOperations.get(cacheKey)).thenReturn(null);
    when(valueOperations.increment(any(String.class))).thenReturn(1L);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse<String>) httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body()).thenReturn("""
        {
          "photos": [
            {
              "thumbnail": {
                "src": "https://cdn.planespotters.net/a.jpg",
                "size": {"width": 200, "height": 133}
              },
              "thumbnail_large": {
                "src": "https://cdn.planespotters.net/a_280.jpg",
                "size": {"width": 420, "height": 280}
              },
              "link": "https://www.planespotters.net/photo/123",
              "photographer": "John"
            }
          ]
        }
        """);

    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", null);

    assertNotNull(photo);
    assertEquals("available", photo.status());
    assertEquals("https://cdn.planespotters.net/a.jpg", photo.thumbnailSrc());
    verify(valueOperations).set(eq(cacheKey), any(String.class), eq(604800L), eq(java.util.concurrent.TimeUnit.SECONDS));
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolvePhoto_acceptsTrustedShortCdnHost() throws Exception {
    String cacheKey = "cloudradar:photo:v1:icao24:abc123";
    when(valueOperations.get(cacheKey)).thenReturn(null);
    when(valueOperations.increment(any(String.class))).thenReturn(1L);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse<String>) httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body()).thenReturn("""
        {
          "photos": [
            {
              "thumbnail": {
                "src": "https://t.plnspttrs.net/a.jpg",
                "size": {"width": 200, "height": 133}
              },
              "thumbnail_large": {
                "src": "https://t.plnspttrs.net/a_280.jpg",
                "size": {"width": 420, "height": 280}
              },
              "link": "https://www.planespotters.net/photo/123",
              "photographer": "John"
            }
          ]
        }
        """);

    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", null);

    assertNotNull(photo);
    assertEquals("available", photo.status());
    assertEquals("https://t.plnspttrs.net/a.jpg", photo.thumbnailSrc());
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolvePhoto_returnsRateLimitedOnUpstream429() throws Exception {
    String cacheKey = "cloudradar:photo:v1:icao24:abc123";
    when(valueOperations.get(cacheKey)).thenReturn(null);
    when(valueOperations.increment(any(String.class))).thenReturn(1L);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse<String>) httpResponse);
    when(httpResponse.statusCode()).thenReturn(429);

    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", null);

    assertNotNull(photo);
    assertEquals("rate_limited", photo.status());
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolvePhoto_returnsErrorOnMalformedJson() throws Exception {
    String cacheKey = "cloudradar:photo:v1:icao24:abc123";
    when(valueOperations.get(cacheKey)).thenReturn(null);
    when(valueOperations.increment(any(String.class))).thenReturn(1L);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse<String>) httpResponse);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body()).thenReturn("{not-json");

    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", null);

    assertNotNull(photo);
    assertEquals("error", photo.status());
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolvePhoto_usesRegistrationFallbackWhenHexNotFound() throws Exception {
    String cacheKey = "cloudradar:photo:v1:icao24:abc123";
    when(valueOperations.get(cacheKey)).thenReturn(null);
    when(valueOperations.increment(any(String.class))).thenReturn(1L, 1L);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn((HttpResponse<String>) httpResponse, (HttpResponse<String>) httpResponse);
    when(httpResponse.statusCode()).thenReturn(200, 200);
    when(httpResponse.body()).thenReturn(
        "{\"photos\":[]}",
        """
        {
          "photos": [
            {
              "thumbnail": {
                "src": "https://cdn.planespotters.net/fallback-small.jpg",
                "size": {"width": 200, "height": 133}
              },
              "thumbnail_large": {
                "src": "https://cdn.planespotters.net/fallback-large.jpg",
                "size": {"width": 420, "height": 280}
              },
              "link": "https://www.planespotters.net/photo/999",
              "photographer": "Fallback"
            }
          ]
        }
        """);

    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", "f-gkxa");

    assertNotNull(photo);
    assertEquals("available", photo.status());
    assertEquals("https://cdn.planespotters.net/fallback-small.jpg", photo.thumbnailSrc());
    verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void resolvePhoto_returnsNullWhenDisabled() {
    properties.getPlanespotters().setEnabled(false);
    PlanespottersPhotoService service = new PlanespottersPhotoService(redisTemplate, objectMapper, properties, httpClient);
    FlightPhoto photo = service.resolvePhoto("abc123", null);
    assertNull(photo);
  }
}
