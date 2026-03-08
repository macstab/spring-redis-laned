/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.micrometer;

import lombok.experimental.UtilityClass;

/**
 * Metric names and configuration constants for Micrometer integration.
 *
 * <p><strong>Naming Convention:</strong> {@code redis.lettuce.laned.*} (hierarchical taxonomy)
 *
 * <ul>
 *   <li>{@code redis} - Domain (vs jedis, redisson)
 *   <li>{@code lettuce} - Client library
 *   <li>{@code laned} - Feature (vs pooled, classic)
 * </ul>
 *
 * <p><strong>Prometheus Output:</strong> Micrometer's {@code PrometheusNamingConvention}
 * automatically converts dots to underscores:
 *
 * <pre>
 * redis.lettuce.laned.lane.selections → redis_lettuce_laned_lane_selections_total
 * redis.lettuce.laned.lane.in_flight  → redis_lettuce_laned_lane_in_flight
 * </pre>
 *
 * <p><strong>Why dots in code:</strong> Micrometer convention, monitoring-system agnostic.
 *
 * <p><strong>Construction:</strong> This is a utility class (Lombok {@code @UtilityClass}) which
 * generates a private constructor to prevent instantiation. Use static constants directly.
 *
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 */
@UtilityClass
public class MetricsConfiguration {

  /** Metric name prefix (hierarchical: domain.client.feature). */
  public static final String PREFIX = "redis.lettuce.laned";

  /**
   * Lane selection counter (proves round-robin distribution).
   *
   * <p><strong>Type:</strong> Counter
   *
   * <p><strong>Tags:</strong>
   *
   * <ul>
   *   <li>{@code lane} - lane index (0, 1, 2, ...)
   *   <li>{@code strategy} - strategy name (round-robin, thread-affinity, least-used)
   * </ul>
   *
   * <p><strong>Usage:</strong> Validate uniform distribution (all lanes ~equal count).
   */
  public static final String LANE_SELECTIONS = PREFIX + ".lane.selections";

  /**
   * Lane in-flight gauge (current operations per lane).
   *
   * <p><strong>Type:</strong> Gauge
   *
   * <p><strong>Tags:</strong>
   *
   * <ul>
   *   <li>{@code lane} - lane index (0, 1, 2, ...)
   * </ul>
   *
   * <p><strong>Usage:</strong> Detect lane bottlenecks (one lane much higher than others).
   */
  public static final String LANE_IN_FLIGHT = PREFIX + ".lane.in_flight";

  /**
   * Estimated HOL blocking percentage (theoretical).
   *
   * <p><strong>Type:</strong> Gauge
   *
   * <p><strong>Formula:</strong> {@code 100 / numLanes}
   *
   * <p><strong>Examples:</strong>
   *
   * <ul>
   *   <li>1 lane: 100% blocking
   *   <li>8 lanes: 12.5% blocking
   *   <li>16 lanes: 6.25% blocking
   * </ul>
   *
   * <p><strong>Usage:</strong> Prove HOL reduction (show 87.5% improvement with 8 lanes).
   */
  public static final String HOL_BLOCKING_ESTIMATED = PREFIX + ".hol.blocking.estimated";

  /**
   * CAS retry counter (strategy contention).
   *
   * <p><strong>Type:</strong> Counter
   *
   * <p><strong>Tags:</strong>
   *
   * <ul>
   *   <li>{@code strategy} - strategy name (least-used, etc.)
   * </ul>
   *
   * <p><strong>Usage:</strong> Detect excessive contention (high retries → need more lanes).
   */
  public static final String CAS_RETRIES = PREFIX + ".strategy.cas.retries";

  /**
   * Slow command counter (commands > threshold).
   *
   * <p><strong>Type:</strong> Counter
   *
   * <p><strong>Tags:</strong>
   *
   * <ul>
   *   <li>{@code lane} - lane index (0, 1, 2, ...)
   *   <li>{@code threshold} - threshold duration (10ms, 100ms, etc.)
   * </ul>
   *
   * <p><strong>Usage:</strong> Identify HOL blocking candidates.
   *
   * <p><strong>Note:</strong> Currently NOT populated by core library (future enhancement).
   */
  public static final String SLOW_COMMANDS = PREFIX + ".slow.commands";

  /**
   * Command latency gauge (Lettuce built-in collector export).
   *
   * <p><strong>Type:</strong> Gauge
   *
   * <p><strong>Tags:</strong>
   *
   * <ul>
   *   <li>{@code connection.name} - connection name (primary, cache, etc.)
   *   <li>{@code command} - command type (GET, SET, HGETALL, etc.)
   *   <li>{@code percentile} - percentile (0.50, 0.95, 0.99)
   *   <li>{@code unit} - time unit (MICROSECONDS)
   * </ul>
   *
   * <p><strong>Source:</strong> Lettuce {@code CommandLatencyCollector}
   *
   * <p><strong>Usage:</strong> P50/P95/P99 latency tracking per command type.
   *
   * @since 1.2.0
   */
  public static final String COMMAND_LATENCY = PREFIX + ".command.latency";

  /**
   * Total connections gauge (total lanes configured).
   *
   * <p><strong>Type:</strong> Gauge
   *
   * <p><strong>Usage:</strong> Show connection count reduction (8 lanes vs 50-pool connections).
   */
  public static final String CONNECTIONS_TOTAL = PREFIX + ".connections.total";

  /**
   * Open connections gauge (healthy lanes).
   *
   * <p><strong>Type:</strong> Gauge
   *
   * <p><strong>Usage:</strong> Health monitoring (should equal {@code connections.total}).
   */
  public static final String CONNECTIONS_OPEN = PREFIX + ".connections.open";

  // ==================== Metric Tag Keys ====================

  /** Tag key: connection name (e.g., "primary", "cache", "session"). */
  public static final String TAG_CONNECTION_NAME = "connection.name";

  /** Tag key: Lettuce client name (for multi-client setups). */
  public static final String TAG_CLIENT_NAME = "client.name";

  /** Tag key: lane index (0-based, range [0, numLanes-1]). */
  public static final String TAG_LANE_INDEX = "lane.index";

  /** Tag key: strategy name (e.g., "round-robin", "thread-affinity", "least-used"). */
  public static final String TAG_STRATEGY_NAME = "strategy.name";

  /** Tag key: Redis command type (e.g., "GET", "SET", "HGETALL"). */
  public static final String TAG_COMMAND = "command";

  /** Tag key: slow command threshold (e.g., "10ms", "100ms"). */
  public static final String TAG_THRESHOLD = "threshold";

  /** Tag key: latency percentile (e.g., "0.50", "0.95", "0.99"). */
  public static final String TAG_PERCENTILE = "percentile";

  /** Tag key: time unit for latency (e.g., "MICROSECONDS"). */
  public static final String TAG_UNIT = "unit";

  // ==================== Deprecated Tags (Backward Compatibility) ====================

  /**
   * Deprecated alias for {@link #TAG_LANE_INDEX}.
   *
   * @deprecated Use {@link #TAG_LANE_INDEX} instead (since 1.1.0, removed in 2.0.0)
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  public static final String TAG_LANE = TAG_LANE_INDEX;

  /**
   * Deprecated alias for {@link #TAG_STRATEGY_NAME}.
   *
   * @deprecated Use {@link #TAG_STRATEGY_NAME} instead (since 1.1.0, removed in 2.0.0)
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  public static final String TAG_STRATEGY = TAG_STRATEGY_NAME;
}
