/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

import com.macstab.oss.redis.laned.ConnectionLane;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

/**
 * Abstract base class for stateful lane selection strategies.
 *
 * <p><strong>Why abstract base class (vs interface with default methods):</strong>
 *
 * <p>Stateful strategies (e.g., {@link LeastUsedStrategy}) need access to {@code ConnectionLane[]}
 * to read in-flight counts. Two design options:
 *
 * <ol>
 *   <li><strong>Interface with {@code getLanes()} method:</strong> Forces ALL strategies to
 *       implement {@code getLanes()}, even stateless ones (e.g., {@code RoundRobinStrategy}).
 *       Breaks encapsulation (exposes internal state). Code smell.
 *   <li><strong>Abstract base class with {@code protected lanes} field:</strong> Only stateful
 *       strategies extend base class. Stateless strategies implement interface directly. Clean
 *       separation. No unnecessary coupling.
 * </ol>
 *
 * <p>This class provides:
 *
 * <ul>
 *   <li><strong>Lifecycle tracking:</strong> {@code onConnectionAcquired()} increments in-flight
 *       count, {@code onConnectionReleased()} decrements (CAS loop with underflow protection)
 *   <li><strong>Metrics access:</strong> {@code getInFlightCount()} exposes counts for
 *       observability
 *   <li><strong>Initialization:</strong> {@code initialize()} stores lane references (two-phase
 *       init pattern)
 * </ul>
 *
 * <p><strong>Stateless strategies (do NOT extend this class):</strong>
 *
 * <ul>
 *   <li>{@code RoundRobinStrategy} - Counter-based, no lane state needed
 *   <li>{@code ThreadAffinityStrategy} - Hash-based, no lane state needed
 * </ul>
 *
 * <p>These implement {@link LaneSelectionStrategy} directly with no-op lifecycle methods.
 *
 * <p><strong>Stateful strategies (extend this class):</strong>
 *
 * <ul>
 *   <li>{@link LeastUsedStrategy} - Reads {@code lanes[i].getInFlightCount()} in {@code
 *       selectLane()}
 *   <li>Future: {@code WeightedStrategy}, {@code KeyAffinityStrategy}, etc.
 * </ul>
 *
 * <p>These access {@code protected lanes} field for lane state observation.
 *
 * <p><strong>Memory overhead (stateful vs stateless):</strong>
 *
 * <pre>
 * Stateless (RoundRobin):  16 bytes (object header) + 4 bytes (AtomicInteger) = 20 bytes
 * Stateful (LeastUsed):    16 bytes (object header) + 8 bytes (lanes reference) = 24 bytes
 *
 * Difference: 4 bytes per strategy instance (negligible)
 * </pre>
 *
 * <p><strong>Why protected field (not private with getter):</strong>
 *
 * <p>Subclasses need direct field access in hot path ({@code selectLane()} called 100K/sec). Getter
 * adds virtual method call overhead (~1-5ns, prevents JIT inlining). Protected field: zero
 * overhead, subclass accesses directly.
 *
 * <p><strong>Thread safety (field publication):</strong>
 *
 * <p>{@code lanes} field set in {@code initialize()}, called from {@code LanedConnectionManager}
 * constructor (single-threaded). Constructor completion establishes happens-before edge (JLS §17.5)
 * → all threads see initialized {@code lanes} without additional synchronization.
 *
 * @see LaneSelectionStrategy
 * @see LeastUsedStrategy
 * @see <a href="https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html#jls-17.5">JLS §17.5
 *     - final Field Semantics</a>
 */
@FieldDefaults(level = AccessLevel.PROTECTED)
public abstract class AbstractLaneSelectionStrategy implements LaneSelectionStrategy {

  /**
   * Lane references for reading in-flight counts (protected for subclass access).
   *
   * <p><strong>NOT final (two-phase initialization):</strong> Set in {@link
   * #initialize(ConnectionLane[])} after construction. Manager guarantees {@code initialize()}
   * called exactly once before first {@code selectLane()} invocation.
   *
   * <p><strong>Why protected (not private):</strong> Subclasses access directly in hot path (e.g.,
   * {@code LeastUsedStrategy.selectLane()} scans all lanes). Direct field access: zero overhead.
   * Getter method: virtual call overhead (~1-5ns), prevents JIT inlining.
   *
   * <p><strong>Memory visibility guarantee:</strong> {@code initialize()} called from manager
   * constructor (single-threaded). Constructor completion establishes happens-before edge (JLS
   * §17.5) → all threads see initialized {@code lanes} reference without additional
   * synchronization.
   *
   * <p><strong>Array mutability:</strong> Array reference is mutable (set once in {@code
   * initialize()}), but array <em>contents</em> are immutable (lane instances created once in
   * manager constructor, never replaced). Effectively final after initialization.
   *
   * @see <a href="https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html#jls-17.5">JLS
   *     §17.5</a>
   */
  ConnectionLane[] lanes; // Not final - see class javadoc

