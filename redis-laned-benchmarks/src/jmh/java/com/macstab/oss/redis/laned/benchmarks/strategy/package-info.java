/* (C)2026 Macstab GmbH */

/**
 * Benchmarks comparing lane selection strategies under realistic mixed workloads.
 *
 * <p><strong>Goal:</strong> Measure latency percentiles (p50/p95/p99) for RoundRobin vs
 * ThreadAffinity vs LeastUsed under HOL blocking.
 *
 * <p><strong>Included Benchmarks:</strong>
 *
 * <ul>
 *   <li>{@link com.macstab.oss.redis.laned.benchmarks.strategy.StrategyComparisonBenchmark} -
 *       Strategy comparison under mixed workload (95% fast, 5% slow)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.oss.redis.laned.benchmarks.strategy;
