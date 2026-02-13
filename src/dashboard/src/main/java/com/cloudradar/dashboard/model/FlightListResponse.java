package com.cloudradar.dashboard.model;

import java.util.List;
import java.util.Map;

/**
 * Response contract for {@code GET /api/flights}.
 *
 * @param items selected map items
 * @param count number of returned items
 * @param totalMatched total items matching filters before limit
 * @param limit applied response limit
 * @param bbox effective bbox used for filtering
 * @param timestamp response generation timestamp
 */
public record FlightListResponse(
    List<FlightMapItem> items,
    int count,
    int totalMatched,
    int limit,
    Map<String, Double> bbox,
    String timestamp) {}
