package com.cloudradar.ingester.opensky;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TokenCooldownPolicyTest {

  @Test
  void registerFailureUsesProgressiveCooldown() {
    TokenCooldownPolicy policy = new TokenCooldownPolicy();
    Instant now = Instant.now();

    TokenCooldownPolicy.FailureState first = policy.registerFailure(now);
    TokenCooldownPolicy.FailureState second = policy.registerFailure(now.plusSeconds(20));

    assertThat(first.failureCount()).isEqualTo(1);
    assertThat(first.cooldownSeconds()).isEqualTo(15L);
    assertThat(second.failureCount()).isEqualTo(2);
    assertThat(second.cooldownSeconds()).isEqualTo(30L);
  }

  @Test
  void resetClearsCooldownWindow() {
    TokenCooldownPolicy policy = new TokenCooldownPolicy();
    Instant now = Instant.now();
    policy.registerFailure(now);

    assertThat(policy.isBlocked(now.plusSeconds(1))).isTrue();

    policy.reset();

    assertThat(policy.isBlocked(now.plusSeconds(1))).isFalse();
  }

  @Test
  void remainingSecondsIsAtLeastOneSecond() {
    TokenCooldownPolicy policy = new TokenCooldownPolicy();
    Instant now = Instant.now();
    policy.registerFailure(now);

    assertThat(policy.remainingSeconds(now.plusSeconds(14))).isEqualTo(1L);
  }
}
