package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudradar.ingester.config.IngesterProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.StringRedisTemplate;

class OpenSkyClientTest {

  @Test
  void fetchStatesMapsOpenSkyRowAndRateLimitHeaders() throws Exception {
    OpenSkyEndpointProvider endpointProvider = org.mockito.Mockito.mock(OpenSkyEndpointProvider.class);
    when(endpointProvider.baseUrl()).thenReturn("https://opensky.example");

    OpenSkyTokenService tokenService = org.mockito.Mockito.mock(OpenSkyTokenService.class);
    when(tokenService.getToken()).thenReturn("test-token");

    StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = (HttpResponse<String>) org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("""
        {
          "states": [
            ["abc123", "AFR123  ", null, 1700000001, 1700000002, 2.3522, 48.8566, 11000.0, false, 230.5, 180.0, null, null, 11300.0]
          ]
        }
        """);
    when(response.headers()).thenReturn(HttpHeaders.of(
        Map.of(
            "X-Rate-Limit-Remaining", List.of("3990"),
            "X-Rate-Limit-Limit", List.of("4000"),
            "X-Rate-Limit-Reset", List.of("120")),
        (name, value) -> true));

    when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
        .thenReturn(response);

    IngesterProperties properties = new IngesterProperties(
        10_000L,
        new IngesterProperties.Redis("cloudradar:ingest:queue"),
        new IngesterProperties.Bbox(46.0, 50.0, 2.0, 4.0),
        new IngesterProperties.RateLimit(4000, 50, 80, 95, 30_000L, 300_000L),
        new IngesterProperties.BboxBoost("cloudradar:opensky:bbox:boost:active", 1.0));

    OpenSkyClient client = new OpenSkyClient(
        endpointProvider,
        properties,
        redisTemplate,
        tokenService,
        new SimpleMeterRegistry(),
        httpClient,
        new ObjectMapper());

    long beforeCallEpoch = System.currentTimeMillis() / 1000;
    FetchResult result = client.fetchStates();

    assertThat(result.states()).hasSize(1);
    FlightState state = result.states().get(0);
    assertThat(state.icao24()).isEqualTo("abc123");
    assertThat(state.callsign()).isEqualTo("AFR123");
    assertThat(state.latitude()).isEqualTo(48.8566);
    assertThat(state.longitude()).isEqualTo(2.3522);
    assertThat(state.velocity()).isEqualTo(230.5);
    assertThat(state.heading()).isEqualTo(180.0);
    assertThat(state.geoAltitude()).isEqualTo(11300.0);
    assertThat(state.baroAltitude()).isEqualTo(11000.0);
    assertThat(state.onGround()).isFalse();
    assertThat(state.timePosition()).isEqualTo(1700000001L);
    assertThat(state.lastContact()).isEqualTo(1700000002L);

    assertThat(result.remainingCredits()).isEqualTo(3990);
    assertThat(result.creditLimit()).isEqualTo(4000);
    assertThat(result.resetAtEpochSeconds()).isBetween(beforeCallEpoch + 110L, beforeCallEpoch + 130L);

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    HttpRequest request = requestCaptor.getValue();
    assertThat(request.uri().toString())
        .isEqualTo("https://opensky.example/states/all?lamin=46.0&lamax=50.0&lomin=2.0&lomax=4.0");
    assertThat(request.headers().firstValue("Authorization")).hasValue("Bearer test-token");
  }
}
