/* (C)2026 Macstab GmbH */

/**
 * Benchmarks measuring Head-of-Line (HOL) blocking impact.
 *
 * <p><strong>Goal:</strong> Quantify latency reduction from multi-lane architecture under realistic
 * mixed workloads (fast + slow commands).
 *
 * <p><strong>Included Benchmarks:</strong>
 *
 * <ul>
 *   <li>{@link com.macstab.oss.redis.laned.benchmarks.hol.HolImpactBenchmark} - Single-lane
 *       baseline vs multi-lane comparison (p50/p95/p99 latency)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.oss.redis.laned.benchmarks.hol;
