package com.cloudradar.dashboard.api;

import com.cloudradar.dashboard.model.FlightDetailResponse;
import com.cloudradar.dashboard.model.FlightListResponse;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import com.cloudradar.dashboard.service.FlightQueryService;
import com.cloudradar.dashboard.service.FlightUpdateStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller exposing read-only flight endpoints for the frontend.
 *
 * <p>Route design:
 * <ul>
 *   <li>{@code GET /api/flights}: lightweight map payload</li>
 *   <li>{@code GET /api/flights/stream}: SSE updates when a new OpenSky batch is available</li>
 *   <li>{@code GET /api/flights/{icao24}}: enriched detail payload</li>
 *   <li>{@code GET /api/flights/metrics}: aggregated KPI payload</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/flights")
public class DashboardController {
  private final FlightQueryService flightQueryService;
  private final FlightUpdateStreamService flightUpdateStreamService;

  /**
   * Creates the controller with the query service dependency.
   *
   * @param flightQueryService business service used by query endpoints
   * @param flightUpdateStreamService SSE broadcaster for frontend refresh signals
   */
  public DashboardController(
      FlightQueryService flightQueryService,
      FlightUpdateStreamService flightUpdateStreamService) {
    this.flightQueryService = flightQueryService;
    this.flightUpdateStreamService = flightUpdateStreamService;
  }

  /**
   * Returns a filtered and sorted list of active flights for map rendering.
   *
   * @param bbox optional bounding box in {@code minLon,minLat,maxLon,maxLat} format
   * @param since optional lower bound on {@code lastSeen} (epoch or ISO-8601)
   * @param limit optional max number of returned items
   * @param sort optional sort field ({@code lastSeen|speed|altitude})
   * @param order optional sort order ({@code asc|desc})
   * @param militaryHint optional military hint filter
   * @param airframeType optional airframe type filter
   * @param category optional category filter
   * @param country optional country filter
   * @param typecode optional typecode filter
   * @return frontend-ready map payload
   */
  @GetMapping
  public FlightListResponse listFlights(
      @RequestParam(value = "bbox", required = false) String bbox,
      @RequestParam(value = "since", required = false) String since,
      @RequestParam(value = "limit", required = false) String limit,
      @RequestParam(value = "sort", required = false) String sort,
      @RequestParam(value = "order", required = false) String order,
      @RequestParam(value = "militaryHint", required = false) String militaryHint,
      @RequestParam(value = "airframeType", required = false) String airframeType,
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "country", required = false) String country,
      @RequestParam(value = "typecode", required = false) String typecode) {
    return flightQueryService.listFlights(
        bbox,
        since,
        limit,
        sort,
        order,
        militaryHint,
        airframeType,
        category,
        country,
        typecode);
  }

  /**
   * Opens an SSE stream used by frontend to refresh on new backend data.
   *
   * @return emitter that sends {@code batch-update} and heartbeat events
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return flightUpdateStreamService.openStream();
  }

  /**
   * Returns aggregated flight KPIs used by cards/charts under the map.
   *
   * @param bbox optional aggregation bounding box
   * @param window optional time window (for example {@code 24h}, {@code 30m})
   * @return metrics payload with breakdowns and activity series
   */
  @GetMapping("/metrics")
  public FlightsMetricsResponse metrics(
      @RequestParam(value = "bbox", required = false) String bbox,
      @RequestParam(value = "window", required = false) String window) {
    return flightQueryService.getFlightsMetrics(bbox, window);
  }

  /**
   * Returns the detail view for a single aircraft.
   *
   * @param icao24 normalized hexadecimal aircraft identifier
   * @param include optional include list ({@code track,enrichment})
   * @return enriched detail payload for one flight
   */
  @GetMapping("/{icao24:[A-Fa-f0-9]{6}}")
  public FlightDetailResponse detail(
      @PathVariable("icao24") String icao24,
      @RequestParam(value = "include", required = false) String include) {
    return flightQueryService.getFlightDetail(icao24, include);
  }

}
