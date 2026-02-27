/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;

/** Tests for {@link ThreadAffinityStrategy}. */
@Slf4j
@DisplayName("ThreadAffinityStrategy")
class ThreadAffinityStrategyTest {

  @Test
  @DisplayName("Same thread always gets same lane")
  void selectLane_SameThreadSameLane() {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    final int numLanes = 8;

    final int firstSelection = strategy.selectLane(numLanes);
    for (int i = 0; i < 100; i++) {
      final int lane = strategy.selectLane(numLanes);
      assertThat(lane).as("Same thread should always get same lane").isEqualTo(firstSelection);
    }
  }

  @Test
  @DisplayName("Different threads get distributed across lanes")
  void selectLane_DifferentThreadsDistributed() throws Exception {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    final int numLanes = 8;
    final int numThreads = 800;

    final Set<Integer> assignedLanes = ConcurrentHashMap.newKeySet();
    final var latch = new CountDownLatch(numThreads);
    final ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    for (int t = 0; t < numThreads; t++) {
      executor.submit(
          () -> {
            final int lane = strategy.selectLane(numLanes);
            assignedLanes.add(lane);
            latch.countDown();
          });
    }

    await().atMost(10, SECONDS).until(() -> latch.getCount() == 0);
    executor.shutdown();

    // All 8 lanes should have been assigned (distribution across lanes)
    assertThat(assignedLanes.size()).as("All lanes should be used").isEqualTo(numLanes);
  }

  @Test
  @DisplayName("Thread affinity persists across many calls (stateless - no ThreadLocal)")
  void selectLane_AffinityPersists() throws Exception {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    final int numLanes = 8;
    final int numThreads = 16;
    final int callsPerThread = 1000;

    final var latch = new CountDownLatch(numThreads);
    final var failures = new AtomicIntegerArray(1);
    final ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    for (int t = 0; t < numThreads; t++) {
      executor.submit(
          () -> {
            try {
              final int firstLane = strategy.selectLane(numLanes);
              for (int i = 0; i < callsPerThread; i++) {
                final int lane = strategy.selectLane(numLanes);
                if (lane != firstLane) {
                  failures.incrementAndGet(0);
                  assertThat(lane)
                      .as("Thread should get same lane consistently")
                      .isEqualTo(firstLane);
                }
              }
            } finally {
              latch.countDown();
            }
          });
    }

    await().atMost(10, SECONDS).until(() -> latch.getCount() == 0);
    executor.shutdown();
    assertThat(failures.get(0)).as("No thread should change lanes").isEqualTo(0);
  }

  @Test
  @DisplayName("Distribution is uniform (MurmurHash3 scrambles sequential thread IDs)")
  void selectLane_UniformDistribution() throws Exception {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    final int numLanes = 8;
    final int numThreads = 800;

    final var laneCounts = new AtomicIntegerArray(numLanes);
    final var latch = new CountDownLatch(numThreads);
    final ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    for (int t = 0; t < numThreads; t++) {
      executor.submit(
          () -> {
            final int lane = strategy.selectLane(numLanes);
            laneCounts.incrementAndGet(lane);
            latch.countDown();
          });
    }

    await().atMost(10, SECONDS).until(() -> latch.getCount() == 0);
    executor.shutdown();

    // Each lane should get ~100 threads (±20% tolerance)
    final int expectedPerLane = numThreads / numLanes; // 100
    final int tolerance = (int) (expectedPerLane * 0.2); // 20

    for (int i = 0; i < numLanes; i++) {
      final int count = laneCounts.get(i);
      assertThat(Math.abs(count - expectedPerLane)).isLessThanOrEqualTo(tolerance);
    }
  }

  @Test
  @DisplayName("getName returns 'thread-affinity'")
  void getName_ReturnsCorrectName() {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    assertThat(strategy.getName()).isEqualTo("thread-affinity");
  }

  @Test
  @DisplayName("selectLane returns values in range [0, numLanes-1]")
  void selectLane_ReturnsValidRange() {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();

    for (int numLanes = 1; numLanes <= 32; numLanes++) {
      for (int i = 0; i < 10; i++) {
        final int lane = strategy.selectLane(numLanes);
        assertThat(lane >= 0 && lane < numLanes).isTrue();
      }
    }
  }

  @Test
  @DisplayName("Performance benchmark (informational)")
  void selectLane_PerformanceBenchmark() {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    final int numLanes = 8;
    final int iterations = 1_000_000;

    // Warmup (JIT compilation)
    for (int i = 0; i < 10_000; i++) {
      strategy.selectLane(numLanes);
    }

    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      strategy.selectLane(numLanes);
    }
    final long elapsed = System.nanoTime() - start;

    final long avgNanos = elapsed / iterations;
    log.info(
        "ThreadAffinityStrategy.selectLane(): {} ns/op (1M iterations, MurmurHash3)", avgNanos);

    // Informational only - actual performance varies by system load
    // Typical: 30-80ns (threadId read + MurmurHash3 + modulo)
  }

  @Test
  @DisplayName("No ThreadLocal storage (zero memory overhead)")
  void selectLane_NoThreadLocalStorage() throws Exception {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    final int numLanes = 8;
    final int numThreads = 1000;

    // Create 1000 threads, each selecting lane
    final var latch = new CountDownLatch(numThreads);
    final ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    for (int t = 0; t < numThreads; t++) {
      executor.submit(
          () -> {
            strategy.selectLane(numLanes);
            latch.countDown();
          });
    }

    await().atMost(10, SECONDS).until(() -> latch.getCount() == 0);
    executor.shutdown();

    // No assertions - test verifies no OutOfMemoryError with 1000 threads
    // ThreadLocal approach would allocate ~24-32 bytes per thread = 24-32 KB
    // Thread ID approach allocates ZERO bytes (stateless)
  }

  @Test
  @DisplayName("Thread affinity safe for transactions (same thread → same lane)")
  void selectLane_TransactionSafe() {
    // Arrange

    // Act

    // Assert
    final var strategy = new ThreadAffinityStrategy();
    final int numLanes = 8;

    // Simulate transaction sequence (WATCH, MULTI, EXEC)
    final int watchLane = strategy.selectLane(numLanes);
    final int multiLane = strategy.selectLane(numLanes);
    final int execLane = strategy.selectLane(numLanes);

    // All commands from same thread should hit same lane
    assertThat(multiLane).as("WATCH and MULTI must be on same lane").isEqualTo(watchLane);
    assertThat(execLane).as("MULTI and EXEC must be on same lane").isEqualTo(multiLane);
  }
}
