/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.macstab.oss.redis.laned.ConnectionLane;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Contract tests for {@link LaneSelectionStrategy} implementations.
 *
 * <p>Verifies that ALL strategy implementations satisfy the interface contract:
 *
 * <ul>
 *   <li>Return value in range [0, numLanes-1]
 *   <li>Thread-safe (no race conditions, no exceptions under concurrency)
 *   <li>Performance acceptable (&lt;500ns per selection)
 *   <li>getName() returns non-null, non-empty string
 * </ul>
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
@DisplayName("LaneSelectionStrategy Contract")
class LaneSelectionStrategyContractTest {

  @Mock private StatefulRedisConnection<String, String> mockConnection;

  private ConnectionLane[] lanes;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockConnection.isOpen()).thenReturn(true);

    // Arrange: Create 8 lanes with mock connections
    lanes = new ConnectionLane[8];
    for (int i = 0; i < 8; i++) {
      lanes[i] = new ConnectionLane(i, mockConnection);
    }
  }

  @Nested
  @DisplayName("RoundRobinStrategy")
  class RoundRobinStrategyContract {

    @Test
    @DisplayName("satisfies contract")
    void satisfiesContract() {
      // Arrange
      var strategy = new RoundRobinStrategy();

      // Act & Assert
      verifyStrategyContract(strategy);
    }
  }

  @Nested
  @DisplayName("ThreadAffinityStrategy")
  class ThreadAffinityStrategyContract {

    @Test
    @DisplayName("satisfies contract")
    void satisfiesContract() {
      // Arrange
      var strategy = new ThreadAffinityStrategy();

      // Act & Assert
      verifyStrategyContract(strategy);
    }
  }

  @Nested
  @DisplayName("LeastUsedStrategy")
  class LeastUsedStrategyContract {

    @Test
    @DisplayName("satisfies contract")
    void satisfiesContract() {
      // Arrange: Create larger lanes array for contract testing (up to 32 lanes)
      ConnectionLane[] largeLanes = new ConnectionLane[32];
      for (int i = 0; i < 32; i++) {
        largeLanes[i] = new ConnectionLane(i, mockConnection);
      }

      // Two-phase initialization (no circular dependency)
      var strategy = new LeastUsedStrategy();
      strategy.initialize(largeLanes);

      // Act & Assert
      verifyStrategyContract(strategy);
    }
  }

  /**
   * Generic contract verification for any {@link LaneSelectionStrategy} implementation.
   *
   * <p><strong>Why contract testing matters (Liskov Substitution Principle):</strong>
   *
   * <p>The {@link com.macstab.oss.redis.laned.LanedConnectionManager} depends on {@link
   * LaneSelectionStrategy} interface, NOT specific implementations. By Liskov Substitution
   * Principle (LSP), any implementation must satisfy the same behavioral contract. If a custom
   * strategy violates the contract (e.g., returns out-of-range lane index, throws exception under
   * concurrency), it breaks {@code LanedConnectionManager} at runtime.
   *
   * <p>This test ensures:
   *
   * <ol>
   *   <li><strong>Correctness:</strong> Lane index always in valid range
   *   <li><strong>Safety:</strong> Thread-safe under concurrent access
   *   <li><strong>Usability:</strong> getName() returns identifiable string
   * </ol>
   *
   * <p><strong>Performance testing moved to load-tests module:</strong>
   *
   * <p>Performance assertions removed from unit tests (hardware-dependent, fail on slow CI/VMs).
   * Real performance benchmarking via JMH in {@code redis-laned-load-tests} module (manual
   * execution only).
   *
   * @param strategy strategy implementation to test
   */
  private void verifyStrategyContract(final LaneSelectionStrategy strategy) {
    // Assert
    assertThat(strategy).as("Strategy must not be null").isNotNull();

    // Contract 1: Lane index in range [0, numLanes-1]
    verifyLaneIndexRange(strategy);

    // Contract 2: Thread-safe (no exceptions, no race conditions)
    verifyThreadSafety(strategy);

    // Contract 3: getName() returns non-null, non-empty string
    verifyName(strategy);
  }

  /**
   * Verifies lane index always in range [0, numLanes-1].
   *
   * <p>Tests with lane counts: 1, 2, 4, 7, 8, 16, 32 (power-of-2 and non-power-of-2).
   */
  private void verifyLaneIndexRange(final LaneSelectionStrategy strategy) {
    // Arrange
    final int[] laneCounts = {1, 2, 4, 7, 8, 16, 32};

    // Act & Assert
    for (final int numLanes : laneCounts) {
      for (int i = 0; i < 100; i++) {
        final int lane = strategy.selectLane(numLanes);
        assertThat(lane).isBetween(0, numLanes - 1);
      }
    }
  }

  /**
   * Verifies thread safety under concurrent access.
   *
   * <p>Launches 50 threads, each performing 1,000 selections. Verifies:
   *
   * <ul>
   *   <li>No exceptions thrown
   *   <li>All lane indices valid
   *   <li>All threads complete within timeout
   * </ul>
   */
  private void verifyThreadSafety(final LaneSelectionStrategy strategy) {
    // Arrange
    final int numThreads = 50;
    final int selectionsPerThread = 1_000;
    final int numLanes = 8;

    final var exceptionOccurred = new AtomicBoolean(false);
    final var latch = new CountDownLatch(numThreads);
    final ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    // Act: Concurrent selections
    for (int t = 0; t < numThreads; t++) {
      executor.submit(
          () -> {
            try {
              for (int i = 0; i < selectionsPerThread; i++) {
                final int lane = strategy.selectLane(numLanes);
                if (lane < 0 || lane >= numLanes) {
                  exceptionOccurred.set(true);
                  fail(
                      strategy.getName()
                          + ": Invalid lane index "
                          + lane
                          + " (expected [0, "
                          + numLanes
                          + "))");
                }
              }
            } catch (final Exception ex) {
              exceptionOccurred.set(true);
              fail(strategy.getName() + ": Exception during concurrent access: " + ex.getMessage());
            } finally {
              latch.countDown();
            }
          });
    }

    // Assert
    try {
      await().atMost(10, SECONDS).until(() -> latch.getCount() == 0);
    } catch (final Exception ex) {
      fail(strategy.getName() + ": Threads did not complete within 10 seconds");
    } finally {
      executor.shutdown();
    }

    assertThat(exceptionOccurred.get())
        .as(strategy.getName() + ": No exceptions should occur under concurrency")
        .isFalse();
  }

  /**
   * Performance testing removed (hardware-dependent).
   *
   * <p><strong>Why performance assertions removed from unit tests:</strong>
   *
   * <ul>
   *   <li>Fail on slower hardware (CI servers, VMs, older CPUs)
   *   <li>Fail under system load (CPU contention, thermal throttling)
   *   <li>Non-deterministic (GC pauses, OS scheduling, turbo boost)
   * </ul>
   *
   * <p><strong>Real performance benchmarking:</strong>
   *
   * <p>Use JMH (Java Microbenchmark Harness) in {@code redis-laned-load-tests} module:
   *
   * <ul>
   *   <li>Warmup iterations (JIT compilation)
   *   <li>Fork JVM (isolated runs, stable environment)
   *   <li>Statistical analysis (mean, p50, p99, std dev)
   *   <li>Manual execution only (not in CI)
   * </ul>
   *
   * <p><strong>Performance targets (for reference, not enforced):</strong>
   *
   * <ul>
   *   <li>Target: &lt;50ns (negligible overhead at 1M req/sec)
   *   <li>Acceptable: &lt;500ns (low overhead at 100K req/sec)
   *   <li>Unacceptable: &gt;1μs (10% overhead at 100K req/sec)
   * </ul>
   */

  /**
   * Verifies getName() returns non-null, non-empty string.
   *
   * <p>Used for logging, metrics tagging, debugging. Must be identifiable and unique per strategy.
   */
  private void verifyName(final LaneSelectionStrategy strategy) {
    // Act
    final String name = strategy.getName();

    // Assert
    assertThat(name).as("Strategy name must not be null").isNotNull();
    assertThat(name.isBlank()).as("Strategy name must not be blank").isFalse();
  }
}
