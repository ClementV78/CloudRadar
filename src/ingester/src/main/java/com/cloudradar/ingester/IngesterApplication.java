package com.cloudradar.ingester;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IngesterApplication {
  // Main entrypoint: boots Spring and enables the scheduled ingestion loop.
  public static void main(String[] args) {
    SpringApplication.run(IngesterApplication.class, args);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @ConditionalOnProperty(
      prefix = "ingester.scheduling",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  static class SchedulingConfiguration {}
}
