/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3.latency;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.lettuce.core.metrics.CommandLatencyCollector;
import io.lettuce.core.metrics.DefaultCommandLatencyCollector;
import io.lettuce.core.metrics.DefaultCommandLatencyCollectorOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration for Lettuce command latency tracking (Spring Boot 3.x).
 *
 * <p><strong>Activation Conditions:</strong>
 *
 * <ul>
 *   <li>Lettuce {@link CommandLatencyCollector} on classpath
 *   <li>NO {@link ClientResources} bean exists (respects user's bean if provided)
 *   <li>Property {@code management.metrics.laned-redis.command-latency.enabled=true}
 * </ul>
 *
 * <p><strong>Beans Created:</strong>
 *
 * <ul>
 *   <li>{@link ClientResources} - Lettuce client resources with {@link CommandLatencyCollector}
 *       enabled
 * </ul>
 *
 * <p><strong>Configuration Example:</strong>
 *
 * <pre>{@code
 * management:
 *   metrics:
 *     laned-redis:
 *       command-latency:
 *         enabled: true
 *         reset-after-export: true
 * }</pre>
 *
 * <p><strong>User Override:</strong> If user provides their own {@link ClientResources} bean, this
 * auto-configuration is skipped. User has full control over Lettuce configuration.
 *
 * <p><strong>Example User Override:</strong>
 *
 * <pre>{@code
 * @Configuration
 * public class MyRedisConfig {
 *
 *     @Bean
 *     public ClientResources customClientResources() {
 *         return ClientResources.builder()
 *             .commandLatencyCollector(myCustomCollector())
 *             .build();
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Lifecycle:</strong> ClientResources is {@link AutoCloseable}, Spring Boot calls {@code
 * close()} on shutdown to release thread pools and DNS resolver.
 *
 * <p><strong>Order:</strong> Runs BEFORE LettuceConnectionConfiguration (ClientResources must exist
 * before RedisClient is created).
 *
 * @since 1.2.0
 * @author Christian Schnapka - Macstab GmbH
 * @see CommandLatencyCollector
 * @see DefaultCommandLatencyCollector
 */
@Slf4j
@AutoConfiguration(
    beforeName = "org.springframework.boot.autoconfigure.data.redis.LettuceConnectionConfiguration")
@ConditionalOnClass({CommandLatencyCollector.class, ClientResources.class})
@ConditionalOnProperty(
    prefix = "management.metrics.laned-redis.command-latency",
    name = "enabled",
    havingValue = "true")
@EnableConfigurationProperties(CommandLatencyProperties.class)
public class CommandLatencyAutoConfiguration {

  /**
   * Creates {@link ClientResources} bean with {@link CommandLatencyCollector} enabled.
   *
   * <p><strong>Conditional:</strong> Only created if NO ClientResources bean exists (respects
   * user's bean via {@link ConditionalOnMissingBean}).
   *
   * <p><strong>Configuration:</strong>
   *
   * <ul>
   *   <li>Command latency collector: {@link DefaultCommandLatencyCollector} with configured options
   *   <li>Thread pools: Lettuce defaults (computation threads = CPU cores, I/O threads = CPU cores)
   *   <li>DNS resolver: Lettuce defaults (JDK DNS with 60s TTL)
   * </ul>
   *
   * <p><strong>Lifecycle:</strong> Spring Boot calls {@code close()} on shutdown (releases
   * resources).
   *
   * @param properties command latency properties (auto-wired from configuration)
   * @return configured client resources
   */
  @Bean(destroyMethod = "shutdown")
  @ConditionalOnMissingBean(ClientResources.class)
  @SuppressWarnings("deprecation") // Lettuce commandLatencyCollector API - still functional
  public ClientResources clientResourcesWithLatencyCollector(
      final CommandLatencyProperties properties) {

    final DefaultCommandLatencyCollectorOptions options =
        DefaultCommandLatencyCollectorOptions.builder()
            .enable()
            .resetLatenciesAfterEvent(properties.isResetAfterExport())
            .build();

    final ClientResources resources =
        DefaultClientResources.builder()
            .commandLatencyCollector(new DefaultCommandLatencyCollector(options))
            .build();

    if (log.isInfoEnabled()) {
      log.info(
          "Created ClientResources with CommandLatencyCollector (resetAfterExport: {})",
          properties.isResetAfterExport());
    }

    return resources;
  }
}
