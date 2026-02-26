package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.OpenSkyProperties;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenSkyEndpointProvider {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyEndpointProvider.class);
  private static final String MODE_DIRECT = "direct";
  private static final String MODE_TUNNEL_PRIMARY = "tunnel-primary";
  private static final String MODE_WORKER_FALLBACK = "worker-fallback";
  private static final String DEFAULT_RELAY_AUTH_HEADER = "X-CloudRadar-Relay-Token";

  private final OpenSkyProperties properties;
  private ResolvedEndpoints endpoints;

  public OpenSkyEndpointProvider(OpenSkyProperties properties) {
    this.properties = properties;
  }

  public synchronized String baseUrl() {
    return resolve().baseUrl();
  }

  public synchronized String tokenUrl() {
    return resolve().tokenUrl();
  }

  public synchronized String relayAuthHeaderName() {
    return resolve().relayAuthHeaderName();
  }

  public synchronized String relayAuthHeaderValue() {
    return resolve().relayAuthHeaderValue();
  }

  private boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }

  private ResolvedEndpoints resolve() {
    if (endpoints != null) {
      return endpoints;
    }

    String mode = normalizeMode(properties.routingMode());
    endpoints = switch (mode) {
      case MODE_DIRECT -> new ResolvedEndpoints(
          mode,
          required("opensky.base-url", properties.baseUrl()),
          required("opensky.token-url", properties.tokenUrl()),
          null,
          null);
      case MODE_TUNNEL_PRIMARY -> new ResolvedEndpoints(
          mode,
          required("opensky.tunnel-base-url", properties.tunnelBaseUrl()),
          required("opensky.tunnel-token-url", properties.tunnelTokenUrl()),
          resolveRelayAuthHeaderName(),
          resolveRelayAuthHeaderValue());
      case MODE_WORKER_FALLBACK -> new ResolvedEndpoints(
          mode,
          required("opensky.worker-base-url", properties.workerBaseUrl()),
          required("opensky.worker-token-url", properties.workerTokenUrl()),
          null,
          null);
      default -> throw new IllegalStateException(
          "Unsupported OpenSky routing mode '" + mode + "'. Expected one of: direct, tunnel-primary, worker-fallback");
    };
    log.info("OpenSky routing mode selected: {}", endpoints.mode());
    return endpoints;
  }

  private String normalizeMode(String mode) {
    if (!isPresent(mode)) {
      return MODE_DIRECT;
    }
    return mode.trim().toLowerCase(Locale.ROOT);
  }

  private String required(String field, String value) {
    if (!isPresent(value)) {
      throw new IllegalStateException("OpenSky configuration missing: " + field);
    }
    return value.trim();
  }

  private String resolveRelayAuthHeaderName() {
    if (!isPresent(properties.relayAuthToken())) {
      return null;
    }
    if (!isPresent(properties.relayAuthHeader())) {
      return DEFAULT_RELAY_AUTH_HEADER;
    }
    return properties.relayAuthHeader().trim();
  }

  private String resolveRelayAuthHeaderValue() {
    if (!isPresent(properties.relayAuthToken())) {
      return null;
    }
    return properties.relayAuthToken().trim();
  }

  private record ResolvedEndpoints(
      String mode,
      String baseUrl,
      String tokenUrl,
      String relayAuthHeaderName,
      String relayAuthHeaderValue) {}
}
