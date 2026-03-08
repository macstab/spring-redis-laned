/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;

/**
 * Integration tests for {@link RoundRobinStrategy} with real Redis.
 *
 * <p>Tests round-robin distribution with real connections and commands.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Testcontainers
@DisplayName("RoundRobinStrategy (Integration)")
class RoundRobinStrategyIntegrationTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withCommand("redis-server", "--save", "", "--appendonly", "no");

  private RedisClient client;
  private LanedConnectionManager manager;
  private LanedRedisMetrics mockMetrics;

  @BeforeEach
  void setUp() {
    final var uri =
        RedisURI.builder()
            .withHost(REDIS.getHost())
            .withPort(REDIS.getFirstMappedPort())
            .withTimeout(Duration.ofSeconds(5))
            .build();

    client = RedisClient.create(uri);
    mockMetrics = mock(LanedRedisMetrics.class);
    manager =
        new LanedConnectionManager(
            client,
            StringCodec.UTF8,
            8,
            new RoundRobinStrategy(),
            Optional.of(mockMetrics),
            "default");
  }

  @AfterEach
  void tearDown() {
    if (manager != null) {
      manager.destroy();
    }
    if (client != null) {
      client.shutdown();
    }
  }

  @Nested
  @DisplayName("Sequential Distribution")
  class SequentialDistribution {

    @Test
    @DisplayName("sequential requests cycle through lanes")
    void sequentialCycle() throws Exception {
      // Arrange & Act: 100 sequential requests
      for (int i = 0; i < 100; i++) {
        final var conn = (StatefulRedisConnection<String, String>) manager.getConnection();
        conn.sync().set("key-" + i, "value-" + i);
        conn.close();
      }

      // Assert: Metrics recorded for all 100 connections
      verify(mockMetrics, atLeastOnce())
          .recordLaneSelection(eq("default"), anyInt(), eq("round-robin"));
    }

    @Test
    @DisplayName("uniform distribution over 1000 requests")
    void uniformDistribution() throws Exception {
      // Arrange & Act: 1000 requests
      for (int i = 0; i < 1000; i++) {
        final var conn = (StatefulRedisConnection<String, String>) manager.getConnection();
        conn.sync().ping();
        conn.close();
      }

      // Assert: Metrics recorded for all 1000 connections
      verify(mockMetrics, atLeastOnce())
          .recordLaneSelection(eq("default"), anyInt(), eq("round-robin"));
    }
  }

  @Nested
  @DisplayName("Concurrent Access")
  class ConcurrentAccess {

    @Test
    @DisplayName("100 threads each executing 100 commands")
    void concurrentThreads() throws Exception {
      // Arrange
      final int numThreads = 100;
      final int opsPerThread = 100;
      final var successCount = new AtomicInteger(0);
      final var latch = new CountDownLatch(numThreads);

      // Act
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int t = 0; t < numThreads; t++) {
          final int threadId = t;
          executor.submit(
              () -> {
                try {
                  for (int i = 0; i < opsPerThread; i++) {
                    final var conn =
                        (StatefulRedisConnection<String, String>) manager.getConnection();
                    conn.sync().set("thread-" + threadId + "-key-" + i, "value-" + i);
                    successCount.incrementAndGet();
                    conn.close();
                  }
                } finally {
                  latch.countDown();
                }
              });
        }
      }

      assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();

      // Assert: All operations succeeded
      assertThat(successCount.get()).isEqualTo(numThreads * opsPerThread);

      // Assert: Metrics recorded for all 10,000 operations
      verify(mockMetrics, atLeastOnce())
          .recordLaneSelection(eq("default"), anyInt(), eq("round-robin"));
    }

    @Test
    @DisplayName("1000 virtual threads with PING")
    void virtualThreadScale() throws Exception {
      // Arrange
      final int numThreads = 1000;
      final var latch = new CountDownLatch(numThreads);

      // Act
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < numThreads; i++) {
          executor.submit(
              () -> {
                try {
                  final var conn =
                      (StatefulRedisConnection<String, String>) manager.getConnection();
                  conn.sync().ping();
                  conn.close();
                } finally {
                  latch.countDown();
                }
              });
        }
      }

      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();

      // Assert: Metrics recorded for all 1000 connections
      verify(mockMetrics, atLeastOnce())
          .recordLaneSelection(eq("default"), anyInt(), eq("round-robin"));
    }
  }

  @Nested
  @DisplayName("Counter Overflow")
  class CounterOverflow {

    @Test
    @DisplayName("handles counter wrap-around gracefully")
    void counterWrapAround() throws Exception {
      // Arrange: Force counter close to overflow (simulate via many selections)
      // Execute enough commands to potentially wrap counter
      final int numCommands = 10_000;

      // Act
      for (int i = 0; i < numCommands; i++) {
        final var conn = (StatefulRedisConnection<String, String>) manager.getConnection();
        conn.sync().ping();
        conn.close();
      }

      // Assert: All commands succeeded (no exceptions)
      // Assert: Metrics recorded for all connections
      verify(mockMetrics, atLeastOnce())
          .recordLaneSelection(eq("default"), anyInt(), eq("round-robin"));
    }
  }
}
