/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3;

import java.util.Optional;

import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;

import com.macstab.oss.redis.laned.LanedConnectionManager;
import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.RedisCodec;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring Boot 3.x connection factory using laned connections.
 *
 * <p>Extension of {@link LettuceConnectionFactory} that uses {@link LanedConnectionManager} for
 * standalone, sentinel, and enterprise Redis topologies.
 *
 * <p><strong>Supported topologies:</strong>
 *
 * <ul>
 *   <li><strong>Redis Standalone</strong> - uses laned connections
 *   <li><strong>Redis Sentinel</strong> - uses laned connections
 *   <li><strong>Redis Enterprise</strong> (proxy mode) - uses laned connections
 *   <li><strong>Redis Cluster</strong> - falls back to default ClusterConnectionProvider
 * </ul>
 *
 * @see LanedConnectionManager
 * @see LanedLettuceConnectionProvider
 */
@Slf4j
public final class LanedLettuceConnectionFactory extends LettuceConnectionFactory {

  private final int numLanes;
  private final com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy strategy;
  private final Optional<LanedRedisMetrics> metrics;
  @Getter private LanedLettuceConnectionProvider lanedProvider;

  /**
   * Creates a laned connection factory for standalone Redis (no metrics, default strategy).
   *
   * @param standaloneConfig standalone configuration
   * @param clientConfig Lettuce client configuration
   * @param numLanes number of lanes (recommended: 8)
   * @throws IllegalArgumentException if standaloneConfig or clientConfig is null, or numLanes is
   *     out of range [1, 64]
   */
  public LanedLettuceConnectionFactory(
      final RedisStandaloneConfiguration standaloneConfig,
      final LettuceClientConfiguration clientConfig,
      final int numLanes) {
    this(
        standaloneConfig,
        clientConfig,
        numLanes,
        new com.macstab.oss.redis.laned.strategy.RoundRobinStrategy(),
        Optional.empty());
  }

  /**
   * Creates a laned connection factory for standalone Redis with strategy and metrics.
   *
   * @param standaloneConfig standalone configuration
   * @param clientConfig Lettuce client configuration
   * @param numLanes number of lanes (recommended: 8)
   * @param strategy lane selection strategy
   * @param metrics metrics collector (optional, defaults to NOOP if not present)
   * @throws IllegalArgumentException if standaloneConfig or clientConfig is null, or numLanes is
   *     out of range [1, 64]
   */
  public LanedLettuceConnectionFactory(
      final RedisStandaloneConfiguration standaloneConfig,
      final LettuceClientConfiguration clientConfig,
      final int numLanes,
      final com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy strategy,
      final Optional<LanedRedisMetrics> metrics) {
    super(standaloneConfig, clientConfig);
    validateNumLanes(numLanes);
    this.numLanes = numLanes;
    this.strategy =
        strategy != null ? strategy : new com.macstab.oss.redis.laned.strategy.RoundRobinStrategy();
    this.metrics = metrics;
  }

  /**
   * Creates a laned connection factory for Redis Sentinel (no metrics, default strategy).
   *
   * @param sentinelConfig Sentinel configuration
   * @param clientConfig Lettuce client configuration
   * @param numLanes number of lanes (recommended: 8)
   * @throws IllegalArgumentException if sentinelConfig or clientConfig is null, or numLanes is out
   *     of range [1, 64]
   */
  public LanedLettuceConnectionFactory(
      final RedisSentinelConfiguration sentinelConfig,
      final LettuceClientConfiguration clientConfig,
      final int numLanes) {
    this(
        sentinelConfig,
        clientConfig,
        numLanes,
        new com.macstab.oss.redis.laned.strategy.RoundRobinStrategy(),
        Optional.empty());
  }

  /**
   * Creates a laned connection factory for Redis Sentinel with strategy and metrics.
   *
   * @param sentinelConfig Sentinel configuration
   * @param clientConfig Lettuce client configuration
   * @param numLanes number of lanes (recommended: 8)
   * @param strategy lane selection strategy
   * @param metrics metrics collector (optional, defaults to NOOP if not present)
   * @throws IllegalArgumentException if sentinelConfig or clientConfig is null, or numLanes is out
   *     of range [1, 64]
   */
  public LanedLettuceConnectionFactory(
      final RedisSentinelConfiguration sentinelConfig,
      final LettuceClientConfiguration clientConfig,
      final int numLanes,
      final com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy strategy,
      final Optional<LanedRedisMetrics> metrics) {
    super(sentinelConfig, clientConfig);
    validateNumLanes(numLanes);
    this.numLanes = numLanes;
    this.strategy =
        strategy != null ? strategy : new com.macstab.oss.redis.laned.strategy.RoundRobinStrategy();
    this.metrics = metrics;
  }

