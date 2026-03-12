package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.config.DashboardProperties;
import com.cloudradar.dashboard.model.Bbox;
import java.time.Duration;

public final class QueryParser {
  private QueryParser() {}

  public static Bbox parseBboxOrDefault(String raw, DashboardProperties properties) {
    return QueryBboxParser.parseBboxOrDefault(raw, properties);
  }

  public static Bbox parseBbox(String raw) {
    return QueryBboxParser.parseBbox(raw);
  }

  public static void validateBboxBoundaries(Bbox bbox, DashboardProperties properties) {
    QueryBboxParser.validateBboxBoundaries(bbox, properties);
  }

  public static Long parseSince(String raw) {
    return QueryCommonParser.parseSince(raw);
  }

  public static int parseLimit(String raw, int defaultLimit, int maxLimit) {
    return QueryCommonParser.parseLimit(raw, defaultLimit, maxLimit);
  }

  public static String parseSort(String raw, String defaultSort) {
    return QueryCommonParser.parseSort(raw, defaultSort);
  }

  public static String parseOrder(String raw, String defaultOrder) {
    return QueryCommonParser.parseOrder(raw, defaultOrder);
  }

  public static Duration parseWindow(String raw, Duration defaultValue, Duration maxValue) {
    return QueryWindowParser.parseWindow(raw, defaultValue, maxValue);
  }

  public static long cutoffEpoch(Duration window) {
    return QueryWindowParser.cutoffEpoch(window);
  }
}
