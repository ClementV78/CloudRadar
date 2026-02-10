package com.cloudradar.processor.aircraft;

import com.cloudradar.processor.config.ProcessorProperties;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AircraftDbConfig {

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

