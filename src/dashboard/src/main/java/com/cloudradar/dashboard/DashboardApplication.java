package com.cloudradar.dashboard;

import com.cloudradar.dashboard.config.DashboardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Main Spring Boot entrypoint for the CloudRadar dashboard API service.
 *
 * <p>The application mainly exposes read endpoints used by the frontend map/KPI cards, plus a
 * lightweight control endpoint for temporary OpenSky bbox boost.
 */
@SpringBootApplication
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardApplication {
  /**
   * Starts the dashboard API application.
   *
   * @param args standard Spring Boot startup arguments
   */
  public static void main(String[] args) {
    SpringApplication.run(DashboardApplication.class, args);
  }
}
