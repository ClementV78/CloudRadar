package com.cloudradar.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PrometheusQueryResponseParserTest {

  private final PrometheusQueryResponseParser parser =
      new PrometheusQueryResponseParser(new ObjectMapper());

  @Test
  void parse_returnsValueWhenPrometheusPayloadIsValid() throws Exception {
    String body = "{\"status\":\"success\",\"data\":{\"result\":[{\"value\":[1700000000,\"1.5\"]}]}}";
    assertThat(parser.parse(body)).contains(1.5);
  }

  @Test
  void parse_returnsEmptyWhenValueIsNotNumeric() throws Exception {
    String body = "{\"status\":\"success\",\"data\":{\"result\":[{\"value\":[1700000000,\"not-a-number\"]}]}}";
    assertThat(parser.parse(body)).isEmpty();
  }

  @Test
  void parse_returnsEmptyWhenStatusIsNotSuccess() throws Exception {
    assertThat(parser.parse("{\"status\":\"error\"}")).isEmpty();
  }
}
