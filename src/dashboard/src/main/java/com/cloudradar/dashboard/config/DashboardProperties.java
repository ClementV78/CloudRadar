package com.cloudradar.dashboard.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration container for the dashboard API service.
 *
 * <p>Values are bound from {@code dashboard.*} in {@code application.yml} and environment
 * variables.
 */
@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties {
  private final Redis redis = new Redis();
  private final Api api = new Api();
  private final AircraftDb aircraftDb = new AircraftDb();

  public Redis getRedis() {
    return redis;
  }

  public Api getApi() {
    return api;
  }

  public AircraftDb getAircraftDb() {
    return aircraftDb;
  }

  /** Redis key configuration used by dashboard read paths. */
  public static class Redis {
    private String lastPositionsKey = "cloudradar:aircraft:last";
    private String trackKeyPrefix = "cloudradar:aircraft:track:";

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
  }

  /** API-level behavior configuration (limits, sort defaults, bbox, CORS, rate limits). */
  public static class Api {
    private int defaultLimit = 200;
    private int maxLimit = 1000;
    private String defaultSort = "lastSeen";
    private String defaultOrder = "desc";
    private Duration metricsWindowDefault = Duration.ofHours(24);
    private Duration metricsWindowMax = Duration.ofDays(7);
    private final Bbox bbox = new Bbox();
    private final Cors cors = new Cors();
    private final RateLimit rateLimit = new RateLimit();

    public int getDefaultLimit() {
      return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
      this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
      return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
      this.maxLimit = maxLimit;
    }

    public String getDefaultSort() {
      return defaultSort;
    }

    public void setDefaultSort(String defaultSort) {
      this.defaultSort = defaultSort;
    }

    public String getDefaultOrder() {
      return defaultOrder;
    }

    public void setDefaultOrder(String defaultOrder) {
      this.defaultOrder = defaultOrder;
    }

    public Duration getMetricsWindowDefault() {
      return metricsWindowDefault;
    }

    public void setMetricsWindowDefault(Duration metricsWindowDefault) {
      this.metricsWindowDefault = metricsWindowDefault;
    }

    public Duration getMetricsWindowMax() {
      return metricsWindowMax;
    }

    public void setMetricsWindowMax(Duration metricsWindowMax) {
      this.metricsWindowMax = metricsWindowMax;
    }

    public Bbox getBbox() {
      return bbox;
    }

    public Cors getCors() {
      return cors;
    }

    public RateLimit getRateLimit() {
      return rateLimit;
    }
  }

  /** Bounding-box constraints and defaults used to validate map queries. */
  public static class Bbox {
    private double allowedLatMin = -90.0;
    private double allowedLatMax = 90.0;
    private double allowedLonMin = -180.0;
    private double allowedLonMax = 180.0;
    private String defaultValue = "0.9823,47.9557,3.7221,49.7575";
    private double maxAreaDeg2 = 100.0;

    public double getAllowedLatMin() {
      return allowedLatMin;
    }

    public void setAllowedLatMin(double allowedLatMin) {
      this.allowedLatMin = allowedLatMin;
    }

    public double getAllowedLatMax() {
      return allowedLatMax;
    }

    public void setAllowedLatMax(double allowedLatMax) {
      this.allowedLatMax = allowedLatMax;
    }

    public double getAllowedLonMin() {
      return allowedLonMin;
    }

    public void setAllowedLonMin(double allowedLonMin) {
      this.allowedLonMin = allowedLonMin;
    }

    public double getAllowedLonMax() {
      return allowedLonMax;
    }

    public void setAllowedLonMax(double allowedLonMax) {
      this.allowedLonMax = allowedLonMax;
    }

    public String getDefaultValue() {
      return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
      this.defaultValue = defaultValue;
    }

    public double getMaxAreaDeg2() {
      return maxAreaDeg2;
    }

    public void setMaxAreaDeg2(double maxAreaDeg2) {
      this.maxAreaDeg2 = maxAreaDeg2;
    }
  }

  /** CORS allowlist configuration for frontend consumers. */
  public static class Cors {
    private List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
      return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
      this.allowedOrigins = allowedOrigins;
    }
  }

  /** Rate limiting configuration applied to {@code /api/**} endpoints. */
  public static class RateLimit {
    private int windowSeconds = 60;
    private int maxRequests = 120;

    public int getWindowSeconds() {
      return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
      this.windowSeconds = windowSeconds;
    }

    public int getMaxRequests() {
      return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
      this.maxRequests = maxRequests;
    }
  }

  /** Optional local aircraft reference database configuration. */
  public static class AircraftDb {
    private boolean enabled = false;
    private String path = "/refdata/aircraft.db";
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
