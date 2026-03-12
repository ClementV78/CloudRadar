package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudradar.dashboard.model.FlightMapItem;
import com.cloudradar.dashboard.model.PositionEvent;
import org.junit.jupiter.api.Test;

class FlightTaxonomyTest {

  private final FlightTaxonomy taxonomy = new FlightTaxonomy();

  @Test
  void inferAirframeType_detectsHelicopterFromCategoryAndTypecode() {
    assertEquals("helicopter", taxonomy.inferAirframeType("H2T", null));
    assertEquals("helicopter", taxonomy.inferAirframeType(null, "EC45"));
    assertEquals("airplane", taxonomy.inferAirframeType("Commercial", "A320"));
    assertNull(taxonomy.inferAirframeType(null, null));
  }

  @Test
  void fleetType_detectsRescueBeforeCommercialFallback() {
    FlightSnapshot snapshot = snapshot("rescue", "SAMUIDF", "Commercial", "EC45", false, "SAMU IDF");
    assertEquals("rescue", taxonomy.fleetType(snapshot));
  }

  @Test
  void aircraftSize_usesCategoryAndTypecodeHeuristics() {
    assertEquals("heavy", taxonomy.aircraftSize(snapshot("hvy", "TEST", "heavy", "A380", false, null)));
    assertEquals("small", taxonomy.aircraftSize(snapshot("small", "TEST", null, "C172", false, null)));
    assertEquals("medium", taxonomy.aircraftSize(snapshot("med", "TEST", null, "A220", false, null)));
  }

  @Test
  void toMapItem_mapsUnknownAirframeWhenMissing() {
    FlightSnapshot snapshot = snapshot("abc123", "CALL  ", null, null, null, null);
    FlightMapItem item = taxonomy.toMapItem(snapshot);

    assertEquals("abc123", item.icao24());
    assertEquals("CALL", item.callsign());
    assertEquals("unknown", item.airframeType());
    assertTrue(item.lastSeen() > 0);
  }

  private FlightSnapshot snapshot(
      String icao24,
      String callsign,
      String category,
      String typecode,
      Boolean militaryHint,
      String ownerOperator) {
    PositionEvent event =
        new PositionEvent(
            icao24,
            callsign,
            48.8566,
            2.3522,
            90.0,
            120.0,
            1000.0,
            1000.0,
            0.0,
            false,
            1L,
            2L,
            null,
            10L);

    return new FlightSnapshot(
        icao24,
        event,
        category,
        "France",
        typecode,
        militaryHint,
        taxonomy.inferAirframeType(category, typecode),
        ownerOperator);
  }
}
