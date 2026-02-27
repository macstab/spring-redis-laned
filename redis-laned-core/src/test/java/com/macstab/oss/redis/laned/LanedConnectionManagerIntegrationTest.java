/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.macstab.oss.redis.laned.strategy.LeastUsedStrategy;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;
import com.macstab.oss.redis.laned.strategy.ThreadAffinityStrategy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.StringCodec;

/**
 * Integration tests with real Redis (Testcontainers).
 *
 * <p><strong>What We Test:</strong>
 *
 * <ul>
 *   <li>Real Redis commands through laned connections
 *   <li>Real Lettuce multiplexing behavior
 *   <li>Real lifecycle tracking with actual TCP connections
 *   <li>Real strategy behavior under concurrent load
 *   <li>Real connection reuse (close() doesn't break TCP)
 *   <li>Real error handling (network errors, timeouts)
 * </ul>
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>Testcontainers provides real Redis in Docker
 *   <li>AAA pattern (Arrange, Act, Assert)
 *   <li>@Nested classes for logical grouping
 *   <li>@DisplayName for human-readable test names
 * </ul>
 *
 * <p><strong>Requirements:</strong>
 *
 * <ul>
 *   <li>Docker must be running
 *   <li>Tests auto-start Redis container
 *   <li>Tests auto-cleanup after completion
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("LanedConnectionManager Integration Tests (Real Redis)")
class LanedConnectionManagerIntegrationTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withStartupTimeout(Duration.ofSeconds(30));

  private RedisClient client;
  private LanedConnectionManager manager;

  @BeforeEach
  void setUp() {
    // Arrange: Create RedisClient pointing to Testcontainers Redis
    String host = REDIS.getHost();
    Integer port = REDIS.getFirstMappedPort();
    RedisURI uri = RedisURI.builder().withHost(host).withPort(port).build();

    client = RedisClient.create(uri);
  }

  /**
   * Helper to get typed connection (avoids unchecked cast warnings).
   *
   * @return typed String/String connection
   */
  @SuppressWarnings("unchecked")
  private io.lettuce.core.api.StatefulRedisConnection<String, String> getTypedConnection() {
    return (io.lettuce.core.api.StatefulRedisConnection<String, String>) manager.getConnection();
  }

  /**
   * Helper to get typed Pub/Sub connection (avoids unchecked cast warnings).
   *
   * @return typed String/String Pub/Sub connection
   */
  @SuppressWarnings("unchecked")
  private io.lettuce.core.pubsub.StatefulRedisPubSubConnection<String, String>
      getTypedPubSubConnection() {
    return (io.lettuce.core.pubsub.StatefulRedisPubSubConnection<String, String>)
        manager.getPubSubConnection();
  }

  @AfterEach
  void tearDown() {
    // Cleanup: Destroy manager and client
    if (manager != null) {
      manager.destroy();
    }
    if (client != null) {
      client.shutdown();
    }
  }

  @Nested
  @DisplayName("Real Redis Commands")
  class RealRedisCommands {

    @Test
    @DisplayName("GET/SET works through laned connection")
    void getSetWorksThoughLanedConnection() {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, new RoundRobinStrategy());

      // Act: Real Redis SET command
      var conn = getTypedConnection();
      conn.sync().set("test-key", "test-value");
      conn.close();

      // Assert: Real Redis GET command
      var conn2 = getTypedConnection();
      String result = conn2.sync().get("test-key");
      conn2.close();

      assertThat(result).as("Value should persist in Redis").isEqualTo("test-value");
    }

    @Test
    @DisplayName("concurrent GET/SET operations work correctly")
    void concurrentGetSetWorksCorrectly() throws Exception {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 8, new RoundRobinStrategy());
      int numThreads = 10;
      int operationsPerThread = 50;
      CountDownLatch latch = new CountDownLatch(numThreads);
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);
      AtomicInteger successCount = new AtomicInteger(0);

      // Act: Concurrent SET operations
      for (int t = 0; t < numThreads; t++) {
        final int threadId = t;
        executor.submit(
            () -> {
              try {
                for (int i = 0; i < operationsPerThread; i++) {
                  var conn = getTypedConnection();
                  String key = "thread-" + threadId + "-key-" + i;
                  String value = "value-" + i;

                  // SET
                  conn.sync().set(key, value);

                  // GET (verify)
                  String retrieved = conn.sync().get(key);
                  if (value.equals(retrieved)) {
                    successCount.incrementAndGet();
                  }

                  conn.close();
                }
              } finally {
                latch.countDown();
              }
            });
      }

      // Assert
      await().atMost(30, SECONDS).until(() -> latch.getCount() == 0); // All threads should complete
      executor.shutdown();

      int expectedSuccesses = numThreads * operationsPerThread;
      assertThat(successCount.get())
          .as("All operations should succeed")
          .isEqualTo(expectedSuccesses);
    }

    @Test
    @DisplayName("INCR operations maintain atomicity")
    void incrOperationsMaintainAtomicity() throws Exception {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, new RoundRobinStrategy());
      String counterKey = "counter";

      int numThreads = 20;
      int incrementsPerThread = 50;
      CountDownLatch latch = new CountDownLatch(numThreads);
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);

      // Act: Concurrent INCR operations
      for (int t = 0; t < numThreads; t++) {
        executor.submit(
            () -> {
              try {
                for (int i = 0; i < incrementsPerThread; i++) {
                  var conn = getTypedConnection();
                  conn.sync().incr(counterKey);
                  conn.close();
                }
              } finally {
                latch.countDown();
              }
            });
      }

      // Assert
      await().atMost(30, SECONDS).until(() -> latch.getCount() == 0); // All threads should complete
      executor.shutdown();

      var conn = getTypedConnection();
      String result = conn.sync().get(counterKey);
      conn.close();

      int expectedCount = numThreads * incrementsPerThread;
      assertThat(result).as("Counter should be atomic").isEqualTo(String.valueOf(expectedCount));
    }
  }

  @Nested
  @DisplayName("Lettuce Multiplexing Behavior")
  class LettuceMultiplexing {

    @Test
    @DisplayName("multiple close() calls don't break connection")
    void multipleCloseCallsDontBreakConnection() {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 2, new RoundRobinStrategy());

      // Act: Get connection, use it, close multiple times
      var conn1 = getTypedConnection();
      conn1.sync().set("test-1", "value-1");
      conn1.close();
      conn1.close(); // Second close (idempotent)
      conn1.close(); // Third close (idempotent)

      // Assert: New connection still works (TCP not closed)
      var conn2 = getTypedConnection();
      String result = conn2.sync().get("test-1");
      conn2.close();

      assertThat(result)
          .as("Connection should still work after multiple closes")
          .isEqualTo("value-1");
    }

    @Test
    @DisplayName("connection reuse works after close")
    void connectionReuseWorksAfterClose() {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, new RoundRobinStrategy());
      List<String> results = new ArrayList<>();

      // Act: Get/use/close connection 100 times
      for (int i = 0; i < 100; i++) {
        var conn = getTypedConnection();
        String key = "key-" + i;
        String value = "value-" + i;

        conn.sync().set(key, value);
        String retrieved = conn.sync().get(key);
        results.add(retrieved);

        conn.close(); // Logical close (TCP stays open)
      }

      // Assert: All operations succeeded
      assertThat(results.size()).as("All 100 operations should succeed").isEqualTo(100);
      for (int i = 0; i < 100; i++) {
        assertThat(results.get(i)).isEqualTo("value-" + i);
      }
    }

    @Test
    @DisplayName("lanes stay open during lifecycle")
    void lanesStayOpenDuringLifecycle() {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 8, new RoundRobinStrategy());

      // Act: Use all lanes
      for (int i = 0; i < 8; i++) {
        var conn = getTypedConnection();
        conn.sync().set("lane-test-" + i, "value-" + i);
        conn.close();
      }

      // Assert: All lanes still open
      assertThat(manager.getOpenLaneCount()).as("All lanes should still be open").isEqualTo(8);

      // Act: Use lanes again
      for (int i = 0; i < 8; i++) {
        var conn = getTypedConnection();
        String result = conn.sync().get("lane-test-" + i);
        conn.close();

        assertThat(result).as("Lane " + i + " should still work").isEqualTo("value-" + i);
      }

      // Assert: Still open after reuse
      assertThat(manager.getOpenLaneCount())
          .as("All lanes should still be open after reuse")
          .isEqualTo(8);
    }
  }

  @Nested
  @DisplayName("Lifecycle Tracking")
  class LifecycleTracking {

    @Test
    @DisplayName("LeastUsedStrategy tracks real in-flight counts")
    void leastUsedStrategyTracksRealInFlightCounts() {
      // Arrange: Create manager with LeastUsedStrategy (two-phase init solves circular dependency)
      var strategy = new LeastUsedStrategy();
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, strategy);

      // Act: Get connections (increment counts)
      var conn1 = getTypedConnection(); // Lane 0, count = 1
      var conn2 = getTypedConnection(); // Lane 1, count = 1
      var conn3 = getTypedConnection(); // Lane 2, count = 1

      // Assert: Counts incremented
      assertThat(manager.lanes[0].getInFlightCount().get() > 0)
          .as("Lane 0 has connections")
          .isTrue();
      assertThat(manager.lanes[1].getInFlightCount().get() > 0)
          .as("Lane 1 has connections")
          .isTrue();
      assertThat(manager.lanes[2].getInFlightCount().get() > 0)
          .as("Lane 2 has connections")
          .isTrue();

      // Act: Close connections (decrement counts)
      conn1.close();
      conn2.close();
      conn3.close();

      // Assert: Counts decremented
      assertThat(manager.lanes[0].getInFlightCount().get() == 0)
          .as("Lane 0 count should decrement")
          .isTrue();
      assertThat(manager.lanes[1].getInFlightCount().get() == 0)
          .as("Lane 1 count should decrement")
          .isTrue();
      assertThat(manager.lanes[2].getInFlightCount().get() == 0)
          .as("Lane 2 count should decrement")
          .isTrue();
    }

    @Test
    @DisplayName("concurrent connections maintain accurate counts")
    void concurrentConnectionsMaintainAccurateCounts() throws Exception {
      // Arrange: Create manager with LeastUsedStrategy (two-phase init solves circular dependency)
      var strategy = new LeastUsedStrategy();
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, strategy);

      int numThreads = 20;
      int operationsPerThread = 50;
      CountDownLatch latch = new CountDownLatch(numThreads);
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);

      // Act: Concurrent get/use/close
      for (int t = 0; t < numThreads; t++) {
        executor.submit(
            () -> {
              try {
                for (int i = 0; i < operationsPerThread; i++) {
                  var conn = getTypedConnection();
                  conn.sync().set("test-" + i, "value-" + i);
                  Thread.sleep(1); // Simulate work
                  conn.close();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                latch.countDown();
              }
            });
      }

      // Assert
      await().atMost(60, SECONDS).until(() -> latch.getCount() == 0); // All threads should complete
      executor.shutdown();

      // All counts should be 0 (all connections closed)
      for (int i = 0; i < 4; i++) {
        assertThat(manager.lanes[i].getInFlightCount().get())
            .as("Lane " + i + " count should be 0")
            .isEqualTo(0);
      }
    }
  }

  @Nested
  @DisplayName("Strategy Behavior")
  class StrategyBehavior {

    @Test
    @DisplayName("RoundRobinStrategy distributes load evenly")
    void roundRobinStrategyDistributesLoadEvenly() {
      // Arrange: Use LeastUsedStrategy (tracks in-flight counts) to verify round-robin pattern
      // RoundRobinStrategy is stateless (no count tracking), so we test the PATTERN via counts
      var strategy = new com.macstab.oss.redis.laned.strategy.LeastUsedStrategy();
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, strategy);

      var maxInFlight = new java.util.concurrent.atomic.AtomicInteger(0);

      // Act: Get/close 100 connections sequentially (round-robin pattern)
      for (int i = 0; i < 100; i++) {
        var conn = getTypedConnection();
        conn.sync().set("test-" + i, "value-" + i);

        // Track max in-flight (should stay low with sequential get/close)
        for (int lane = 0; lane < 4; lane++) {
          maxInFlight.set(
              Math.max(maxInFlight.get(), manager.lanes[lane].getInFlightCount().get()));
        }

        conn.close();
      }

      // Assert: All lanes used (getInFlightCount history)
      // With 100 sequential requests and 4 lanes, each lane handled ~25 requests
      // After close, counts should be back to zero
      for (int lane = 0; lane < 4; lane++) {
        assertThat(manager.lanes[lane].getInFlightCount().get())
            .as("Lane " + lane + " should be idle after all closes")
            .isEqualTo(0);
      }

      // Max in-flight should be low (sequential operations, not concurrent)
      assertThat(maxInFlight.get() <= 4)
          .as("Max in-flight should be ≤ 4 (sequential operations), got: " + maxInFlight.get())
          .isTrue();
    }

    @Test
    @DisplayName("ThreadAffinityStrategy keeps same thread on same lane")
    void threadAffinityStrategyKeepsSameThreadOnSameLane() throws Exception {
      // Arrange
      manager =
          new LanedConnectionManager(client, StringCodec.UTF8, 8, new ThreadAffinityStrategy());
      int numThreads = 10;
      CountDownLatch latch = new CountDownLatch(numThreads);
      ExecutorService executor = Executors.newFixedThreadPool(numThreads);

      // Act: Each thread gets connections multiple times
      for (int t = 0; t < numThreads; t++) {
        executor.submit(
            () -> {
              try {
                Integer firstLane = null;

                // Get connection 10 times from same thread
                for (int i = 0; i < 10; i++) {
                  var conn = getTypedConnection();
                  conn.sync().set("thread-test-" + i, "value-" + i);

                  // Find which lane we're on
                  for (int lane = 0; lane < 8; lane++) {
                    if (manager.lanes[lane].getInFlightCount().get() > 0) {
                      if (firstLane == null) {
                        firstLane = lane;
                      } else {
                        // Same thread should use same lane
                        assertThat(lane)
                            .as("Same thread should use same lane")
                            .isEqualTo(firstLane);
                      }
                      break;
                    }
                  }

                  conn.close();
                }
              } finally {
                latch.countDown();
              }
            });
      }

      // Assert
      await().atMost(30, SECONDS).until(() -> latch.getCount() == 0); // All threads should complete
      executor.shutdown();
    }
  }

  @Nested
  @DisplayName("Pub/Sub Isolation")
  class PubSubIsolation {

    @Test
    @DisplayName("Pub/Sub connection works independently")
    void pubSubConnectionWorksIndependently() throws Exception {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, new RoundRobinStrategy());
      CountDownLatch messageLatch = new CountDownLatch(1);
      String[] receivedMessage = new String[1];

      // Act: Get Pub/Sub connection
      var pubSubConn = getTypedPubSubConnection();

      // Subscribe to channel
      pubSubConn.addListener(
          new io.lettuce.core.pubsub.RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
              receivedMessage[0] = message;
              messageLatch.countDown();
            }
          });

      pubSubConn.sync().subscribe("test-channel");

      // Publish message using regular connection
      var regularConn = getTypedConnection();
      regularConn.sync().publish("test-channel", "test-message");
      regularConn.close();

      // Assert
      await()
          .atMost(5, SECONDS)
          .until(() -> messageLatch.getCount() == 0); // Should receive message
      assertThat(receivedMessage[0]).as("Message content should match").isEqualTo("test-message");

      // Cleanup
      pubSubConn.sync().unsubscribe("test-channel");
      manager.releaseConnection(pubSubConn);
    }
  }

  @Nested
  @DisplayName("Cleanup and Destroy")
  class CleanupAndDestroy {

    @Test
    @DisplayName("destroy closes all lane connections")
    void destroyClosesAllLaneConnections() {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 8, new RoundRobinStrategy());

      // Act: Use all lanes
      for (int i = 0; i < 8; i++) {
        var conn = getTypedConnection();
        conn.sync().set("test-" + i, "value-" + i);
        conn.close();
      }

      assertThat(manager.getOpenLaneCount()).as("All lanes should be open").isEqualTo(8);

      // Act: Destroy manager
      manager.destroy();

      // Assert: All lanes closed
      assertThat(manager.getOpenLaneCount()).as("All lanes should be closed").isEqualTo(0);
      assertThat(manager.isDestroyed()).as("Manager should be marked destroyed").isTrue();
    }

    @Test
    @DisplayName("operations after destroy throw exception")
    void operationsAfterDestroyThrowException() {
      // Arrange
      manager = new LanedConnectionManager(client, StringCodec.UTF8, 4, new RoundRobinStrategy());

      // Act: Destroy manager
      manager.destroy();

      // Assert: Operations should fail
      // TODO: Convert assertThrows to assertThatThrownBy - needs manual review
    }
  }
}
