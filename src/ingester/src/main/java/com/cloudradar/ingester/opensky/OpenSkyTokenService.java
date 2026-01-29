package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.OpenSkyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
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

  private String accessToken;
  private Instant expiry;

  public OpenSkyTokenService(
      OpenSkyEndpointProvider endpointProvider,
      OpenSkyProperties properties,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this.endpointProvider = endpointProvider;
    this.properties = properties;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public synchronized String getToken() {
    // Cache token until near expiry to avoid unnecessary auth calls.
    if (accessToken != null && expiry != null && expiry.isAfter(Instant.now().plusSeconds(15))) {
      return accessToken;
    }

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

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        throw new RuntimeException("Failed to get token from " + tokenUrl + ": " + response.statusCode());
      }

      JsonNode json = objectMapper.readTree(response.body());
      accessToken = json.get("access_token").asText();
      long expiresIn = json.get("expires_in").asLong();
      expiry = Instant.now().plusSeconds(expiresIn);

      log.info("OpenSky token refreshed, expires in {} seconds", expiresIn);
      return accessToken;

    } catch (Exception e) {
      log.error("Failed to get OpenSky token", e);
      throw new RuntimeException("Token refresh failed: " + e.getMessage(), e);
    }
  }
}
