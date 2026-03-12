package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenSkyResponseParserTest {

  @Test
  void parseStatesResponseThrowsWhenPayloadIsMalformedJson() {
    OpenSkyResponseParser parser = new OpenSkyResponseParser(new ObjectMapper());
    OpenSkyRateLimitHeaders headers = new OpenSkyRateLimitHeaders(100, 4000, 1_700_000_000L);

    assertThatThrownBy(() -> parser.parseStatesResponse("{\"states\":[", headers))
        .isInstanceOf(JsonProcessingException.class);
  }

  @Test
  void parseStatesResponseReturnsEmptyResultWhenStatesNodeIsNotArray() throws Exception {
    OpenSkyResponseParser parser = new OpenSkyResponseParser(new ObjectMapper());
    OpenSkyRateLimitHeaders headers = new OpenSkyRateLimitHeaders(100, 4000, 1_700_000_000L);

    FetchResult result = parser.parseStatesResponse("{\"states\":{}}", headers);

    assertThat(result.states()).isEmpty();
    assertThat(result.remainingCredits()).isEqualTo(100);
    assertThat(result.creditLimit()).isEqualTo(4000);
    assertThat(result.resetAtEpochSeconds()).isEqualTo(1_700_000_000L);
  }
}
