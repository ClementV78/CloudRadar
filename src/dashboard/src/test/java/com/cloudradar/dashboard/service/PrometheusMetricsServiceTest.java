package com.cloudradar.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class PrometheusMetricsServiceTest {

  @Test
  void queryOpenSkyCreditsPerRequest24hTrimsTrailingSlashesInBaseUrl() throws Exception {
    DashboardProperties properties = buildProperties("http://prometheus.test///");
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = (HttpResponse<String>) org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn("{\"status\":\"success\",\"data\":{\"result\":[{\"value\":[1700000000,\"1.5\"]}]}}");
    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(response);

    PrometheusMetricsService service =
        new PrometheusMetricsService(properties, new ObjectMapper(), httpClient);

    assertThat(service.queryOpenSkyCreditsPerRequest24h()).contains(1.5);

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient)
        .send(requestCaptor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    String requestUri = requestCaptor.getValue().uri().toString();
    assertThat(requestUri).startsWith("http://prometheus.test/api/v1/query?query=");
    assertThat(requestUri).doesNotContain("test///");
  }

  @Test
  void queryOpenSkyCreditsPerRequest24hReinterruptsThreadWhenHttpClientIsInterrupted()
      throws Exception {
    DashboardProperties properties = buildProperties("http://prometheus.test");
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenThrow(new InterruptedException("interrupted"));

    PrometheusMetricsService service =
        new PrometheusMetricsService(properties, new ObjectMapper(), httpClient);

    assertThat(service.queryOpenSkyCreditsPerRequest24h()).isEmpty();
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
  }

  private DashboardProperties buildProperties(String baseUrl) {
    DashboardProperties properties = new DashboardProperties();
    properties.getPrometheus().setEnabled(true);
    properties.getPrometheus().setBaseUrl(baseUrl);
    properties.getPrometheus().setQueryTimeoutMs(500);
    return properties;
  }
}