  /**
   * Initializes strategy with lane references (two-phase initialization).
   *
   * <p><strong>Contract (enforced by manager):</strong>
   *
   * <ol>
   *   <li>Called EXACTLY ONCE by {@code LanedConnectionManager} constructor
   *   <li>Called BEFORE any {@code selectLane()} invocation
   *   <li>Called from single thread (constructor thread)
   *   <li>Completion establishes happens-before edge (JLS §17.5)
   * </ol>
   *
   * <p><strong>Why this ordering is safe (JMM guarantees):</strong>
   *
   * <p>Manager constructor sequence:
   *
   * <pre>{@code
   * 1. this.lanes = new ConnectionLane[numLanes];  // Write lanes array
   * 2. initializeLanes();                          // Populate array
   * 3. strategy.initialize(this.lanes);            // Pass reference to strategy
   * 4. Constructor returns                         // Happens-before edge
   * 5. Other threads can call getConnection()      // See initialized strategy
   * }</pre>
   *
   * <p>JMM guarantee: Constructor completion → all threads see all writes from constructor
   * (including {@code strategy} field assignment). No {@code volatile} needed. CPU cache coherence
   * (MESI protocol) invalidates stale cache lines in other cores.
   *
   * <p><strong>Why validation needed (defensive programming):</strong>
   *
   * <p>Manager enforces contract, but strategy is public API. Custom subclasses might violate
   * contract (call {@code initialize(null)}, call twice, etc.). Defensive check: fail fast with
   * clear error message.
   *
   * @param lanes connection lanes created by manager (must not be null or empty)
   * @throws IllegalArgumentException if lanes is null or empty
   * @see <a href="https://docs.oracle.com/javase/specs/jls/se25/html/jls-17.html#jls-17.5">JLS
   *     §17.5 - final Field Semantics</a>
   */
  @Override
  public void initialize(@NonNull final ConnectionLane[] lanes) {
    if (lanes.length == 0) {
      throw new IllegalArgumentException("Lanes array cannot be empty");
    }
    this.lanes = lanes;
  }

  /**
   * Increments in-flight count when connection acquired (lifecycle tracking).
   *
   * <p><strong>When called:</strong> {@code LanedConnectionManager.getConnection()} after {@code
   * selectLane()} returns lane index. Tracks number of active connections per lane (in-flight
   * commands).
   *
   * <p><strong>Why atomic increment (thread safety):</strong>
   *
   * <p>Multiple threads concurrently call {@code getConnection()} → multiple concurrent increments
   * on same lane. {@code AtomicInteger.incrementAndGet()} ensures atomicity:
   *
   * <pre>
   * x86_64 assembly:
   *   LOCK XADD [count], 1    // Atomic fetch-and-add
   * </pre>
   *
   * <p>{@code LOCK} prefix: Locks memory bus (exclusive access to cache line), prevents lost
   * updates from concurrent threads.
   *
   * <p><strong>Performance cost (hot path):</strong>
   *
   * <pre>
   * Uncontended:  ~5-10ns  (single atomic operation, L1 cache hit)
   * Contended:    ~50-100ns (cache line bouncing between cores, MESI invalidation)
   * </pre>
   *
   * <p>At 100K req/sec with 8 lanes: ~12.5K increments/sec per lane. Low contention (different
   * threads select different lanes via round-robin/least-used). Uncontended case dominates (~5-10ns
   * overhead per request).
   *
   * <p><strong>Why track in-flight count (not selection count):</strong>
   *
   * <p>In-flight count = CURRENT load (commands queued right now). Selection count = HISTORICAL
   * metric (how many times selected in past). LeastUsedStrategy needs CURRENT load to avoid queuing
   * behind slow commands.
   *
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   */
  @Override
  public void onConnectionAcquired(final int laneIndex) {
    lanes[laneIndex].getInFlightCount().incrementAndGet();
  }

