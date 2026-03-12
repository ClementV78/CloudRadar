package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.aircraft.AircraftMetadataRepository;
import com.cloudradar.dashboard.config.DashboardProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

final class FlightQueryHandlers {
  private final FlightListQueryHandler listQueryHandler;
  private final FlightDetailQueryHandler detailQueryHandler;
  private final FlightMetricsQueryHandler metricsQueryHandler;

  private FlightQueryHandlers(
      FlightListQueryHandler listQueryHandler,
      FlightDetailQueryHandler detailQueryHandler,
      FlightMetricsQueryHandler metricsQueryHandler) {
    this.listQueryHandler = listQueryHandler;
    this.detailQueryHandler = detailQueryHandler;
    this.metricsQueryHandler = metricsQueryHandler;
  }

  static FlightQueryHandlers build(
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      DashboardProperties properties,
      Optional<AircraftMetadataRepository> aircraftRepo,
      Optional<PrometheusMetricsService> prometheusMetricsService,
      Optional<PlanespottersPhotoService> planespottersPhotoService) {
    FlightSnapshotComponents components =
        FlightSnapshotComponents.build(redisTemplate, objectMapper, properties, aircraftRepo);

    FlightListQueryHandler listQueryHandler =
        new FlightListQueryHandler(properties, components.snapshotReader(), components.taxonomy());
    FlightDetailQueryHandler detailQueryHandler =
        new FlightDetailQueryHandler(
            components.snapshotReader(), components.snapshotEnricher(), planespottersPhotoService);
    FlightMetricsQueryHandler metricsQueryHandler =
        new FlightMetricsQueryHandler(
            properties,
            components.snapshotReader(),
            components.taxonomy(),
            components.metricsSupport(),
            prometheusMetricsService);

    return new FlightQueryHandlers(listQueryHandler, detailQueryHandler, metricsQueryHandler);
  }

  FlightListQueryHandler listQueryHandler() {
    return listQueryHandler;
  }

  FlightDetailQueryHandler detailQueryHandler() {
    return detailQueryHandler;
  }

  FlightMetricsQueryHandler metricsQueryHandler() {
    return metricsQueryHandler;
  }
}
