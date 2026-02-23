package com.cloudradar.dashboard.model;

/**
 * Aircraft photo metadata returned in detailed flight responses.
 *
 * @param status resolution status ({@code available|not_found|rate_limited|error})
 * @param thumbnailSrc regular thumbnail URL
 * @param thumbnailWidth regular thumbnail width
 * @param thumbnailHeight regular thumbnail height
 * @param thumbnailLargeSrc large thumbnail URL
 * @param thumbnailLargeWidth large thumbnail width
 * @param thumbnailLargeHeight large thumbnail height
 * @param photographer photographer credit
 * @param sourceLink source page URL on planespotters.net
 */
public record FlightPhoto(
    String status,
    String thumbnailSrc,
    Integer thumbnailWidth,
    Integer thumbnailHeight,
    String thumbnailLargeSrc,
    Integer thumbnailLargeWidth,
    Integer thumbnailLargeHeight,
    String photographer,
    String sourceLink) {

  public static FlightPhoto available(
      String thumbnailSrc,
      Integer thumbnailWidth,
      Integer thumbnailHeight,
      String thumbnailLargeSrc,
      Integer thumbnailLargeWidth,
      Integer thumbnailLargeHeight,
      String photographer,
      String sourceLink) {
    return new FlightPhoto(
        "available",
        thumbnailSrc,
        thumbnailWidth,
        thumbnailHeight,
        thumbnailLargeSrc,
        thumbnailLargeWidth,
        thumbnailLargeHeight,
        photographer,
        sourceLink);
  }

  public static FlightPhoto notFound() {
    return new FlightPhoto("not_found", null, null, null, null, null, null, null, null);
  }

  public static FlightPhoto rateLimited() {
    return new FlightPhoto("rate_limited", null, null, null, null, null, null, null, null);
  }

  public static FlightPhoto error() {
    return new FlightPhoto("error", null, null, null, null, null, null, null, null);
  }
}
