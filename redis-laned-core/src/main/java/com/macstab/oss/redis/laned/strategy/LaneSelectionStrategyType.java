/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

/**
 * Lane selection strategy types for Redis Laned configuration.
 *
 * <p>Enum representation of available {@link LaneSelectionStrategy} implementations, used for
 * declarative configuration via annotations or properties.
 *
 * <p><strong>Strategy Characteristics:</strong>
 *
 * <table border="1">
 *   <caption>Strategy Comparison</caption>
 *   <tr>
 *     <th>Strategy</th>
 *     <th>Best For</th>
 *     <th>Pros</th>
 *     <th>Cons</th>
 *   </tr>
 *   <tr>
 *     <td>ROUND_ROBIN</td>
 *     <td>Uniform workloads, stateless operations</td>
 *     <td>Fair distribution, predictable</td>
 *     <td>May cause contention on counter</td>
 *   </tr>
 *   <tr>
 *     <td>THREAD_AFFINITY</td>
 *     <td>Thread-local caching, transaction-heavy</td>
 *     <td>Zero contention, cache-friendly</td>
 *     <td>Unbalanced if threads ≠ lanes</td>
 *   </tr>
 *   <tr>
 *     <td>LEAST_USED</td>
 *     <td>Variable workloads, mixed operations</td>
 *     <td>Dynamic load balancing</td>
 *     <td>Slight overhead (atomic reads)</td>
 *   </tr>
 * </table>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LaneSelectionStrategy
 * @see RoundRobinStrategy
 * @see ThreadAffinityStrategy
 * @see LeastUsedStrategy
 */
public enum LaneSelectionStrategyType {

  /**
   * Round-robin selection (sequential cycling through lanes).
   *
   * <p><strong>How it works:</strong> Increments an atomic counter and selects lane = counter %
   * numLanes.
   *
   * <p><strong>Use when:</strong>
   *
   * <ul>
   *   <li>Uniform command latency (no slow/fast operations)
   *   <li>Stateless application (no thread-local data)
   *   <li>General-purpose workload
   * </ul>
   *
   * <p><strong>Default strategy</strong> - good for most applications.
   */
  ROUND_ROBIN,

  /**
   * Thread affinity (sticky lane per thread).
   *
   * <p><strong>How it works:</strong> Maps {@code Thread.currentThread().getId()} to a fixed lane.
   *
   * <p><strong>Use when:</strong>
   *
   * <ul>
   *   <li>Thread-local caching (same thread accesses same keys repeatedly)
   *   <li>Transaction-heavy workload (multi-command sequences on same connection)
   *   <li>High thread count (~equal to lane count)
   * </ul>
   *
   * <p><strong>Best performance</strong> when thread count ≈ lane count (e.g., 8 threads, 8 lanes).
   */
  THREAD_AFFINITY,

  /**
   * Least-used selection (dynamic load balancing).
   *
   * <p><strong>How it works:</strong> Selects lane with lowest active connection count (atomic
   * read).
   *
   * <p><strong>Use when:</strong>
   *
   * <ul>
   *   <li>Mixed command latency (slow BLPOP + fast GET)
   *   <li>Variable load patterns
   *   <li>Need guaranteed fair distribution
   * </ul>
   *
   * <p><strong>Adaptive</strong> - adjusts to actual load, best for heterogeneous workloads.
   */
  LEAST_USED;

  /**
   * Create a {@link LaneSelectionStrategy} instance for this strategy type.
   *
   * @return new strategy instance
   */
  public LaneSelectionStrategy createStrategy() {
    return switch (this) {
      case ROUND_ROBIN -> new RoundRobinStrategy();
      case THREAD_AFFINITY -> new ThreadAffinityStrategy();
      case LEAST_USED -> new LeastUsedStrategy();
    };
  }
}
