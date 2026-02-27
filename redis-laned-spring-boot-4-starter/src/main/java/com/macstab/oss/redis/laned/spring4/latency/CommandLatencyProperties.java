/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.latency;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for Lettuce command latency tracking (Spring Boot 4.x).
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
 * <p><strong>Purpose:</strong> Controls whether {@link io.lettuce.core.resource.ClientResources} is
 * created with {@link io.lettuce.core.metrics.CommandLatencyCollector} enabled.
 *
 * <p><strong>Default:</strong> {@code enabled=false} (zero overhead when not needed)
 *
 * <p><strong>Note:</strong> This configuration is ONLY used if no {@link
 * io.lettuce.core.resource.ClientResources} bean exists. If user provides their own
 * ClientResources, this auto-configuration is skipped (respects user's choice).
 *
 * @since 1.2.0
 * @author Christian Schnapka - Macstab GmbH
 * @see CommandLatencyAutoConfiguration
 */
@Data
@ConfigurationProperties(prefix = "management.metrics.laned-redis.command-latency")
public class CommandLatencyProperties {

  /**
   * Enable command latency tracking.
   *
   * <p><strong>Default:</strong> {@code false} (opt-in, zero overhead when disabled)
   *
   * <p><strong>Effect:</strong> When {@code true}, creates {@link
   * io.lettuce.core.resource.ClientResources} bean with {@link
   * io.lettuce.core.metrics.CommandLatencyCollector} configured.
   *
   * <p><strong>Integration:</strong> Works with {@code redis-laned-metrics} module. Metrics module
   * creates {@code CommandLatencyExporter} bean if both:
   *
   * <ul>
   *   <li>This property is {@code true} (collector enabled)
   *   <li>{@code management.metrics.laned-redis.command-latency.enabled=true} (export enabled)
   * </ul>
   */
  private boolean enabled = false;

  /**
   * Reset latencies after each export.
   *
   * <p><strong>Default:</strong> {@code true} (point-in-time snapshot)
   *
   * <p><strong>Trade-off:</strong>
   *
   * <ul>
   *   <li>{@code true}: Fresh snapshot each export (accurate per-period latencies)
   *   <li>{@code false}: Cumulative since startup (may grow memory, less accurate)
   * </ul>
   *
   * <p><strong>Note:</strong> This setting is passed to {@link
   * io.lettuce.core.metrics.DefaultCommandLatencyCollectorOptions}.
   */
  private boolean resetAfterExport = true;
}