  /**
   * Decrements in-flight count when connection released (lifecycle tracking).
   *
   * <p><strong>When called:</strong> {@code LanedConnectionWrapper.close()} when application code
   * closes connection. Decrements in-flight count (connection no longer active).
   *
   * <p><strong>Thread safety (lock-free CAS loop):</strong>
   *
   * <p>Multiple threads can concurrently close connections on the same lane. CAS (Compare-And-Swap)
   * ensures atomicity without locks:
   *
   * <pre>
   * x86_64 assembly:
   *   1. MOV EAX, [count]           // Load current value
   *   2. CMP EAX, 0                 // Check if zero
   *   3. JLE skip                   // Jump if ≤ 0
   *   4. LEA ECX, [EAX - 1]         // Compute new value (current - 1)
   *   5. LOCK CMPXCHG [count], ECX  // Atomic compare-and-swap
   *   6. JNE retry                  // Retry if another thread modified count
   *   skip:
   * </pre>
   *
   * <p>{@code LOCK CMPXCHG} (Compare-Exchange):
   *
   * <ul>
   *   <li>Locks memory bus (exclusive access to cache line)
   *   <li>Compares {@code [count]} with {@code EAX}
   *   <li>If equal: writes {@code ECX} to {@code [count]} (atomic swap)
   *   <li>If not equal: loads current {@code [count]} into {@code EAX} (retry)
   * </ul>
   *
   * <p>Cost: ~5-10ns (uncontended), ~50-100ns (contended, cache line bouncing). Uncontended is
   * common (different threads close on different lanes).
   *
   * <p><strong>Underflow protection (prevents negative counts):</strong>
   *
   * <p>If {@code close()} called multiple times (connection.close() is idempotent in Lettuce):
   *
   * <pre>
   * Scenario:
   *   Thread A: wrapper.close() → count = 1 → decrement → count = 0
   *   Thread A: wrapper.close() → count = 0 → SKIP (no decrement)
   *   Thread B: wrapper.close() → count = 0 → SKIP (no decrement)
   * </pre>
   *
   * <p>Protection: CAS loop checks {@code current <= 0} before decrement. If count already zero,
   * returns immediately (no CAS attempt). This prevents:
   *
   * <ul>
   *   <li>Negative counts (would make lane appear "least used" incorrectly)
   *   <li>Integer underflow (0 - 1 = -1, wraps to -2,147,483,648 in two's complement)
   *   <li>ABA problem (count goes 0 → -1 → 0, CAS succeeds incorrectly)
   * </ul>
   *
   * <p><strong>Performance cost (close path, not hot path):</strong>
   *
   * <p>Close path happens ~1/minute (long-lived connections). CAS loop costs:
   *
   * <ul>
   *   <li>Uncontended: ~5-10ns (single CAS attempt)
   *   <li>Contended (10 threads): ~50-100ns (avg 2-3 retries)
   *   <li>Heavily contended (100 threads): ~200-500ns (avg 10-20 retries, cache line ping-pong)
   * </ul>
   *
   * <p>Negligible compared to {@code close()} cost: TCP FIN (1-2 RTT = ~1-5ms), Netty channel
   * cleanup (~10-50μs), FD release (~1-5μs). CAS overhead: &lt;0.01% of total close cost.
   *
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   */
  @Override
  public void onConnectionReleased(final int laneIndex) {
    final var count = lanes[laneIndex].getInFlightCount();

    // CAS loop: decrement only if count > 0 (prevents negative counts from duplicate close())
    var current = count.get();
    while (current > 0) {
      if (count.compareAndSet(current, current - 1)) {
        return; // Success: count decremented atomically, exit
      }
      current = count.get(); // CAS failed: another thread modified count, reload and retry
    }
  }

  /**
   * Returns in-flight count for specific lane (metrics/monitoring).
   *
   * <p><strong>Use case:</strong> Metrics collection, monitoring dashboards, debugging. NOT for
   * production hot path (strategies read counts directly via {@code lanes} field for zero
   * overhead).
   *
   * <p><strong>Thread safety:</strong> Volatile read (see {@link #onConnectionAcquired(int)} for
   * details). May return stale value if concurrent increment/decrement in progress.
   *
   * <p><strong>Observability (metrics export):</strong>
   *
   * <p>All strategies expose in-flight counts via this method. Enables:
   *
   * <ul>
   *   <li>Prometheus metrics: {@code redis_lane_inflight_count{lane="0"}}
   *   <li>Health checks: Alert if lane stuck at high count (HOL blocking detected)
   *   <li>Load distribution visualization (Grafana dashboard)
   * </ul>
   *
   * @param laneIndex lane index (0-based, range [0, numLanes-1])
   * @return current in-flight count (may be stale)
   */
  @Override
  public int getInFlightCount(final int laneIndex) {
    return lanes[laneIndex].getInFlightCount().get();
  }
}
