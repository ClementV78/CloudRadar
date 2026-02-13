package com.cloudradar.dashboard.model;

import java.util.List;
import java.util.Map;

/**
 * Aggregated KPI payload returned by {@code GET /api/flights/metrics}.
 *
 * @param activeAircraft number of active aircraft in scope
 * @param trafficDensityPer10kKm2 aircraft density normalized per 10k km²
 * @param militarySharePercent military share percentage
 * @param defenseActivityScore defense activity score (v1 mirrors military share)
 * @param fleetBreakdown fleet segmentation breakdown
 * @param aircraftSizes size segmentation breakdown
 * @param aircraftTypes top aircraft type/category labels
 * @param activitySeries activity timeline buckets
 * @param estimates estimated/placeholder indicators and notes
 * @param timestamp response generation timestamp
 */
public record FlightsMetricsResponse(
    int activeAircraft,
    double trafficDensityPer10kKm2,
    double militarySharePercent,
    double defenseActivityScore,
    List<TypeBreakdownItem> fleetBreakdown,
    List<TypeBreakdownItem> aircraftSizes,
    List<TypeBreakdownItem> aircraftTypes,
    List<TimeBucket> activitySeries,
    Estimates estimates,
    String timestamp) {

  /**
   * Breakdown item represented by count and percentage.
   *
   * @param key bucket identifier
   * @param count absolute count
   * @param percent percentage over active aircraft
   */
  public record TypeBreakdownItem(String key, int count, double percent) {}

  /**
   * Time-bucket point for chart rendering.
   *
   * @param epoch bucket start epoch (seconds)
   * @param count bucket value
   */
  public record TimeBucket(long epoch, int count) {}

  /**
   * Estimated metrics and explanatory notes.
   *
   * @param takeoffsWindow estimated takeoff count in active window
   * @param landingsWindow estimated landing count in active window
   * @param noiseProxyIndex estimated proxy for noise impact
   * @param notes free-form notes about estimation status/limitations
   */
  public record Estimates(
      Integer takeoffsWindow,
      Integer landingsWindow,
      Double noiseProxyIndex,
      Map<String, String> notes) {}
}
