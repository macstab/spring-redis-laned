/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.connection;

import java.util.List;
import java.util.Objects;

import io.lettuce.core.RedisURI;
import lombok.Value;

/**
 * Immutable value object representing Redis Sentinel topology information.
 *
 * <p>Contains the master name and Sentinel node URIs required for {@link
 * io.lettuce.core.masterreplica.MasterReplica#connect(io.lettuce.core.RedisClient,
 * io.lettuce.core.codec.RedisCodec, RedisURI)} when using Sentinel mode with read-from-replica
 * routing.
 *
 * <p><strong>Thread-safety:</strong> Immutable, safe for concurrent access.
 *
 * <p><strong>Validation:</strong> Factory method ensures master name is non-null and sentinel nodes
 * list is non-empty.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * final var topology = SentinelTopology.of(
 *     "mymaster",
 *     List.of(
 *         RedisURI.create("redis-sentinel://sentinel1:26379"),
 *         RedisURI.create("redis-sentinel://sentinel2:26379")
 *     )
 * );
 * }</pre>
 *
 * @see io.lettuce.core.masterreplica.MasterReplica
 */
@Value
public class SentinelTopology {

  /** Sentinel master name (e.g., "mymaster"). */
  String masterName;

  /** Sentinel node URIs. Immutable defensive copy. */
  List<RedisURI> sentinelNodes;

  /**
   * Private constructor. Use {@link #of(String, List)} factory method.
   *
   * @param masterName master name
   * @param sentinelNodes sentinel node URIs
   */
  private SentinelTopology(final String masterName, final List<RedisURI> sentinelNodes) {
    this.masterName = masterName;
    this.sentinelNodes = sentinelNodes;
  }

  /**
   * Creates a validated SentinelTopology instance.
   *
   * <p>Validates:
   *
   * <ul>
   *   <li>Master name is non-null and non-blank
   *   <li>Sentinel nodes list is non-null and non-empty
   * </ul>
   *
   * @param masterName sentinel master name (e.g., "mymaster")
   * @param sentinelNodes sentinel node URIs (at least one required)
   * @return immutable topology instance
   * @throws NullPointerException if masterName or sentinelNodes is null
   * @throws IllegalArgumentException if masterName is blank or sentinelNodes is empty
   */
  public static SentinelTopology of(final String masterName, final List<RedisURI> sentinelNodes) {
    Objects.requireNonNull(masterName, "masterName cannot be null");
    Objects.requireNonNull(sentinelNodes, "sentinelNodes cannot be null");

    if (masterName.isBlank()) {
      throw new IllegalArgumentException("masterName cannot be blank");
    }

    if (sentinelNodes.isEmpty()) {
      throw new IllegalArgumentException(
          "sentinelNodes cannot be empty (at least one Sentinel node required)");
    }

    // Defensive copy to ensure immutability
    return new SentinelTopology(masterName, List.copyOf(sentinelNodes));
  }

  /**
   * Creates a Sentinel URI suitable for {@link io.lettuce.core.masterreplica.MasterReplica}.
   *
   * <p>Uses the first Sentinel node as the base URI and sets the master name.
   *
   * @return Sentinel URI with master name
   */
  public RedisURI toSentinelUri() {
    final RedisURI base = sentinelNodes.get(0);

    // Build new URI with master ID
    final var builder = RedisURI.builder().withSentinelMasterId(masterName);

    // Add all sentinel nodes
    for (final var node : sentinelNodes) {
      // Sentinel nodes are stored in the sentinels list when created with builder().withSentinel()
      if (!node.getSentinels().isEmpty()) {
        for (final var sentinel : node.getSentinels()) {
          builder.withSentinel(sentinel.getHost(), sentinel.getPort());
        }
      } else {
        // Fallback: direct host/port (shouldn't happen with proper URIs)
        builder.withSentinel(node.getHost(), node.getPort());
      }
    }

    // Copy authentication from first node
    if (base.getCredentialsProvider() != null) {
      builder.withAuthentication(base.getCredentialsProvider());
    }

    // Copy SSL settings from first node
    if (base.isSsl()) {
      builder.withSsl(true);
      builder.withVerifyPeer(base.isVerifyPeer());
    }

    // Copy database from first node
    builder.withDatabase(base.getDatabase());

    return builder.build();
  }
}
