package com.cloudradar.dashboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PlanespottersPhotoPayloadParserTest {

  private final PlanespottersPhotoPayloadParser parser =
      new PlanespottersPhotoPayloadParser(new ObjectMapper());

  @Test
  void parse_returnsAvailableWhenPayloadContainsTrustedUrls() throws Exception {
    String body =
        """
        {
          "photos": [
            {
              "thumbnail": {"src": "https://cdn.planespotters.net/a.jpg", "size": {"width": 200, "height": 133}},
              "thumbnail_large": {"src": "https://cdn.planespotters.net/a_280.jpg", "size": {"width": 420, "height": 280}},
              "link": "https://www.planespotters.net/photo/123",
              "photographer": "John"
            }
          ]
        }
        """;

    assertEquals("available", parser.parse(body).status());
  }

  @Test
  void parse_returnsNotFoundWhenNoPhotos() throws Exception {
    assertEquals("not_found", parser.parse("{\"photos\":[]}").status());
  }

  @Test
  void parse_returnsErrorWhenThumbnailHostIsUntrusted() throws Exception {
    String body =
        """
        {
          "photos": [
            {
              "thumbnail": {"src": "https://evil.example/a.jpg"},
              "thumbnail_large": {"src": "https://cdn.planespotters.net/a_280.jpg"},
              "link": "https://www.planespotters.net/photo/123"
            }
          ]
        }
        """;

    assertEquals("error", parser.parse(body).status());
  }
}
