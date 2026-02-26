package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.cloudradar.ingester.config.OpenSkyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class OpenSkyTokenServiceTest {

  @Test
  void getTokenReinterruptsThreadWhenHttpClientSendIsInterrupted() throws Exception {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example",
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
    OpenSkyEndpointProvider endpointProvider = new OpenSkyEndpointProvider(properties);
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenThrow(new InterruptedException("interrupted"));

    OpenSkyTokenService service =
        new OpenSkyTokenService(
            endpointProvider, properties, new SimpleMeterRegistry(), httpClient, new ObjectMapper());

    assertThatThrownBy(service::getToken)
        .isInstanceOf(OpenSkyTokenService.TokenRefreshException.class)
        .hasMessageContaining("interrupted");
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  @Test
  void getTokenAddsRelayHeaderInTunnelPrimaryMode() throws Exception {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example",
            "https://opensky.example/token",
            "client-id",
            "client-secret",
            "tunnel-primary",
            "https://tunnel.example/api",
            "https://tunnel.example/token",
            "https://worker.example/api",
            "https://worker.example/token",
            "X-CloudRadar-Relay-Token",
            "relay-token");
    OpenSkyEndpointProvider endpointProvider = new OpenSkyEndpointProvider(properties);
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = (HttpResponse<String>) org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"access_token\":\"abc\",\"expires_in\":300}");
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(response);

    OpenSkyTokenService service =
        new OpenSkyTokenService(
            endpointProvider, properties, new SimpleMeterRegistry(), httpClient, new ObjectMapper());

    String token = service.getToken();

    assertThat(token).isEqualTo("abc");
    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    HttpRequest request = requestCaptor.getValue();
    assertThat(request.uri().toString()).isEqualTo("https://tunnel.example/token");
    assertThat(request.headers().allValues("X-CloudRadar-Relay-Token")).containsExactly("relay-token");
    assertThat(request.headers().allValues("Content-Type"))
        .containsExactly("application/x-www-form-urlencoded");
  }

  @Test
  void getTokenUsesCachedTokenWithoutSecondHttpCall() throws Exception {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example",
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
    OpenSkyEndpointProvider endpointProvider = new OpenSkyEndpointProvider(properties);
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = (HttpResponse<String>) org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"access_token\":\"cached\",\"expires_in\":3600}");
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(response);

    OpenSkyTokenService service =
        new OpenSkyTokenService(
            endpointProvider, properties, new SimpleMeterRegistry(), httpClient, new ObjectMapper());

    assertThat(service.getToken()).isEqualTo("cached");
    assertThat(service.getToken()).isEqualTo("cached");
    verify(httpClient, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
  }

  @Test
  void getTokenAppliesCooldownAfterFailure() throws Exception {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example",
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
    OpenSkyEndpointProvider endpointProvider = new OpenSkyEndpointProvider(properties);
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = (HttpResponse<String>) org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(500);
    when(response.body()).thenReturn("{\"error\":\"upstream\"}");
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(response);

    OpenSkyTokenService service =
        new OpenSkyTokenService(
            endpointProvider, properties, new SimpleMeterRegistry(), httpClient, new ObjectMapper());

    assertThatThrownBy(service::getToken)
        .isInstanceOf(OpenSkyTokenService.TokenRefreshException.class)
        .hasMessageContaining("Token refresh failed");
    assertThatThrownBy(service::getToken)
        .isInstanceOf(OpenSkyTokenService.TokenRefreshException.class)
        .hasMessageContaining("cooldown active");
    verify(httpClient, times(1)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
  }
}
