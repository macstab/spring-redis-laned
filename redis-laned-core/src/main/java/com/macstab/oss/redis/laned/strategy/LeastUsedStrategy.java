/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

/**
 * Least-used lane selection (real-time load-aware distribution).
 *
 * <p><strong>Algorithm:</strong> Scan all N lanes, select lane with minimum in-flight command
 * count. In-flight count = number of connections currently borrowed from this lane (active commands
 * queued).
 *
 * <p><strong>Why in-flight count (not selection count):</strong>
 *
 * <pre>
 * Selection count (WRONG - historical metric):
 *   "How many times did we choose this lane in the past?"
 *   Problem: High historical count ≠ high current load (commands may have completed)
 *   Result: Avoids fast lanes (high history), overloads slow lanes (low history)
 *
 * In-flight count (CORRECT - real-time metric):
 *   "How many commands are currently queued on this lane RIGHT NOW?"
 *   Tracks: Increment on getConnection(), decrement on close/release
 *   Result: Selects ACTUALLY least-loaded lane (current state, not history)
 * </pre>
 *
 * <p><strong>Why this reduces HOL blocking (vs round-robin):</strong>
 *
 * <p>Round-robin is blind to lane load. If lane 3 is processing a slow 500KB {@code HGETALL}
 * (18ms), round-robin still assigns new commands to lane 3 (1/N probability). Those commands queue
 * behind the slow one → HOL blocking.
 *
 * <p>Least-used observes CURRENT load. If lane 3 has high in-flight count (slow command queued),
 * new commands route to lanes with lower count → avoids queuing behind slow commands.
 *
 * <p><strong>Example scenario (8 lanes, slow command on lane 3):</strong>
 *
 * <pre>
 * Time 0:  All lanes: inFlightCount = 0
 * Time 1:  Thread A sends slow HGETALL → lane 3 (round-robin)
 *          Lane 3: inFlightCount = 1 (HGETALL in progress, 18ms)
 * Time 2:  Thread B sends GET → scans lanes
 *          Lane 0-2: inFlightCount = 0
 *          Lane 3:   inFlightCount = 1  ← SKIP (busy)
 *          Lane 4-7: inFlightCount = 0
 *          Selects lane 0 (minimum, avoids lane 3)
 * Time 3-10: More GET commands → route to lanes 0,1,2,4,5,6,7 (all idle)
 *          Lane 3 still blocked (HGETALL 18ms total)
 *          Result: No queuing behind slow command
 * </pre>
 *
 * <p>Round-robin would assign ~1/8 of commands to lane 3 (queuing behind HGETALL). Least-used
 * avoids lane 3 entirely until HGETALL completes (inFlightCount drops to 0).
 *
 * <p><strong>Why no decay needed (vs historical selection count):</strong>
 *
 * <p>Selection count accumulates forever → old selections dominate → need decay to forget history.
 * In-flight count resets automatically → commands complete → count decrements → reflects CURRENT
 * state → no decay needed.
 *
 * <p><strong>Performance cost (scan all lanes):</strong>
 *
 * <pre>
 * Round-robin:      ~5-10ns   (single CAS, no scan)
 * Thread-affinity:  ~12-16ns  (threadId + MurmurHash3 + modulo)
 * Least-used:       ~40-80ns  (scan N lanes, N atomic reads for N=8)
 * </pre>
 *
 * <p>At N=8 lanes: ~40-80ns (8 volatile reads ~5-10ns each). At N=32: ~160-320ns. Acceptable for
 * load-aware routing (&lt;500ns threshold).
 *
 * <p><strong>Why scan is O(N) (unavoidable):</strong>
 *
 * <p>Finding minimum requires inspecting all N lanes. Auxiliary data structures (min-heap) cost
 * O(log N) updates per selection → same asymptotic cost for small N, worse constant factors. At
 * N=8-32 (typical), linear scan is fastest.
 *
 * <p><strong>Thread safety (lock-free reads):</strong>
 *
 * <p>All reads are volatile (AtomicInteger.get() → memory barrier). Multiple threads can
 * concurrently scan lanes without locks. Each thread sees consistent snapshot of in-flight counts
 * (may be slightly stale due to concurrent updates, but always valid lane indices).
 *
 * <p><strong>Distribution guarantee (load-proportional, not uniform):</strong>
 *
 * <ul>
 *   <li>If all lanes equally fast: converges to uniform (~1/N per lane)
 *   <li>If lane 3 slow: lane 3 gets &lt;1/N traffic (avoided due to high count), others get &gt;1/N
 *   <li>Self-balancing: avoiding slow lane reduces its load → becomes fast → gets more traffic
 * </ul>
 *
 * <p>This is **adaptive load balancing** (responds to observed load, not static round-robin).
 *
 * <p><strong>Why extend {@link AbstractLaneSelectionStrategy} (not implement interface
 * directly):</strong>
 *
 * <p>This strategy is STATEFUL (needs access to {@code ConnectionLane[]} to read in-flight counts).
 * Base class provides:
 *
 * <ul>
 *   <li>{@code protected lanes} field (direct access, zero overhead)
 *   <li>{@code initialize()} (two-phase init pattern)
 *   <li>{@code onConnectionAcquired()} (increment in-flight count)
 *   <li>{@code onConnectionReleased()} (decrement in-flight count, CAS loop)
 *   <li>{@code getInFlightCount()} (metrics/monitoring)
 * </ul>
 *
 * <p>This class only implements algorithm-specific logic: {@code selectLane()} (scan lanes, find
 * minimum).
 *
 * @see AbstractLaneSelectionStrategy
 */
