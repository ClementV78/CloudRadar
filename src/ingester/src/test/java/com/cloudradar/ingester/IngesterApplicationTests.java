package com.cloudradar.ingester;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
    properties = {
      "spring.task.scheduling.enabled=false"
    })
class IngesterApplicationTests {
  @Autowired
  private ApplicationContext applicationContext;

  @Test
  void contextLoads() {
    assertNotNull(applicationContext);
    assertNotNull(applicationContext.getBean(FlightIngestJob.class));
  }
}
