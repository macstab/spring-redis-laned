/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.macstab.oss.redis.laned.ConnectionLane;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Tests for {@link LeastUsedStrategy} - real-time load-aware lane selection.
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
@DisplayName("LeastUsedStrategy")
class LeastUsedStrategyTest {

  @Mock private StatefulRedisConnection<String, String> mockConnection;

  private ConnectionLane[] lanes;
  private LeastUsedStrategy strategy;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockConnection.isOpen()).thenReturn(true);

    // Arrange: Create 8 lanes with mock connections
    lanes = new ConnectionLane[8];
    for (int i = 0; i < 8; i++) {
      lanes[i] = new ConnectionLane(i, mockConnection);
    }

    // Two-phase initialization (no circular dependency)
    strategy = new LeastUsedStrategy();
    strategy.initialize(lanes);
  }

  @Nested
  @DisplayName("Lane Selection")
  class LaneSelection {

    @Test
    @DisplayName("selects lane with minimum in-flight count")
    void selectsLaneWithMinimumCount() {
      // Arrange: Set different in-flight counts
      lanes[0].getInFlightCount().set(5);
      lanes[1].getInFlightCount().set(0); // Minimum
      lanes[2].getInFlightCount().set(10);
      lanes[3].getInFlightCount().set(3);

      // Act
      int selectedLane = strategy.selectLane(8);

      // Assert
      assertThat(selectedLane).as("Should select lane with minimum count (lane 1)").isEqualTo(1);
    }

    @Test
    @DisplayName("tie-breaks by selecting lowest index")
    void tieBreaksByLowestIndex() {
      // Arrange: Lanes 0, 2, 5 are idle (count = 0), others busy
      lanes[0].getInFlightCount().set(0); // Tied minimum
      lanes[1].getInFlightCount().set(3);
      lanes[2].getInFlightCount().set(0); // Tied minimum
      lanes[3].getInFlightCount().set(7);
      lanes[4].getInFlightCount().set(2);
      lanes[5].getInFlightCount().set(0); // Tied minimum
      lanes[6].getInFlightCount().set(4);
      lanes[7].getInFlightCount().set(1);

      // Act
      int selectedLane = strategy.selectLane(8);

      // Assert
      assertThat(selectedLane)
          .as("Should select lowest index among tied lanes (lane 0)")
          .isEqualTo(0);
    }

    @Test
    @DisplayName("adapts to changing in-flight counts")
    void adaptsToChangingCounts() {
      // Arrange: Initially all idle
      int firstSelection = strategy.selectLane(8);
      assertThat(firstSelection).as("First selection should be lane 0").isEqualTo(0);

      // Act: Simulate lane 0 becomes busy
      lanes[0].getInFlightCount().set(10);
      int secondSelection = strategy.selectLane(8);

      // Assert: Should avoid busy lane 0
      assertThat(secondSelection).as("Should select lane 1 (next idle lane)").isEqualTo(1);

      // Act: Simulate lane 0 becomes idle again
      lanes[0].getInFlightCount().set(0);
      int thirdSelection = strategy.selectLane(8);

      // Assert: Should again consider lane 0
      assertThat(thirdSelection).as("Should select lane 0 again (now idle)").isEqualTo(0);
    }

    @Test
    @DisplayName("returns values in range [0, numLanes-1]")
    void returnsValidRange() {
      // Arrange: Set random counts
      lanes[0].getInFlightCount().set(5);
      lanes[1].getInFlightCount().set(2);
      lanes[2].getInFlightCount().set(8);
      lanes[3].getInFlightCount().set(1);
      lanes[4].getInFlightCount().set(6);
      lanes[5].getInFlightCount().set(3);
      lanes[6].getInFlightCount().set(9);
      lanes[7].getInFlightCount().set(4);

      // Act & Assert: Test multiple lane counts
      for (int numLanes = 1; numLanes <= 8; numLanes++) {
        for (int i = 0; i < 100; i++) {
          int lane = strategy.selectLane(numLanes);
          assertThat(lane >= 0 && lane < numLanes)
              .as("Lane must be in range [0).as(" + (numLanes - 1) + "], got: " + lane)
              .isTrue();
        }
      }
    }
  }

  @Nested
  @DisplayName("Lifecycle Tracking")
  class LifecycleTracking {

    @Test
    @DisplayName("onConnectionReleased decrements in-flight count")
    void onConnectionReleasedDecrementsCount() {
      // Arrange: Lane 5 has count = 10
      lanes[5].getInFlightCount().set(10);
      assertThat(lanes[5].getInFlightCount().get()).isEqualTo(10);

      // Act
      strategy.onConnectionReleased(5);

      // Assert
      assertThat(lanes[5].getInFlightCount().get()).as("Count should decrement to 9").isEqualTo(9);
    }

    @Test
    @DisplayName("onConnectionReleased does not decrement below zero")
    void onConnectionReleasedDoesNotGoNegative() {
      // Arrange: Lane 2 has count = 0
      lanes[2].getInFlightCount().set(0);

      // Act: Release connection multiple times (simulate duplicate close() calls)
      strategy.onConnectionReleased(2);
      strategy.onConnectionReleased(2);
      strategy.onConnectionReleased(2);

      // Assert
      assertThat(lanes[2].getInFlightCount().get())
          .as("Count should stay at 0 (no negative)")
          .isEqualTo(0);
    }

    @Test
    @DisplayName("getInFlightCount returns correct count for lane")
    void getInFlightCountReturnsCorrectCount() {
      // Arrange: Set various counts
      lanes[0].getInFlightCount().set(5);
      lanes[3].getInFlightCount().set(12);
      lanes[7].getInFlightCount().set(0);

      // Act & Assert
      assertThat(strategy.getInFlightCount(0)).as("Lane 0 count should be 5").isEqualTo(5);
      assertThat(strategy.getInFlightCount(3)).as("Lane 3 count should be 12").isEqualTo(12);
      assertThat(strategy.getInFlightCount(7)).as("Lane 7 count should be 0").isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Thread Safety")
  class ThreadSafety {

    @Test
    @DisplayName("selectLane is thread-safe under concurrent access")
    void selectLaneIsThreadSafe() throws Exception {
      // Arrange
      int numThreads = 100;
      int selectionsPerThread = 1000;
      CountDownLatch latch = new CountDownLatch(numThreads);
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Act: Concurrent selections
      for (int t = 0; t < numThreads; t++) {
        executor.submit(
            () -> {
              try {
                for (int i = 0; i < selectionsPerThread; i++) {
                  int lane = strategy.selectLane(8);
                  if (lane < 0 || lane >= 8) {
                    errorCount.incrementAndGet();
                  }
                }
              } finally {
                latch.countDown();
              }
            });
      }

      // Assert
      await()
          .atMost(10, SECONDS)
          .until(() -> latch.getCount() == 0); // All threads should complete within 10 seconds
      executor.shutdown();
      assertThat(errorCount.get()).as("No invalid lane indices should be returned").isEqualTo(0);

      // Verify all lanes are in valid state (no corruption)
      for (int i = 0; i < 8; i++) {
        int count = lanes[i].getInFlightCount().get();
        assertThat(count >= 0).as("In-flight count must be non-negative").isTrue();
      }
    }

    @Test
    @DisplayName("onConnectionReleased is thread-safe under concurrent releases")
    void onConnectionReleasedIsThreadSafe() throws Exception {
      // Arrange
      lanes[3].getInFlightCount().set(1000); // Start with high count
      int numThreads = 50;
      int releasesPerThread = 20; // 50 × 20 = 1000 total releases
      CountDownLatch latch = new CountDownLatch(numThreads);
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);

      // Act: Concurrent releases on same lane
      for (int t = 0; t < numThreads; t++) {
        executor.submit(
            () -> {
              try {
                for (int i = 0; i < releasesPerThread; i++) {
                  strategy.onConnectionReleased(3);
                }
              } finally {
                latch.countDown();
              }
            });
      }

      // Assert
      await().atMost(10, SECONDS).until(() -> latch.getCount() == 0); // All threads should complete
      executor.shutdown();
      assertThat(lanes[3].getInFlightCount().get())
          .as("Count should decrement to exactly 0")
          .isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Metadata")
  class Metadata {

    @Test
    @DisplayName("getName returns 'least-used'")
    void getNameReturnsCorrectName() {
      // Arrange (setup in @BeforeEach)

      // Act
      String name = strategy.getName();

      // Assert
      assertThat(name).as("Strategy name should be 'least-used'").isEqualTo("least-used");
    }
  }
}
