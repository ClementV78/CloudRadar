package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.OpenSkyProperties;
import org.springframework.stereotype.Component;

@Component
public class OpenSkyEndpointProvider {
  private final OpenSkyProperties properties;
  private String baseUrl;
  private String tokenUrl;

  public OpenSkyEndpointProvider(OpenSkyProperties properties) {
    this.properties = properties;
  }

  public synchronized String baseUrl() {
    if (baseUrl != null) {
      return baseUrl;
    }
    if (isPresent(properties.baseUrl())) {
      baseUrl = properties.baseUrl();
      return baseUrl;
    }
    return null;
  }

  public synchronized String tokenUrl() {
    if (tokenUrl != null) {
      return tokenUrl;
    }
    if (isPresent(properties.tokenUrl())) {
      tokenUrl = properties.tokenUrl();
      return tokenUrl;
    }
    return null;
  }

  private boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
