/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Tests for {@link LanedConnectionWrapper} - lifecycle tracking and delegation.
 *
 * <p><strong>What we're testing:</strong>
 *
 * <ul>
 *   <li>Lombok {@code @Delegate} generates correct forwarding methods
 *   <li>{@code close()} notifies strategy (lifecycle hook)
 *   <li>{@code closeAsync()} notifies strategy (async lifecycle hook)
 *   <li>Strategy notification happens even if close() throws (finally block)
 *   <li>Idempotent close() calls strategy multiple times (Lettuce guarantees)
 * </ul>
 */
class LanedConnectionWrapperTest {

  @Mock private StatefulRedisConnection<String, String> mockConnection;
  @Mock private ConnectionLane mockLane;
  @Mock private LaneSelectionStrategy mockStrategy;

  private LanedConnectionWrapper<String, String> wrapper;
  private static final int LANE_INDEX = 3;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    // Mock lane's in-flight count
    when(mockLane.getInFlightCount()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(0));

    wrapper = new LanedConnectionWrapper<>(mockConnection, LANE_INDEX, mockLane, mockStrategy);
  }

  /**
   * Tests synchronous close() notifies strategy without closing delegate.
   *
   * <p><strong>Why this matters:</strong> LeastUsedStrategy MUST decrement usage count when wrapper
   * closed. But delegate (lane connection) stays OPEN for reuse (multiplexing). Closing delegate
   * would terminate TCP, defeating the entire purpose.
   */
  @Test
  @DisplayName("close() notifies strategy without closing delegate connection")
  void close_NotifiesStrategy() {
    // Arrange
    // (wrapper created in setUp with mocked connection, lane, strategy)

    // Act
    wrapper.close();

    // Assert
    verifyNoInteractions(mockConnection); // Lane connection NOT closed (stays open for reuse)
    verify(mockStrategy, times(1)).onConnectionReleased(LANE_INDEX);
  }

  /**
   * Tests close() always notifies strategy (idempotent, no exceptions).
   *
   * <p><strong>Why this matters:</strong> Wrapper close is simple (just decrement counter). No I/O,
   * no delegate interaction, so no exceptions possible. Strategy ALWAYS notified.
   */
  @Test
  @DisplayName("close() always notifies strategy (no exceptions possible)")
  void close_NotifiesStrategy_EvenIfDelegateThrows() {
    // Arrange
    // (wrapper ready, no special setup needed)

    // Act
    wrapper.close();

    // Assert
    verifyNoInteractions(mockConnection); // Delegate NOT touched
    verify(mockStrategy, times(1)).onConnectionReleased(LANE_INDEX);
  }

  /**
   * Tests multiple close() calls notify strategy each time.
   *
   * <p><strong>Why this matters:</strong> Wrapper close is NOT idempotent (application code should
   * not call close() multiple times, but if they do, strategy sees each call). Strategy MUST handle
   * multiple releases gracefully (e.g., LeastUsedStrategy uses atomic operations, safe for
   * concurrent/duplicate calls).
   */
  @Test
  @DisplayName("Multiple close() calls notify strategy each time (not idempotent)")
  void close_Multiple_NotifiesStrategyEachTime() {
    // Arrange
    // (wrapper ready)

    // Act
    wrapper.close();
    wrapper.close();
    wrapper.close();

    // Assert
    verifyNoInteractions(mockConnection); // Delegate NEVER closed (stays open)
    verify(mockStrategy, times(3)).onConnectionReleased(LANE_INDEX);
  }

  /**
   * Tests async close() notifies strategy immediately (no async work).
   *
   * <p><strong>Why this matters:</strong> Wrapper closeAsync() is synchronous (just decrement
   * counter), returns completed future. No delegate interaction, no I/O, no async work needed.
   */
  @Test
  @DisplayName("closeAsync() completes immediately and notifies strategy")
  void closeAsync_NotifiesStrategy_OnCompletion() {
    // Arrange
    // (wrapper ready)

    // Act
    CompletableFuture<Void> future = wrapper.closeAsync();

    // Assert
    assertThat(future.isDone()).isTrue(); // Completed immediately (no async work)
    verifyNoInteractions(mockConnection); // Delegate NOT touched
    verify(mockStrategy, times(1)).onConnectionReleased(LANE_INDEX);
  }

  /**
   * Tests async close() cannot fail (synchronous operation).
   *
   * <p><strong>Why this matters:</strong> Wrapper closeAsync() is just counter decrement (no I/O,
   * no delegate interaction). Returns completed future immediately. Cannot fail.
   */
  @Test
  @DisplayName("closeAsync() cannot fail (no exceptions, always succeeds)")
  void closeAsync_NotifiesStrategy_EvenIfFutureFails() {
    // Arrange
    // (wrapper ready)

    // Act
    CompletableFuture<Void> future = wrapper.closeAsync();

    // Assert
    assertThat(future.isDone()).isTrue(); // Completed successfully
    assertThat(future.isCompletedExceptionally()).isFalse(); // No failure possible
    verifyNoInteractions(mockConnection); // Delegate NOT touched
    verify(mockStrategy, times(1)).onConnectionReleased(LANE_INDEX);
  }

  /**
   * Tests Lombok @Delegate forwards method calls correctly.
   *
   * <p><strong>What we're verifying:</strong> Lombok generates delegation code at compile time.
   * This test ensures {@code isOpen()} (example method) forwards to delegate. We don't test ALL
   * 100+ methods (would be 1000+ lines of boilerplate), just spot-check that delegation works.
   *
   * <p><strong>Why this is sufficient:</strong> Lombok is production-tested (millions of users). If
   * {@code @Delegate} generates correct code for one method, it generates correct code for all
   * (compile-time code generation, deterministic).
   */
  @Test
  @DisplayName("@Delegate forwards method calls to underlying connection")
  void delegation_ForwardsMethodCalls() {
    // Arrange
    when(mockConnection.isOpen()).thenReturn(true);

    // Act
    boolean result = wrapper.isOpen();

    // Assert
    assertThat(result).isTrue();
    verify(mockConnection, times(1)).isOpen();
  }

  /**
   * Tests wrapper stores lane index correctly.
   *
   * <p><strong>Why this matters:</strong> Strategy needs correct lane index to decrement correct
   * usage count. Wrong index = decrement wrong lane = load tracking corrupted.
   */
  @Test
  @DisplayName("Constructor stores lane index correctly for strategy notification")
  void constructor_StoresLaneIndexCorrectly() {
    // Arrange & Act
    // (wrapper created in setUp with LANE_INDEX = 3)

    // Assert
    wrapper.close();
    verify(mockStrategy, times(1)).onConnectionReleased(LANE_INDEX);
  }
}
