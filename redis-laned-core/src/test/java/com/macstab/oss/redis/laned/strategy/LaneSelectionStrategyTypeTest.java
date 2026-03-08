/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LaneSelectionStrategyType}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LaneSelectionStrategyType")
class LaneSelectionStrategyTypeTest {

  @Test
  @DisplayName("ROUND_ROBIN creates RoundRobinStrategy instance")
  void roundRobinCreatesCorrectStrategy() {
    final var strategy = LaneSelectionStrategyType.ROUND_ROBIN.createStrategy();

    assertThat(strategy).isInstanceOf(RoundRobinStrategy.class);
  }

  @Test
  @DisplayName("THREAD_AFFINITY creates ThreadAffinityStrategy instance")
  void threadAffinityCreatesCorrectStrategy() {
    final var strategy = LaneSelectionStrategyType.THREAD_AFFINITY.createStrategy();

    assertThat(strategy).isInstanceOf(ThreadAffinityStrategy.class);
  }

  @Test
  @DisplayName("LEAST_USED creates LeastUsedStrategy instance")
  void leastUsedCreatesCorrectStrategy() {
    final var strategy = LaneSelectionStrategyType.LEAST_USED.createStrategy();

    assertThat(strategy).isInstanceOf(LeastUsedStrategy.class);
  }

  @Test
  @DisplayName("KEY_AFFINITY creates KeyAffinityStrategy instance")
  void keyAffinityCreatesCorrectStrategy() {
    final var strategy = LaneSelectionStrategyType.KEY_AFFINITY.createStrategy();

    assertThat(strategy).isInstanceOf(KeyAffinityStrategy.class);
  }

  @Test
  @DisplayName("All enum values have corresponding strategy implementations")
  void allEnumValuesHaveImplementations() {
    for (final var strategyType : LaneSelectionStrategyType.values()) {
      final var strategy = strategyType.createStrategy();

      assertThat(strategy)
          .as("Strategy type %s must create a non-null instance", strategyType)
          .isNotNull()
          .isInstanceOf(LaneSelectionStrategy.class);
    }
  }

  @Test
  @DisplayName("Multiple createStrategy() calls return independent instances")
  void createStrategyReturnsNewInstances() {
    final var strategy1 = LaneSelectionStrategyType.ROUND_ROBIN.createStrategy();
    final var strategy2 = LaneSelectionStrategyType.ROUND_ROBIN.createStrategy();

    assertThat(strategy1).isNotSameAs(strategy2);
  }
}
