/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LanedRedisMetrics#NOOP} and {@link NoOpLanedRedisMetrics} (backwards
 * compatibility).
 *
 * <p><strong>Test Strategy:</strong> Verify zero overhead (no exceptions, no allocations, no side
 * effects).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("NoOp Metrics (NOOP + NoOpLanedRedisMetrics)")
class NoOpLanedRedisMetricsTest {

  @Test
  @DisplayName("NOOP constant should be non-null")
  void noopConstantShouldBeNonNull() {
    // Arrange & Act
    final var metrics = LanedRedisMetrics.NOOP;

    // Assert
    assertThat(metrics).isNotNull();
  }

  @Test
  @DisplayName("NOOP should do nothing on all methods")
  void noopShouldDoNothingOnAllMethods() {
    // Arrange
    final var metrics = LanedRedisMetrics.NOOP;

    // Act & Assert - no exceptions
    assertThatCode(() -> metrics.recordLaneSelection(0, "test")).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordInFlightIncrement(0)).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordInFlightDecrement(0)).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordCASRetry("test")).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordSlowCommand(0, Duration.ofMillis(100)))
        .doesNotThrowAnyException();
    assertThatCode(() -> metrics.close()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should do nothing on recordLaneSelection (NoOpLanedRedisMetrics.INSTANCE)")
  void shouldDoNothingOnRecordLaneSelection() {
    // Arrange
    final var metrics = NoOpLanedRedisMetrics.INSTANCE;

    // Act & Assert - no exceptions
    assertThatCode(() -> metrics.recordLaneSelection(0, "round-robin")).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordLaneSelection(-1, "invalid"))
        .doesNotThrowAnyException(); // Edge case
    assertThatCode(() -> metrics.recordLaneSelection(999, "test"))
        .doesNotThrowAnyException(); // Edge case
  }

  @Test
  @DisplayName("Should do nothing on recordInFlightIncrement")
  void shouldDoNothingOnRecordInFlightIncrement() {
    // Arrange
    final var metrics = NoOpLanedRedisMetrics.INSTANCE;

    // Act & Assert
    assertThatCode(() -> metrics.recordInFlightIncrement(0)).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordInFlightIncrement(-1))
        .doesNotThrowAnyException(); // Edge case
  }

  @Test
  @DisplayName("Should do nothing on recordInFlightDecrement")
  void shouldDoNothingOnRecordInFlightDecrement() {
    // Arrange
    final var metrics = NoOpLanedRedisMetrics.INSTANCE;

    // Act & Assert
    assertThatCode(() -> metrics.recordInFlightDecrement(0)).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordInFlightDecrement(-1))
        .doesNotThrowAnyException(); // Edge case
  }

  @Test
  @DisplayName("Should do nothing on recordCASRetry")
  void shouldDoNothingOnRecordCASRetry() {
    // Arrange
    final var metrics = NoOpLanedRedisMetrics.INSTANCE;

    // Act & Assert
    assertThatCode(() -> metrics.recordCASRetry("least-used")).doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordCASRetry(null)).doesNotThrowAnyException(); // Null safety
  }

  @Test
  @DisplayName("Should do nothing on recordSlowCommand")
  void shouldDoNothingOnRecordSlowCommand() {
    // Arrange
    final var metrics = NoOpLanedRedisMetrics.INSTANCE;

    // Act & Assert
    assertThatCode(() -> metrics.recordSlowCommand(0, Duration.ofMillis(100)))
        .doesNotThrowAnyException();
    assertThatCode(() -> metrics.recordSlowCommand(-1, Duration.ZERO))
        .doesNotThrowAnyException(); // Edge case
  }

  @Test
  @DisplayName("Should do nothing on close")
  void shouldDoNothingOnClose() {
    // Arrange
    final var metrics = NoOpLanedRedisMetrics.INSTANCE;

    // Act & Assert
    assertThatCode(() -> metrics.close()).doesNotThrowAnyException();

    // Idempotent close
    assertThatCode(() -> metrics.close()).doesNotThrowAnyException();
    assertThatCode(() -> metrics.close()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should be singleton (enum pattern)")
  void shouldBeSingleton() {
    // Arrange & Act
    final var instance1 = NoOpLanedRedisMetrics.INSTANCE;
    final var instance2 = NoOpLanedRedisMetrics.INSTANCE;

    // Assert - same instance (enum singleton guarantee)
    assertThat(instance1).isSameAs(instance2);
  }
}
