package com.cloudradar.ingester;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "ingester.scheduling.enabled=false",
      "spring.task.scheduling.enabled=false"
    })
class IngesterApplicationTests {

  @Test
  void contextLoads() {
  }
}
