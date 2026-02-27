/**
 * Shared infrastructure for JMH benchmarks.
 *
 * <p><strong>Components:</strong>
 *
 * <ul>
 *   <li>{@link com.macstab.oss.redis.laned.benchmarks.support.RedisTestContainer} - Singleton
 *       container manager
 *   <li>{@link com.macstab.oss.redis.laned.benchmarks.support.BackgroundLoadGenerator} - Sustained
 *       HOL load
 *   <li>{@link com.macstab.oss.redis.laned.benchmarks.support.WorkloadPatterns} - Reusable workload
 *       patterns
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
package com.macstab.oss.redis.laned.benchmarks.support;
