package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.api.BadRequestException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class FlightDetailIncludeParser {
  private static final Set<String> DEFAULT_INCLUDE = Set.of("enrichment");

  private FlightDetailIncludeParser() {}

  static Set<String> parse(String includeRaw, Set<String> supportedIncludes) {
    if (includeRaw == null || includeRaw.isBlank()) {
      return DEFAULT_INCLUDE;
    }

    Set<String> includes = Arrays.stream(includeRaw.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toSet());

    List<String> invalid = includes.stream().filter(i -> !supportedIncludes.contains(i)).toList();
    if (!invalid.isEmpty()) {
      throw new BadRequestException("include contains unsupported values: " + String.join(",", invalid));
    }

    if (!includes.contains("enrichment")) {
      includes = new HashSet<>(includes);
      includes.add("enrichment");
    }
    return includes;
  }
}
