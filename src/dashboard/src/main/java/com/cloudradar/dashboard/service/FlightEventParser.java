package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.model.PositionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

final class FlightEventParser {
  private final ObjectMapper objectMapper;

  FlightEventParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  Optional<PositionEvent> parse(String payload) {
    try {
      return Optional.of(objectMapper.readValue(payload, PositionEvent.class));
    } catch (Exception ex) {
      return Optional.empty();
    }
  }
}
