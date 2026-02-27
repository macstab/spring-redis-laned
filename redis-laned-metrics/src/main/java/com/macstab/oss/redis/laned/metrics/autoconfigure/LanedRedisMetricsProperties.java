/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.autoconfigure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for laned Redis metrics.
 *
 * <p><strong>Configuration Example:</strong>
 *
 * <pre>{@code
 * management:
 *   metrics:
 *     laned-redis:
 *       enabled: true
 *       connection-name: primary
 *       max-cache-size: 1000
 *       slow-command-threshold: 10ms
 * }</pre>
 *
 * <p><strong>Defaults:</strong>
 *
 * <ul>
 *   <li>{@code enabled}: {@code true} (metrics enabled by default if Micrometer on classpath)
 *   <li>{@code connection-name}: {@code "default"} (can override per connection)
 *   <li>{@code max-cache-size}: {@code 1000} (bounded memory, graceful degradation)
 *   <li>{@code slow-command-threshold}: {@code 10ms}
 * </ul>
 *
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 */
@Data
@ConfigurationProperties(prefix = "management.metrics.laned-redis")
public class LanedRedisMetricsProperties {

  /**
   * Enable laned Redis metrics collection.
   *
   * <p><strong>Default:</strong> {@code true}
   *
   * <p><strong>When disabled:</strong> {@code NoOpLanedRedisMetrics} used (zero overhead).
   */
  private boolean enabled = true;

  /**
   * Connection name for dimensional metrics.
   *
   * <p><strong>Default:</strong> {@code "default"}
   *
   * <p><strong>Usage:</strong> Distinguishes multiple Redis connections (primary, cache, session).
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * # Primary connection
   * management.metrics.laned-redis.connection-name=primary
   *
   * # Cache connection
   * management.metrics.laned-redis.connection-name=cache
   * }</pre>
   *
   * @since 1.1.0
   */
  private String connectionName = "default";

  /**
   * Maximum cached metric instances (bounded memory).
   *
   * <p><strong>Default:</strong> {@code 1000}
   *
   * <p><strong>Purpose:</strong> Prevent unbounded cache growth (e.g., dynamic tags).
   *
   * <p><strong>Typical Usage:</strong> 4 lanes × 10 connections × 3 strategies = ~120 cached
   * counters. Default {@code 1000} provides 8× safety margin.
   *
   * <p><strong>Graceful Degradation:</strong> When cache full, metrics still work (direct
   * Micrometer registry, slower but functional). Warning logged.
   *
   * <p><strong>Tuning:</strong>
   *
   * <ul>
   *   <li>Increase if: Many connections, lanes, or strategies
   *   <li>Decrease if: Memory-constrained environment
   * </ul>
   *
   * @since 1.1.0
   */
  private int maxCacheSize = 1000;

  /**
   * Slow command threshold for detection.
   *
   * <p><strong>Default:</strong> 10ms
   *
   * <p><strong>Usage:</strong> Commands exceeding this duration trigger {@code
   * redis.lettuce.laned.slow.commands} counter.
   *
   * <p><strong>Note:</strong> Requires CommandListener implementation (future enhancement, 1 day
   * effort).
   */
  private Duration slowCommandThreshold = Duration.ofMillis(10);

  /**
   * Command latency tracking configuration.
   *
   * <p><strong>Default:</strong> Disabled (zero overhead when not needed)
   *
   * @since 1.2.0
   */
  private CommandLatency commandLatency = new CommandLatency();

  /**
   * Configurable metric names (enterprise integration).
   *
   * <p><strong>Default:</strong> {@code redis.lettuce.laned.*} hierarchy
   *
   * @since 1.2.0
   */
  private MetricNames metricNames = new MetricNames();

  /**
   * Command latency tracking configuration.
   *
   * <p>Integrates with Lettuce's {@code CommandLatencyCollector} to export P50/P95/P99 latencies
   * per command type.
   *
   * @since 1.2.0
   */
  @Data
  public static class CommandLatency {

