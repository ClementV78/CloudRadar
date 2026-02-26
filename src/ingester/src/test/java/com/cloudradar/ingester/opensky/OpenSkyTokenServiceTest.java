package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.cloudradar.ingester.config.OpenSkyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class OpenSkyTokenServiceTest {

  @Test
  void getTokenReinterruptsThreadWhenHttpClientSendIsInterrupted() throws Exception {
    OpenSkyProperties properties =
        new OpenSkyProperties(
            "https://opensky.example",
            "https://opensky.example/token",
            "client-id",
            "client-secret");
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
}
