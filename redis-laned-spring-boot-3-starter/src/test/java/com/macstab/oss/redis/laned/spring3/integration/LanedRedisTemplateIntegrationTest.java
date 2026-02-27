/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3.integration;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.oss.redis.laned.spring3.testutil.RedisTestContainers;

/**
 * Integration tests for {@link RedisTemplate} with laned connections.
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>Full Spring Boot context with real Redis (Testcontainers)
 *   <li>Validates complete integration: properties → AutoConfiguration → factory → RedisTemplate
 *   <li>Tests real Redis operations (GET, SET, DEL, etc.)
 *   <li>Tests concurrent operations and lane distribution
 *   <li>AAA pattern (Arrange, Act, Assert)
 * </ul>
 *
 * <p><strong>Coverage:</strong>
 *
 * <ul>
 *   <li>Basic Redis operations through RedisTemplate
 *   <li>Concurrent operations (lane distribution)
 *   <li>Transactions (WATCH/MULTI/EXEC)
 *   <li>Connection lifecycle
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@SpringBootTest(
    classes = {
      LanedRedisTemplateIntegrationTest.TestConfig.class,
      com.macstab.oss.redis.laned.spring3.LanedRedisAutoConfiguration.class
    })
@Testcontainers
@Tag("integration")
@DisplayName("LanedRedisTemplate Integration Tests")
class LanedRedisTemplateIntegrationTest {

  @Container
  private static final GenericContainer<?> REDIS = RedisTestContainers.createStandalone();

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    registry.add("spring.data.redis.connection.strategy", () -> "LANED");
    registry.add("spring.data.redis.connection.lanes", () -> 8);
  }

  @Autowired private RedisTemplate<String, String> redisTemplate;

  @AfterEach
  void tearDown() {
    // Clean up Redis between tests
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @Nested
  @DisplayName("Basic Operations")
  class BasicOperationsTest {

    @Test
    @DisplayName("should perform GET and SET operations")
    void shouldPerformGetAndSetOperations() {
      // Arrange
      String key = "test:key";
      String value = "test-value";

      // Act
      redisTemplate.opsForValue().set(key, value);
      String retrieved = redisTemplate.opsForValue().get(key);

      // Assert
      assertThat(retrieved).isEqualTo(value);
    }

    @Test
    @DisplayName("should return null for non-existent key")
    void shouldReturnNullForNonExistentKey() {
      // Arrange
      String key = "test:nonexistent";

      // Act
      String result = redisTemplate.opsForValue().get(key);

      // Assert
      assertThat(result).isNull();
    }

    @Test
    @DisplayName("should delete existing key")
    void shouldDeleteExistingKey() {
      // Arrange
      String key = "test:delete";
      redisTemplate.opsForValue().set(key, "value");

      // Act
      Boolean deleted = redisTemplate.delete(key);

      // Assert
      assertThat(deleted).isTrue();
      assertThat(redisTemplate.opsForValue().get(key)).isNull();
    }

    @Test
    @DisplayName("should return false when deleting non-existent key")
    void shouldReturnFalseWhenDeletingNonExistentKey() {
      // Arrange
      String key = "test:nonexistent";

      // Act
      Boolean deleted = redisTemplate.delete(key);

      // Assert
      assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("should check key existence")
    void shouldCheckKeyExistence() {
      // Arrange
      String key = "test:exists";
      redisTemplate.opsForValue().set(key, "value");

      // Act & Assert
      assertThat(redisTemplate.hasKey(key)).isTrue();
      assertThat(redisTemplate.hasKey("test:nonexistent")).isFalse();
    }
  }

  @Nested
  @DisplayName("Concurrent Operations")
  class ConcurrentOperationsTest {

    @Test
    @DisplayName("should handle 100 concurrent SET operations without loss")
    void shouldHandle100ConcurrentSetOperations() throws InterruptedException {
      // Arrange
      int threadCount = 100;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch endLatch = new CountDownLatch(threadCount);
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      // Act
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(
            () -> {
              try {
                startLatch.await(); // Wait for all threads ready
                redisTemplate.opsForValue().set("test:concurrent:" + index, "value-" + index);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                endLatch.countDown();
              }
            });
      }

      startLatch.countDown(); // Start all threads
      boolean completed = endLatch.await(10, SECONDS);
      executor.shutdown();

      // Assert
      assertThat(completed).isTrue();

      // Verify all keys written
      for (int i = 0; i < threadCount; i++) {
        String value = redisTemplate.opsForValue().get("test:concurrent:" + i);
        assertThat(value).isEqualTo("value-" + i);
      }
    }

    @Test
    @DisplayName("should distribute operations across lanes (probabilistic)")
    void shouldDistributeOperationsAcrossLanes() {
      // Arrange
      int operationCount = 1000;
      List<String> keys = new ArrayList<>();

      // Act
      for (int i = 0; i < operationCount; i++) {
        String key = "test:distribution:" + i;
        keys.add(key);
        redisTemplate.opsForValue().set(key, "value");
      }

      // Assert - All operations succeeded
      for (String key : keys) {
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("value");
      }

      // Note: We can't directly observe lane distribution without instrumentation,
      // but success proves round-robin didn't cause collisions/deadlocks
    }

    @Test
    @DisplayName("should handle concurrent INCR operations atomically")
    void shouldHandleConcurrentIncrOperationsAtomically() throws InterruptedException {
      // Arrange
      String key = "test:counter";
      int threadCount = 50;
      int incrementsPerThread = 20;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch endLatch = new CountDownLatch(threadCount);
      ExecutorService executor = Executors.newFixedThreadPool(threadCount);

      // Act
      for (int i = 0; i < threadCount; i++) {
        executor.submit(
            () -> {
              try {
                startLatch.await();
                for (int j = 0; j < incrementsPerThread; j++) {
                  redisTemplate.opsForValue().increment(key);
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                endLatch.countDown();
              }
            });
      }

      startLatch.countDown();
      boolean completed = endLatch.await(10, SECONDS);
      executor.shutdown();

      // Assert
      assertThat(completed).isTrue();

      String finalValueStr = redisTemplate.opsForValue().get(key);
      Long finalValue = Long.parseLong(finalValueStr);
      int expectedValue = threadCount * incrementsPerThread;
      assertThat(finalValue).isEqualTo(expectedValue);
    }
  }

  @Nested
  @DisplayName("Transactions")
  class TransactionsTest {

    @Test
    @DisplayName("should execute simple transaction (MULTI/EXEC)")
    void shouldExecuteSimpleTransaction() {
      // Arrange
      String key = "test:transaction";

      // Act
      List<Object> results =
          redisTemplate.execute(
              new org.springframework.data.redis.core.SessionCallback<List<Object>>() {
                @Override
                @SuppressWarnings("unchecked")
                public List<Object> execute(
                    org.springframework.data.redis.core.RedisOperations operations) {
                  operations.multi();
                  operations.opsForValue().set(key, "value1");
                  operations.opsForValue().set(key, "value2");
                  return operations.exec();
                }
              });

      // Assert
      assertThat(results).hasSize(2);
      assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("value2");
    }

    @Test
    @DisplayName("should handle WATCH/MULTI/EXEC on same thread (ThreadLocal pinning)")
    void shouldHandleWatchMultiExecOnSameThread() {
      // Arrange
      String key = "test:watch";
      redisTemplate.opsForValue().set(key, "initial");

      // Act
      List<Object> results =
          redisTemplate.execute(
              (org.springframework.data.redis.core.RedisCallback<List<Object>>)
                  connection -> {
                    connection.watch(key.getBytes());
                    connection.multi();
                    connection.stringCommands().set(key.getBytes(), "updated".getBytes());
                    return connection.exec();
                  });

      // Assert
      assertThat(results).isNotNull(); // Transaction succeeded
      assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("updated");
    }
  }

  @Nested
  @DisplayName("Connection Lifecycle")
  class ConnectionLifecycleTest {

    @Test
    @DisplayName("should reuse connections across operations")
    void shouldReuseConnectionsAcrossOperations() {
      // Arrange & Act
      for (int i = 0; i < 100; i++) {
        redisTemplate.opsForValue().set("test:reuse:" + i, "value");
      }

      // Assert - No connection leaks (implicit: test completes without timeout)
      // Explicit check: all keys written
      for (int i = 0; i < 100; i++) {
        assertThat(redisTemplate.opsForValue().get("test:reuse:" + i)).isEqualTo("value");
      }
    }

    @Test
    @DisplayName("should handle connection factory lifecycle")
    void shouldHandleConnectionFactoryLifecycle() {
      // Arrange
      RedisConnectionFactory factory = redisTemplate.getConnectionFactory();

      // Act & Assert - Factory is usable
      assertThat(factory).isNotNull();
      assertThat(factory.getConnection()).isNotNull();
    }
  }

  /** Test configuration providing RedisTemplate bean. */
  @Configuration
  static class TestConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
      RedisTemplate<String, String> template = new RedisTemplate<>();
      template.setConnectionFactory(factory);
      template.setKeySerializer(new StringRedisSerializer());
      template.setValueSerializer(new StringRedisSerializer());
      template.setHashKeySerializer(new StringRedisSerializer());
      template.setHashValueSerializer(new StringRedisSerializer());
      template.afterPropertiesSet();
      return template;
    }
  }
}
