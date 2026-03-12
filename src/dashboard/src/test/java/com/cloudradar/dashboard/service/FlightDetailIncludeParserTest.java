package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudradar.dashboard.api.BadRequestException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FlightDetailIncludeParserTest {

  @Test
  void parse_returnsDefaultWhenBlank() {
    Set<String> includes = FlightDetailIncludeParser.parse(" ", Set.of("track", "enrichment"));
    assertEquals(Set.of("enrichment"), includes);
  }

  @Test
  void parse_addsEnrichmentWhenMissing() {
    Set<String> includes = FlightDetailIncludeParser.parse("track", Set.of("track", "enrichment"));
    assertEquals(Set.of("track", "enrichment"), includes);
  }

  @Test
  void parse_rejectsUnsupportedValues() {
    assertThrows(
        BadRequestException.class,
        () -> FlightDetailIncludeParser.parse("track,unknown", Set.of("track", "enrichment")));
  }
}
