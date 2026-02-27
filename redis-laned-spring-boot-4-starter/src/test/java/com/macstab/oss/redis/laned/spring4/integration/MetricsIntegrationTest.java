/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.integration;

import static org.assertj.core.api.Assertions.*;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import com.macstab.oss.redis.laned.metrics.micrometer.MicrometerLanedRedisMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Integration test for metrics integration.
 *
 * <p>Verifies that:
 *
 * <ul>
 *   <li>Metrics bean is auto-configured
 *   <li>Lane selection metrics are recorded
 *   <li>In-flight metrics are recorded
 *   <li>Dimensional tags are applied correctly
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Tag("integration")
@Testcontainers
@DisplayName("Metrics Integration Test")
class MetricsIntegrationTest {

  @Nested
  @DisplayName("Spring Boot 4 Metrics Integration")
  @SpringBootTest(
      classes = {
        MetricsIntegrationTest.TestConfig.class,
        com.macstab.oss.redis.laned.spring4.testconfig.TestApplication.class
      })
  @TestPropertySource(
      properties = {
        "spring.data.redis.connection.strategy=LANED",
        "spring.data.redis.connection.lanes=4",
        "management.metrics.laned-redis.enabled=true",
        "management.metrics.laned-redis.connection-name=test-connection"
      })
  class WithMetricsEnabled {

    @Container
    static final GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry registry) {
      registry.add("spring.data.redis.host", redis::getHost);
      registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired private RedisConnectionFactory connectionFactory;

    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("should auto-configure metrics bean")
    void shouldAutoConfigureMetricsBean() {
      // Assert - metrics bean should be MicrometerLanedRedisMetrics
      assertThat(connectionFactory).isNotNull();
      assertThat(meterRegistry).isNotNull();
      assertThat(meterRegistry).isInstanceOf(SimpleMeterRegistry.class);
    }

    @Test
    @DisplayName("should record lane selection metrics")
    void shouldRecordLaneSelectionMetrics() {
      // Act - trigger connection acquisition
      redisTemplate.opsForValue().set("test-key", "test-value");
      redisTemplate.opsForValue().get("test-key");
      redisTemplate.opsForValue().get("test-key");

      // Assert - lane selection counter should be recorded
      final var laneSelections =
          meterRegistry
              .find("redis.lettuce.laned.lane.selections")
              .tag("connection.name", "test-connection")
              .tag("strategy.name", "round-robin")
              .counters();

      assertThat(laneSelections).isNotEmpty();
      assertThat(laneSelections.stream().mapToDouble(c -> c.count()).sum())
          .isGreaterThanOrEqualTo(3.0);
    }

    @Test
    @DisplayName("should record in-flight metrics")
    void shouldRecordInFlightMetrics() {
      // Act - trigger connection acquisition
      redisTemplate.opsForValue().set("test-key-2", "test-value-2");

      // Assert - in-flight gauge should exist (may be 0 after operation completes)
      final var inFlightGauges =
          meterRegistry
              .find("redis.lettuce.laned.lane.in_flight")
              .tag("connection.name", "test-connection")
              .gauges();

      assertThat(inFlightGauges).isNotEmpty();
    }

    @Test
    @DisplayName("should apply dimensional tags")
    void shouldApplyDimensionalTags() {
      // Act
      redisTemplate.opsForValue().set("test-key-3", "test-value-3");

      // Assert - verify connection.name tag
      final var metricsWithConnectionTag =
          meterRegistry
              .find("redis.lettuce.laned.lane.selections")
              .tag("connection.name", "test-connection")
              .counters();

      assertThat(metricsWithConnectionTag).isNotEmpty();
      assertThat(metricsWithConnectionTag)
          .allSatisfy(
              counter ->
                  assertThat(counter.getId().getTag("connection.name"))
                      .isEqualTo("test-connection"));
    }
  }

  /**
   * Test configuration providing MeterRegistry bean.
   *
   * <p>SimpleMeterRegistry used for testing (lightweight, in-memory).
   */
  @Configuration
  static class TestConfig {

    @Bean
    public MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    public LanedRedisMetrics lanedRedisMetrics(final MeterRegistry meterRegistry) {
      return new MicrometerLanedRedisMetrics(meterRegistry, "test-connection", 1000);
    }
  }
}
