package com.cloudradar.dashboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import org.springframework.util.StringUtils;

final class PrometheusQueryResponseParser {
  private final ObjectMapper objectMapper;

  PrometheusQueryResponseParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  Optional<Double> parse(String body) throws IOException {
    JsonNode root = objectMapper.readTree(body);
    if (!"success".equals(root.path("status").asText())) {
      return Optional.empty();
    }

    JsonNode result = root.path("data").path("result");
    if (!result.isArray() || result.isEmpty()) {
      return Optional.empty();
    }

    JsonNode valueNode = result.get(0).path("value");
    if (!valueNode.isArray() || valueNode.size() < 2) {
      return Optional.empty();
    }

    String raw = valueNode.get(1).asText();
    if (!StringUtils.hasText(raw)) {
      return Optional.empty();
    }

    try {
      return Optional.of(Double.parseDouble(raw));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }
}
