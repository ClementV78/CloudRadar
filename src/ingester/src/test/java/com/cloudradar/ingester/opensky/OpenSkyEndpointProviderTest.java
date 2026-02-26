package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudradar.ingester.config.OpenSkyProperties;
import org.junit.jupiter.api.Test;

class OpenSkyEndpointProviderTest {

  @Test
  void resolvesDirectModeByDefault() {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example/api",
            "https://opensky.example/token",
            "client-id",
            "client-secret",
            "direct",
            null,
            null,
            null,
            null,
            null,
            null);

    OpenSkyEndpointProvider provider = new OpenSkyEndpointProvider(properties);

    assertThat(provider.baseUrl()).isEqualTo("https://opensky.example/api");
    assertThat(provider.tokenUrl()).isEqualTo("https://opensky.example/token");
    assertThat(provider.relayAuthHeaderName()).isNull();
    assertThat(provider.relayAuthHeaderValue()).isNull();
  }

  @Test
  void resolvesTunnelPrimaryModeWithRelayHeader() {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example/api",
            "https://opensky.example/token",
            "client-id",
            "client-secret",
            "tunnel-primary",
            "https://tunnel.example/api",
            "https://tunnel.example/token",
            "https://worker.example/api",
            "https://worker.example/token",
            "X-Relay",
            "relay-token");

    OpenSkyEndpointProvider provider = new OpenSkyEndpointProvider(properties);

    assertThat(provider.baseUrl()).isEqualTo("https://tunnel.example/api");
    assertThat(provider.tokenUrl()).isEqualTo("https://tunnel.example/token");
    assertThat(provider.relayAuthHeaderName()).isEqualTo("X-Relay");
    assertThat(provider.relayAuthHeaderValue()).isEqualTo("relay-token");
  }

  @Test
  void resolvesWorkerFallbackModeWithoutRelayHeader() {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example/api",
            "https://opensky.example/token",
            "client-id",
            "client-secret",
            "worker-fallback",
            "https://tunnel.example/api",
            "https://tunnel.example/token",
            "https://worker.example/api",
            "https://worker.example/token",
            "X-Relay",
            "relay-token");

    OpenSkyEndpointProvider provider = new OpenSkyEndpointProvider(properties);

    assertThat(provider.baseUrl()).isEqualTo("https://worker.example/api");
    assertThat(provider.tokenUrl()).isEqualTo("https://worker.example/token");
    assertThat(provider.relayAuthHeaderName()).isNull();
    assertThat(provider.relayAuthHeaderValue()).isNull();
  }

  @Test
  void usesDefaultRelayHeaderNameWhenTokenIsSetWithoutHeader() {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example/api",
            "https://opensky.example/token",
            "client-id",
            "client-secret",
            "tunnel-primary",
            "https://tunnel.example/api",
            "https://tunnel.example/token",
            "https://worker.example/api",
            "https://worker.example/token",
            "",
            "relay-token");

    OpenSkyEndpointProvider provider = new OpenSkyEndpointProvider(properties);

    assertThat(provider.relayAuthHeaderName()).isEqualTo("X-CloudRadar-Relay-Token");
    assertThat(provider.relayAuthHeaderValue()).isEqualTo("relay-token");
  }

  @Test
  void failsFastWhenRequiredModeEndpointIsMissing() {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example/api",
            "https://opensky.example/token",
            "client-id",
            "client-secret",
            "tunnel-primary",
            "",
            "https://tunnel.example/token",
            "https://worker.example/api",
            "https://worker.example/token",
            "X-Relay",
            "relay-token");

    OpenSkyEndpointProvider provider = new OpenSkyEndpointProvider(properties);

    assertThatThrownBy(provider::baseUrl)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opensky.tunnel-base-url");
  }

  @Test
  void failsFastWhenModeIsUnknown() {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example/api",
            "https://opensky.example/token",
            "client-id",
            "client-secret",
            "unknown",
            "https://tunnel.example/api",
            "https://tunnel.example/token",
            "https://worker.example/api",
            "https://worker.example/token",
            "X-Relay",
            "relay-token");

    OpenSkyEndpointProvider provider = new OpenSkyEndpointProvider(properties);

    assertThatThrownBy(provider::baseUrl)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported OpenSky routing mode");
  }
}
