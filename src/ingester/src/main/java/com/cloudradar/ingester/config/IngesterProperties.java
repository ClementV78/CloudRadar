package com.cloudradar.ingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingester")
public record IngesterProperties(long refreshMs, Redis redis, Bbox bbox, RateLimit rateLimit, BboxBoost bboxBoost) {
  public record Redis(String key) {}

  public record Bbox(double latMin, double latMax, double lonMin, double lonMax) {}

  public record BboxBoost(String redisKey, double factor) {}

  public record RateLimit(
      long quota,
      int warn50,
      int warn80,
      int warn95,
      long refreshWarnMs,
      long refreshCriticalMs) {}
}
