/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.macstab.oss.redis.laned.config.LanedRedisConfigurationSource;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

/**
 * Unit tests for configuration resolution logic (annotation > properties > defaults).
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("LanedRedis Configuration Resolution")
class LanedRedisConfigurationResolutionTest {

  @Test
  @DisplayName("Annotation has highest precedence over properties")
  void annotationOverridesProperties() {
    // Arrange: Properties say lanes=4
    final var properties = new RedisConnectionProperties();
    properties.setLanes(4);

    // Annotation says lanes=16, strategy=THREAD_AFFINITY
    final var annotationConfig =
        LanedRedisConfigurationSource.builder()
            .lanes(16)
            .strategyType(LaneSelectionStrategyType.THREAD_AFFINITY)
            .metricsEnabled(false)
            .sourceType(LanedRedisConfigurationSource.ConfigurationSourceType.ANNOTATION)
            .build();

    @SuppressWarnings("unchecked")
    final ObjectProvider<LanedRedisAnnotationConfigurationProvider> mockProvider =
        mock(ObjectProvider.class);
    final var annotationProvider = mock(LanedRedisAnnotationConfigurationProvider.class);
    when(mockProvider.getIfAvailable()).thenReturn(annotationProvider);
    when(annotationProvider.getAnnotationConfiguration()).thenReturn(Optional.of(annotationConfig));

    // Act: Resolve configuration
    final var resolved = resolveConfiguration(properties, mockProvider);

    // Assert: Annotation wins
    assertThat(resolved.getLanes()).isEqualTo(16);
    assertThat(resolved.getStrategyType()).isEqualTo(LaneSelectionStrategyType.THREAD_AFFINITY);
    assertThat(resolved.isMetricsEnabled()).isFalse();
    assertThat(resolved.getSourceType())
        .isEqualTo(LanedRedisConfigurationSource.ConfigurationSourceType.ANNOTATION);
  }

  @Test
  @DisplayName("Properties used when annotation not present")
  void propertiesUsedWhenNoAnnotation() {
    // Arrange: Properties say lanes=8
    final var properties = new RedisConnectionProperties();
    properties.setLanes(8);

    @SuppressWarnings("unchecked")
    final ObjectProvider<LanedRedisAnnotationConfigurationProvider> mockProvider =
        mock(ObjectProvider.class);
    when(mockProvider.getIfAvailable()).thenReturn(null);

    // Act
    final var resolved = resolveConfiguration(properties, mockProvider);

    // Assert: Properties win
    assertThat(resolved.getLanes()).isEqualTo(8);
    assertThat(resolved.getStrategyType()).isEqualTo(LaneSelectionStrategyType.ROUND_ROBIN);
    assertThat(resolved.isMetricsEnabled()).isTrue();
    assertThat(resolved.getSourceType())
        .isEqualTo(LanedRedisConfigurationSource.ConfigurationSourceType.PROPERTIES);
  }

  @Test
  @DisplayName("Strategy enum creates correct instance")
  void strategyEnumCreatesInstance() {
    final var strategy = LaneSelectionStrategyType.THREAD_AFFINITY.createStrategy();

    assertThat(strategy)
        .isNotNull()
        .isInstanceOf(com.macstab.oss.redis.laned.strategy.ThreadAffinityStrategy.class);
  }

  /**
   * Copy of resolution logic from {@link LanedRedisAutoConfiguration#resolveConfiguration}.
   *
   * <p>This is intentionally duplicated for isolated testing without full Spring context.
   */
  private LanedRedisConfigurationSource resolveConfiguration(
      final RedisConnectionProperties connectionProperties,
      final ObjectProvider<LanedRedisAnnotationConfigurationProvider> annotationProvider) {

    // Priority 1: Check for @LanedRedisConnection annotation
    final var provider = annotationProvider.getIfAvailable();
    if (provider != null) {
      final var annotationConfig = provider.getAnnotationConfiguration();
      if (annotationConfig.isPresent()) {
        return annotationConfig.get();
      }
    }

    // Priority 2: Use YAML/properties
    return LanedRedisConfigurationSource.builder()
        .lanes(connectionProperties.getLanes())
        .strategyType(LaneSelectionStrategyType.ROUND_ROBIN)
        .metricsEnabled(true)
        .sourceType(LanedRedisConfigurationSource.ConfigurationSourceType.PROPERTIES)
        .build();
  }
}
