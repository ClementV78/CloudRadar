package com.cloudradar.ingester.opensky;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenSkyTokenService {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyTokenService.class);
  private static final long[] TOKEN_FAILURE_BACKOFF_SECONDS = {15L, 30L, 60L, 120L, 300L, 600L};

  private final OpenSkyEndpointProvider endpointProvider;
  private final OpenSkyProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Timer tokenRequestTimer;
  private final Counter tokenRequestSuccessCounter;
  private final Counter tokenRequestClientErrorCounter;
  private final Counter tokenRequestServerErrorCounter;
  private final Counter tokenRequestExceptionCounter;

  private String accessToken;
  private Instant expiry;
  private int tokenFailureCount;
  private Instant nextTokenAttemptAt = Instant.EPOCH;

  public static class TokenRefreshException extends RuntimeException {
    private TokenRefreshException(String message) {
      super(message);
    }

    private TokenRefreshException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public OpenSkyTokenService(
      OpenSkyEndpointProvider endpointProvider,
      OpenSkyProperties properties,
      MeterRegistry meterRegistry,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this.endpointProvider = endpointProvider;
    this.properties = properties;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;

    this.tokenRequestTimer = Timer.builder("ingester.opensky.token.http.duration")
        .description("OpenSky token HTTP request duration (seconds)")
        .publishPercentileHistogram(true)
        .register(meterRegistry);

    this.tokenRequestSuccessCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "success")
        .register(meterRegistry);
    this.tokenRequestClientErrorCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "client_error")
        .register(meterRegistry);
    this.tokenRequestServerErrorCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "server_error")
        .register(meterRegistry);
    this.tokenRequestExceptionCounter = Counter.builder("ingester.opensky.token.http.requests.total")
        .description("OpenSky token HTTP requests (by outcome)")
        .tag("outcome", "exception")
        .register(meterRegistry);
  }

  public synchronized String getToken() {
    // Cache token until near expiry to avoid unnecessary auth calls.
    if (accessToken != null && expiry != null && expiry.isAfter(Instant.now().plusSeconds(15))) {
      return accessToken;
    }

    Instant now = Instant.now();
    if (nextTokenAttemptAt != null && now.isBefore(nextTokenAttemptAt)) {
      long waitSeconds = Math.max(1L, Duration.between(now, nextTokenAttemptAt).toSeconds());
      String message = "Token refresh cooldown active (" + waitSeconds + "s remaining)";
      tokenRequestExceptionCounter.increment();
      throw new TokenRefreshException(message);
    }

    long httpStartNs = -1L;
    boolean recorded = false;
    try {
      String clientId = properties.clientId();
      String clientSecret = properties.clientSecret();
      
      if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
        throw new IllegalStateException("OpenSky credentials missing (OPENSKY_CLIENT_ID, OPENSKY_CLIENT_SECRET)");
      }
      
      String tokenUrl = endpointProvider.tokenUrl();
      if (tokenUrl == null || tokenUrl.isBlank()) {
        throw new IllegalStateException("OpenSky token URL is missing.");
      }

      // OAuth2 token request
      String body = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
          "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(tokenUrl))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();

      httpStartNs = System.nanoTime();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      tokenRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      recorded = true;

      if (response.statusCode() != 200) {
        if (response.statusCode() >= 500) {
          tokenRequestServerErrorCounter.increment();
        } else {
          tokenRequestClientErrorCounter.increment();
        }
        throw registerTokenFailure("Failed to get token from " + tokenUrl + ": " + response.statusCode(), null);
      }
      tokenRequestSuccessCounter.increment();

      JsonNode json = objectMapper.readTree(response.body());
      accessToken = json.get("access_token").asText();
      long expiresIn = json.get("expires_in").asLong();
      expiry = Instant.now().plusSeconds(expiresIn);
      tokenFailureCount = 0;
      nextTokenAttemptAt = Instant.EPOCH;

      log.info("OpenSky token refreshed, expires in {} seconds", expiresIn);
      return accessToken;

    } catch (TokenRefreshException e) {
      if (!recorded && httpStartNs > 0) {
        tokenRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      }
      log.error("Failed to get OpenSky token", e);
      throw e;
    } catch (InterruptedException e) {
      if (!recorded && httpStartNs > 0) {
        tokenRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      }
      Thread.currentThread().interrupt();
      tokenRequestExceptionCounter.increment();
      TokenRefreshException failure =
          registerTokenFailure("Token refresh request interrupted", e);
      log.error("Failed to get OpenSky token", failure);
      throw failure;
    } catch (Exception e) {
      if (!recorded && httpStartNs > 0) {
        tokenRequestTimer.record(System.nanoTime() - httpStartNs, TimeUnit.NANOSECONDS);
      }
      tokenRequestExceptionCounter.increment();
      TokenRefreshException failure =
          registerTokenFailure("Token refresh request failed: " + e.getMessage(), e);
      log.error("Failed to get OpenSky token", failure);
      throw failure;
    }
  }

  private TokenRefreshException registerTokenFailure(String message, Throwable cause) {
    tokenFailureCount++;
    int index = Math.min(tokenFailureCount - 1, TOKEN_FAILURE_BACKOFF_SECONDS.length - 1);
    long cooldownSeconds = TOKEN_FAILURE_BACKOFF_SECONDS[index];
    nextTokenAttemptAt = Instant.now().plusSeconds(cooldownSeconds);
    log.warn(
        "OpenSky token refresh failed (attempt {}), cooldown {}s",
        tokenFailureCount,
        cooldownSeconds);
    if (cause == null) {
      return new TokenRefreshException("Token refresh failed: " + message);
    }
    return new TokenRefreshException("Token refresh failed: " + message, cause);
  }
}
