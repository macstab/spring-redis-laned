/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import com.macstab.oss.redis.laned.metrics.latency.CommandLatencyExporter;

import io.lettuce.core.metrics.CommandLatencyCollector;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration for Lettuce command latency export to Micrometer.
 *
 * <p><strong>Activation Conditions:</strong>
 *
 * <ul>
 *   <li>Lettuce {@link CommandLatencyCollector} on classpath
 *   <li>Micrometer {@link MeterRegistry} on classpath
 *   <li>{@link ClientResources} bean exists (provided by Spring Boot or user)
 *   <li>Property {@code management.metrics.laned-redis.command-latency.enabled=true}
 * </ul>
 *
 * <p><strong>Beans Created:</strong>
 *
 * <ul>
 *   <li>{@link CommandLatencyExporter} - Service to export latencies (user calls {@code
 *       exportCommandLatencies()})
 * </ul>
 *
 * <p><strong>Library Principle:</strong> This auto-configuration creates the exporter bean but does
 * NOT schedule it. Users are responsible for calling {@code exportCommandLatencies()} when they
 * need metrics (e.g., via {@code @Scheduled} task).
 *
 * <p><strong>Configuration Example:</strong>
 *
 * <pre>{@code
 * management:
 *   metrics:
 *     laned-redis:
 *       command-latency:
 *         enabled: true
 *         percentiles: [0.50, 0.95, 0.99]
 *         reset-after-export: true
 * }</pre>
 *
 * <p><strong>Usage Pattern:</strong>
 *
 * <pre>{@code
 * @Autowired
 * private CommandLatencyExporter exporter;
 *
 * @Scheduled(fixedRate = 10000)  // User's responsibility
 * public void exportMetrics() {
 *     exporter.exportCommandLatencies();
 * }
 * }</pre>
 *
 * @since 1.2.0
 * @author Christian Schnapka - Macstab GmbH
 * @see CommandLatencyExporter
 * @see LanedRedisMetricsProperties.CommandLatency
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass({CommandLatencyCollector.class, MeterRegistry.class, ClientResources.class})
@ConditionalOnBean(ClientResources.class)
@ConditionalOnProperty(
    prefix = "management.metrics.laned-redis.command-latency",
    name = "enabled",
    havingValue = "true")
@EnableConfigurationProperties(LanedRedisMetricsProperties.class)
public class CommandLatencyExportAutoConfiguration {

  /**
   * Creates {@link CommandLatencyExporter} bean.
   *
   * <p><strong>Preconditions:</strong>
   *
   * <ul>
   *   <li>{@link ClientResources} bean must exist (Spring Boot Lettuce auto-config or user-defined)
   *   <li>ClientResources SHOULD have {@link CommandLatencyCollector} configured (otherwise
   *       exporter is no-op)
   * </ul>
   *
   * <p><strong>Optional Dependency:</strong> If ClientResources has NO CommandLatencyCollector,
   * exporter bean is still created but {@code exportCommandLatencies()} becomes a silent no-op.
   * This allows flexible configuration without breaking Spring Boot startup.
   *
   * <p><strong>Lifecycle:</strong> Exporter is {@link AutoCloseable}, Spring Boot calls {@code
   * close()} on shutdown to unregister gauges from MeterRegistry (prevents memory leaks).
   *
   * @param clientResources Lettuce client resources (auto-wired from Spring context)
   * @param registry Micrometer meter registry (auto-wired from Spring Boot Actuator)
   * @param properties laned Redis metrics properties (auto-wired from configuration)
   * @return command latency exporter bean
   */
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  public CommandLatencyExporter commandLatencyExporter(
      final ClientResources clientResources,
      final MeterRegistry registry,
      final LanedRedisMetricsProperties properties) {

    final var exporter =
        new CommandLatencyExporter(
            clientResources,
            registry,
            properties.getConnectionName(),
            properties.getMetricNames(),
            properties.getCommandLatency().getPercentiles(),
            properties.getCommandLatency().isResetAfterExport());

    if (log.isInfoEnabled()) {
      log.info(
          "Created CommandLatencyExporter for connection '{}' (percentiles: {}, reset: {})",
          properties.getConnectionName(),
          percentilesToString(properties.getCommandLatency().getPercentiles()),
          properties.getCommandLatency().isResetAfterExport());
    }

    return exporter;
  }

  private static String percentilesToString(final double[] percentiles) {
    final StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < percentiles.length; i++) {
      sb.append(String.format("%.2f", percentiles[i]));
      if (i < percentiles.length - 1) {
        sb.append(", ");
      }
    }
    sb.append("]");
    return sb.toString();
  }
}
