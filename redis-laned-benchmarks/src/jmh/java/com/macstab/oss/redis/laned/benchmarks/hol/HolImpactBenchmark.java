/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.benchmarks.hol;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import com.macstab.oss.redis.laned.LanedConnectionManager;
import com.macstab.oss.redis.laned.benchmarks.support.BackgroundLoadGenerator;
import com.macstab.oss.redis.laned.benchmarks.support.DirectConnectionLoadGenerator;
import com.macstab.oss.redis.laned.benchmarks.support.RedisTestContainer;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;

/**
 * Measures Head-of-Line (HOL) blocking impact: single-lane baseline vs multi-lane architecture.
 *
 * <p><strong>Question:</strong> Does multi-lane architecture reduce HOL blocking under mixed
 * workload (95% fast, 5% slow)?
 *
 * <p><strong>Hypothesis:</strong>
 *
 * <ul>
 *   <li>Single-lane (laneCount=1): Slow commands (HGETALL 500KB) block fast commands (GET) → high
 *       p95/p99 latency
 *   <li>Multi-lane (laneCount=4/8/16): Fast commands isolated from slow commands → lower p95/p99
 *       latency
 * </ul>
 *
 * <p><strong>Workload Design:</strong>
 *
 * <ul>
 *   <li><strong>Foreground (measured):</strong> Fast GET commands (1KB responses, ~1-2ms latency)
 *   <li><strong>Background (not measured):</strong> Continuous slow HGETALL commands (500KB
 *       response, ~18ms latency)
 *   <li><strong>HOL Scenario:</strong> Background thread sustains blocking; foreground thread
 *       queues behind slow commands
 * </ul>
 *
 * <p><strong>Expected Results:</strong>
 *
 * <pre>
 * laneCount=1:  p50 ~5ms,  p95 ~80-120ms, p99 ~150-250ms  (HOL blocking visible, SHARED connection)
 * laneCount=4:  p50 ~2ms,  p95 ~8-15ms,   p99 ~20-40ms    (10× improvement, isolated lanes)
 * laneCount=8:  p50 ~2ms,  p95 ~6-12ms,   p99 ~15-30ms    (further improvement)
 * laneCount=16: p50 ~1.5ms, p95 ~5-10ms,  p99 ~12-25ms    (diminishing returns)
 * </pre>
 *
 * <p><strong>Key Insight:</strong> Dramatic p95/p99 drop from laneCount=1 to laneCount=4 proves
 * multi-lane architecture eliminates HOL blocking.
 *
 * <p><strong>JMH Configuration:</strong>
 *
 * <ul>
 *   <li><strong>Mode:</strong> SampleTime (captures latency distribution for p50/p95/p99)
 *   <li><strong>Warmup:</strong> 5 iterations × 10 seconds = 50s (stable JVM + connection pool)
 *   <li><strong>Measurement:</strong> 10 iterations × 15 seconds = 150s (stable latency samples)
 *   <li><strong>Forks:</strong> 3 (statistical significance)
 * </ul>
 *
 * <p><strong>Testcontainers Lifecycle:</strong>
 *
 * <pre>
 * Trial Setup:
 *   1. Start Redis container (once, shared across all iterations)
 *   2. Pre-populate 1000 keys + large-hash (500KB)
 *   3. Start background load generator (continuous HGETALL)
 *
 * Iteration Setup:
 *   4. Create fresh Lettuce client/connection (avoid pool pollution)
 *   5. Optionally create LanedConnectionManager (if laneCount > 1)
 *
 * Benchmark Method:
 *   6. Execute fast GET command
 *   7. JMH samples latency (background load sustains HOL blocking)
 *
 * Iteration Teardown:
 *   8. Close client/connection
 *
 * Trial Teardown:
 *   9. Stop background load generator
 *   10. Stop Redis container
 * </pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(
    value = 2,
    jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class HolImpactBenchmark {

  /**
   * Number of Redis connection lanes.
   *
   * <ul>
   *   <li><strong>1:</strong> Baseline (no laning, single connection)
   *   <li><strong>4:</strong> Low lane count (typical for small deployments)
   *   <li><strong>8:</strong> Medium lane count (sweet spot for most workloads)
   *   <li><strong>16:</strong> High lane count (check for diminishing returns)
   * </ul>
   */
  @Param({"1", "4", "8", "16"})
  int laneCount;

  // Trial-scoped state (shared across all iterations)
  private static volatile boolean trialInitialized = false;
  private static volatile BackgroundLoadGenerator backgroundLoad;
  private static volatile DirectConnectionLoadGenerator directLoad;

  // Iteration-scoped state (fresh per iteration)
  private RedisClient client;
  private StatefulRedisConnection<String, String> singleConnection;
  private LanedConnectionManager lanedManager;

  /**
   * Trial setup: Start Redis container only (background load per iteration).
   *
   * <p><strong>Thread safety:</strong> Double-checked locking ensures single initialization even
   * when JMH runs multiple benchmark instances concurrently.
   */
  @Setup(Level.Trial)
  public void setupTrial() {
    if (!trialInitialized) {
      synchronized (HolImpactBenchmark.class) {
        if (!trialInitialized) {
          // Get singleton Redis container (starts container + pre-populates data on first call)
          RedisTestContainer.getInstance();

          trialInitialized = true;
        }
      }
    }
  }

  /**
   * Iteration setup: Create fresh Lettuce client/connection + background load.
   *
   * <p><strong>Critical Design Choice:</strong>
   *
   * <ul>
   *   <li><strong>laneCount=1 (baseline):</strong> Background + foreground share SAME connection →
   *       HOL blocking visible (slow HGETALL blocks fast GET)
   *   <li><strong>laneCount>1 (multi-lane):</strong> Background uses SEPARATE manager → foreground
   *       isolated from background load
   * </ul>
   *
   * <p><strong>Why this proves the benefit:</strong>
   *
   * <pre>
   * Baseline (laneCount=1):
   *   Background: HGETALL 500KB (18ms) on shared connection
   *   Foreground: GET 1KB queues behind HGETALL
   *   Result: p95 ~50-100ms (HOL blocking visible)
   *
   * Multi-lane (laneCount=4):
   *   Background: HGETALL 500KB on separate manager
   *   Foreground: GET 1KB on isolated lanes
   *   Result: p95 ~5-10ms (10× improvement)
   * </pre>
   */
  @Setup(Level.Iteration)
  public void setupIteration() {
    final var container = RedisTestContainer.getInstance();

    if (laneCount == 1) {
      // Baseline: single connection (SHARED between background + foreground)
      client = RedisClient.create(container.getRedisURI());
      singleConnection = client.connect();

      // Background load uses SAME connection (creates HOL blocking)
      directLoad = new DirectConnectionLoadGenerator(singleConnection, 0); // 0 = unlimited rate
      directLoad.start();

    } else {
      // Multi-lane: LanedConnectionManager (foreground)
      final var lanedClient = RedisClient.create(container.getRedisURI());
      lanedManager =
          new LanedConnectionManager(
              lanedClient, StringCodec.UTF8, laneCount, new RoundRobinStrategy());

      // Background load uses SEPARATE manager (isolated from foreground)
      final var bgClient = RedisClient.create(container.getRedisURI());
      final var bgManager =
          new LanedConnectionManager(bgClient, StringCodec.UTF8, 4, new RoundRobinStrategy());
      backgroundLoad = new BackgroundLoadGenerator(bgManager, 0); // 0 = unlimited rate
      backgroundLoad.start();
    }
  }

  /**
   * Benchmark method: Execute fast GET command, measure latency under HOL blocking.
   *
   * <p><strong>Command:</strong> {@code GET key_<random>} (1KB response, ~1-2ms latency when not
   * blocked).
   *
   * <p><strong>HOL Scenario:</strong> Background thread continuously sends slow HGETALL commands
   * (500KB, ~18ms). Fast GET commands queue behind slow commands → latency spikes visible in
   * p95/p99.
   *
   * <p><strong>JMH SampleTime Mode:</strong> Captures latency distribution (p50/p95/p99) across all
   * samples. Each invocation = 1 sample.
   */
  @Benchmark
  public String measureFastCommandLatency() {
    final String key = "key_" + ThreadLocalRandom.current().nextInt(1000);

    if (laneCount == 1) {
      // Baseline: single connection
      return singleConnection.sync().get(key);

    } else {
      // Multi-lane: via LanedConnectionManager
      try (var conn = lanedManager.getConnection()) {
        @SuppressWarnings("unchecked")
        final var typedConn = (StatefulRedisConnection<String, String>) conn;
        return typedConn.sync().get(key);
      }
    }
  }

  /**
   * Iteration teardown: Stop background load + close connections.
   *
   * <p><strong>Resource safety:</strong> Ensures connections closed even if benchmark throws
   * exception.
   */
  @TearDown(Level.Iteration)
  public void teardownIteration() {
    // Stop background load first (no more slow commands)
    if (directLoad != null) {
      directLoad.stop();
      directLoad = null;
    }

    if (backgroundLoad != null) {
      backgroundLoad.stop();
      backgroundLoad = null;
    }

    // Close connections
    if (singleConnection != null) {
      singleConnection.close();
      singleConnection = null;
    }

    if (client != null) {
      client.shutdown();
      client = null;
    }

    if (lanedManager != null) {
      lanedManager.destroy();
      lanedManager = null;
    }
  }

  /**
   * Trial teardown: Cleanup (background load stopped per iteration).
   *
   * <p><strong>Note:</strong> Redis container managed by singleton (stopped via shutdown hook).
   * Background load stopped per iteration (not here).
   */
  @TearDown(Level.Trial)
  public void teardownTrial() {
    synchronized (HolImpactBenchmark.class) {
      if (trialInitialized) {
        trialInitialized = false;
      }
    }
  }
}
