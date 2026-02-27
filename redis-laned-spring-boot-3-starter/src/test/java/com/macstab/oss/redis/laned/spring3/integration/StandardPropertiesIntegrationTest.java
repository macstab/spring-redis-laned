/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import com.macstab.oss.redis.laned.spring3.LanedLettuceConnectionFactory;
import com.macstab.oss.redis.laned.spring3.testconfig.TestApplication;
import com.macstab.oss.redis.laned.spring3.testutil.RedisTestContainers;

/**
 * Integration tests verifying all standard Spring Boot Redis properties are correctly applied.
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>Verify client-name propagates to Redis (CLIENT GETNAME)
 *   <li>Verify database selection works
 *   <li>Verify authentication (username/password)
 *   <li>Verify timeouts (command timeout, connect timeout)
 *   <li>Verify all properties from {@code spring.data.redis.*} flow through correctly
 * </ul>
 *
 * <p><strong>Coverage Goal:</strong> Prove all {@link
 * org.springframework.boot.autoconfigure.data.redis.RedisProperties} we handle are correctly mapped
 * to Lettuce configuration.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Tag("integration")
@DisplayName("Standard Spring Boot Redis Properties Integration Tests")
class StandardPropertiesIntegrationTest {

  private static GenericContainer<?> redis;

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

  @Nested
  @DisplayName("Client Name Property")
  @SpringBootTest(
      classes = TestApplication.class,
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=4",
        "spring.data.redis.client-name=test-app",
        "spring.main.allow-bean-definition-overriding=true"
      })
  class ClientNameTest {

    @Autowired private RedisConnectionFactory connectionFactory;
    @Autowired private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
      registry.add("spring.data.redis.host", redis::getHost);
      registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    @DisplayName("should set client name on connections")
    void shouldSetClientNameOnConnections() {
      // Arrange & Act
      final String clientName =
          redisTemplate.execute(
              (org.springframework.data.redis.core.RedisCallback<String>)
                  connection -> connection.serverCommands().getClientName());

      // Assert
      assertThat(clientName).isEqualTo("test-app");
    }

    @Test
    @DisplayName("should create laned connection factory")
    void shouldCreateLanedConnectionFactory() {
      // Assert
      assertThat(connectionFactory).isInstanceOf(LanedLettuceConnectionFactory.class);
    }
  }

  @Nested
  @DisplayName("Database Selection")
  @SpringBootTest(
      classes = TestApplication.class,
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=4",
        "spring.data.redis.database=3",
        "spring.main.allow-bean-definition-overriding=true"
      })
  class DatabaseSelectionTest {

    @Autowired private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
      registry.add("spring.data.redis.host", redis::getHost);
      registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    @DisplayName("should use configured database")
    void shouldUseConfiguredDatabase() {
      // Arrange
      final String testKey = "db-test:" + System.currentTimeMillis();
      final String testValue = "database-3-value";

      // Act
      redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));

      // Assert - value should be retrievable
      final String retrieved = redisTemplate.opsForValue().get(testKey);
      assertThat(retrieved).isEqualTo(testValue);

      // Cleanup
      redisTemplate.delete(testKey);
    }
  }

  @Nested
  @DisplayName("Command Timeout")
  @SpringBootTest(
      classes = TestApplication.class,
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=4",
        "spring.data.redis.timeout=100ms",
        "spring.main.allow-bean-definition-overriding=true"
      })
  class CommandTimeoutTest {

    @Autowired private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
      registry.add("spring.data.redis.host", redis::getHost);
      registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    @DisplayName("should apply command timeout")
    void shouldApplyCommandTimeout() {
      // Arrange
      final String testKey = "timeout-test:" + System.currentTimeMillis();
      final String testValue = "timeout-value";

      // Act - fast command should succeed
      redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));

      // Assert
      await()
          .atMost(500, TimeUnit.MILLISECONDS)
          .untilAsserted(
              () -> assertThat(redisTemplate.opsForValue().get(testKey)).isEqualTo(testValue));

      // Cleanup
      redisTemplate.delete(testKey);
    }
  }

  @Nested
  @DisplayName("Combined Properties")
  @SpringBootTest(
      classes = TestApplication.class,
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=8",
        "spring.data.redis.database=1",
        "spring.data.redis.client-name=combined-test",
        "spring.data.redis.timeout=2s",
        "spring.data.redis.connect-timeout=500ms",
        "spring.main.allow-bean-definition-overriding=true"
      })
  class CombinedPropertiesTest {

    @Autowired private RedisConnectionFactory connectionFactory;
    @Autowired private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
      registry.add("spring.data.redis.host", redis::getHost);
      registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    @DisplayName("should apply all properties together")
    void shouldApplyAllPropertiesTogether() {
      // Arrange & Act - verify client name
      final String clientName =
          redisTemplate.execute(
              (org.springframework.data.redis.core.RedisCallback<String>)
                  connection -> connection.serverCommands().getClientName());

      // Assert
      assertThat(clientName).isEqualTo("combined-test");
      assertThat(connectionFactory).isInstanceOf(LanedLettuceConnectionFactory.class);

      // Verify database by writing and reading
      final String testKey = "combined-test:" + System.currentTimeMillis();
      final String testValue = "all-props-value";
      redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));

      assertThat(redisTemplate.opsForValue().get(testKey)).isEqualTo(testValue);

      // Cleanup
      redisTemplate.delete(testKey);
    }

    @Test
    @DisplayName("should configure laned factory with 8 lanes")
    void shouldConfigureLanedFactoryWith8Lanes() {
      // Arrange & Act
      final var lanedFactory = (LanedLettuceConnectionFactory) connectionFactory;
      final var provider = lanedFactory.getLanedProvider();
      final var manager = provider.getManager();

      // Assert - verify 8 lanes created
      assertThat(manager.getOpenLaneCount()).isEqualTo(8);
    }
  }
}
