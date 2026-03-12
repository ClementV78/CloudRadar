package com.cloudradar.ingester;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IngestionBackoffControllerTest {

  @Test
  void recordFailureAppliesProgressiveBackoff() {
    IngestionBackoffController controller = new IngestionBackoffController();

    IngestionBackoffController.FailureDecision first = controller.recordFailure(System.currentTimeMillis());
    IngestionBackoffController.FailureDecision second = controller.recordFailure(System.currentTimeMillis());

    assertThat(first.failureCount()).isEqualTo(1);
    assertThat(first.backoffMs()).isEqualTo(1_000L);
    assertThat(second.failureCount()).isEqualTo(2);
    assertThat(second.backoffMs()).isEqualTo(2_000L);
    assertThat(controller.currentBackoffSeconds()).isEqualTo(2L);
  }

  @Test
  void recordFailureDisablesAfterMaxAttempts() {
    IngestionBackoffController controller = new IngestionBackoffController();

    IngestionBackoffController.FailureDecision decision = null;
    for (int i = 0; i < 11; i++) {
      decision = controller.recordFailure(System.currentTimeMillis());
    }

    assertThat(decision).isNotNull();
    assertThat(decision.disabled()).isTrue();
    assertThat(controller.disabledGaugeValue()).isEqualTo(1);
    assertThat(controller.shouldSkipCycle(System.currentTimeMillis())).isTrue();
  }

  @Test
  void recordSuccessResetsFailureStateAndSchedulesNextCycle() {
    IngestionBackoffController controller = new IngestionBackoffController();
    controller.recordFailure(System.currentTimeMillis());

    long now = System.currentTimeMillis();
    controller.recordSuccess(now, 10_000L);

    assertThat(controller.currentBackoffSeconds()).isZero();
    assertThat(controller.disabledGaugeValue()).isZero();
    assertThat(controller.shouldSkipCycle(now)).isTrue();
    assertThat(controller.shouldSkipCycle(now + 11_000L)).isFalse();
  }
}
