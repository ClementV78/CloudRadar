package com.cloudradar.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the processor service.
 *
 * <p>Values are bound from {@code application.yml} and environment variables under the
 * {@code processor.*} prefix.
 */
@ConfigurationProperties(prefix = "processor")
public class ProcessorProperties {
  private final Redis redis = new Redis();
  private final Bbox bbox = new Bbox();
  private final AircraftDb aircraftDb = new AircraftDb();
  private int trackLength = 180;
  private long pollTimeoutSeconds = 2;
  private long activityBucketSeconds = 60;
  private long activityBucketRetentionSeconds = 172800;

  public Redis getRedis() {
    return redis;
  }

  public Bbox getBbox() {
    return bbox;
  }

  public AircraftDb getAircraftDb() {
    return aircraftDb;
  }

  public int getTrackLength() {
    return trackLength;
  }

  public void setTrackLength(int trackLength) {
    this.trackLength = trackLength;
  }

  public long getPollTimeoutSeconds() {
    return pollTimeoutSeconds;
  }

  public void setPollTimeoutSeconds(long pollTimeoutSeconds) {
    this.pollTimeoutSeconds = pollTimeoutSeconds;
  }

  public long getActivityBucketSeconds() {
    return activityBucketSeconds;
  }

  public void setActivityBucketSeconds(long activityBucketSeconds) {
    this.activityBucketSeconds = activityBucketSeconds;
  }

  public long getActivityBucketRetentionSeconds() {
    return activityBucketRetentionSeconds;
  }

  public void setActivityBucketRetentionSeconds(long activityBucketRetentionSeconds) {
    this.activityBucketRetentionSeconds = activityBucketRetentionSeconds;
  }

  /** Redis key names used by the processor read/write path. */
  public static class Redis {
    private String inputKey = "cloudradar:ingest:queue";
    private String lastPositionsKey = "cloudradar:aircraft:last";
    private String trackKeyPrefix = "cloudradar:aircraft:track:";
    private String bboxSetKey = "cloudradar:aircraft:in_bbox";
    private String activityBucketKeyPrefix = "cloudradar:activity:bucket:";

    public String getInputKey() {
      return inputKey;
    }

    public void setInputKey(String inputKey) {
      this.inputKey = inputKey;
    }

    public String getLastPositionsKey() {
      return lastPositionsKey;
    }

    public void setLastPositionsKey(String lastPositionsKey) {
      this.lastPositionsKey = lastPositionsKey;
    }

    public String getTrackKeyPrefix() {
      return trackKeyPrefix;
    }

    public void setTrackKeyPrefix(String trackKeyPrefix) {
      this.trackKeyPrefix = trackKeyPrefix;
    }

    public String getBboxSetKey() {
      return bboxSetKey;
    }

    public void setBboxSetKey(String bboxSetKey) {
      this.bboxSetKey = bboxSetKey;
    }

    public String getActivityBucketKeyPrefix() {
      return activityBucketKeyPrefix;
    }

    public void setActivityBucketKeyPrefix(String activityBucketKeyPrefix) {
      this.activityBucketKeyPrefix = activityBucketKeyPrefix;
    }
  }

  /** Geographic bounding box used to maintain the in-bbox aircraft set. */
  public static class Bbox {
    private double latMin = 46.8296;
    private double latMax = 50.8836;
    private double lonMin = -0.7389;
    private double lonMax = 5.4433;

    public double getLatMin() {
      return latMin;
    }

    public void setLatMin(double latMin) {
      this.latMin = latMin;
    }

    public double getLatMax() {
      return latMax;
    }

    public void setLatMax(double latMax) {
      this.latMax = latMax;
    }

    public double getLonMin() {
      return lonMin;
    }

    public void setLonMin(double lonMin) {
      this.lonMin = lonMin;
    }

    public double getLonMax() {
      return lonMax;
    }

    public void setLonMax(double lonMax) {
      this.lonMax = lonMax;
    }
  }

  /** Aircraft reference DB settings used by optional metadata enrichment. */
  public static class AircraftDb {
    private boolean enabled = false;
    private String path = "";
    private int cacheSize = 50000;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getPath() {
      return path;
    }

    public void setPath(String path) {
      this.path = path;
    }

    public int getCacheSize() {
      return cacheSize;
    }

    public void setCacheSize(int cacheSize) {
      this.cacheSize = cacheSize;
    }
  }
}
