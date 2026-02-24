package com.cloudradar.processor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.cloudradar.processor.service.RedisAggregateProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class ProcessorApplicationTests {
  @Autowired
  private ApplicationContext applicationContext;

  @MockBean
  private RedisAggregateProcessor redisAggregateProcessor;

  @Test
  void contextLoads() {
    assertNotNull(applicationContext);
  }
}
