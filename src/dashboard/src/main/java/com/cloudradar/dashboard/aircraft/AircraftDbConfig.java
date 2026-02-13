package com.cloudradar.dashboard.aircraft;

import com.cloudradar.dashboard.config.DashboardProperties;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for optional aircraft metadata enrichment.
 *
 * <p>The repository bean is only created when SQLite enrichment is enabled by configuration.
 */
@Configuration
public class AircraftDbConfig {

  /**
   * Creates a read-only SQLite-backed metadata repository.
   *
   * @param properties typed dashboard properties
   * @return metadata repository instance
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "dashboard.aircraft-db", name = "enabled", havingValue = "true")
  public AircraftMetadataRepository aircraftMetadataRepository(DashboardProperties properties) {
    String path = properties.getAircraftDb().getPath();
    if (path == null || path.isBlank()) {
      throw new IllegalStateException("dashboard.aircraft-db.enabled=true but path is empty");
    }
    return new SqliteAircraftMetadataRepository(Path.of(path), properties.getAircraftDb().getCacheSize());
  }
}
