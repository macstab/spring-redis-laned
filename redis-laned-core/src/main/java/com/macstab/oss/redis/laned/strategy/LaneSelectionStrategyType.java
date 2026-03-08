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
 *   <tr>
 *     <td>KEY_AFFINITY</td>
 *     <td>Multi-tenant, key-isolated workloads</td>
 *     <td>Same key always routed to same lane, zero cross-lane contention</td>
 *     <td>Lazy initialization overhead (~200ns per wrapper)</td>
 *   </tr>
 * </table>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LaneSelectionStrategy
 * @see RoundRobinStrategy
 * @see ThreadAffinityStrategy
 * @see LeastUsedStrategy
 * @see KeyAffinityStrategy
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
  LEAST_USED,

  /**
   * Key affinity (same key always routes to same lane).
   *
   * <p><strong>How it works:</strong> Hashes Redis key with MurmurHash3, selects lane = hash %
   * numLanes. Uses dynamic proxy to intercept commands and extract keys lazily.
   *
   * <p><strong>Use when:</strong>
   *
   * <ul>
   *   <li>Multi-tenant workloads (tenant ID embedded in key)
   *   <li>Key-isolated operations (same key accessed repeatedly)
   *   <li>Transaction safety required (same key → same connection → safe MULTI/EXEC)
   *   <li>Cache locality desired (same lane processes related keys)
   * </ul>
   *
   * <p><strong>Implementation note:</strong> Unlike other strategies, lane selection is LAZY
   * (happens on first command, not during {@code getConnection()}). This is because the Redis key
   * is only available when the user calls {@code async().get(key)}, not when the connection is
   * requested. Uses {@link com.macstab.oss.redis.laned.KeyAffinityConnectionWrapper} with dynamic
   * proxy to intercept commands.
   *
   * <p><strong>Performance characteristics:</strong>
   *
   * <ul>
   *   <li>Lane selection: O(key length) for hashing (~50-200ns for typical keys)
   *   <li>Wrapper creation: ~200ns (dynamic proxy overhead)
   *   <li>Command dispatch: ~50ns (method interception + forwarding)
   *   <li>Total overhead: ~250-450ns per connection (amortized over commands)
   * </ul>
   *
   * <p><strong>Best for:</strong> Workloads where key locality matters more than raw throughput.
   * For maximum throughput with no key locality requirements, use {@link #ROUND_ROBIN} or {@link
   * #THREAD_AFFINITY}.
   *
   * @see KeyAffinityStrategy
   * @see com.macstab.oss.redis.laned.KeyAffinityConnectionWrapper
   */
  KEY_AFFINITY;

  /**
   * Create a {@link LaneSelectionStrategy} instance for this strategy type.
   *
   * <p><strong>Factory method</strong> - returns a new instance on every call. Strategies are
   * lightweight (no mutable state), but instances are NOT shared across managers. Each {@code
   * LanedConnectionManager} gets its own strategy instance.
   *
   * @return new strategy instance (never null)
   */
  public LaneSelectionStrategy createStrategy() {
    return switch (this) {
      case ROUND_ROBIN -> new RoundRobinStrategy();
      case THREAD_AFFINITY -> new ThreadAffinityStrategy();
      case LEAST_USED -> new LeastUsedStrategy();
      case KEY_AFFINITY -> new KeyAffinityStrategy();
    };
  }
}
