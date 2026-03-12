package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.IngesterProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenSkyClient {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyClient.class);

  private final OpenSkyEndpointProvider endpointProvider;
  private final OpenSkyTokenService tokenService;
  private final HttpClient httpClient;
  private final OpenSkyBboxResolver bboxResolver;
  private final OpenSkyResponseParser responseParser;
  private final OpenSkyStatesHttpMetrics httpMetrics;

  public OpenSkyClient(
      OpenSkyEndpointProvider endpointProvider,
      OpenSkyTokenService tokenService,
      HttpClient httpClient,
      OpenSkyBboxResolver bboxResolver,
      OpenSkyResponseParser responseParser,
      OpenSkyStatesHttpMetrics httpMetrics) {
    this.endpointProvider = endpointProvider;
    this.tokenService = tokenService;
    this.httpClient = httpClient;
    this.bboxResolver = bboxResolver;
    this.responseParser = responseParser;
    this.httpMetrics = httpMetrics;
  }

  public FetchResult fetchStates() {
    long httpStartNs = -1L;
    boolean requestRecorded = false;
    try {
      HttpRequest request = buildStatesRequest();
      httpStartNs = System.nanoTime();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      requestRecorded = true;
      return handleResponse(response, httpStartNs);
    } catch (OpenSkyTokenService.TokenRefreshException ex) {
      handleRequestException(httpStartNs, requestRecorded, ex, false);
      throw ex;
    } catch (InterruptedException ex) {
      handleRequestException(httpStartNs, requestRecorded, ex, true);
      return new FetchResult(List.of(), null, null, null);
    } catch (Exception ex) {
      handleRequestException(httpStartNs, requestRecorded, ex, false);
      return new FetchResult(List.of(), null, null, null);
    }
  }

  private HttpRequest buildStatesRequest() {
    IngesterProperties.Bbox bbox = bboxResolver.resolveEffectiveBbox();
    String baseUrl = endpointProvider.baseUrl();
    if (!isPresent(baseUrl)) {
      throw new IllegalStateException("OpenSky base URL is missing.");
    }

    String url = String.format(
        "%s/states/all?lamin=%s&lamax=%s&lomin=%s&lomax=%s",
        baseUrl,
        bbox.latMin(),
        bbox.latMax(),
        bbox.lonMin(),
        bbox.lonMax());

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Authorization", "Bearer " + tokenService.getToken());
    addRelayAuthHeader(requestBuilder);
    return requestBuilder.GET().build();
  }

  private FetchResult handleResponse(HttpResponse<String> response, long httpStartNs) throws Exception {
    int statusCode = response.statusCode();
    OpenSkyRateLimitHeaders headers = responseParser.parseHeaders(response);

    if (statusCode == 429) {
      httpMetrics.recordResponse(httpStartNs, statusCode, OpenSkyStatesHttpMetrics.Outcome.RATE_LIMITED);
      log.warn("OpenSky rate limit hit (429)");
      return emptyResult(headers);
    }

    if (statusCode >= 400) {
      OpenSkyStatesHttpMetrics.Outcome outcome =
          statusCode >= 500
              ? OpenSkyStatesHttpMetrics.Outcome.SERVER_ERROR
              : OpenSkyStatesHttpMetrics.Outcome.CLIENT_ERROR;
      httpMetrics.recordResponse(httpStartNs, statusCode, outcome);
      log.warn("OpenSky fetch failed: status={}", statusCode);
      return emptyResult(headers);
    }

    httpMetrics.recordResponse(httpStartNs, statusCode, OpenSkyStatesHttpMetrics.Outcome.SUCCESS);
    return responseParser.parseStatesResponse(response.body(), headers);
  }

  private FetchResult emptyResult(OpenSkyRateLimitHeaders headers) {
    return new FetchResult(List.of(), headers.remainingCredits(), headers.creditLimit(), headers.resetAtEpochSeconds());
  }

  private void handleRequestException(
      long httpStartNs,
      boolean requestRecorded,
      Exception exception,
      boolean interrupted) {
    httpMetrics.recordException(httpStartNs, requestRecorded);

    if (interrupted) {
      Thread.currentThread().interrupt();
      log.error("Failed to fetch OpenSky states: request interrupted", exception);
      return;
    }
    log.error("Failed to fetch OpenSky states", exception);
  }

  private void addRelayAuthHeader(HttpRequest.Builder requestBuilder) {
    String headerName = endpointProvider.relayAuthHeaderName();
    String headerValue = endpointProvider.relayAuthHeaderValue();
    if (!isPresent(headerName) || !isPresent(headerValue)) {
      return;
    }
    requestBuilder.header(headerName, headerValue);
  }

  private boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
