/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics;

/**
 * No-op implementation of {@link LanedRedisMetrics} (backwards compatibility).
 *
 * <p><strong>Deprecated:</strong> Use {@link LanedRedisMetrics#NOOP} directly instead.
 *
 * <p><strong>Why deprecated:</strong> Interface now has default no-op methods. This enum singleton
 * is redundant - {@code LanedRedisMetrics.NOOP} provides the same functionality with cleaner API.
 *
 * <p><strong>Migration:</strong>
 *
 * <pre>{@code
 * // Old
 * LanedRedisMetrics metrics = NoOpLanedRedisMetrics.INSTANCE;
 *
 * // New
 * LanedRedisMetrics metrics = LanedRedisMetrics.NOOP;
 * }</pre>
 *
 * <p><strong>When used:</strong>
 *
 * <ul>
 *   <li>Metrics module not on classpath (no Micrometer dependency)
 *   <li>Metrics explicitly disabled via {@code management.metrics.laned-redis.enabled=false}
 *   <li>Backward compatibility (existing code using {@code NoOpLanedRedisMetrics.INSTANCE})
 * </ul>
 *
 * <p><strong>Performance Characteristics:</strong> Identical to {@link LanedRedisMetrics#NOOP} -
 * zero overhead, JIT eliminates all calls.
 *
 * @deprecated Use {@link LanedRedisMetrics#NOOP} instead (simpler API, same functionality)
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 */
@Deprecated(since = "1.0.0", forRemoval = false)
public enum NoOpLanedRedisMetrics implements LanedRedisMetrics {
  /**
   * Singleton instance (delegates to {@link LanedRedisMetrics#NOOP}).
   *
   * <p><strong>Why enum:</strong> Historical (enum singleton pattern). Now redundant - use {@link
   * LanedRedisMetrics#NOOP} directly.
   *
   * @deprecated Use {@link LanedRedisMetrics#NOOP} instead
   */
  @Deprecated(since = "1.0.0", forRemoval = false)
  INSTANCE;

  // All methods use default implementations from LanedRedisMetrics interface
  // No overrides needed - interface defaults are already no-op
}
