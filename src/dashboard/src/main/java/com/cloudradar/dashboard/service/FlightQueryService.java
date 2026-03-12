package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.aircraft.AircraftMetadataRepository;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.FlightDetailResponse;
import com.cloudradar.dashboard.model.FlightListResponse;
import com.cloudradar.dashboard.model.FlightsMetricsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class FlightQueryService {
  private final FlightListQueryHandler listQueryHandler;
  private final FlightDetailQueryHandler detailQueryHandler;
  private final FlightMetricsQueryHandler metricsQueryHandler;

  @Autowired
  public FlightQueryService(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties properties,
      Optional<AircraftMetadataRepository> aircraftRepo,
      Optional<PrometheusMetricsService> prometheusMetricsService,
      Optional<PlanespottersPhotoService> planespottersPhotoService) {
    this(
        FlightQueryHandlers.build(
            redisTemplate,
            objectMapper,
            properties,
            aircraftRepo,
            prometheusMetricsService,
            planespottersPhotoService));
  }

  FlightQueryService(FlightQueryHandlers handlers) {
    this.listQueryHandler = handlers.listQueryHandler();
    this.detailQueryHandler = handlers.detailQueryHandler();
    this.metricsQueryHandler = handlers.metricsQueryHandler();
  }

  public FlightListResponse listFlights(
      String bboxRaw,
      String sinceRaw,
      String limitRaw,
      String sortRaw,
      String orderRaw,
      String militaryHintRaw,
      String airframeTypeRaw,
      String categoryRaw,
      String countryRaw,
      String typecodeRaw) {
    return listQueryHandler.listFlights(
        bboxRaw,
        sinceRaw,
        limitRaw,
        sortRaw,
        orderRaw,
        militaryHintRaw,
        airframeTypeRaw,
        categoryRaw,
        countryRaw,
        typecodeRaw);
  }

  public FlightDetailResponse getFlightDetail(String icao24Raw, String includeRaw) {
    return detailQueryHandler.getFlightDetail(icao24Raw, includeRaw);
  }

  public FlightsMetricsResponse getFlightsMetrics(String bboxRaw, String windowRaw) {
    return metricsQueryHandler.getFlightsMetrics(bboxRaw, windowRaw);
  }
}
