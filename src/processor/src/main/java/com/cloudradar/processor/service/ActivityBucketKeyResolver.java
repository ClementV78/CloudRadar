package com.cloudradar.processor.service;

/**
 * Pure domain class for activity bucket key resolution.
 *
 * <p>Computes the bucket epoch, Redis key names, and TTL
 * from a wall-clock epoch and configuration parameters.
 * No Redis dependency — pure temporal math.
 */
public class ActivityBucketKeyResolver {

  /** Resolved bucket key information. */
  public record BucketKey(
      String hashKey,
      String hllKey,
      String militaryHllKey,
      long bucketEpoch,
      long ttlSeconds) {}

  private static final String AIRCRAFT_HLL_SUFFIX = ":aircraft_hll";
  private static final String AIRCRAFT_MILITARY_HLL_SUFFIX = ":aircraft_military_hll";

  /**
   * Resolves bucket keys and TTL for a given epoch.
   *
   * @param epochSeconds current wall-clock epoch in seconds
   * @param bucketSizeSeconds bucket width (≥1)
   * @param retentionSeconds retention period for bucket expiry
   * @param prefix Redis key prefix (e.g. {@code cloudradar:activity:bucket:})
   * @return resolved bucket key information
   */
  public BucketKey resolve(long epochSeconds, long bucketSizeSeconds, long retentionSeconds, String prefix) {
    long safeBucketSize = Math.max(1L, bucketSizeSeconds);
    long bucketEpoch = (epochSeconds / safeBucketSize) * safeBucketSize;
    String hashKey = prefix + bucketEpoch;
    String hllKey = hashKey + AIRCRAFT_HLL_SUFFIX;
    String militaryHllKey = hashKey + AIRCRAFT_MILITARY_HLL_SUFFIX;
    long ttl = Math.max(safeBucketSize, retentionSeconds + safeBucketSize);
    return new BucketKey(hashKey, hllKey, militaryHllKey, bucketEpoch, ttl);
  }
}
