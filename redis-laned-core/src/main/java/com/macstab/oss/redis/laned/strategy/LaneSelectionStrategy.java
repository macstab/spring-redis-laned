/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import com.macstab.oss.redis.laned.ConnectionLane;

/**
 * Strategy for selecting which lane handles the next Redis command.
 *
 * <p><strong>Why strategies matter (head-of-line blocking mitigation):</strong>
 *
 * <p>RESP protocol forces FIFO response matching (no request IDs). A slow command blocks all
 * subsequent responses on the same connection until fully read from TCP socket. Selection strategy
 * determines HOL-blocking probability:
 *
 * <ul>
 *   <li><strong>Round-robin:</strong> Uniform distribution, 1/N blocking probability (N lanes)
 *   <li><strong>Least-used:</strong> Route to emptiest queue, avoids loaded lanes
 *   <li><strong>Key-affinity:</strong> Same key → same lane (cache locality, transaction safety)
 *   <li><strong>Weighted:</strong> Priority-based routing (critical vs background traffic)
 * </ul>
 *
 * <p><strong>Thread safety requirement:</strong>
 *
 * <p>Implementations MUST be thread-safe. {@code selectLane()} is called concurrently by multiple
 * threads (application threads issuing Redis commands). Lock-free algorithms (CAS, atomic counters)
 * preferred over synchronized blocks (avoid contention bottleneck).
 *
 * <p><strong>Performance requirement:</strong>
 *
 * <p>Selection latency adds to EVERY Redis command. Target: &lt;50ns (faster than L3 cache miss
 * ~100ns). Acceptable: &lt;500ns (faster than context switch ~1-5μs). Unacceptable: &gt;1μs (slower
 * than Redis command execution for GET ~0.1-1μs).
 *
 * @see RoundRobinStrategy
 */
public interface LaneSelectionStrategy {

  /**
   * Initializes strategy with lane references (two-phase initialization).
   *
   * <p><strong>Why two-phase initialization:</strong>
   *
   * <p>Solves circular dependency: {@code LeastUsedStrategy} needs {@code ConnectionLane[]} to
   * track in-flight counts. {@code LanedConnectionManager} creates lanes, then creates strategy.
   * Without two-phase init:
   *
   * <pre>{@code
   * // IMPOSSIBLE:
   * strategy = new LeastUsedStrategy(lanes);  // lanes don't exist yet!
   * manager = new Manager(..., strategy);     // manager creates lanes
   * }</pre>
   *
   * <p>With two-phase init:
   *
   * <pre>{@code
   * // WORKS:
   * strategy = new LeastUsedStrategy();       // No lanes needed in constructor
   * manager = new Manager(..., strategy);     // Manager creates lanes
   * strategy.initialize(manager.lanes);       // Manager calls after creating lanes
   * }</pre>
   *
   * <p><strong>Contract:</strong>
   *
   * <ul>
   *   <li>Called EXACTLY ONCE by {@code LanedConnectionManager} constructor
   *   <li>Called BEFORE first {@code selectLane()} invocation
   *   <li>Implementations MAY store lane references (e.g., {@code LeastUsedStrategy})
   *   <li>Implementations MAY ignore (e.g., {@code RoundRobinStrategy}, {@code
   *       ThreadAffinityStrategy} are stateless)
   * </ul>
   *
   * <p><strong>Thread safety:</strong>
   *
   * <p>NOT thread-safe (called only once from manager constructor, single-threaded).
   * Implementations do NOT need synchronization.
   *
   * @param lanes connection lanes created by manager
   */
  default void initialize(ConnectionLane[] lanes) {
    // Default: No-op (stateless strategies don't need lanes)
  }

  /**
   * Increments in-flight count when connection acquired (lifecycle tracking).
   *
   * <p><strong>When called:</strong> {@code LanedConnectionManager.getConnection()} after {@code
   * selectLane()} returns lane index.
   *
   * <p><strong>Default implementation:</strong> No-op for stateless strategies (e.g., {@code
   * RoundRobinStrategy}). Override for stateful strategies that track lane usage.
   *
   * <p><strong>Thread safety:</strong> Must be thread-safe (multiple threads call concurrently).
   *
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   * @see AbstractLaneSelectionStrategy#onConnectionAcquired(int)
   */
  default void onConnectionAcquired(final int laneIndex) {
    // No-op by default (stateless strategies don't track)
  }

  /**
   * Returns in-flight count for specific lane (metrics/monitoring).
   *
   * <p><strong>Use case:</strong> Metrics collection, health checks, debugging.
   *
   * <p><strong>Default implementation:</strong> Returns 0 (stateless strategies don't track).
   * Override for stateful strategies.
   *
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   * @return current in-flight count (0 if not tracked)
   * @see AbstractLaneSelectionStrategy#getInFlightCount(int)
   */
  default int getInFlightCount(final int laneIndex) {
    return 0; // Not tracked by default
  }

  /**
   * Selects the lane index for the next command.
   *
   * <p><strong>Contract:</strong>
   *
   * <ul>
   *   <li>Return value MUST be in range [0, numLanes-1]
   *   <li>MUST be thread-safe (multiple threads call concurrently)
   *   <li>SHOULD complete in &lt;50ns (target) or &lt;500ns (acceptable)
   *   <li>MAY use command metadata (future: command type, key) for routing
   * </ul>
   *
   * @param numLanes total number of available lanes (N ≥ 1)
   * @return lane index (0-based, range [0, numLanes-1])
   */
  int selectLane(final int numLanes);

  /**
   * Returns strategy name for logging/metrics.
   *
   * @return strategy name (e.g., "round-robin", "least-used")
   */
  String getName();

  /**
   * Notifies strategy that a connection was released (closed).
   *
   * <p><strong>Lifecycle tracking (prevents usage count memory leaks):</strong>
   *
   * <p>Called by {@code LanedConnectionWrapper.close()} when application code closes a connection.
   * Enables strategies to track lane utilization accurately:
   *
   * <ul>
   *   <li><strong>LeastUsedStrategy:</strong> Decrements {@code usageCounts[laneIndex]} (prevents
   *       overflow leak)
   *   <li><strong>RoundRobinStrategy:</strong> No-op (stateless, no tracking needed)
   *   <li><strong>ThreadAffinityStrategy:</strong> No-op (stateless, hash-based)
   * </ul>
   *
   * <p><strong>Thread safety requirement:</strong>
   *
   * <p>Implementations MUST handle concurrent calls (same lane, multiple threads). If connection
   * closed multiple times (idempotency): MUST NOT corrupt state. Use atomic operations (e.g.,
   * {@code AtomicIntegerArray.decrementAndGet()}) or synchronized blocks.
   *
   * <p><strong>Exception handling requirement:</strong>
   *
   * <p>This method is called in {@code finally} block (always executes, even if {@code close()}
   * throws). Implementations MUST NOT throw exceptions (would suppress original exception). Catch
   * internally and log errors.
   *
   * <p><strong>Performance requirement:</strong>
   *
   * <p>Called on close path (NOT hot path). Close happens ~1/minute (long-lived connections).
   * Acceptable latency: &lt;1μs. Unacceptable: blocking I/O, heavy computation.
   *
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   */
  void onConnectionReleased(final int laneIndex);
}
