package com.cloudradar.ingester.opensky;

import com.cloudradar.ingester.config.IngesterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class OpenSkyBboxResolver {
  private static final Logger log = LoggerFactory.getLogger(OpenSkyBboxResolver.class);

  private final IngesterProperties ingesterProperties;
  private final StringRedisTemplate redisTemplate;

  OpenSkyBboxResolver(IngesterProperties ingesterProperties, StringRedisTemplate redisTemplate) {
    this.ingesterProperties = ingesterProperties;
    this.redisTemplate = redisTemplate;
  }

  IngesterProperties.Bbox resolveEffectiveBbox() {
    IngesterProperties.Bbox base = ingesterProperties.bbox();
    IngesterProperties.BboxBoost boost = ingesterProperties.bboxBoost();
    if (boost == null || boost.factor() <= 1.0 || !isPresent(boost.redisKey())) {
      return base;
    }

    try {
      String active = redisTemplate.opsForValue().get(boost.redisKey());
      if (!isPresent(active)) {
        return base;
      }
      return scaleBboxByArea(base, boost.factor());
    } catch (Exception ex) {
      log.debug("Unable to read bbox boost key from Redis, using base bbox", ex);
      return base;
    }
  }

  private IngesterProperties.Bbox scaleBboxByArea(IngesterProperties.Bbox bbox, double areaFactor) {
    double factor = Math.max(1.0, areaFactor);
    double linearScale = Math.sqrt(factor);

    double centerLat = (bbox.latMin() + bbox.latMax()) / 2.0;
    double centerLon = (bbox.lonMin() + bbox.lonMax()) / 2.0;
    double halfLat = (bbox.latMax() - bbox.latMin()) / 2.0 * linearScale;
    double halfLon = (bbox.lonMax() - bbox.lonMin()) / 2.0 * linearScale;

    return new IngesterProperties.Bbox(
        clamp(centerLat - halfLat, -90.0, 90.0),
        clamp(centerLat + halfLat, -90.0, 90.0),
        clamp(centerLon - halfLon, -180.0, 180.0),
        clamp(centerLon + halfLon, -180.0, 180.0));
  }

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }

  private boolean isPresent(String value) {
    return value != null && !value.isBlank();
  }
}
