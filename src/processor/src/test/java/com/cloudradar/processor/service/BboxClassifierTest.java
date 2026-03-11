package com.cloudradar.processor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudradar.processor.config.ProcessorProperties;
import com.cloudradar.processor.service.BboxClassifier.BboxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BboxClassifierTest {

  private BboxClassifier classifier;
  private ProcessorProperties.Bbox bbox;

  @BeforeEach
  void setUp() {
    classifier = new BboxClassifier();
    bbox = new ProcessorProperties.Bbox();
    bbox.setLatMin(46.0);
    bbox.setLatMax(50.0);
    bbox.setLonMin(-1.0);
    bbox.setLonMax(5.0);
  }

  @Test
  void insideBbox() {
    assertEquals(BboxResult.INSIDE, classifier.classify(48.0, 2.0, bbox));
  }

  @Test
  void outsideBbox() {
    assertEquals(BboxResult.OUTSIDE, classifier.classify(55.0, 2.0, bbox));
  }

  @Test
  void onBoundaryIsInside() {
    assertEquals(BboxResult.INSIDE, classifier.classify(46.0, -1.0, bbox));
    assertEquals(BboxResult.INSIDE, classifier.classify(50.0, 5.0, bbox));
  }

  @Test
  void nullLatReturnsUnknown() {
    assertEquals(BboxResult.UNKNOWN, classifier.classify(null, 2.0, bbox));
  }

  @Test
  void nullLonReturnsUnknown() {
    assertEquals(BboxResult.UNKNOWN, classifier.classify(48.0, null, bbox));
  }

  @Test
  void bothNullReturnsUnknown() {
    assertEquals(BboxResult.UNKNOWN, classifier.classify(null, null, bbox));
  }
}
