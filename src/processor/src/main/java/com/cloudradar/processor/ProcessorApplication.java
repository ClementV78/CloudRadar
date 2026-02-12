package com.cloudradar.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entrypoint for the processor service.
 *
 * <p>The processor consumes telemetry events from Redis, maintains aggregates, and exposes
 * operational metrics and health endpoints.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProcessorApplication {
  /**
   * Starts the processor application.
   *
   * @param args CLI arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(ProcessorApplication.class, args);
  }
}
