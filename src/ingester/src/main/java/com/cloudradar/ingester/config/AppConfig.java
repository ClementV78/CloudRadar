package com.cloudradar.ingester.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;

@Configuration
public class AppConfig {
  @Bean
  public HttpClient httpClient() {
    return HttpClient.newHttpClient();
  }

  @Bean
  public SsmClient ssmClient(AwsProperties awsProperties) {
    return SsmClient.builder().region(Region.of(awsProperties.region())).build();
  }
}
