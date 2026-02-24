package com.cloudradar.processor;

import com.cloudradar.processor.service.RedisAggregateProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class ProcessorApplicationTests {

  @MockBean
  private RedisAggregateProcessor redisAggregateProcessor;

  @Test
  void contextLoads() {
  }
}
