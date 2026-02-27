/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics;

import java.time.Duration;

/**
 * Framework-agnostic metrics interface for laned Redis connections.
 *
 * <p><strong>Design Pattern:</strong> Interface with default no-op methods. Implementations
 * override only the methods they need, core library calls all methods safely (no null checks).
 *
 * <p><strong>Implementations:</strong>
 *
 * <ul>
 *   <li>{@link #NOOP} - Zero-overhead singleton (uses default methods)
 *   <li>{@code MicrometerLanedRedisMetrics} - Micrometer integration (Spring Boot Actuator)
 *   <li>Future: Prometheus direct, StatsD, Dropwizard Metrics
 * </ul>
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <ol>
 *   <li>Created by Spring Boot auto-configuration or manually
 *   <li>Injected into {@code LanedConnectionManager} (nullable, defaults to {@link #NOOP})
 *   <li>{@code close()} called when {@code LanedConnectionManager.destroy()} invoked
 * </ol>
 *
 * <p><strong>Thread Safety:</strong> Implementations MUST be thread-safe. Multiple threads call
 * recording methods concurrently (all application threads issuing Redis commands).
 *
 * <p><strong>Performance Requirement:</strong> Recording methods MUST complete in &lt;100ns. Called
 * on hot path (every Redis command). Use lock-free data structures (AtomicInteger,
 * ConcurrentHashMap) or accept minor metric inaccuracy for speed.
 *
 * <p><strong>Default Methods:</strong> All methods have no-op default implementations. JIT compiler
 * optimizes these to zero overhead (dead code elimination). Implementations override only what they
 * need.
 *
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 */
public interface LanedRedisMetrics extends AutoCloseable {

  /**
   * No-op singleton instance (uses default methods).
   *
   * <p><strong>Usage:</strong>
   *
   * <pre>{@code
   * LanedRedisMetrics metrics = ...; // nullable
   * metrics = metrics != null ? metrics : LanedRedisMetrics.NOOP;
   * }</pre>
   *
   * <p><strong>Performance:</strong> Zero overhead - JIT compiler eliminates all default method
   * calls.
   */
  LanedRedisMetrics NOOP = new LanedRedisMetrics() {};

  /**
   * Records lane selection by strategy (dimensional version).
   *
   * <p>Called every time {@code LanedConnectionManager.getConnection()} selects a lane. Used to
   * validate selection distribution (round-robin should be uniform).
   *
   * <p><strong>Metric Type:</strong> Counter (monotonically increasing)
   *
   * <p><strong>Expected Metric Name:</strong> {@code redis.lettuce.laned.lane.selections}
   *
   * <p><strong>Expected Tags:</strong>
   *
   * <ul>
   *   <li>{@code connection.name} - connection name (Spring bean name or "default")
   *   <li>{@code lane.index} - lane index (0-based)
   *   <li>{@code strategy.name} - strategy name (e.g., "round-robin", "thread-affinity")
   * </ul>
   *
   * <p><strong>Thread Safety:</strong> MUST be thread-safe (concurrent calls).
   *
   * <p><strong>Performance:</strong> Target &lt;20ns (cached counter increment).
   *
   * <p><strong>Default:</strong> No-op (override to implement).
   *
   * @param connectionName connection name (e.g., "primary", "cache", "default")
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   * @param strategyName strategy name (e.g., "round-robin")
   * @since 1.1.0
   */
  default void recordLaneSelection(String connectionName, int laneIndex, String strategyName) {
    // No-op by default
  }