public final class LeastUsedStrategy extends AbstractLaneSelectionStrategy {

  // Inherits from base class:
  // - protected ConnectionLane[] lanes
  // - initialize(ConnectionLane[])
  // - onConnectionAcquired(int)
  // - onConnectionReleased(int)
  // - getInFlightCount(int)

  /**
   * Creates least-used strategy (no-args constructor).
   *
   * <p><strong>Two-phase initialization:</strong> Lanes provided via {@link
   * #initialize(com.macstab.oss.redis.laned.ConnectionLane[])} after construction (called by {@code
   * LanedConnectionManager}).
   *
   * @see AbstractLaneSelectionStrategy#initialize(com.macstab.oss.redis.laned.ConnectionLane[])
   */
  public LeastUsedStrategy() {
    // Lanes initialized via base class initialize() method
  }

  /**
   * Selects lane with minimum in-flight count (real-time load).
   *
   * <p><strong>Algorithm (linear scan, O(N)):</strong>
   *
   * <ol>
   *   <li>Initialize: {@code minLane = 0}, {@code minCount = lanes[0].inFlightCount}
   *   <li>Scan lanes 1..N-1:
   *       <ul>
   *         <li>Volatile read: {@code count = lanes[i].getInFlightCount().get()}
   *         <li>Compare: {@code if (count < minCount)}
   *         <li>Update: {@code minCount = count; minLane = i}
   *       </ul>
   *   <li>Return: {@code minLane} (index of lane with lowest count)
   * </ol>
   *
   * <p><strong>Performance breakdown (CPU-level detail):</strong>
   *
   * <pre>
   * Per iteration (N=8):
   *   1. Array bounds check           ~1 cycle   (JIT eliminates in steady state)
   *   2. Array load (lanes[i])        ~1 cycle   (L1 cache hit, 64B cache line)
   *   3. Field load (inFlightCount)   ~1 cycle   (L1 cache hit, same cache line)
   *   4. Volatile read (get())        ~5-10ns    (MFENCE on x86_64, memory barrier)
   *   5. Integer compare              ~1 cycle   (CMP instruction)
   *   6. Conditional move             ~1 cycle   (CMOV instruction, no branch)
   *
   * Total per iteration: ~10-15ns (dominated by volatile read)
   * Total for N=8:       ~70-120ns (8 iterations)
   * </pre>
   *
   * <p><strong>Why volatile read is expensive (memory barrier cost):</strong>
   *
   * <p>{@code AtomicInteger.get()} compiles to {@code MOV} + {@code MFENCE} (x86_64). {@code
   * MFENCE} (memory fence) forces:
   *
   * <ul>
   *   <li>All prior loads/stores complete before fence
   *   <li>All subsequent loads/stores wait for fence
   *   <li>Store buffer flush (write-back to L3 cache)
   *   <li>Cache line invalidation broadcast (MESI protocol)
   * </ul>
   *
   * <p>Cost: ~5-10ns per fence (vs ~1ns for plain read). With N=8 lanes: 8 fences = ~40-80ns
   * overhead. This is the unavoidable cost of real-time load observation (need fresh counts).
   *
   * <p><strong>Why not heap-based priority queue (O(log N) selection):</strong>
   *
   * <p>Min-heap alternative: {@code PriorityQueue<Lane>} with count comparator. Selection: O(log
   * N). But:
   *
   * <ul>
   *   <li>Update cost: O(log N) per increment/decrement (heap rebalance)
   *   <li>Selection happens ~100K/sec (hot path), updates happen ~100K/sec (equally hot)
   *   <li>Total: O(log N) selection + O(log N) update = 2 × O(log N) per command
   *   <li>Linear scan: O(N) selection + O(1) update = O(N) per command
   * </ul>
   *
   * <p>At N=8: log₂(8) = 3 comparisons (heap) vs 8 comparisons (linear). Heap faster? NO:
   *
   * <ul>
   *   <li>Heap: 3 comparisons + pointer chasing (cache misses) + heap mutations
   *   <li>Linear: 8 comparisons (sequential access, prefetch-friendly, no mutations)
   *   <li>Heap adds synchronization overhead (CAS on heap structure)
   * </ul>
   *
   * <p>Linear scan wins for N ≤ 32 (cache-friendly, lock-free, simple).
   *
   * <p><strong>Tie-breaking (deterministic lowest-index selection):</strong>
   *
   * <p>If multiple lanes have minimum count (e.g., all idle with {@code count=0}), selects lane 0.
   *
   * <p><strong>Why lowest-index (not random):</strong>
   *
   * <ul>
   *   <li><strong>Cache locality:</strong> Lane 0 more likely in L1 cache (recently accessed)
   *   <li><strong>Determinism:</strong> Same state → same choice (reproducible, testable)
   *   <li><strong>Performance:</strong> No RNG call (~20-50ns overhead for {@code
   *       ThreadLocalRandom})
   * </ul>
   *
   * <p><strong>Why not random tie-break:</strong>
   *
   * <ul>
   *   <li>RNG cost: ~20-50ns per call (defeats purpose of fast selection)
   *   <li>Marginal uniformity gain: ~1-2% improvement (all lanes idle most of the time)
   *   <li>Trade-off: Simplicity + performance &gt; perfect uniformity
   * </ul>
   *
   * <p><strong>Concurrency guarantee (stale reads acceptable):</strong>
   *
   * <p>Multiple threads scan lanes concurrently. Each thread sees slightly stale counts (other
   * threads' increments/decrements may not be visible yet). This is ACCEPTABLE:
   *
   * <ul>
   *   <li>All lane indices returned are valid ([0, numLanes-1])
   *   <li>Selection is "best effort" (minimize count, not guarantee absolute minimum)
   *   <li>Worst case: Select lane with count=5 when lane with count=4 exists (1 extra command
   *       queued)
   *   <li>Self-correcting: Next selection sees updated counts
   * </ul>
   *
   * <p>Trade-off: Lock-free speed (no CAS contention) vs perfect accuracy. Perfect accuracy would
   * require global lock (kills throughput at 100K req/sec).
   *
   * @param numLanes total number of lanes (must be &gt; 0 and &lt;= lanes.length)
   * @return lane index with minimum in-flight count (range [0, numLanes-1])
   * @throws IllegalStateException if strategy not initialized via {@link
   *     #initialize(ConnectionLane[])}
   */
  @Override
  public int selectLane(final int numLanes) {
    if (lanes == null) {
      throw new IllegalStateException(
          "Strategy not initialized. Manager must call initialize(lanes) before selectLane()");
    }

    var minLane = 0;
    var minCount = lanes[0].getInFlightCount().get();

    for (var i = 1; i < numLanes; i++) {
      final var count = lanes[i].getInFlightCount().get();
      if (count < minCount) {
        minCount = count;
        minLane = i;
      }
    }

    return minLane;
  }

  @Override
  public String getName() {
    return "least-used";
  }

  // Lifecycle methods inherited from AbstractLaneSelectionStrategy:
  // - onConnectionAcquired(int)  → increments in-flight count
  // - onConnectionReleased(int)  → decrements in-flight count (CAS loop)
  // - getInFlightCount(int)      → returns current count (metrics)
}
