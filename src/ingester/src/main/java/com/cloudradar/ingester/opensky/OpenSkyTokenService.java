package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.OpenSkyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenSkyTokenService {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyTokenService.class);

  private final OpenSkyEndpointProvider endpointProvider;
  private final OpenSkyProperties properties;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final OpenSkyTokenHttpMetrics httpMetrics;
  private final TokenCooldownPolicy cooldownPolicy = new TokenCooldownPolicy();

  private String accessToken;
  private Instant expiry;

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
      HttpClient httpClient,
      ObjectMapper objectMapper,
      OpenSkyTokenHttpMetrics httpMetrics) {
    this.endpointProvider = endpointProvider;
    this.properties = properties;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.httpMetrics = httpMetrics;
  }

  public synchronized String getToken() {
    if (hasValidCachedToken()) {
      return accessToken;
    }

    Instant now = Instant.now();
    ensureCooldownAllowsRequest(now);

    long httpStartNs = -1L;
    boolean requestRecorded = false;
    try {
      OpenSkyCredentials credentials = resolveCredentials();
      HttpRequest request = buildTokenRequest(credentials);

      httpStartNs = System.nanoTime();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      httpMetrics.recordResponse(httpStartNs, response.statusCode());
      requestRecorded = true;

      handleNonSuccessResponse(response.statusCode(), request.uri().toString());
      return cacheToken(response.body());
    } catch (TokenRefreshException ex) {
      log.error("Failed to get OpenSky token", ex);
      throw ex;
    } catch (InterruptedException ex) {
      httpMetrics.recordException(httpStartNs, requestRecorded);
      Thread.currentThread().interrupt();
      TokenRefreshException failure = registerTokenFailure("Token refresh request interrupted", ex);
      log.error("Failed to get OpenSky token", failure);
      throw failure;
    } catch (Exception ex) {
      httpMetrics.recordException(httpStartNs, requestRecorded);
      TokenRefreshException failure =
          registerTokenFailure("Token refresh request failed: " + ex.getMessage(), ex);
      log.error("Failed to get OpenSky token", failure);
      throw failure;
    }
  }

  private boolean hasValidCachedToken() {
    return accessToken != null && expiry != null && expiry.isAfter(Instant.now().plusSeconds(15));
  }

  private void ensureCooldownAllowsRequest(Instant now) {
    if (!cooldownPolicy.isBlocked(now)) {
      return;
    }

    long waitSeconds = cooldownPolicy.remainingSeconds(now);
    httpMetrics.incrementException();
    throw new TokenRefreshException("Token refresh cooldown active (" + waitSeconds + "s remaining)");
  }

  private OpenSkyCredentials resolveCredentials() {
    String clientId = properties.clientId();
    String clientSecret = properties.clientSecret();
    if (!isPresent(clientId) || !isPresent(clientSecret)) {
      throw new IllegalStateException(
          "OpenSky credentials missing (OPENSKY_CLIENT_ID, OPENSKY_CLIENT_SECRET)");
    }
    return new OpenSkyCredentials(clientId, clientSecret);
  }

  private HttpRequest buildTokenRequest(OpenSkyCredentials credentials) {
    String tokenUrl = endpointProvider.tokenUrl();
    if (!isPresent(tokenUrl)) {
      throw new IllegalStateException("OpenSky token URL is missing.");
    }

    String body = "grant_type=client_credentials"
        + "&client_id=" + URLEncoder.encode(credentials.clientId(), StandardCharsets.UTF_8)
        + "&client_secret=" + URLEncoder.encode(credentials.clientSecret(), StandardCharsets.UTF_8);

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(tokenUrl))
        .header("Content-Type", "application/x-www-form-urlencoded");
    addRelayAuthHeader(requestBuilder);
    return requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
  }

  private void handleNonSuccessResponse(int statusCode, String tokenUrl) {
    if (statusCode == 200) {
      return;
    }

    throw registerTokenFailure("Failed to get token from " + tokenUrl + ": " + statusCode, null);
  }

  private String cacheToken(String responseBody) throws Exception {
    JsonNode json = objectMapper.readTree(responseBody);
    accessToken = json.get("access_token").asText();
    long expiresIn = json.get("expires_in").asLong();
    expiry = Instant.now().plusSeconds(expiresIn);
    cooldownPolicy.reset();
    log.info("OpenSky token refreshed, expires in {} seconds", expiresIn);
    return accessToken;
  }

  private TokenRefreshException registerTokenFailure(String message, Throwable cause) {
    TokenCooldownPolicy.FailureState failureState = cooldownPolicy.registerFailure(Instant.now());
    log.warn(
        "OpenSky token refresh failed (attempt {}), cooldown {}s",
        failureState.failureCount(),
        failureState.cooldownSeconds());

    if (cause == null) {
      return new TokenRefreshException("Token refresh failed: " + message);
    }
    return new TokenRefreshException("Token refresh failed: " + message, cause);
  }

  private void addRelayAuthHeader(HttpRequest.Builder requestBuilder) {
    String headerName = endpointProvider.relayAuthHeaderName();
    String headerValue = endpointProvider.relayAuthHeaderValue();
    if (!isPresent(headerName) || !isPresent(headerValue)) {
      return;
    }
    requestBuilder.header(headerName, headerValue);
  }

  private boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