  /**
   * Records lane selection by strategy (legacy, non-dimensional).
   *
   * @param laneIndex lane index (0-based)
   * @param strategyName strategy name
   * @deprecated Use {@link #recordLaneSelection(String, int, String)} for dimensional metrics
   * @since 1.0.0
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void recordLaneSelection(int laneIndex, String strategyName) {
    recordLaneSelection("default", laneIndex, strategyName);
  }

  /**
   * Sets in-flight operations count for a lane (dimensional version).
   *
   * <p>Called when connection acquired/released to report current in-flight count. Used to track
   * current load per lane.
   *
   * <p><strong>Design:</strong> Single source of truth - {@code ConnectionLane.inFlightCount} owns
   * the count, metrics just REPORT it. Avoids drift between lane state and metrics.
   *
   * <p><strong>Metric Type:</strong> Gauge (current value)
   *
   * <p><strong>Expected Metric Name:</strong> {@code redis.lettuce.laned.lane.in_flight}
   *
   * <p><strong>Expected Tags:</strong>
   *
   * <ul>
   *   <li>{@code connection.name} - connection name (Spring bean name or "default")
   *   <li>{@code lane.index} - lane index (0-based)
   * </ul>
   *
   * <p><strong>Thread Safety:</strong> MUST be thread-safe (concurrent calls).
   *
   * <p><strong>Performance:</strong> Target &lt;20ns (cached AtomicInteger set).
   *
   * <p><strong>Default:</strong> No-op (override to implement).
   *
   * @param connectionName connection name (e.g., "primary", "cache", "default")
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   * @param count current in-flight operations count (>=0)
   * @since 1.1.0
   */
  default void setInFlightOperations(String connectionName, int laneIndex, int count) {
    // No-op by default
  }

  /**
   * Records in-flight count increment when connection acquired (dimensional version).
   *
   * <p><strong>Deprecated:</strong> Use {@link #setInFlightOperations(String, int, int)} instead
   * for single source of truth.
   *
   * @param connectionName connection name (e.g., "primary", "cache", "default")
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   * @deprecated Use {@link #setInFlightOperations(String, int, int)} to avoid state drift
   * @since 1.1.0
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void recordInFlightIncrement(String connectionName, int laneIndex) {
    // No-op by default
  }

  /**
   * Records in-flight count increment (legacy, non-dimensional).
   *
   * @param laneIndex lane index (0-based)
   * @deprecated Use {@link #setInFlightOperations(String, int, int)} for dimensional metrics
   * @since 1.0.0
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void recordInFlightIncrement(int laneIndex) {
    // Delegate to dimensional no-op
  }

  /**
   * Records in-flight count decrement when connection released (dimensional version).
   *
   * <p><strong>Deprecated:</strong> Use {@link #setInFlightOperations(String, int, int)} instead
   * for single source of truth.
   *
   * @param connectionName connection name (e.g., "primary", "cache", "default")
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   * @deprecated Use {@link #setInFlightOperations(String, int, int)} to avoid state drift
   * @since 1.1.0
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void recordInFlightDecrement(String connectionName, int laneIndex) {
    // No-op by default
  }

  /**
   * Records in-flight count decrement (legacy, non-dimensional).
   *
   * @param laneIndex lane index (0-based)
   * @deprecated Use {@link #setInFlightOperations(String, int, int)} for dimensional metrics
   * @since 1.0.0
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void recordInFlightDecrement(int laneIndex) {
    // Delegate to dimensional no-op
  }

  /**
   * Records CAS retry in lane selection strategy (dimensional version).
   *
   * <p>Called when {@code LeastUsedStrategy} (or future strategies) retry CAS loop due to
   * contention. Used to detect excessive contention (indicates need for more lanes).
   *
   * <p><strong>Metric Type:</strong> Counter (monotonically increasing)
   *
   * <p><strong>Expected Metric Name:</strong> {@code redis.lettuce.laned.strategy.cas.retries}
   *
   * <p><strong>Expected Tags:</strong>
   *
   * <ul>
   *   <li>{@code connection.name} - connection name (Spring bean name or "default")
   *   <li>{@code strategy.name} - strategy name (e.g., "least-used")
   * </ul>
   *
   * <p><strong>Usage Example:</strong>
   *
   * <pre>{@code
   * // In LeastUsedStrategy.selectLane()
   * int retries = 0;
   * while (!casSucceeded) {
   *     retries++;
   *     metrics.recordCASRetry(connectionName, "least-used");
   * }
   * }</pre>
   *
   * <p><strong>Thread Safety:</strong> MUST be thread-safe (concurrent calls).
   *
   * <p><strong>Performance:</strong> Target &lt;20ns (cached counter increment).
   *
   * <p><strong>Default:</strong> No-op (override to implement).
   *
   * @param connectionName connection name (e.g., "primary", "cache", "default")
   * @param strategyName strategy name (e.g., "least-used")
   * @since 1.1.0
   */
  default void recordCASRetry(String connectionName, String strategyName) {
    // No-op by default
  }

