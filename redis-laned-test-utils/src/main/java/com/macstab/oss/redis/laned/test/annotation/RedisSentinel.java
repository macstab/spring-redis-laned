/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.test.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import com.macstab.oss.redis.laned.test.condition.DisabledOnNonLinuxHost;
import com.macstab.oss.redis.laned.test.extension.SentinelContainerExtension;

/**
 * Starts a full Redis Sentinel cluster for integration tests using Testcontainers.
 *
 * <p><strong>Cluster Architecture:</strong>
 *
 * <ul>
 *   <li>1 Redis master
 *   <li>N Redis replicas (configurable, default: 2)
 *   <li>M Sentinel monitors (configurable, default: 3)
 *   <li>All connected via Docker network
 * </ul>
 *
 * <p><strong>Platform Requirements:</strong>
 *
 * <p>Redis Sentinel with Testcontainers requires native Docker networking (host network mode),
 * which only works on:
 *
 * <ul>
 *   <li>✅ Linux host
 *   <li>✅ Dev containers / CI containers (even on macOS/Windows host)
 *   <li>❌ macOS host (Docker Desktop uses VM)
 *   <li>❌ Windows host (Docker Desktop uses WSL2/Hyper-V)
 * </ul>
 *
 * <p><strong>Auto-Disabled:</strong> This annotation includes {@link DisabledOnNonLinuxHost}, so
 * tests are automatically skipped on macOS/Windows hosts.
 *
 * <p><strong>Basic Usage:</strong>
 *
 * <pre>{@code
 * @RedisSentinel
 * class SentinelFailoverTest {
 *
 *   @Test
 *   void testFailover(RedisSentinelInfo info) {
 *     // Sentinel cluster running
 *     // Master: info.getMasterHost():info.getMasterPort()
 *     // Sentinels: info.getSentinelNodes()
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Custom Configuration:</strong>
 *
 * <pre>{@code
 * @RedisSentinel(
 *   masterName = "ha-master",
 *   replicas = 3,
 *   sentinels = 5,
 *   quorum = 3
 * )
 * class HighAvailabilityTest {
 *   // 1 master + 3 replicas + 5 sentinels (quorum=3)
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see SentinelContainerExtension
 * @see DisabledOnNonLinuxHost
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(SentinelContainerExtension.class)
@DisabledOnNonLinuxHost(
    "Redis Sentinel tests require native Docker networking (Linux host or dev container)")
public @interface RedisSentinel {

  /**
   * Redis Docker image version tag.
   *
   * <p>Default: {@code "7.4"} (latest stable)
   *
   * @return Docker image tag
   */
  String version() default "7.4";

  /**
   * Sentinel master name (used in Sentinel configuration).
   *
   * <p>Default: {@code "mymaster"}
   *
   * @return master name
   */
  String masterName() default "mymaster";

  /**
   * Number of Redis replicas (slaves).
   *
   * <p>Default: {@code 2} (1 master + 2 replicas = 3 data nodes)
   *
   * <p>Minimum: {@code 1} (at least one replica for HA)
   *
   * @return replica count
   */
  int replicas() default 2;

  /**
   * Number of Sentinel monitor instances.
   *
   * <p>Default: {@code 3} (standard HA setup)
   *
   * <p>Minimum: {@code 1} (but 3 recommended for real quorum)
   *
   * @return sentinel count
   */
  int sentinels() default 3;

  /**
   * Quorum for Sentinel failover decisions.
   *
   * <p>Default: {@code 2} (majority of 3 sentinels)
   *
   * <p>Formula: {@code quorum = (sentinels / 2) + 1} (majority)
   *
   * @return quorum value
   */
  int quorum() default 2;
}
