package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PlanespottersEndpointBuilderTest {

  @Test
  void buildUrl_removesTrailingSlashes() {
    PlanespottersEndpointBuilder builder =
        new PlanespottersEndpointBuilder("https://api.planespotters.net/pub/photos///");

    assertEquals(
        "https://api.planespotters.net/pub/photos/hex/abc123",
        builder.buildUrl("hex/abc123"));
  }

  @Test
  void normalizeRegistration_returnsUppercaseOrNull() {
    PlanespottersEndpointBuilder builder = new PlanespottersEndpointBuilder("https://api.planespotters.net/pub/photos");

    assertEquals("F-GKXA", builder.normalizeRegistration(" f-gkxa "));
    assertNull(builder.normalizeRegistration("   "));
  }
}
