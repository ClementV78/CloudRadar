package com.cloudradar.dashboard.model;

import java.util.Map;

/**
 * Response payload for OpenSky bbox boost state/trigger endpoints.
 *
 * @param active whether boost mode is currently active
 * @param factor bbox area multiplier applied when active
 * @param bbox effective bbox to use for map/metrics queries
 * @param activeUntilEpoch epoch second when active boost ends
 * @param cooldownUntilEpoch epoch second when caller can trigger again
 * @param serverEpoch current server epoch seconds
 */
public record BboxBoostStatusResponse(
    boolean active,
    double factor,
    Map<String, Double> bbox,
    Long activeUntilEpoch,
    Long cooldownUntilEpoch,
    long serverEpoch) {}
