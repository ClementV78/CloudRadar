package com.cloudradar.ingester.opensky;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class OpenSkyResponseParser {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyResponseParser.class);

  private final ObjectMapper objectMapper;

  OpenSkyResponseParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  OpenSkyRateLimitHeaders parseHeaders(HttpResponse<String> response) {
    Integer remainingCredits = parseRemainingCredits(response.headers().firstValue("X-Rate-Limit-Remaining"));
    Integer creditLimit = parseIntHeader(response.headers().firstValue("X-Rate-Limit-Limit"));
    Long resetAtEpochSeconds = parseResetAtEpochSeconds(response.headers().firstValue("X-Rate-Limit-Reset"));
    return new OpenSkyRateLimitHeaders(remainingCredits, creditLimit, resetAtEpochSeconds);
  }

  FetchResult parseStatesResponse(String responseBody, OpenSkyRateLimitHeaders headers) throws Exception {
    JsonNode root = objectMapper.readTree(responseBody);
    JsonNode states = root.path("states");
    if (!states.isArray()) {
      return emptyResult(headers);
    }

    List<FlightState> results = new ArrayList<>();
    for (JsonNode row : states) {
      if (row.isArray()) {
        results.add(parseState(row));
      }
    }
    return new FetchResult(
        results,
        headers.remainingCredits(),
        headers.creditLimit(),
        headers.resetAtEpochSeconds());
  }

  private FetchResult emptyResult(OpenSkyRateLimitHeaders headers) {
    return new FetchResult(List.of(), headers.remainingCredits(), headers.creditLimit(), headers.resetAtEpochSeconds());
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
      long now = System.currentTimeMillis() / 1000L;
      if (value > 0 && value < 1_000_000_000L) {
        return now + value;
      }
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
