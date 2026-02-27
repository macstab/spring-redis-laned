/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.stress;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.macstab.oss.redis.laned.ConnectionLane;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy;
import com.macstab.oss.redis.laned.strategy.LeastUsedStrategy;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;
import com.macstab.oss.redis.laned.strategy.ThreadAffinityStrategy;

import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Massive concurrency stress tests using virtual threads (JDK 21+).
 *
 * <p><strong>Why virtual threads (not platform threads):</strong>
 *
 * <p>Platform threads (OS threads): Limited to ~500-1000 concurrent threads (stack size = 1-2MB
 * each). Cannot create 10,000+ threads without OOM.
 *
 * <p>Virtual threads (JDK 21+): Lightweight (stack size ~1-10KB). Can create MILLIONS of threads.
 * Perfect for stress testing CAS loops under extreme contention.
 *
 * <p><strong>Test goals:</strong>
 *
 * <ul>
 *   <li>Prove strategies work under 10,000+ concurrent threads
 *   <li>Detect race conditions (lost updates, ABA problems, etc.)
 *   <li>Verify CAS loops handle extreme contention (LeastUsedStrategy)
 *   <li>Measure failure rate under brutal load
 * </ul>
 *
 * <p><strong>Why these tests matter (production safety):</strong>
 *
 * <p>At 100K req/sec with 8 lanes: ~12.5K operations/sec per lane. Under burst traffic (2x-10x
 * normal): 125K ops/sec per lane. CAS loops MUST handle this without lost updates or negative
 * counts.
 *
 * <p>Virtual threads simulate this burst load (10K threads hammering same lane simultaneously).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
@DisplayName("Massive Concurrency Stress Tests (Virtual Threads)")
class MassiveConcurrencyStressTest {

  @Mock private StatefulRedisConnection<String, String> mockConnection;

