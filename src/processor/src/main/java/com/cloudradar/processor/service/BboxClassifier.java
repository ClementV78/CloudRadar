package com.cloudradar.processor.service;

import com.cloudradar.processor.config.ProcessorProperties;

/**
 * Pure domain class for geo-fence classification.
 *
 * <p>Determines whether an aircraft position falls inside, outside,
 * or is unknown relative to a configured bounding box.
 */
public class BboxClassifier {

  public enum BboxResult { INSIDE, OUTSIDE, UNKNOWN }

  /**
   * Classifies a position against the given bounding box.
   *
   * @param lat latitude (nullable)
   * @param lon longitude (nullable)
   * @param bbox bounding box configuration
   * @return classification result
   */
  public BboxResult classify(Double lat, Double lon, ProcessorProperties.Bbox bbox) {
    if (lat == null || lon == null) {
      return BboxResult.UNKNOWN;
    }
    boolean inside = lat >= bbox.getLatMin()
        && lat <= bbox.getLatMax()
        && lon >= bbox.getLonMin()
        && lon <= bbox.getLonMax();
    return inside ? BboxResult.INSIDE : BboxResult.OUTSIDE;
  }
}
