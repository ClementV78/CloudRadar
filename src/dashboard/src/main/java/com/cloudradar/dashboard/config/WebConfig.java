package com.cloudradar.dashboard.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for dashboard API security defaults.
 *
 * <p>This class configures CORS specifically for API endpoints.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final DashboardProperties properties;

  /**
   * Creates Web MVC config with typed application properties.
   *
   * @param properties dashboard configuration tree
   */
  public WebConfig(DashboardProperties properties) {
    this.properties = properties;
  }

  /**
   * Registers API CORS mappings when an allowlist is configured.
   *
   * @param registry Spring CORS registry
   */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    List<String> allowedOrigins = properties.getApi().getCors().getAllowedOrigins().stream()
        .filter(origin -> origin != null && !origin.isBlank())
        .toList();
    if (allowedOrigins == null || allowedOrigins.isEmpty()) {
      return;
    }

    registry
        .addMapping("/api/**")
        .allowedMethods("GET", "OPTIONS")
        .allowedHeaders("*")
        .allowedOrigins(allowedOrigins.toArray(String[]::new))
        .maxAge(600);
  }
}
