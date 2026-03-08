/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

/**
 * Tests for {@link LanedRedisConfigurationSource}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LanedRedisConfigurationSource")
class LanedRedisConfigurationSourceTest {

  @Test
  @DisplayName("defaultConfig() returns correct default values")
  void defaultConfigHasCorrectValues() {
    final var config = LanedRedisConfigurationSource.defaultConfig();

    assertThat(config.getLanes()).isEqualTo(4);
    assertThat(config.getStrategyType()).isEqualTo(LaneSelectionStrategyType.ROUND_ROBIN);
    assertThat(config.isMetricsEnabled()).isTrue();
    assertThat(config.getSourceType())
        .isEqualTo(LanedRedisConfigurationSource.ConfigurationSourceType.DEFAULT);
  }

  @Test
  @DisplayName("Builder creates config with custom values")
  void builderCreatesCustomConfig() {
    final var config =
        LanedRedisConfigurationSource.builder()
            .lanes(16)
            .strategyType(LaneSelectionStrategyType.THREAD_AFFINITY)
            .metricsEnabled(false)
            .sourceType(LanedRedisConfigurationSource.ConfigurationSourceType.ANNOTATION)
            .build();

    assertThat(config.getLanes()).isEqualTo(16);
    assertThat(config.getStrategyType()).isEqualTo(LaneSelectionStrategyType.THREAD_AFFINITY);
    assertThat(config.isMetricsEnabled()).isFalse();
    assertThat(config.getSourceType())
        .isEqualTo(LanedRedisConfigurationSource.ConfigurationSourceType.ANNOTATION);
  }

  @Test
  @DisplayName("Config is immutable (Value annotation)")
  void configIsImmutable() {
    final var config1 =
        LanedRedisConfigurationSource.builder()
            .lanes(8)
            .strategyType(LaneSelectionStrategyType.LEAST_USED)
            .metricsEnabled(true)
            .sourceType(LanedRedisConfigurationSource.ConfigurationSourceType.PROPERTIES)
            .build();

    final var config2 =
        LanedRedisConfigurationSource.builder()
            .lanes(8)
            .strategyType(LaneSelectionStrategyType.LEAST_USED)
            .metricsEnabled(true)
            .sourceType(LanedRedisConfigurationSource.ConfigurationSourceType.PROPERTIES)
            .build();

    // Equals/hashCode work correctly
    assertThat(config1).isEqualTo(config2);
    assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
  }

  @Test
  @DisplayName("All source types are distinct")
  void allSourceTypesAreDistinct() {
    final var types = LanedRedisConfigurationSource.ConfigurationSourceType.values();

    assertThat(types).hasSize(3);
    assertThat(types)
        .containsExactlyInAnyOrder(
            LanedRedisConfigurationSource.ConfigurationSourceType.ANNOTATION,
            LanedRedisConfigurationSource.ConfigurationSourceType.PROPERTIES,
            LanedRedisConfigurationSource.ConfigurationSourceType.DEFAULT);
  }
}
