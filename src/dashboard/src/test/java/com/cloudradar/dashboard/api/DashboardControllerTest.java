package com.cloudradar.dashboard.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudradar.dashboard.model.BboxBoostStatusResponse;
import com.cloudradar.dashboard.model.FlightDetailResponse;
import com.cloudradar.dashboard.model.FlightListResponse;
import com.cloudradar.dashboard.model.FlightMapItem;
import com.cloudradar.dashboard.model.FlightTrackPoint;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import com.cloudradar.dashboard.rate.ApiRateLimitFilter;
import com.cloudradar.dashboard.service.BboxBoostService;
import com.cloudradar.dashboard.service.FlightQueryService;
import com.cloudradar.dashboard.service.FlightUpdateStreamService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(controllers = DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockBean private FlightQueryService flightQueryService;
  @MockBean private FlightUpdateStreamService flightUpdateStreamService;
  @MockBean private BboxBoostService bboxBoostService;
  @MockBean private ApiRateLimitFilter apiRateLimitFilter;

  @Test
  void listFlights_returns200() throws Exception {
    FlightListResponse payload = new FlightListResponse(
        List.of(new FlightMapItem(
            "abc123",
            "AFR123",
            48.85,
            2.35,
            90.0,
            1760000000L,
            220.0,
            10500.0,
            false,
            "airplane",
            "commercial",
            "medium")),
        1,
        1,
        200,
        Map.of("minLon", 0.0, "minLat", 45.0, "maxLon", 10.0, "maxLat", 55.0),
        1760000000L,
        "2026-02-13T12:00:00Z");

    when(flightQueryService.listFlights(
        eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
            .thenReturn(payload);

    mockMvc.perform(get("/api/flights"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.items[0].icao24").value("abc123"));
  }

  @Test
  void metrics_returns200() throws Exception {
    FlightsMetricsResponse payload = new FlightsMetricsResponse(
        1,
        12.5,
        0.0,
        0.0,
        List.of(new FlightsMetricsResponse.TypeBreakdownItem("commercial", 1, 100.0)),
        List.of(new FlightsMetricsResponse.TypeBreakdownItem("medium", 1, 100.0)),
        List.of(new FlightsMetricsResponse.TypeBreakdownItem("A320", 1, 100.0)),
        List.of(new FlightsMetricsResponse.TimeBucket(1760000000L, 1)),
        new FlightsMetricsResponse.Estimates(null, null, null, Map.of("takeoffsLandings", "planned_v1_1")),
        0.87,
        "2026-02-13T12:00:00Z");

    when(flightQueryService.getFlightsMetrics(eq(null), eq(null))).thenReturn(payload);

    mockMvc.perform(get("/api/flights/metrics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeAircraft").value(1))
        .andExpect(jsonPath("$.fleetBreakdown[0].key").value("commercial"))
        .andExpect(jsonPath("$.openSkyCreditsPerRequest24h").value(0.87));
  }

  @Test
  void detail_returns200() throws Exception {
    FlightDetailResponse payload = new FlightDetailResponse(
        "abc123",
        "AFR123",
        "F-GKXA",
        "Airbus",
        "A320",
        "A320",
        "Commercial",
        48.85,
        2.35,
        90.0,
        10800.0,
        220.0,
        0.0,
        1760000000L,
        false,
        "France",
        false,
        2011,
        "Air France",
        List.of(new FlightTrackPoint(48.80, 2.20, 88.0, 10700.0, 218.0, 1759999970L, false)),
        "2026-02-13T12:00:00Z");

    when(flightQueryService.getFlightDetail(eq("abc123"), eq("track"))).thenReturn(payload);

    mockMvc.perform(get("/api/flights/abc123").queryParam("include", "track"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.icao24").value("abc123"))
        .andExpect(jsonPath("$.recentTrack[0].lat").value(48.8));
  }

  @Test
  void detail_notFound_returns404() throws Exception {
    when(flightQueryService.getFlightDetail(eq("abc123"), eq(null)))
        .thenThrow(new NotFoundException("flight not found for icao24=abc123"));

    mockMvc.perform(get("/api/flights/abc123"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"));
  }

  @Test
  void detail_invalidIcao24Path_returns404() throws Exception {
    mockMvc.perform(get("/api/flights/ZZZZZZ"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"));
  }

  @Test
  void stream_returns200() throws Exception {
    when(flightUpdateStreamService.openStream()).thenReturn(new SseEmitter(1_000L));

    mockMvc.perform(get("/api/flights/stream"))
        .andExpect(status().isOk());
  }

  @Test
  void bboxBoostStatus_returns200() throws Exception {
    BboxBoostStatusResponse payload = new BboxBoostStatusResponse(
        true,
        2.0,
        Map.of("minLon", 0.0, "minLat", 45.0, "maxLon", 10.0, "maxLat", 55.0),
        1760000300L,
        1760003600L,
        1760000000L);
    when(bboxBoostService.getStatus(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(payload);

    mockMvc.perform(get("/api/flights/bbox/boost"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.factor").value(2.0));
  }

  @Test
  void triggerBboxBoost_returns200() throws Exception {
    BboxBoostStatusResponse payload = new BboxBoostStatusResponse(
        true,
        2.0,
        Map.of("minLon", 0.0, "minLat", 45.0, "maxLon", 10.0, "maxLat", 55.0),
        1760000300L,
        1760003600L,
        1760000000L);
    when(bboxBoostService.triggerBoost(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(payload);

    mockMvc.perform(post("/api/flights/bbox/boost"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.factor").value(2.0));
  }
}
