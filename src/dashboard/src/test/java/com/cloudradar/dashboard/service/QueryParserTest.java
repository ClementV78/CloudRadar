package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudradar.dashboard.api.BadRequestException;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class QueryParserTest {

  @Test
  void parsesEpochSeconds() {
    assertEquals(1700000000L, QueryParser.parseSince("1700000000"));
  }

  @Test
  void parsesEpochMillis() {
    assertEquals(1700000000L, QueryParser.parseSince("1700000000000"));
  }

  @Test
  void parsesIso() {
    assertEquals(5L, QueryParser.parseSince("1970-01-01T00:00:05Z"));
  }

  @Test
  void rejectsInvalidBboxFormat() {
    assertThrows(BadRequestException.class, () -> QueryParser.parseBbox("1,2,3"));
  }

  @Test
  void validatesBboxBoundaries() {
    DashboardProperties properties = new DashboardProperties();
    properties.getApi().getBbox().setAllowedLatMin(40.0);
    properties.getApi().getBbox().setAllowedLatMax(60.0);
    properties.getApi().getBbox().setAllowedLonMin(-10.0);
    properties.getApi().getBbox().setAllowedLonMax(10.0);
    properties.getApi().getBbox().setMaxAreaDeg2(50.0);

    QueryParser.validateBboxBoundaries(new Bbox(-1.0, 45.0, 2.0, 50.0), properties);

    assertThrows(
        BadRequestException.class,
        () -> QueryParser.validateBboxBoundaries(new Bbox(-20.0, 45.0, 2.0, 50.0), properties));
  }

  @Test
  void parsesWindow() {
    Duration parsed = QueryParser.parseWindow("24h", Duration.ofHours(24), Duration.ofDays(7));
    assertEquals(Duration.ofHours(24), parsed);
  }
}
