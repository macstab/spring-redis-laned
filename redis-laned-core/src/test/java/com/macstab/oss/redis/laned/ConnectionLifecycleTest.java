/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.macstab.oss.redis.laned.strategy.LeastUsedStrategy;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;
import com.macstab.oss.redis.laned.strategy.ThreadAffinityStrategy;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Tests for connection lifecycle tracking (increment on get, decrement on close).
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>Unit tests use Mockito mocks (no Redis required)
 *   <li>AAA pattern (Arrange, Act, Assert)
 *   <li>@Nested classes for logical grouping
 *   <li>@DisplayName for human-readable test names
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Connection Lifecycle Tracking")
class ConnectionLifecycleTest {

  @Mock private StatefulRedisConnection<String, String> mockConnection;

  private ConnectionLane[] lanes;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockConnection.isOpen()).thenReturn(true);

    // Create lanes for testing
    lanes = new ConnectionLane[4];
    for (int i = 0; i < 4; i++) {
      lanes[i] = new ConnectionLane(i, mockConnection);
    }
  }

  @Nested
  @DisplayName("In-Flight Count Tracking")
  class InFlightCountTracking {

    @Test
    @DisplayName("wrapper increments count on creation")
    void wrapperIncrementsCountOnCreation() {
      // Arrange
      var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);
      assertThat(lanes[0].getInFlightCount().get()).isZero();

      // Act: Wrapper constructor calls lane.recordAcquire() → increments count
      var wrapper = new LanedConnectionWrapper<>(mockConnection, 0, lanes[0], strategy);

      // Assert
      assertThat(lanes[0].getInFlightCount().get()).as("Count incremented").isEqualTo(1);
    }

    @Test
    @DisplayName("wrapper decrements count on close")
    void wrapperDecrementsCountOnClose() {
      // Arrange
      var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);
      var wrapper = new LanedConnectionWrapper<>(mockConnection, 0, lanes[0], strategy);

      assertThat(lanes[0].getInFlightCount().get()).isEqualTo(1);

      // Act
      wrapper.close();

      // Assert
      assertThat(lanes[0].getInFlightCount().get()).as("Decremented").isEqualTo(0);
    }

    @Test
    @DisplayName("multiple close calls do not cause negative count")
    void multipleCloseDoesNotGoNegative() {
      // Arrange
      var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);
      var wrapper = new LanedConnectionWrapper<>(mockConnection, 0, lanes[0], strategy);

      assertThat(lanes[0].getInFlightCount().get()).isEqualTo(1);

      // Act: Multiple close() calls (simulate idempotency)
      wrapper.close(); // Count = 0
      wrapper.close(); // Should NOT go negative
      wrapper.close(); // Should NOT go negative

      // Assert
      assertThat(lanes[0].getInFlightCount().get()).as("Stays at 0").isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Strategy Integration")
  class StrategyIntegration {

    @Test
    @DisplayName("LeastUsedStrategy decrements count")
    void leastUsedStrategyDecrementsCount() {
      // Arrange
      var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);
      lanes[2].getInFlightCount().set(10);

      // Act
      strategy.onConnectionReleased(2);

      // Assert
      assertThat(lanes[2].getInFlightCount().get()).as("Count decremented").isEqualTo(9);
    }

    @Test
    @DisplayName("RoundRobinStrategy has no-op release")
    void roundRobinStrategyNoOpRelease() {
      // Arrange
      var strategy = new RoundRobinStrategy();
      lanes[0].getInFlightCount().set(5);

      // Act
      strategy.onConnectionReleased(0);

      // Assert
      assertThat(lanes[0].getInFlightCount().get()).as("Count unchanged (no-op)").isEqualTo(5);
    }

    @Test
    @DisplayName("ThreadAffinityStrategy has no-op release")
    void threadAffinityStrategyNoOpRelease() {
      // Arrange
      var strategy = new ThreadAffinityStrategy();
      lanes[0].getInFlightCount().set(5);

      // Act
      strategy.onConnectionReleased(0);

      // Assert
      assertThat(lanes[0].getInFlightCount().get()).as("Count unchanged (no-op)").isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("Thread Safety")
  class ThreadSafety {

    @Test
    @DisplayName("concurrent close calls maintain correct counts")
    void concurrentCloseCallsMaintainCorrectCounts() throws Exception {
      // Arrange
      var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);
      int numThreads = 50;
      int closesPerThread = 20;

      // Set initial count
      lanes[0].getInFlightCount().set(numThreads * closesPerThread);

      CountDownLatch latch = new CountDownLatch(numThreads);
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);

      // Act: Multiple threads close concurrently
      for (int i = 0; i < numThreads; i++) {
        executor.submit(
            () -> {
              try {
                for (int j = 0; j < closesPerThread; j++) {
                  strategy.onConnectionReleased(0);
                }
              } finally {
                latch.countDown();
              }
            });
      }

      // Assert
      await().atMost(10, SECONDS).until(() -> latch.getCount() == 0); // All threads complete
      executor.shutdown();
      assertThat(lanes[0].getInFlightCount().get())
          .as("All releases processed correctly")
          .isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Wrapper Delegation")
  class WrapperDelegation {

    @Test
    @DisplayName("wrapper delegates isOpen to underlying connection")
    void wrapperDelegatesIsOpen() {
      // Arrange
      var strategy = new RoundRobinStrategy();
      when(mockConnection.isOpen()).thenReturn(true);
      var wrapper = new LanedConnectionWrapper<>(mockConnection, 0, lanes[0], strategy);

      // Act
      boolean isOpen = wrapper.isOpen();

      // Assert
      assertThat(isOpen).as("Delegates to mock connection").isTrue();
      verify(mockConnection, times(1)).isOpen();
    }

    @Test
    @DisplayName("wrapper does NOT close underlying connection (multiplexing)")
    void wrapperDoesNotCloseDelegate() {
      // Arrange
      var strategy = new RoundRobinStrategy();
      var wrapper = new LanedConnectionWrapper<>(mockConnection, 0, lanes[0], strategy);

      // Act
      wrapper.close();

      // Assert: Delegate NOT closed (lane connection stays alive for reuse)
      verifyNoInteractions(mockConnection);
    }
  }
}