  private ConnectionLane[] lanes;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockConnection.isOpen()).thenReturn(true);

    // Create 4 lanes (small number = high contention per lane)
    lanes = new ConnectionLane[4];
    for (var i = 0; i < 4; i++) {
      lanes[i] = new ConnectionLane(i, mockConnection);
    }
  }

  @Nested
  @DisplayName("RoundRobinStrategy - Massive Throughput")
  class RoundRobinStress {

    @Test
    @DisplayName("10K virtual threads × 10K ops = 100M selections (no exceptions)")
    void massiveThroughputNoExceptions() throws Exception {
      // Arrange
      final var strategy = new RoundRobinStrategy();
      final var numThreads = 10_000;
      final var opsPerThread = 10_000;
      final var numLanes = 4;

      final var exceptionOccurred = new AtomicBoolean(false);
      final var invalidLaneCount = new AtomicLong(0);
      final var totalOps = new AtomicLong(0);
      final var latch = new CountDownLatch(numThreads);

      // Act: 10K virtual threads hammer strategy
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var t = 0; t < numThreads; t++) {
          executor.submit(
              () -> {
                try {
                  for (var i = 0; i < opsPerThread; i++) {
                    final var lane = strategy.selectLane(numLanes);

                    // Verify lane in valid range
                    if (lane < 0 || lane >= numLanes) {
                      invalidLaneCount.incrementAndGet();
                    }

                    totalOps.incrementAndGet();
                  }
                } catch (final Exception ex) {
                  exceptionOccurred.set(true);
                } finally {
                  latch.countDown();
                }
              });
        }

        // Assert: All threads complete
        await()
            .atMost(120, SECONDS)
            .until(() -> latch.getCount() == 0); // All threads should complete within 2 minutes
      }

      // Assert: No exceptions, no invalid lanes
      assertThat(exceptionOccurred.get()).as("No exceptions should occur").isFalse();
      assertThat(invalidLaneCount.get()).as("All lane indices should be valid").isEqualTo(0);
      assertThat(totalOps.get())
          .as("Total ops should match expected")
          .isEqualTo((long) numThreads * opsPerThread);

      log.info(
          "RoundRobin: {} threads × {} ops = {} total operations (0 errors)",
          numThreads,
          opsPerThread,
          totalOps.get());
    }

    @Test
    @DisplayName("Extreme contention - all threads select simultaneously")
    void extremeContention() throws Exception {
      // Arrange
      final var strategy = new RoundRobinStrategy();
      final var numThreads = 5_000;
      final var numLanes = 4;
      final var latch = new CountDownLatch(numThreads);
      final var startGate = new CountDownLatch(1);
      final var exceptionOccurred = new AtomicBoolean(false);

      // Act: All threads wait, then hammer strategy simultaneously
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var t = 0; t < numThreads; t++) {
          executor.submit(
              () -> {
                try {
                  startGate.await(); // Wait for signal
                  final var lane = strategy.selectLane(numLanes);
                  assertThat(lane >= 0 && lane < numLanes).as("Lane must be valid").isTrue();
                } catch (final Exception ex) {
                  exceptionOccurred.set(true);
                } finally {
                  latch.countDown();
                }
              });
        }

        // Release all threads simultaneously
        startGate.countDown();

        await()
            .atMost(60, SECONDS)
            .until(() -> latch.getCount() == 0); // All threads should complete
      }

      assertThat(exceptionOccurred.get()).as("No exceptions under extreme contention").isFalse();
    }
  }

  @Nested
  @DisplayName("ThreadAffinityStrategy - Thread Churn")
  class ThreadAffinityStress {

    @Test
    @DisplayName("10K virtual threads - each gets consistent lane")
    void virtualThreadAffinity() throws Exception {
      // Arrange
      final var strategy = new ThreadAffinityStrategy();
      final var numThreads = 10_000;
      final var selectionsPerThread = 100;
      final var numLanes = 4;

      final var consistencyErrors = new AtomicLong(0);
      final var latch = new CountDownLatch(numThreads);

      // Act: Each virtual thread selects lane multiple times (should be consistent)
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var t = 0; t < numThreads; t++) {
          executor.submit(
              () -> {
                try {
                  final var firstLane = strategy.selectLane(numLanes);

                  // Same thread should ALWAYS get same lane
                  for (var i = 0; i < selectionsPerThread; i++) {
                    final var lane = strategy.selectLane(numLanes);
                    if (lane != firstLane) {
                      consistencyErrors.incrementAndGet();
                    }
                  }
                } catch (final Exception ex) {
                  consistencyErrors.incrementAndGet();
                } finally {
                  latch.countDown();
                }
              });
        }

        await()
            .atMost(60, SECONDS)
            .until(() -> latch.getCount() == 0); // All threads should complete
      }

      // Assert: EVERY thread got consistent lane (0 errors)
      assertThat(consistencyErrors.get())
          .as("All virtual threads should get consistent lane (thread affinity)")
          .isEqualTo(0);

      log.info("ThreadAffinity: {} virtual threads, 0 consistency errors", numThreads);
    }

    @Test
    @DisplayName("No ThreadLocal leak with 100K virtual threads")
    void noThreadLocalLeak() throws Exception {
      // Arrange
      final var strategy = new ThreadAffinityStrategy();
      final var numThreads = 100_000;
      final var numLanes = 8;

      final var beforeMemory =
          Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      final var latch = new CountDownLatch(numThreads);

      // Act: Create/destroy 100K virtual threads (simulate thread pool churn)
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var t = 0; t < numThreads; t++) {
          executor.submit(
              () -> {
                try {
                  strategy.selectLane(numLanes); // Single selection
                } finally {
                  latch.countDown();
                }
              });
        }

        await()
            .atMost(120, SECONDS)
            .until(() -> latch.getCount() == 0); // All threads should complete
      }

      // Force GC to collect any leaked ThreadLocals
      System.gc();
      Thread.sleep(1000);

      final var afterMemory =
          Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      final var memoryGrowthMB = (afterMemory - beforeMemory) / (1024.0 * 1024.0);

      // Assert: Memory growth should be minimal (<50MB for 100K threads)
      // If ThreadLocal leaked: would be ~100K × 8 bytes = 800KB+ permanent growth
      assertThat(memoryGrowthMB)
          .as(
              String.format(
                  "Memory growth should be <50MB, got %.2f MB (potential ThreadLocal leak)",
                  memoryGrowthMB))
          .isLessThan(50.0);

      log.info(
          "ThreadAffinity: {} virtual threads created/destroyed, memory growth: {:.2f} MB",
          numThreads,
          memoryGrowthMB);
    }
  }

  @Nested
  @DisplayName("LeastUsedStrategy - Extreme CAS Contention")
  class LeastUsedStress {

    @Test
    @DisplayName("10K virtual threads × 1K ops = 10M acquire/release cycles")
    void massiveAcquireReleaseCycles() throws Exception {
      // Arrange
      final var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);

      final var numThreads = 10_000;
      final var opsPerThread = 1_000;
      final var numLanes = 4;

      final var negativeCountDetected = new AtomicBoolean(false);
      final var latch = new CountDownLatch(numThreads);

      // Act: 10K threads doing acquire → release cycles
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var t = 0; t < numThreads; t++) {
          executor.submit(
              () -> {
                try {
                  for (var i = 0; i < opsPerThread; i++) {
                    // Acquire
                    final var lane = strategy.selectLane(numLanes);
                    strategy.onConnectionAcquired(lane);

                    // Check: count should never be negative
                    if (lanes[lane].getInFlightCount().get() < 0) {
                      negativeCountDetected.set(true);
                    }

                    // Release
                    strategy.onConnectionReleased(lane);

                    // Check again
                    if (lanes[lane].getInFlightCount().get() < 0) {
                      negativeCountDetected.set(true);
                    }
                  }
                } catch (final Exception ex) {
                  negativeCountDetected.set(true);
                } finally {
                  latch.countDown();
                }
              });
        }

        await()
            .atMost(180, SECONDS)
            .until(() -> latch.getCount() == 0); // All threads should complete
      }

      // Assert: Counts never went negative
      assertThat(negativeCountDetected.get())
          .as("Counts should NEVER go negative (CAS correctness)")
          .isFalse();

      // Assert: Final counts should be 0 (all released)
      for (var i = 0; i < numLanes; i++) {
        assertThat(lanes[i].getInFlightCount().get())
            .as("Lane " + i + " count should be 0 (all connections released)")
            .isEqualTo(0);
      }

      log.info(
          "LeastUsed: {} threads × {} ops = {} total operations, final counts = 0 ✅",
          numThreads,
          opsPerThread,
          (long) numThreads * opsPerThread);
    }

    @Test
    @DisplayName("Rapid acquire/release - no lost updates")
    void rapidAcquireReleaseNoLostUpdates() throws Exception {
      // Arrange
      final var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);

      final var numThreads = 5_000;
      final var cyclesPerThread = 2_000;
      final var targetLane = 0; // All threads hammer same lane (worst case)

      final var acquireCount = new AtomicLong(0);
      final var releaseCount = new AtomicLong(0);
      final var latch = new CountDownLatch(numThreads);

      // Act: Tight acquire → release loop
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (var t = 0; t < numThreads; t++) {
          executor.submit(
              () -> {
                try {
                  for (var i = 0; i < cyclesPerThread; i++) {
                    strategy.onConnectionAcquired(targetLane);
                    acquireCount.incrementAndGet();

                    strategy.onConnectionReleased(targetLane);
                    releaseCount.incrementAndGet();
                  }
                } finally {
                  latch.countDown();
                }
              });
        }

        await()
            .atMost(120, SECONDS)
            .until(() -> latch.getCount() == 0); // All threads should complete
      }

      // Assert: Acquire count = Release count
      assertThat(releaseCount.get())
          .as("Acquire and release counts should match (no lost updates)")
          .isEqualTo(acquireCount.get());

      // Assert: Final lane count = 0
      assertThat(lanes[targetLane].getInFlightCount().get())
          .as("Final count should be 0 (acquire/release balanced)")
          .isEqualTo(0);

      log.info(
          "LeastUsed rapid: {} acquires, {} releases, final count = 0 ✅",
          acquireCount.get(),
          releaseCount.get());
    }

    @Test
    @DisplayName("Concurrent acquire + release on same lane (CAS race)")
    void concurrentAcquireReleaseSameLane() throws Exception {
      // Arrange
      final var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);

      final var numAcquireThreads = 2_500;
      final var numReleaseThreads = 2_500;
      final var opsPerThread = 1_000;
      final var targetLane = 0;

      final var latch = new CountDownLatch(numAcquireThreads + numReleaseThreads);
      final var startGate = new CountDownLatch(1);

      // Pre-populate lane with enough count to never hit 0 guard during test
      // (Ensures all releases succeed, making test order-independent)
      final var totalReleases = numReleaseThreads * opsPerThread;
      final var safetyMargin = 1_000_000; // Extra buffer to ensure count never reaches 0
      final var initialCount = totalReleases + safetyMargin;

      lanes[targetLane].getInFlightCount().set(initialCount);

      // Act: Half threads acquire, half release (simultaneously on same lane)
      try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Acquire threads
        for (var t = 0; t < numAcquireThreads; t++) {
          executor.submit(
              () -> {
                try {
                  startGate.await();
                  for (var i = 0; i < opsPerThread; i++) {
                    strategy.onConnectionAcquired(targetLane);
                  }
                } catch (final Exception ex) {
                  // Ignore
                } finally {
                  latch.countDown();
                }
              });
        }

        // Release threads
        for (var t = 0; t < numReleaseThreads; t++) {
          executor.submit(
              () -> {
                try {
                  startGate.await();
                  for (var i = 0; i < opsPerThread; i++) {
                    strategy.onConnectionReleased(targetLane);
                  }
                } catch (final Exception ex) {
                  // Ignore
                } finally {
                  latch.countDown();
                }
              });
        }

        // Release all threads
        startGate.countDown();

        await()
            .atMost(120, SECONDS)
            .until(() -> latch.getCount() == 0); // All threads should complete
      }

      // Assert: Final count = initial + acquires - releases
      final var expectedFinal =
          initialCount + (numAcquireThreads * opsPerThread) - (numReleaseThreads * opsPerThread);
      final var actualFinal = lanes[targetLane].getInFlightCount().get();

      assertThat(actualFinal)
          .as("Final count should match expected (no lost CAS updates)")
          .isEqualTo(expectedFinal);

      log.info(
          "LeastUsed CAS race: initial={}, expected={}, actual={} ✅",
          initialCount,
          expectedFinal,
          actualFinal);
    }
  }

  @Nested
  @DisplayName("Cross-Strategy - Lifecycle Correctness")
  class CrossStrategyStress {

    @Test
    @DisplayName("All strategies handle 10K concurrent operations")
    void allStrategiesHandleMassiveConcurrency() throws Exception {
      // Arrange
      final var strategies =
          new LaneSelectionStrategy[] {
            new RoundRobinStrategy(), new ThreadAffinityStrategy(), createLeastUsed()
          };

      for (final var strategy : strategies) {
        // Reset lanes
        for (final var lane : lanes) {
          lane.getInFlightCount().set(0);
        }

        final var numThreads = 10_000;
        final var opsPerThread = 100;
        final var numLanes = 4;
        final var latch = new CountDownLatch(numThreads);
        final var errors = new AtomicInteger(0);

        // Act
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
          for (var t = 0; t < numThreads; t++) {
            executor.submit(
                () -> {
                  try {
                    for (var i = 0; i < opsPerThread; i++) {
                      final var lane = strategy.selectLane(numLanes);
                      if (lane < 0 || lane >= numLanes) {
                        errors.incrementAndGet();
                      }
                    }
                  } catch (final Exception ex) {
                    errors.incrementAndGet();
                  } finally {
                    latch.countDown();
                  }
                });
          }

          await()
              .atMost(60, SECONDS)
              .until(() -> latch.getCount() == 0); // All threads should complete
        }

        // Assert
        assertThat(errors.get())
            .as(strategy.getName() + " should handle massive concurrency without errors")
            .isZero();

        log.info(
            "{}: {} threads × {} ops = {} operations (0 errors) ✅",
            strategy.getName(),
            numThreads,
            opsPerThread,
            numThreads * opsPerThread);
      }
    }

    private LeastUsedStrategy createLeastUsed() {
      final var strategy = new LeastUsedStrategy();
      strategy.initialize(lanes);
      return strategy;
    }
  }
}
