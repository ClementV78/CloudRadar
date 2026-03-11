package com.cloudradar.processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudradar.processor.service.ActivityBucketKeyResolver.BucketKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityBucketKeyResolverTest {

  private ActivityBucketKeyResolver resolver;
  private static final String PREFIX = "cloudradar:activity:bucket:";

  @BeforeEach
  void setUp() {
    resolver = new ActivityBucketKeyResolver();
  }

  @Test
  void bucketEpochAlignsToBucketSize() {
    BucketKey key = resolver.resolve(1_700_000_050L, 300L, 172_800L, PREFIX);
    assertEquals(1_699_999_800L, key.bucketEpoch());
  }

  @Test
  void epochExactlyOnBoundary() {
    BucketKey key = resolver.resolve(1_699_999_800L, 300L, 172_800L, PREFIX);
    assertEquals(1_699_999_800L, key.bucketEpoch());
  }

  @Test
  void hashKeyFormat() {
    BucketKey key = resolver.resolve(1_700_000_050L, 300L, 172_800L, PREFIX);
    assertEquals("cloudradar:activity:bucket:1699999800", key.hashKey());
  }

  @Test
  void hllKeyFormat() {
    BucketKey key = resolver.resolve(1_700_000_050L, 300L, 172_800L, PREFIX);
    assertEquals("cloudradar:activity:bucket:1699999800:aircraft_hll", key.hllKey());
  }

  @Test
  void militaryHllKeyFormat() {
    BucketKey key = resolver.resolve(1_700_000_050L, 300L, 172_800L, PREFIX);
    assertEquals("cloudradar:activity:bucket:1699999800:aircraft_military_hll", key.militaryHllKey());
  }

  @Test
  void ttlCalculation() {
    BucketKey key = resolver.resolve(1_700_000_050L, 300L, 172_800L, PREFIX);
    // ttl = max(300, 172800 + 300) = 173100
    assertEquals(173_100L, key.ttlSeconds());
  }

  @Test
  void ttlWhenRetentionIsZero() {
    BucketKey key = resolver.resolve(1_700_000_050L, 300L, 0L, PREFIX);
    // ttl = max(300, 0 + 300) = 300
    assertEquals(300L, key.ttlSeconds());
  }

  @Test
  void zeroBucketSizeTreatedAsOne() {
    BucketKey key = resolver.resolve(100L, 0L, 60L, PREFIX);
    // safeBucketSize = max(1, 0) = 1
    // bucketEpoch = (100/1)*1 = 100
    assertEquals(100L, key.bucketEpoch());
    // ttl = max(1, 60+1) = 61
    assertEquals(61L, key.ttlSeconds());
  }
}
