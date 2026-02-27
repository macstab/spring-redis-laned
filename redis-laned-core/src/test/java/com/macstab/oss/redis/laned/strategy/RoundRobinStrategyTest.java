/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import lombok.extern.slf4j.Slf4j;

/**
 * Comprehensive tests for {@link RoundRobinStrategy}.
 *
 * <p>Tests verify:
 *
 * <ul>
 *   <li>Round-robin distribution (uniform over 1000 selections)
 *   <li>Overflow safety (counter wraps at Integer.MAX_VALUE)
 *   <li>Thread safety (100 threads, 10,000 selections each)
 *   <li>Lane index range (always [0, numLanes-1])
 *   <li>Power-of-2 vs non-power-of-2 lane counts
 * </ul>
 */
@Slf4j
@DisplayName("RoundRobinStrategy")
class RoundRobinStrategyTest {

  @Test
  @DisplayName("selectLane returns values in range [0, numLanes-1]")
  void selectLane_ReturnsValidRange() {
    // Arrange
    final var strategy = new RoundRobinStrategy();

    // Act & Assert
    for (int numLanes = 1; numLanes <= 32; numLanes++) {
      for (int i = 0; i < 100; i++) {
        final int lane = strategy.selectLane(numLanes);
        assertThat(lane).isGreaterThanOrEqualTo(0);
        assertThat(lane).isLessThan(numLanes);
      }
    }
  }

  @Test
  @DisplayName("selectLane distributes uniformly over 1000 selections (power-of-2)")
  void selectLane_UniformDistribution_PowerOf2() {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();
    final int numLanes = 8;
    final int totalSelections = 1000;

    final int[] counts = new int[numLanes];
    for (int i = 0; i < totalSelections; i++) {
      final int lane = strategy.selectLane(numLanes);
      counts[lane]++;
    }

    final int expectedPerLane = totalSelections / numLanes; // 125
    for (int i = 0; i < numLanes; i++) {
      assertThat(counts[i])
          .as("Lane " + i + " should have exactly " + expectedPerLane + " selections")
          .isEqualTo(expectedPerLane);
    }
  }

  @Test
  @DisplayName("selectLane distributes uniformly over 1000 selections (non-power-of-2)")
  void selectLane_UniformDistribution_NonPowerOf2() {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();
    final int numLanes = 7;
    final int totalSelections = 1001; // 143 per lane (7 × 143 = 1001)

    final int[] counts = new int[numLanes];
    for (int i = 0; i < totalSelections; i++) {
      final int lane = strategy.selectLane(numLanes);
      counts[lane]++;
    }

    final int expectedPerLane = totalSelections / numLanes; // 143
    for (int i = 0; i < numLanes; i++) {
      assertThat(counts[i])
          .as("Lane " + i + " should have exactly " + expectedPerLane + " selections")
          .isEqualTo(expectedPerLane);
    }
  }

  @Test
  @DisplayName("selectLane handles counter overflow gracefully (Integer.MAX_VALUE wrap)")
  void selectLane_HandlesOverflow() throws Exception {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();

    // Force counter to near Integer.MAX_VALUE using reflection
    final var counterField = RoundRobinStrategy.class.getDeclaredField("counter");
    counterField.setAccessible(true);
    final var counter = (AtomicInteger) counterField.get(strategy);
    counter.set(Integer.MAX_VALUE - 10);

    final int numLanes = 8;
    final int[] lanes = new int[20];

    // Select 20 times (wraps from MAX_VALUE to MIN_VALUE around selection 10-11)
    for (int i = 0; i < 20; i++) {
      lanes[i] = strategy.selectLane(numLanes);
    }

    // Verify all selections are valid (0-7)
    for (int i = 0; i < 20; i++) {
      assertThat(lanes[i]).isBetween(0, numLanes - 1);
    }

    // Verify round-robin continued across overflow (no gap/reset)
    // Before overflow: counter ~MAX_VALUE-10, lanes should be sequential mod 8
    // After overflow: counter ~MIN_VALUE+10, lanes should still be sequential mod 8
    for (int i = 1; i < 20; i++) {
      final int expected = (lanes[i - 1] + 1) % numLanes;
      assertThat(lanes[i]).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("selectLane is thread-safe (100 threads, 10,000 selections each)")
  void selectLane_ThreadSafe() throws Exception {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();
    final int numLanes = 8;
    final int numThreads = 100;
    final int selectionsPerThread = 10_000;
    final int totalSelections = numThreads * selectionsPerThread;

    final var counts = new AtomicIntegerArray(numLanes);
    final var latch = new CountDownLatch(numThreads);
    final ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    for (int t = 0; t < numThreads; t++) {
      executor.submit(
          () -> {
            for (int i = 0; i < selectionsPerThread; i++) {
              final int lane = strategy.selectLane(numLanes);
              counts.incrementAndGet(lane);
            }
            latch.countDown();
          });
    }

    await()
        .atMost(10, SECONDS)
        .until(() -> latch.getCount() == 0); // All threads should complete within 10 seconds
    executor.shutdown();

    // Verify total selections
    int sum = 0;
    for (int i = 0; i < numLanes; i++) {
      sum += counts.get(i);
    }
    assertThat(sum).as("Total selections should match expected").isEqualTo(totalSelections);

    // Verify distribution (each lane should get ~125,000 selections ± 5%)
    final int expectedPerLane = totalSelections / numLanes;
    final int tolerance = (int) (expectedPerLane * 0.05); // 5% tolerance

    for (int i = 0; i < numLanes; i++) {
      final int count = counts.get(i);
      assertThat(Math.abs(count - expectedPerLane)).isLessThanOrEqualTo(tolerance);
    }
  }

  @Test
  @DisplayName("getTotalSelections tracks counter correctly")
  void getTotalSelections_TracksCounter() {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();

    assertThat(strategy.getTotalSelections()).as("Initial count should be 0").isEqualTo(0);

    for (int i = 0; i < 100; i++) {
      strategy.selectLane(8);
    }

    assertThat(strategy.getTotalSelections())
        .as("After 100 selections, count should be 100")
        .isEqualTo(100);
  }

  @Test
  @DisplayName("getName returns 'round-robin'")
  void getName_ReturnsCorrectName() {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();
    assertThat(strategy.getName()).isEqualTo("round-robin");
  }

  @Test
  @DisplayName("selectLane with numLanes=1 always returns 0")
  void selectLane_SingleLane() {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();

    for (int i = 0; i < 100; i++) {
      assertThat(strategy.selectLane(1)).as("With 1 lane, should always return 0").isEqualTo(0);
    }
  }

  @Test
  @DisplayName("selectLane performance benchmark (1M selections)")
  void selectLane_PerformanceBenchmark() {
    // Arrange

    // Act

    // Assert
    final var strategy = new RoundRobinStrategy();
    final int numLanes = 8;
    final int iterations = 1_000_000;

    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      strategy.selectLane(numLanes);
    }
    final long elapsed = System.nanoTime() - start;

    final long avgNanos = elapsed / iterations;
    log.info(
        "RoundRobinStrategy.selectLane(): {} ns/op (1M iterations, {} lanes)", avgNanos, numLanes);

    // Should complete in < 500ns per selection (acceptable threshold)
    assertThat(avgNanos < 500)
        .as("Average selection time should be < 500ns, got: " + avgNanos + " ns")
        .isTrue();
  }
}