  /**
   * Creates a laned connection factory for Redis Cluster (no metrics, default strategy).
   *
   * <p><strong>Note:</strong> Cluster mode falls back to default provider (per-shard connections).
   * Per-shard laning is planned for a future release.
   *
   * @param clusterConfig cluster configuration
   * @param clientConfig Lettuce client configuration
   * @param numLanes number of lanes (ignored for cluster - uses default provider)
   * @throws IllegalArgumentException if clusterConfig or clientConfig is null, or numLanes is out
   *     of range [1, 64]
   */
  public LanedLettuceConnectionFactory(
      final RedisClusterConfiguration clusterConfig,
      final LettuceClientConfiguration clientConfig,
      final int numLanes) {
    this(
        clusterConfig,
        clientConfig,
        numLanes,
        new com.macstab.oss.redis.laned.strategy.RoundRobinStrategy(),
        Optional.empty());
  }

  /**
   * Creates a laned connection factory for Redis Cluster with strategy and metrics.
   *
   * <p><strong>Note:</strong> Cluster mode falls back to default provider (per-shard connections).
   * Per-shard laning is planned for a future release.
   *
   * @param clusterConfig cluster configuration
   * @param clientConfig Lettuce client configuration
   * @param numLanes number of lanes (ignored for cluster - uses default provider)
   * @param strategy lane selection strategy (ignored for cluster)
   * @param metrics metrics collector (optional, defaults to NOOP if not present)
   * @throws IllegalArgumentException if clusterConfig or clientConfig is null, or numLanes is out
   *     of range [1, 64]
   */
  public LanedLettuceConnectionFactory(
      final RedisClusterConfiguration clusterConfig,
      final LettuceClientConfiguration clientConfig,
      final int numLanes,
      final com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy strategy,
      final Optional<LanedRedisMetrics> metrics) {
    super(clusterConfig, clientConfig);
    validateNumLanes(numLanes);
    this.numLanes = numLanes;
    this.strategy =
        strategy != null ? strategy : new com.macstab.oss.redis.laned.strategy.RoundRobinStrategy();
    this.metrics = metrics;
  }

  /**
   * Creates the connection provider.
   *
   * <p>For {@link RedisClient} (standalone/sentinel/enterprise), creates {@link
   * LanedConnectionManager} and wraps it in {@link LanedLettuceConnectionProvider}. For cluster
   * mode, falls back to default Spring behavior.
   */
  @Override
  protected LettuceConnectionProvider doCreateConnectionProvider(
      final AbstractRedisClient client, final RedisCodec<?, ?> codec) {

    // Cluster mode: fall back to default
    if (!(client instanceof RedisClient redisClient)) {
      if (log.isInfoEnabled()) {
        log.info("Cluster mode detected, using default connection provider");
      }
      return super.doCreateConnectionProvider(client, codec);
    }

    // Extract ReadFrom from LettuceClientConfiguration
    final Optional<io.lettuce.core.ReadFrom> readFrom = getClientConfiguration().getReadFrom();

    // Extract Sentinel topology if configured
    final Optional<com.macstab.oss.redis.laned.connection.SentinelTopology> sentinelTopology =
        extractSentinelTopology();

    // Standalone/Sentinel/Enterprise: use laned manager with metrics
    final var manager =
        new LanedConnectionManager(
            redisClient, codec, numLanes, this.strategy, metrics, null, readFrom, sentinelTopology);
    this.lanedProvider = new LanedLettuceConnectionProvider(manager);

    if (log.isInfoEnabled()) {
      log.info(
          "Created LanedLettuceConnectionProvider: lanes={}, readFrom={}, sentinel={}, metrics={}",
          numLanes,
          readFrom.map(Object::toString).orElse("none"),
          sentinelTopology.isPresent() ? "enabled" : "none",
          metrics.isPresent() ? "enabled" : "disabled");
    }

    return lanedProvider;
  }

  /**
   * Extracts Sentinel topology from configuration.
   *
   * @return Sentinel topology if configured, empty otherwise
   */
  private Optional<com.macstab.oss.redis.laned.connection.SentinelTopology>
      extractSentinelTopology() {
    if (getSentinelConfiguration() == null) {
      return Optional.empty();
    }

    try {
      final var topology =
          com.macstab.oss.redis.laned.spring3.sentinel.SentinelUriBuilder.buildTopology(
              getSentinelConfiguration());
      return Optional.of(topology);
    } catch (final Exception e) {
      log.error("Failed to build Sentinel topology, falling back to direct connection", e);
      return Optional.empty();
    }
  }

  @Override
  public void destroy() {
    if (lanedProvider != null) {
      lanedProvider.getManager().destroy();
    }
    super.destroy();
  }

  /**
   * Validates the number of lanes.
   *
   * @param lanes number of lanes to validate
   * @throws IllegalArgumentException if lanes is out of range [1, 64]
   */
  private static void validateNumLanes(final int lanes) {
    if (lanes < 1 || lanes > 64) {
      throw new IllegalArgumentException("Number of lanes must be between 1 and 64, got: " + lanes);
    }
  }
}
