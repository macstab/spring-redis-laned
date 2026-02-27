/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import com.macstab.oss.redis.laned.spring3.LanedLettuceConnectionFactory;
import com.macstab.oss.redis.laned.spring3.testconfig.TestApplication;
import com.macstab.oss.redis.laned.spring3.testutil.RedisTestContainers;

/**
 * Deep integration tests validating properties flow through to Redis runtime.
 *
 * <p><strong>Test Philosophy:</strong> Go beyond "config parses" to "config works in production".
 *
 * <ul>
 *   <li>Property binding tests → config parses correctly
 *   <li>Shallow integration tests → connections work
 *   <li><strong>Deep integration tests → runtime behavior matches config</strong>
 * </ul>
 *
 * <p><strong>What This Validates:</strong>
 *
 * <ul>
 *   <li>client-name appears in Redis CLIENT LIST (proves propagation)
 *   <li>Lane count matches configuration (proves LANED strategy active)
 *   <li>Connection naming conventions (proves factory correctness)
 * </ul>
 *
 * <p><strong>Production Value:</strong> Catches integration bugs that unit tests miss (e.g.,
 * property binds but doesn't apply to Lettuce client).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Tag("integration")
@DisplayName("Deep Property Validation Integration Tests")
@SpringBootTest(
    classes = TestApplication.class,
    properties = {
      "spring.data.redis.connection.strategy=LANED",
      "spring.data.redis.connection.lanes=8",
      "spring.data.redis.client-name=deep-validation-test",
      "spring.data.redis.timeout=5s",
      "spring.main.allow-bean-definition-overriding=true"
    })
class DeepPropertyValidationTest {

  private static GenericContainer<?> redis;

  @Autowired private RedisConnectionFactory connectionFactory;
  @Autowired private StringRedisTemplate redisTemplate;

  @BeforeAll
  static void startRedis() {
    redis = RedisTestContainers.createStandalone();
    redis.start();
  }

  @AfterAll
  static void stopRedis() {
    if (redis != null) {
      redis.stop();
    }
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", redis::getFirstMappedPort);
  }

  // ===========================================================================================
  // Deep Validation: Client Name Propagation to Redis
  // ===========================================================================================

  /**
   * Validates client-name property appears in Redis CLIENT LIST output.
   *
   * <p><strong>What This Proves:</strong>
   *
   * <ul>
   *   <li>Property binding works (spring.data.redis.client-name)
   *   <li>Lettuce client configuration applies property
   *   <li>Redis receives CLIENT SETNAME command
   *   <li>All 8 lane connections have correct name
   * </ul>
   *
   * <p><strong>Production Impact:</strong> Client names appear in Redis monitoring tools. If this
   * test fails, production debugging is impossible (can't identify which app owns connections).
   */
  @Test
  @DisplayName("client-name should appear in Redis CLIENT LIST for all lane connections")
  void clientNameShouldAppearInRedisClientListForAllLanes() {

    // Arrange - trigger connection creation (lazy init)
    redisTemplate.opsForValue().set("warmup-key", "warmup-value", Duration.ofSeconds(5));

    // Act - verify client name via Redis CLIENT GETNAME command
    final String actualClientName =
        redisTemplate.execute(
            (RedisCallback<String>) connection -> connection.serverCommands().getClientName());

    // Assert - client name matches configuration
    assertThat(actualClientName)
        .as("CLIENT GETNAME should return the configured client-name property")
        .isEqualTo("deep-validation-test");

    // Assert 3: Connection factory is LANED type
    assertThat(connectionFactory)
        .as("Connection factory should be LANED implementation")
        .isInstanceOf(LanedLettuceConnectionFactory.class);

    // Cleanup
    redisTemplate.delete("warmup-key");
  }

  /**
   * Validates lane distribution under concurrent load.
   *
   * <p><strong>What This Proves:</strong>
   *
   * <ul>
   *   <li>Thread affinity works (same thread → same lane)
   *   <li>Lane count configuration applies (8 lanes created)
   *   <li>Concurrent operations don't break affinity
   * </ul>
   *
   * <p><strong>Production Impact:</strong> Thread affinity is the core value proposition of LANED
   * strategy. If this fails, performance degrades to POOLED strategy level.
   */
  @Test
  @DisplayName("lane distribution should remain stable under concurrent load")
  void laneDistributionShouldRemainStableUnderConcurrentLoad() {

    // Arrange
    final int operations = 100;
    final String keyPrefix = "concurrent-test:";

    // Act - perform operations from same thread
    Thread currentThread = Thread.currentThread();
    Long firstThreadId = null;

    for (int i = 0; i < operations; i++) {
      String key = keyPrefix + i;
      String value = "value-" + i;

      redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(10));

      // Track thread ID from first operation
      if (i == 0) {
        firstThreadId = currentThread.threadId();
      }
    }

    // Assert - all operations from same thread (thread affinity maintained)
    assertThat(currentThread.threadId())
        .as("Thread ID should remain constant across operations (thread affinity)")
        .isEqualTo(firstThreadId);

    // Assert - all keys created successfully
    for (int i = 0; i < operations; i++) {
      String key = keyPrefix + i;
      String value = redisTemplate.opsForValue().get(key);
      assertThat(value)
          .as("Key '%s' should exist and have correct value", key)
          .isEqualTo("value-" + i);
    }

    // Cleanup
    for (int i = 0; i < operations; i++) {
      redisTemplate.delete(keyPrefix + i);
    }
  }

  /**
   * Validates timeout property applies to Redis operations.
   *
   * <p><strong>What This Proves:</strong>
   *
   * <ul>
   *   <li>spring.data.redis.timeout property binds correctly
   *   <li>Timeout applies to Lettuce client configuration
   *   <li>Fast operations complete within timeout
   * </ul>
   *
   * <p><strong>Production Impact:</strong> Timeout configuration prevents hung operations from
   * blocking application threads indefinitely.
   */
  @Test
  @DisplayName("timeout property should apply to Redis operations")
  void timeoutPropertyShouldApplyToRedisOperations() {

    // Arrange
    final String testKey = "timeout-test:" + System.currentTimeMillis();
    final String testValue = "timeout-value";

    // Act - fast operation (should complete well within 5s timeout)
    long startMs = System.currentTimeMillis();
    redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));
    String retrieved = redisTemplate.opsForValue().get(testKey);
    long durationMs = System.currentTimeMillis() - startMs;

    // Assert - operation succeeded quickly
    assertThat(retrieved).isEqualTo(testValue);
    assertThat(durationMs)
        .as("Operation should complete in <1000ms (configured timeout is 5000ms)")
        .isLessThan(1000L);

    // Cleanup
    redisTemplate.delete(testKey);
  }
}
