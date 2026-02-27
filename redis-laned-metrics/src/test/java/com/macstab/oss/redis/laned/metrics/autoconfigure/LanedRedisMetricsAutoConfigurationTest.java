/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import com.macstab.oss.redis.laned.metrics.micrometer.MicrometerLanedRedisMetrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests for {@link LanedRedisMetricsAutoConfiguration}.
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>Use {@link ApplicationContextRunner} for Spring Boot auto-configuration testing
 *   <li>Test conditional bean creation (enabled/disabled/missing MeterRegistry)
 *   <li>Test user-defined bean takes precedence
 *   <li>AAA pattern strictly enforced
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LanedRedisMetricsAutoConfiguration")
class LanedRedisMetricsAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(LanedRedisMetricsAutoConfiguration.class));

  @Test
  @DisplayName("Should create MicrometerLanedRedisMetrics when Micrometer present and enabled")
  void shouldCreateMicrometerMetricsWhenEnabled() {
    // Arrange & Act
    contextRunner
        .withUserConfiguration(MeterRegistryConfiguration.class)
        .withPropertyValues("management.metrics.laned-redis.enabled=true")
        .run(
            context -> {
              // Assert
              assertThat(context).hasSingleBean(LanedRedisMetrics.class);
              assertThat(context.getBean(LanedRedisMetrics.class))
                  .isInstanceOf(MicrometerLanedRedisMetrics.class);
            });
  }

  @Test
  @DisplayName("Should create MicrometerLanedRedisMetrics by default (enabled=true)")
  void shouldCreateMicrometerMetricsByDefault() {
    // Arrange & Act
    contextRunner
        .withUserConfiguration(MeterRegistryConfiguration.class)
        .run(
            context -> {
              // Assert - no explicit enabled property (should default to true)
              assertThat(context).hasSingleBean(LanedRedisMetrics.class);
              assertThat(context.getBean(LanedRedisMetrics.class))
                  .isInstanceOf(MicrometerLanedRedisMetrics.class);
            });
  }

  @Test
  @DisplayName("Should create NOOP metrics when explicitly disabled")
  void shouldCreateNoOpMetricsWhenDisabled() {
    // Arrange & Act
    contextRunner
        .withUserConfiguration(MeterRegistryConfiguration.class)
        .withPropertyValues("management.metrics.laned-redis.enabled=false")
        .run(
            context -> {
              // Assert
              assertThat(context).hasSingleBean(LanedRedisMetrics.class);
              assertThat(context.getBean(LanedRedisMetrics.class)).isSameAs(LanedRedisMetrics.NOOP);
            });
  }

  @Test
  @DisplayName("Should create NOOP metrics when MeterRegistry missing")
  void shouldCreateNoOpMetricsWhenMeterRegistryMissing() {
    // Arrange & Act - no MeterRegistry bean
    contextRunner
        .withPropertyValues("management.metrics.laned-redis.enabled=true")
        .run(
            context -> {
              // Assert - NOOP fallback
              assertThat(context).hasSingleBean(LanedRedisMetrics.class);
              assertThat(context.getBean(LanedRedisMetrics.class)).isSameAs(LanedRedisMetrics.NOOP);
            });
  }

  @Test
  @DisplayName("Should not create bean when user defines custom LanedRedisMetrics")
  void shouldNotCreateBeanWhenUserDefinesCustom() {
    // Arrange & Act
    contextRunner
        .withUserConfiguration(MeterRegistryConfiguration.class, CustomMetricsConfiguration.class)
        .withPropertyValues("management.metrics.laned-redis.enabled=true")
        .run(
            context -> {
              // Assert - user bean takes precedence
              assertThat(context).hasSingleBean(LanedRedisMetrics.class);
              assertThat(context.getBean(LanedRedisMetrics.class))
                  .isSameAs(LanedRedisMetrics.NOOP); // Custom bean (NOOP in this test)
            });
  }

  @Test
  @DisplayName("Should bind properties correctly")
  void shouldBindPropertiesCorrectly() {
    // Arrange & Act
    contextRunner
        .withUserConfiguration(MeterRegistryConfiguration.class)
        .withPropertyValues(
            "management.metrics.laned-redis.enabled=true",
            "management.metrics.laned-redis.slow-command-threshold=50ms")
        .run(
            context -> {
              // Assert
              assertThat(context).hasSingleBean(LanedRedisMetricsProperties.class);

              final var props = context.getBean(LanedRedisMetricsProperties.class);
              assertThat(props.isEnabled()).isTrue();
              assertThat(props.getSlowCommandThreshold()).hasMillis(50);
            });
  }

  /** Provides {@link SimpleMeterRegistry} bean for testing. */
  @Configuration
  static class MeterRegistryConfiguration {
    @Bean
    SimpleMeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  /** Provides custom {@link LanedRedisMetrics} bean for testing precedence. */
  @Configuration
  static class CustomMetricsConfiguration {
    @Bean
    LanedRedisMetrics customLanedRedisMetrics() {
      return LanedRedisMetrics.NOOP; // Custom bean (NOOP for simplicity)
    }
  }
}
