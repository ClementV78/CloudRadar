package com.cloudradar.processor.aircraft;

import com.cloudradar.processor.config.ProcessorProperties;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * Spring configuration for optional aircraft metadata enrichment.
 *
 * <p>When enabled, this configuration exposes a read-only SQLite-backed repository used by the
 * processor to enrich events and metrics.
 */
public class AircraftDbConfig {

  /**
   * Creates the aircraft metadata repository when {@code processor.aircraft-db.enabled=true}.
   *
   * @param properties processor configuration properties
   * @return repository backed by the local SQLite artifact
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "processor.aircraft-db", name = "enabled", havingValue = "true")
  public AircraftMetadataRepository aircraftMetadataRepository(ProcessorProperties properties) {
    String path = properties.getAircraftDb().getPath();
    if (path == null || path.isBlank()) {
      throw new IllegalStateException("processor.aircraft-db.enabled=true but processor.aircraft-db.path is empty");
    }
    return new SqliteAircraftMetadataRepository(Path.of(path), properties.getAircraftDb().getCacheSize());
  }
}
