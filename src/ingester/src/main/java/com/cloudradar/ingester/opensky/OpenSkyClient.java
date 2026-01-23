package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.IngesterProperties;
import com.cloudradar.ingester.config.OpenSkyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenSkyClient {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyClient.class);

  private final OpenSkyProperties properties;
  private final IngesterProperties ingesterProperties;
  private final OpenSkyTokenService tokenService;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public OpenSkyClient(
      OpenSkyProperties properties,
      IngesterProperties ingesterProperties,
      OpenSkyTokenService tokenService,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.ingesterProperties = ingesterProperties;
    this.tokenService = tokenService;
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
  }

  public FetchResult fetchStates() {
    try {
      // Query OpenSky states endpoint within the configured bbox.
      IngesterProperties.Bbox bbox = ingesterProperties.bbox();
      String url = String.format(
          "%s/states/all?lamin=%s&lamax=%s&lomin=%s&lomax=%s",
          properties.baseUrl(), bbox.latMin(), bbox.latMax(), bbox.lonMin(), bbox.lonMax());

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Authorization", "Bearer " + tokenService.getToken())
          .GET()
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      Integer remainingCredits = parseRemainingCredits(response.headers().firstValue("X-Rate-Limit-Remaining"));

      if (response.statusCode() == 429) {
        log.warn("OpenSky rate limit hit (429)");
        return new FetchResult(List.of(), remainingCredits);
      }
      if (response.statusCode() >= 400) {
        log.warn("OpenSky fetch failed: status={}", response.statusCode());
        return new FetchResult(List.of(), remainingCredits);
      }

      JsonNode root = objectMapper.readTree(response.body());
      JsonNode states = root.path("states");
      if (!states.isArray()) {
        return new FetchResult(List.of(), remainingCredits);
      }

      // Each row is a fixed-position array defined by OpenSky; we map selected fields.
      List<FlightState> results = new ArrayList<>();
      for (JsonNode row : states) {
        if (!row.isArray()) {
          continue;
        }
        results.add(parseState(row));
      }
      return new FetchResult(results, remainingCredits);
    } catch (Exception ex) {
      log.error("Failed to fetch OpenSky states", ex);
      return new FetchResult(List.of(), null);
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
}
