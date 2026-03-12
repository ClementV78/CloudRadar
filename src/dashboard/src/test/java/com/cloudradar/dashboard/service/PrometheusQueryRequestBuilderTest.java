package com.cloudradar.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PrometheusQueryRequestBuilderTest {

  @Test
  void buildUri_trimsTrailingSlashesAndEncodesQuery() {
    PrometheusQueryRequestBuilder builder = new PrometheusQueryRequestBuilder("http://prometheus.test///");
    URI uri = builder.buildUri("up == 1");

    assertThat(uri.toString()).startsWith("http://prometheus.test/api/v1/query?query=");
    assertThat(uri.toString()).contains("up+%3D%3D+1");
  }
}
