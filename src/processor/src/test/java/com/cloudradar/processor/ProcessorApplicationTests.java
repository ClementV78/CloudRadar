package com.cloudradar.processor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "processor.loop.enabled=false")
class ProcessorApplicationTests {

  @Test
  void contextLoads() {
  }
}
