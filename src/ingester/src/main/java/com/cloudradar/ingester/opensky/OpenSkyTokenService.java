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

  private final OpenSkyProperties properties;
  private final OpenSkyCredentialsProvider credentialsProvider;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  private String accessToken;
  private Instant expiry;

  public OpenSkyTokenService(
      OpenSkyProperties properties,
      OpenSkyCredentialsProvider credentialsProvider,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.credentialsProvider = credentialsProvider;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public synchronized String getToken() {
    // Cache token until near expiry to avoid unnecessary auth calls.
    if (accessToken != null && expiry != null && expiry.isAfter(Instant.now().plusSeconds(15))) {
      return accessToken;
    }

    try {
      OpenSkyCredentials creds = credentialsProvider.get();
      String form = "grant_type=client_credentials"
          + "&client_id=" + urlEncode(creds.clientId())
          + "&client_secret=" + urlEncode(creds.clientSecret());
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(properties.tokenUrl()))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("OpenSky token request failed: " + response.statusCode());
      }

      JsonNode node = objectMapper.readTree(response.body());
      accessToken = node.path("access_token").asText();
      long expiresIn = node.path("expires_in").asLong(300);
      expiry = Instant.now().plusSeconds(expiresIn);
      return accessToken;
    } catch (Exception ex) {
      log.error("Failed to obtain OpenSky token", ex);
      throw new IllegalStateException("OpenSky token acquisition failed", ex);
    }
  }

  private String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
