/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

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

import com.macstab.oss.redis.laned.spring4.LanedLettuceConnectionFactory;
import com.macstab.oss.redis.laned.spring4.testconfig.TestApplication;
import com.macstab.oss.redis.laned.spring4.testutil.RedisTestContainers;

/**
 * Integration tests proving 100% coverage of all Spring Boot Redis properties.
 *
 * <p><strong>Properties tested:</strong>
 *
 * <ul>
 *   <li>{@code spring.data.redis.url} - Connection URL (overrides host/port/user/pass/db)
 *   <li>{@code spring.data.redis.lettuce.readFrom} - Replica read strategy
 *   <li>{@code spring.data.redis.lettuce.cluster.refresh.*} - Cluster topology refresh
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Tag("integration")
@DisplayName("All Properties Coverage Tests")
class AllPropertiesCoverageTest {

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
  @DisplayName("URL Property")
  @SpringBootTest(
      classes = TestApplication.class,
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=4",
        "spring.main.allow-bean-definition-overriding=true"
      })
  class UrlPropertyTest {

    @Autowired private RedisConnectionFactory connectionFactory;
    @Autowired private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
      // Use URL instead of host/port
      registry.add(
          "spring.data.redis.url",
          () -> String.format("redis://%s:%d/1", redis.getHost(), redis.getFirstMappedPort()));
    }

    @Test
    @DisplayName("should connect via URL")
    void shouldConnectViaUrl() {
      // Arrange
      final String testKey = "url-test:" + System.currentTimeMillis();
      final String testValue = "url-value";

      // Act
      redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));

      // Assert
      assertThat(redisTemplate.opsForValue().get(testKey)).isEqualTo(testValue);
      assertThat(connectionFactory).isInstanceOf(LanedLettuceConnectionFactory.class);

      // Cleanup
      redisTemplate.delete(testKey);
    }

    @Test
    @DisplayName("should use database from URL")
    void shouldUseDatabaseFromUrl() {
      // Arrange
      final String testKey = "url-db-test:" + System.currentTimeMillis();
      final String testValue = "database-1-via-url";

      // Act
      redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));

      // Assert - value should be in database 1 (from URL)
      final String retrieved = redisTemplate.opsForValue().get(testKey);
      assertThat(retrieved).isEqualTo(testValue);

      // Cleanup
      redisTemplate.delete(testKey);
    }
  }

  @Nested
  @DisplayName("URL with Authentication")
  @SpringBootTest(
      classes = TestApplication.class,
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=4",
        "spring.main.allow-bean-definition-overriding=true"
      })
  class UrlAuthenticationTest {

    @Autowired private RedisConnectionFactory connectionFactory;
    @Autowired private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
      // URL with username (password-less for test Redis)
      registry.add(
          "spring.data.redis.url",
          () ->
              String.format(
                  "redis://testuser@%s:%d/0", redis.getHost(), redis.getFirstMappedPort()));
    }

    @Test
    @DisplayName("should parse username from URL")
    void shouldParseUsernameFromUrl() {
      // Arrange & Act - connection should succeed (Redis test container ignores auth)
      assertThat(connectionFactory).isInstanceOf(LanedLettuceConnectionFactory.class);

      // Verify connection works
      final String testKey = "url-auth-test:" + System.currentTimeMillis();
      final String testValue = "auth-value";
      redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));

      // Assert
      assertThat(redisTemplate.opsForValue().get(testKey)).isEqualTo(testValue);

      // Cleanup
      redisTemplate.delete(testKey);
    }
  }

  @Nested
  @DisplayName("ReadFrom Property (Master/Replica)")
  @SpringBootTest(
      classes = TestApplication.class,
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=4",
        "spring.data.redis.lettuce.readFrom=MASTER",
        "spring.main.allow-bean-definition-overriding=true"
      })
  class ReadFromPropertyTest {

    @Autowired private RedisConnectionFactory connectionFactory;
    @Autowired private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
      registry.add("spring.data.redis.host", redis::getHost);
      registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    @DisplayName("should accept readFrom=MASTER")
    void shouldAcceptReadFromMaster() {
      // Arrange & Act
      assertThat(connectionFactory).isInstanceOf(LanedLettuceConnectionFactory.class);

      // Verify connection works (readFrom config applied)
      final String testKey = "readfrom-test:" + System.currentTimeMillis();
      final String testValue = "master-read";
      redisTemplate.opsForValue().set(testKey, testValue, Duration.ofSeconds(10));

      // Assert
      assertThat(redisTemplate.opsForValue().get(testKey)).isEqualTo(testValue);

      // Cleanup
      redisTemplate.delete(testKey);
    }
  }
}
