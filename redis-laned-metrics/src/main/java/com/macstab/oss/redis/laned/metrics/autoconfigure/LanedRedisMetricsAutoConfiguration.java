/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import com.macstab.oss.redis.laned.metrics.micrometer.MicrometerLanedRedisMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring Boot auto-configuration for laned Redis metrics with dimensional tags.
 *
 * <p><strong>Activation Conditions:</strong>
 *
 * <ol>
 *   <li>{@code MeterRegistry.class} on classpath (Micrometer present)
 *   <li>{@code MeterRegistry} bean exists (Spring Boot Actuator configured)
 *   <li>{@code management.metrics.laned-redis.enabled=true} (default: true)
 * </ol>
 *
 * <p><strong>Bean Created:</strong>
 *
 * <ul>
 *   <li>If conditions met: {@code MicrometerLanedRedisMetrics} (dimensional metrics)
 *   <li>If disabled: {@code NoOpLanedRedisMetrics} (zero overhead)
 * </ul>
 *
 * <p><strong>Dimensional Metrics:</strong> All metrics include {@code connection.name} tag to
 * distinguish multiple Redis connections (primary, cache, session).
 *
 * <p><strong>Connection Name Priority:</strong>
 *
 * <ol>
 *   <li>Property: {@code management.metrics.laned-redis.connection-name}
 *   <li>Fallback: {@code "default"}
 * </ol>
 *
 * <p><strong>Usage in Spring Boot 3/4 Starters:</strong>
 *
 * <pre>{@code
 * @Configuration
 * public class LanedRedisAutoConfiguration {
 *
 *     @Bean
 *     public LanedLettuceConnectionFactory connectionFactory(
 *         RedisClient client,
 *         @Autowired(required = false) LanedRedisMetrics metrics  // nullable
 *     ) {
 *         // Core library handles null → LanedRedisMetrics.NOOP
 *         return new LanedLettuceConnectionFactory(..., metrics);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Spring Boot 3+4 Compatible:</strong> Micrometer API stable across versions.
 *
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@EnableConfigurationProperties(LanedRedisMetricsProperties.class)
public class LanedRedisMetricsAutoConfiguration {

  /**
   * Creates Micrometer-based metrics collector when enabled (dimensional version).
   *
   * <p><strong>Activation:</strong>
   *
   * <ul>
   *   <li>{@code MeterRegistry} bean exists
   *   <li>{@code management.metrics.laned-redis.enabled=true} (default)
   *   <li>No user-defined {@code LanedRedisMetrics} bean exists
   * </ul>
   *
   * <p><strong>Dimensional Tags:</strong> All metrics include {@code connection.name} tag from
   * properties (default: "default").
   *
   * @param registry Micrometer meter registry (injected by Spring Boot Actuator)
   * @param properties metrics configuration properties
   * @return Micrometer metrics collector with dimensional tags
   */
  @Bean
  @ConditionalOnBean(MeterRegistry.class)
  @ConditionalOnProperty(
      prefix = "management.metrics.laned-redis",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(LanedRedisMetrics.class)
  public LanedRedisMetrics micrometerLanedRedisMetrics(
      final MeterRegistry registry, final LanedRedisMetricsProperties properties) {

    final var connectionName = properties.getConnectionName();
    final var maxCacheSize = properties.getMaxCacheSize();

    log.info(
        "Activating laned Redis metrics (Micrometer) - connection: '{}', maxCacheSize: {}, slowCommandThreshold: {}",
        connectionName,
        maxCacheSize,
        properties.getSlowCommandThreshold());

    return new MicrometerLanedRedisMetrics(registry, connectionName, maxCacheSize);
  }

  /**
   * Creates no-op metrics collector when disabled.
   *
   * <p><strong>Activation:</strong>
   *
   * <ul>
   *   <li>{@code management.metrics.laned-redis.enabled=false}
   *   <li>No {@code MeterRegistry} bean exists
   * </ul>
   *
   * <p><strong>Note:</strong> This bean is technically optional (core library defaults to {@link
   * LanedRedisMetrics#NOOP} when null). Provided for explicit Spring context clarity.
   *
   * @return No-op metrics collector (zero overhead)
   */
  @Bean
  @ConditionalOnMissingBean(LanedRedisMetrics.class)
  public LanedRedisMetrics noOpLanedRedisMetrics() {
    log.debug("Laned Redis metrics disabled - using NOOP singleton");
    return LanedRedisMetrics.NOOP;
  }
}
