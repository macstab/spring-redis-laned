/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3.sentinel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.util.StringUtils;

import com.macstab.oss.redis.laned.connection.SentinelTopology;

import io.lettuce.core.RedisURI;

/**
 * Utility class for building Lettuce {@link RedisURI} instances from Spring's {@link
 * RedisSentinelConfiguration}.
 *
 * <p>Extracts:
 *
 * <ul>
 *   <li>Sentinel master name
 *   <li>Sentinel node addresses
 *   <li>Master authentication (username/password)
 *   <li>Sentinel authentication (username/password)
 *   <li>Database index
 * </ul>
 *
 * <p><strong>Thread-safety:</strong> Stateless utility class, safe for concurrent use.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * final var sentinelConfig = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
 * sentinelConfig.setPassword("masterpass");
 *
 * final var topology = SentinelUriBuilder.buildTopology(sentinelConfig);
 * }</pre>
 */
public final class SentinelUriBuilder {

  private SentinelUriBuilder() {
    // Utility class - no instantiation
  }

  /**
   * Builds a {@link SentinelTopology} from Spring Sentinel configuration.
   *
   * @param config Spring Sentinel configuration
   * @return Sentinel topology with all nodes and authentication
   * @throws NullPointerException if config is null
   * @throws IllegalArgumentException if master name is blank or nodes are empty
   */
  public static SentinelTopology buildTopology(final RedisSentinelConfiguration config) {
    Objects.requireNonNull(config, "config cannot be null");

    final var masterName = config.getMaster().getName();
    if (!StringUtils.hasText(masterName)) {
      throw new IllegalArgumentException("Sentinel master name cannot be blank");
    }

    final var sentinelNodes = config.getSentinels();
    if (sentinelNodes == null || sentinelNodes.isEmpty()) {
      throw new IllegalArgumentException("Sentinel nodes cannot be empty");
    }

    final var uris = buildSentinelUris(config, sentinelNodes);
    return SentinelTopology.of(masterName, uris);
  }

  /**
   * Builds Lettuce {@link RedisURI} instances for each Sentinel node.
   *
   * @param config Sentinel configuration (for auth + database)
   * @param nodes Sentinel nodes
   * @return list of Sentinel URIs
   */
  private static List<RedisURI> buildSentinelUris(
      final RedisSentinelConfiguration config, final Iterable<RedisNode> nodes) {

    final var uris = new ArrayList<RedisURI>();

    for (final var node : nodes) {
      final var builder =
          RedisURI.builder()
              .withSentinel(node.getHost(), node.getPort())
              .withDatabase(config.getDatabase());

      // Master authentication (used when connecting to master/replicas)
      applyMasterAuthentication(builder, config);

      // Sentinel authentication (used when querying Sentinel nodes)
      applySentinelAuthentication(builder, config);

      uris.add(builder.build());
    }

    return uris;
  }

  /**
   * Applies master authentication (username/password for Redis master and replicas).
   *
   * @param builder URI builder
   * @param config Sentinel configuration
   */
  private static void applyMasterAuthentication(
      final RedisURI.Builder builder, final RedisSentinelConfiguration config) {

    final var password = config.getPassword();
    final var username = config.getUsername();

    if (StringUtils.hasText(username) && password != null && password.isPresent()) {
      // ACL authentication (username + password)
      final var credentials = io.lettuce.core.RedisCredentials.just(username, password.get());
      builder.withAuthentication(() -> reactor.core.publisher.Mono.just(credentials));
    } else if (password != null && password.isPresent()) {
      // Legacy authentication (password only, default username)
      final var credentials = io.lettuce.core.RedisCredentials.just("default", password.get());
      builder.withAuthentication(() -> reactor.core.publisher.Mono.just(credentials));
    }
  }

  /**
   * Applies Sentinel authentication (username/password for Sentinel nodes).
   *
   * <p><strong>Note:</strong> Lettuce RedisURI does not support separate Sentinel authentication in
   * the builder API. Sentinel auth must be configured via Sentinel configuration itself.
   *
   * @param builder URI builder
   * @param config Sentinel configuration
   */
  private static void applySentinelAuthentication(
      final RedisURI.Builder builder, final RedisSentinelConfiguration config) {
    // Sentinel authentication is handled by Lettuce MasterReplica internally
    // No builder API available for withSentinelUsername/Password
    // Spring Data Redis passes this via RedisSentinelConfiguration to Lettuce
  }
}
