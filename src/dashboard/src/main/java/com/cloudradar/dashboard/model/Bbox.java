package com.cloudradar.dashboard.model;

/**
 * Immutable geographic bounding box used for filtering and KPI aggregation.
 *
 * @param minLon minimum longitude
 * @param minLat minimum latitude
 * @param maxLon maximum longitude
 * @param maxLat maximum latitude
 */
public record Bbox(double minLon, double minLat, double maxLon, double maxLat) {
  /**
   * Checks whether a point is inside this bounding box.
   *
   * @param lat latitude to test
   * @param lon longitude to test
   * @return {@code true} when the point is inside or on the border
   */
  public boolean contains(Double lat, Double lon) {
    if (lat == null || lon == null) {
      return false;
    }
    return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
  }

  /**
   * Computes the rectangular area in square degrees.
   *
   * @return approximate area in deg²
   */
  public double areaDeg2() {
    return Math.max(0.0, (maxLon - minLon) * (maxLat - minLat));
  }

  /**
   * Computes an approximate area in km² for map density indicators.
   *
   * @return approximate area in square kilometers
   */
  public double areaKm2() {
    double dLat = Math.max(0.0, maxLat - minLat);
    double dLon = Math.max(0.0, maxLon - minLon);
    double latMidRad = Math.toRadians((minLat + maxLat) / 2.0);

    double heightKm = dLat * 110.574;
    double widthKm = dLon * 111.320 * Math.cos(latMidRad);
    return Math.max(0.0, heightKm * widthKm);
  }
}
