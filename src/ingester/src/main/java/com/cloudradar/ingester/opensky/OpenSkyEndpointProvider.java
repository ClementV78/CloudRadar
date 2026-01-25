package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.OpenSkyProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@Component
public class OpenSkyEndpointProvider {
  private final OpenSkyProperties properties;
  private final SsmClient ssmClient;
  private String baseUrl;
  private String tokenUrl;

  public OpenSkyEndpointProvider(OpenSkyProperties properties, SsmClient ssmClient) {
    this.properties = properties;
    this.ssmClient = ssmClient;
  }

  public synchronized String baseUrl() {
    if (baseUrl != null) {
      return baseUrl;
    }
    if (isPresent(properties.baseUrlSsm())) {
      baseUrl = getParameter(properties.baseUrlSsm());
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
    if (isPresent(properties.tokenUrlSsm())) {
      tokenUrl = getParameter(properties.tokenUrlSsm());
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

  private String getParameter(String name) {
    return ssmClient.getParameter(
        GetParameterRequest.builder().name(name).withDecryption(true).build()).parameter().value();
  }
}
