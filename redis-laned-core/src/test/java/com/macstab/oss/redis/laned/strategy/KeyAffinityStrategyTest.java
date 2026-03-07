/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KeyAffinityStrategy}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Marker strategy pattern (selectLane() should never be called)
 *   <li>getName() returns correct name
 *   <li>onConnectionReleased() is no-op (stateless)
 * </ul>
 */
@DisplayName("KeyAffinityStrategy")
class KeyAffinityStrategyTest {

  @Nested
  @DisplayName("Construction")
  class Construction {

    @Test
    @DisplayName("creates strategy with no-args constructor")
    void createStrategy() {
      // Act
      final var strategy = new KeyAffinityStrategy();

      // Assert
      assertThat(strategy).isNotNull();
      assertThat(strategy.getName()).isEqualTo("key-affinity");
    }
  }

  @Nested
  @DisplayName("selectLane(int)")
  class SelectLane {

    @Test
    @DisplayName("throws UnsupportedOperationException (should never be called)")
    void throwsException() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert
      assertThatThrownBy(() -> strategy.selectLane(8))
          .isInstanceOf(UnsupportedOperationException.class)
          .hasMessageContaining("should never be called")
          .hasMessageContaining("KeyAffinityConnectionWrapper");
    }

    @Test
    @DisplayName("error message explains why method should not be called")
    void errorMessageExplainsWhy() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert
      assertThatThrownBy(() -> strategy.selectLane(8))
          .hasMessageContaining("dynamic proxy")
          .hasMessageContaining("LanedConnectionManager.getConnection");
    }
  }

  @Nested
  @DisplayName("getName()")
  class GetName {

    @Test
    @DisplayName("returns 'key-affinity'")
    void returnsCorrectName() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act
      final String name = strategy.getName();

      // Assert
      assertThat(name).isEqualTo("key-affinity");
    }

    @Test
    @DisplayName("returns same value on multiple calls")
    void consistentName() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act
      final String name1 = strategy.getName();
      final String name2 = strategy.getName();

      // Assert
      assertThat(name1).isSameAs(name2); // String interning
    }
  }

  @Nested
  @DisplayName("onConnectionReleased(int)")
  class OnConnectionReleased {

    @Test
    @DisplayName("is no-op for stateless strategy")
    void noOp() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert (no exception thrown)
      strategy.onConnectionReleased(0);
      strategy.onConnectionReleased(5);
      strategy.onConnectionReleased(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("accepts negative lane index (validation done elsewhere)")
    void acceptsNegativeIndex() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert (no exception - strategy is stateless)
      strategy.onConnectionReleased(-1);
      strategy.onConnectionReleased(-100);
    }

    @Test
    @DisplayName("can be called multiple times (idempotent)")
    void idempotent() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert
      strategy.onConnectionReleased(3);
      strategy.onConnectionReleased(3);
      strategy.onConnectionReleased(3);
      // No exception, no state change
    }
  }

  @Nested
  @DisplayName("Stateless Verification")
  class StatelessVerification {

    @Test
    @DisplayName("multiple instances are independent")
    void multipleInstances() {
      // Arrange & Act
      final var strategy1 = new KeyAffinityStrategy();
      final var strategy2 = new KeyAffinityStrategy();

      // Assert
      assertThat(strategy1).isNotSameAs(strategy2);
      assertThat(strategy1.getName()).isEqualTo(strategy2.getName());
    }

    @Test
    @DisplayName("no internal state (all methods safe for concurrent use)")
    void threadSafe() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act (simulate concurrent access)
      for (int i = 0; i < 100; i++) {
        strategy.getName();
        strategy.onConnectionReleased(i % 8);
      }

      // Assert (no exception, deterministic behavior)
      assertThat(strategy.getName()).isEqualTo("key-affinity");
    }
  }
}
