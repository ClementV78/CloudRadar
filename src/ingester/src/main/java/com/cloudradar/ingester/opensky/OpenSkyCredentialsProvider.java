package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.OpenSkyProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

@Component
public class OpenSkyCredentialsProvider {
  private final OpenSkyProperties properties;
  private final SsmClient ssmClient;
  private String clientId;
  private String clientSecret;

  public OpenSkyCredentialsProvider(OpenSkyProperties properties, SsmClient ssmClient) {
    this.properties = properties;
    this.ssmClient = ssmClient;
  }

  public synchronized OpenSkyCredentials get() {
    // Prefer explicit env vars; fall back to SSM parameter store if configured.
    if (clientId != null && clientSecret != null) {
      return new OpenSkyCredentials(clientId, clientSecret);
    }

    if (isPresent(properties.clientId()) && isPresent(properties.clientSecret())) {
      clientId = properties.clientId();
      clientSecret = properties.clientSecret();
      return new OpenSkyCredentials(clientId, clientSecret);
    }

    if (isPresent(properties.clientIdSsm()) && isPresent(properties.clientSecretSsm())) {
      clientId = getParameter(properties.clientIdSsm());
      clientSecret = getParameter(properties.clientSecretSsm());
      return new OpenSkyCredentials(clientId, clientSecret);
    }

    throw new IllegalStateException("OpenSky credentials are missing. Set OPENSKY_CLIENT_ID/OPENSKY_CLIENT_SECRET or SSM parameter names.");
  }

  private boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }

  private String getParameter(String name) {
    return ssmClient.getParameter(
        GetParameterRequest.builder().name(name).withDecryption(true).build()).parameter().value();
  }
}