    /**
     * Enable command latency tracking and export.
     *
     * <p><strong>Default:</strong> {@code false} (opt-in, zero overhead when disabled)
     *
     * <p><strong>Requirements:</strong>
     *
     * <ul>
     *   <li>ClientResources bean with CommandLatencyCollector configured
     *   <li>User must call {@code CommandLatencyExporter.exportCommandLatencies()} periodically
     * </ul>
     */
    private boolean enabled = false;

    /**
     * Percentiles to track (P50, P95, P99, etc.).
     *
     * <p><strong>Default:</strong> [0.50, 0.95, 0.99]
     *
     * <p><strong>Storage cost:</strong> 1 gauge per command type × percentiles
     *
     * <p><strong>Example:</strong> 20 command types × 3 percentiles = 60 gauges
     */
    private double[] percentiles = {0.50, 0.95, 0.99};

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
     */
    private boolean resetAfterExport = true;
  }

  /**
   * Configurable metric names for enterprise integration.
   *
   * <p>Allows customization of metric names to match enterprise naming conventions and avoid
   * conflicts with other teams.
   *
   * <p><strong>Default naming:</strong> {@code redis.lettuce.laned.*}
   *
   * <p><strong>Configuration example:</strong>
   *
   * <pre>{@code
   * management:
   *   metrics:
   *     laned-redis:
   *       metric-names:
   *         prefix: "platform.redis.laned"
   *         command-latency: "platform.redis.cmd_latency"
   * }</pre>
   *
   * @since 1.2.0
   */
  @Data
  public static class MetricNames {

    /** Metric name prefix (hierarchical: domain.client.feature). */
    private String prefix = "redis.lettuce.laned";

    /**
     * Lane selection counter metric name.
     *
     * <p><strong>Default:</strong> {@code redis.lettuce.laned.lane.selections}
     */
    private String laneSelections;

    /**
     * Lane in-flight gauge metric name.
     *
     * <p><strong>Default:</strong> {@code redis.lettuce.laned.lane.in_flight}
     */
    private String laneInFlight;

    /**
     * Command latency gauge metric name.
     *
     * <p><strong>Default:</strong> {@code redis.lettuce.laned.command.latency}
     */
    private String commandLatency;

    /**
     * HOL blocking estimate gauge metric name.
     *
     * <p><strong>Default:</strong> {@code redis.lettuce.laned.hol.blocking.estimated}
     */
    private String holBlocking;

    /**
     * CAS retry counter metric name.
     *
     * <p><strong>Default:</strong> {@code redis.lettuce.laned.strategy.cas.retries}
     */
    private String casRetries;

    /**
     * Slow commands counter metric name.
     *
     * <p><strong>Default:</strong> {@code redis.lettuce.laned.slow.commands}
     */
    private String slowCommands;

    /**
     * Gets lane selections metric name with fallback.
     *
     * @return configured name or computed default
     */
    public String getLaneSelections() {
      return laneSelections != null ? laneSelections : prefix + ".lane.selections";
    }

    /**
     * Gets lane in-flight metric name with fallback.
     *
     * @return configured name or computed default
     */
    public String getLaneInFlight() {
      return laneInFlight != null ? laneInFlight : prefix + ".lane.in_flight";
    }

    /**
     * Gets command latency metric name with fallback.
     *
     * @return configured name or computed default
     */
    public String getCommandLatency() {
      return commandLatency != null ? commandLatency : prefix + ".command.latency";
    }

    /**
     * Gets HOL blocking metric name with fallback.
     *
     * @return configured name or computed default
     */
    public String getHolBlocking() {
      return holBlocking != null ? holBlocking : prefix + ".hol.blocking.estimated";
    }

    /**
     * Gets CAS retries metric name with fallback.
     *
     * @return configured name or computed default
     */
    public String getCasRetries() {
      return casRetries != null ? casRetries : prefix + ".strategy.cas.retries";
    }

    /**
     * Gets slow commands metric name with fallback.
     *
     * @return configured name or computed default
     */
    public String getSlowCommands() {
      return slowCommands != null ? slowCommands : prefix + ".slow.commands";
    }
  }
}