  /**
   * Records CAS retry (legacy, non-dimensional).
   *
   * @param strategyName strategy name
   * @deprecated Use {@link #recordCASRetry(String, String)} for dimensional metrics
   * @since 1.0.0
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void recordCASRetry(String strategyName) {
    recordCASRetry("default", strategyName);
  }

  /**
   * Records slow command detection (dimensional version).
   *
   * <p>Called when command duration exceeds configured threshold (e.g., 10ms). Used to detect HOL
   * blocking candidates.
   *
   * <p><strong>Metric Type:</strong> Counter (monotonically increasing)
   *
   * <p><strong>Expected Metric Name:</strong> {@code redis.lettuce.laned.slow.commands}
   *
   * <p><strong>Expected Tags:</strong>
   *
   * <ul>
   *   <li>{@code connection.name} - connection name (Spring bean name or "default")
   *   <li>{@code command} - command name (e.g., "GET", "SMEMBERS")
   * </ul>
   *
   * <p><strong>Note:</strong> Requires CommandListener implementation (future enhancement, 1 day
   * effort).
   *
   * <p><strong>Thread Safety:</strong> MUST be thread-safe (concurrent calls).
   *
   * <p><strong>Performance:</strong> Target &lt;20ns (cached counter increment).
   *
   * <p><strong>Default:</strong> No-op (override to implement).
   *
   * @param connectionName connection name (e.g., "primary", "cache", "default")
   * @param command command name (e.g., "GET", "SMEMBERS")
   * @param durationMs command duration in milliseconds
   * @since 1.1.0
   */
  default void recordSlowCommand(String connectionName, String command, long durationMs) {
    // No-op by default
  }

  /**
   * Records slow command (legacy, non-dimensional).
   *
   * @param laneIndex lane index (0-based)
   * @param duration command duration
   * @deprecated Use {@link #recordSlowCommand(String, String, long)} for dimensional metrics
   * @since 1.0.0
   */
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void recordSlowCommand(int laneIndex, Duration duration) {
    recordSlowCommand("default", "UNKNOWN", duration.toMillis());
  }

  /**
   * Closes metrics for a specific connection (dimensional cleanup).
   *
   * <p><strong>Why needed:</strong> Micrometer Gauges hold strong references. If not removed from
   * {@code MeterRegistry}, memory leak occurs (~1KB per gauge × 8 lanes × 1000 managers = 8MB
   * leaked).
   *
   * <p><strong>When called:</strong> {@code LanedConnectionManager.destroy()}
   *
   * <p><strong>Idempotency:</strong> MUST be safe to call multiple times (no-op on subsequent
   * calls).
   *
   * <p><strong>Thread Safety:</strong> MUST be thread-safe (may be called concurrently).
   *
   * <p><strong>Exception Handling:</strong> MUST NOT throw exceptions (called in {@code finally}
   * block). Catch internally and log errors.
   *
   * <p><strong>Default:</strong> No-op (override to implement cleanup).
   *
   * @param connectionName connection name to clean up gauges for
   * @since 1.1.0
   */
  default void close(String connectionName) {
    // No-op by default
  }

  /**
   * Closes all metrics (legacy, non-dimensional).
   *
   * <p><strong>Implementation Example:</strong>
   *
   * <pre>{@code
   * @Override
   * public void close() {
   *     registeredGauges.forEach(meterRegistry::remove);
   *     registeredGauges.clear();
   * }
   * }</pre>
   *
   * @deprecated Use {@link #close(String)} for dimensional cleanup
   * @since 1.0.0
   */
  @Override
  @Deprecated(since = "1.1.0", forRemoval = true)
  default void close() {
    close("default");
  }
}
