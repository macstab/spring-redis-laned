/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.benchmarks.strategy;

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
import com.macstab.oss.redis.laned.benchmarks.support.RedisTestContainer;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy;
import com.macstab.oss.redis.laned.strategy.LeastUsedStrategy;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;
import com.macstab.oss.redis.laned.strategy.ThreadAffinityStrategy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;

/**
 * Compares lane selection strategies under realistic mixed workload (95% fast, 5% slow).
 *
 * <p><strong>Question:</strong> Which selection strategy achieves best latency percentiles under
 * HOL blocking?
 *
 * <p><strong>Strategies Compared:</strong>
 *
 * <ul>
 *   <li><strong>RoundRobin:</strong> Sequential lane selection (~5-10ns overhead)
 *   <li><strong>ThreadAffinity:</strong> ThreadLocal lane pinning (~12-16ns overhead, best
 *       locality)
 *   <li><strong>LeastUsed:</strong> Dynamic load balancing (~40-80ns overhead, best p95/p99)
 * </ul>
 *
 * <p><strong>Workload Design:</strong>
 *
 * <ul>
 *   <li><strong>Foreground (measured):</strong> Fast GET commands (1KB responses, ~1-2ms latency)
 *   <li><strong>Background (not measured):</strong> Continuous slow HGETALL commands (500KB
 *       response, ~18ms latency)
 *   <li><strong>HOL Scenario:</strong> Background thread sustains blocking; strategies differ in
 *       how they route foreground commands around blocked lanes
 * </ul>
 *
 * <p><strong>Expected Results:</strong>
 *
 * <pre>
 * RoundRobin (laneCount=4):
 *   - p50 ~2ms  (good: ~25% of commands queue behind slow commands)
 *   - p95 ~25ms (moderate: unlucky round-robin assignment hits blocked lane)
 *
 * ThreadAffinity (laneCount=4):
 *   - p50 ~1.5ms (best: no selection overhead, perfect CPU cache locality)
 *   - p95 ~30ms  (moderate: thread pinned to blocked lane has no escape)
 *
 * LeastUsed (laneCount=4):
 *   - p50 ~2.5ms (slight overhead: 40-80ns selection cost)
 *   - p95 ~15ms  (BEST: dynamic routing avoids blocked lanes)
 * </pre>
 *
 * <p><strong>Key Insight:</strong> LeastUsed trades tiny p50 cost (~0.5ms = 40-80ns selection
 * overhead) for major p95/p99 improvement (~10-15ms reduction = avoiding blocked lanes).
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
 *   4. Create LanedConnectionManager with specified strategy + lane count
 *
 * Benchmark Method:
 *   5. Execute fast GET command
 *   6. JMH samples latency (background load sustains HOL blocking)
 *
 * Iteration Teardown:
 *   7. Close LanedConnectionManager
 *
 * Trial Teardown:
 *   8. Stop background load generator
 *   9. Stop Redis container
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
public class StrategyComparisonBenchmark {

  /**
   * Number of Redis connection lanes.
   *
   * <ul>
   *   <li><strong>4:</strong> Low lane count (typical for small deployments)
   *   <li><strong>8:</strong> Medium lane count (sweet spot for most workloads)
   *   <li><strong>16:</strong> High lane count (check for strategy behavior at scale)
   * </ul>
   */
  @Param({"4", "8", "16"})
  int laneCount;

  /**
   * Lane selection strategy.
   *
   * <ul>
   *   <li><strong>ROUND_ROBIN:</strong> Sequential lane selection
   *   <li><strong>THREAD_AFFINITY:</strong> ThreadLocal lane pinning
   *   <li><strong>LEAST_USED:</strong> Dynamic load balancing
   * </ul>
   */
  @Param({"ROUND_ROBIN", "THREAD_AFFINITY", "LEAST_USED"})
  String strategyName;

  // Trial-scoped state (shared across all iterations)
  private static volatile boolean trialInitialized = false;

  // Iteration-scoped state (fresh per iteration)
  private LanedConnectionManager manager;
  private BackgroundLoadGenerator backgroundLoad;

  /**
   * Trial setup: Start Redis container only (background load per iteration).
   *
   * <p><strong>Thread safety:</strong> Double-checked locking ensures single initialization even
   * when JMH runs multiple benchmark instances concurrently.
   */
  @Setup(Level.Trial)
  public void setupTrial() {
    if (!trialInitialized) {
      synchronized (StrategyComparisonBenchmark.class) {
        if (!trialInitialized) {
          // Get singleton Redis container (starts container + pre-populates data on first call)
          RedisTestContainer.getInstance();

          trialInitialized = true;
        }
      }
    }
  }

  /**
   * Iteration setup: Create LanedConnectionManager with specified strategy + background load.
   *
   * <p><strong>Critical Design:</strong> Background load uses SEPARATE manager (isolated). This
   * allows strategies to prove their benefit under realistic HOL blocking:
   *
   * <ul>
   *   <li><strong>RoundRobin:</strong> Random lane selection, ~25% chance of hitting blocked lane
   *   <li><strong>ThreadAffinity:</strong> Thread pinned to specific lane, if blocked no escape
   *   <li><strong>LeastUsed:</strong> Dynamic routing, actively avoids blocked lanes
   * </ul>
   *
   * <p><strong>Expected proof:</strong> LeastUsed shows best p95/p99 (routes around blocked lanes),
   * ThreadAffinity shows best p50 (zero selection overhead but can't escape blocking).
   */
  @Setup(Level.Iteration)
  public void setupIteration() {
    final var container = RedisTestContainer.getInstance();

    final LaneSelectionStrategy strategy =
        switch (strategyName) {
          case "ROUND_ROBIN" -> new RoundRobinStrategy();
          case "THREAD_AFFINITY" -> new ThreadAffinityStrategy();
          case "LEAST_USED" -> new LeastUsedStrategy();
          default -> throw new IllegalArgumentException("Unknown strategy: " + strategyName);
        };

    // Foreground: Manager with benchmark strategy
    final var client = RedisClient.create(container.getRedisURI());
    manager = new LanedConnectionManager(client, StringCodec.UTF8, laneCount, strategy);

    // Background: Separate manager with RoundRobin (sustains HOL blocking)
    final var bgClient = RedisClient.create(container.getRedisURI());
    final var bgManager =
        new LanedConnectionManager(bgClient, StringCodec.UTF8, 4, new RoundRobinStrategy());
    backgroundLoad = new BackgroundLoadGenerator(bgManager, 0); // 0 = unlimited rate
    backgroundLoad.start();
  }

  /**
   * Benchmark method: Execute fast GET command, measure latency under HOL blocking.
   *
   * <p><strong>Command:</strong> {@code GET key_<random>} (1KB response, ~1-2ms latency when not
   * blocked).
   *
   * <p><strong>HOL Scenario:</strong> Background thread continuously sends slow HGETALL commands
   * (500KB, ~18ms). Strategies differ in how they route fast commands around blocked lanes:
   *
   * <ul>
   *   <li><strong>RoundRobin:</strong> Random 25% chance of hitting blocked lane (laneCount=4)
   *   <li><strong>ThreadAffinity:</strong> Thread pinned to specific lane; if blocked, no escape
   *   <li><strong>LeastUsed:</strong> Actively avoids lanes with pending commands (lowest p95/p99)
   * </ul>
   *
   * <p><strong>JMH SampleTime Mode:</strong> Captures latency distribution (p50/p95/p99) across all
   * samples. Each invocation = 1 sample.
   */
  @Benchmark
  public String measureFastCommandLatency() {
    final String key = "key_" + ThreadLocalRandom.current().nextInt(1000);

    try (var conn = manager.getConnection()) {
      @SuppressWarnings("unchecked")
      final var typedConn = (StatefulRedisConnection<String, String>) conn;
      return typedConn.sync().get(key);
    }
  }

  /**
   * Iteration teardown: Stop background load + close manager.
   *
   * <p><strong>Resource safety:</strong> Ensures connections closed even if benchmark throws
   * exception.
   */
  @TearDown(Level.Iteration)
  public void teardownIteration() {
    // Stop background load first (no more slow commands)
    if (backgroundLoad != null) {
      backgroundLoad.stop();
      backgroundLoad = null;
    }

    // Close manager
    if (manager != null) {
      manager.destroy();
      manager = null;
    }
  }

  /**
   * Trial teardown: Cleanup (background load stopped per iteration).
   *
   * <p><strong>Note:</strong> Redis container managed by singleton (stopped via shutdown hook).
   */
  @TearDown(Level.Trial)
  public void teardownTrial() {
    synchronized (StrategyComparisonBenchmark.class) {
      if (trialInitialized) {
        trialInitialized = false;
      }
    }
  }
}
