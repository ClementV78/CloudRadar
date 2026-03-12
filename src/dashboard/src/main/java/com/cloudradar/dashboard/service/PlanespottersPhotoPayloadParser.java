package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.model.FlightPhoto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

final class PlanespottersPhotoPayloadParser {
  private static final String PHOTOS_FIELD = "photos";
  private static final String THUMBNAIL_FIELD = "thumbnail";
  private static final String THUMBNAIL_LARGE_FIELD = "thumbnail_large";
  private static final Set<String> TRUSTED_HOSTS =
      Set.of(
          "t.plnspttrs.net",
          "cdn.planespotters.net",
          "www.planespotters.net",
          "planespotters.net");

  private final ObjectMapper objectMapper;

  PlanespottersPhotoPayloadParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  FlightPhoto parse(String body) throws IOException {
    JsonNode root = objectMapper.readTree(body);
    if (root.path("error").isTextual()) {
      return FlightPhoto.error();
    }

    JsonNode photos = root.path(PHOTOS_FIELD);
    if (!photos.isArray() || photos.isEmpty()) {
      return FlightPhoto.notFound();
    }

    JsonNode first = photos.get(0);
    String thumbSrc = sanitizeUrl(first.path(THUMBNAIL_FIELD).path("src").asText(null));
    String thumbLargeSrc = sanitizeUrl(first.path(THUMBNAIL_LARGE_FIELD).path("src").asText(null));
    String sourceLink = sanitizeUrl(first.path("link").asText(null));
    if (thumbSrc == null || thumbLargeSrc == null || sourceLink == null) {
      return FlightPhoto.error();
    }

    return FlightPhoto.available(
        thumbSrc,
        toNullableInt(first.path(THUMBNAIL_FIELD).path("size").path("width")),
        toNullableInt(first.path(THUMBNAIL_FIELD).path("size").path("height")),
        thumbLargeSrc,
        toNullableInt(first.path(THUMBNAIL_LARGE_FIELD).path("size").path("width")),
        toNullableInt(first.path(THUMBNAIL_LARGE_FIELD).path("size").path("height")),
        FlightQueryValues.trimToNull(first.path("photographer").asText(null)),
        sourceLink);
  }

  private static String sanitizeUrl(String value) {
    String trimmed = FlightQueryValues.trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    try {
      URI uri = URI.create(trimmed);
      if (!"https".equalsIgnoreCase(uri.getScheme())) {
        return null;
      }
      String host = uri.getHost();
      if (host == null) {
        return null;
      }
      String normalizedHost = host.toLowerCase(Locale.ROOT);
      return TRUSTED_HOSTS.contains(normalizedHost) ? uri.toString() : null;
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static Integer toNullableInt(JsonNode node) {
    return node != null && node.isNumber() ? node.asInt() : null;
  }
}
