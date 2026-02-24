package com.cloudradar.ingester;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(
    properties = {
      "spring.task.scheduling.enabled=false"
    })
class IngesterApplicationTests {
  @MockBean
  private FlightIngestJob flightIngestJob;

  @Test
  void contextLoads() {
  }
}
