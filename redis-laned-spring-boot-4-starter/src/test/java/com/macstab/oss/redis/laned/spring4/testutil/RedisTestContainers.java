/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Factory for Redis Testcontainers (standalone, Sentinel, SSL).
 *
 * <p><strong>Design Pattern:</strong> Factory Method - creates configured containers for different
 * topologies.
 *
 * <p><strong>Lifecycle:</strong> Containers are returned in {@code started} state, ready for use.
 * Callers must {@code stop()} when done (or use {@code @Container} auto-cleanup).
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * // Standalone Redis
 * GenericContainer<?> redis = RedisTestContainers.createStandalone();
 * String host = redis.getHost();
 * Integer port = redis.getFirstMappedPort();
 *
 * // SSL/TLS Redis
 * GenericContainer<?> redisSSL = RedisTestContainers.createStandaloneWithSSL();
 *
 * // Sentinel (3 sentinels + 1 master + 2 replicas)
 * SentinelCluster cluster = RedisTestContainers.createSentinelCluster();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisTestContainers {

  private RedisTestContainers() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Creates a standalone Redis container (no auth, no SSL).
   *
   * @return started Redis container
   */
  public static GenericContainer<?> createStandalone() {
    return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .withStartupTimeout(Duration.ofSeconds(30))
        .withReuse(false); // Fresh instance per test
  }

  /**
   * Creates a standalone Redis container with TLS/SSL enabled (mutual TLS).
   *
   * <p><strong>Certificates:</strong> Uses test certificates from {@code src/test/resources/certs/}
   * (10-year validity, valid until 2036).
   *
   * <p><strong>Redis config:</strong> TLS enabled, client certificates required.
   *
   * @return started Redis container with SSL on port 6380
   */
  public static GenericContainer<?> createStandaloneWithSSL() {
    Path certsDir = Paths.get("src/test/resources/certs");

    return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6380) // TLS port
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("ca.crt")), "/tls/ca.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("server.crt")), "/tls/server.crt")
        .withCopyFileToContainer(
            MountableFile.forHostPath(certsDir.resolve("server.key"), 0644), "/tls/server.key")
        .withCommand(
            "redis-server",
            "--port",
            "0", // Disable non-TLS port
            "--tls-port",
            "6380",
            "--tls-cert-file",
            "/tls/server.crt",
            "--tls-key-file",
            "/tls/server.key",
            "--tls-ca-cert-file",
            "/tls/ca.crt",
            "--tls-auth-clients",
            "yes" // Require client certificates
            )
        .withStartupTimeout(Duration.ofSeconds(30))
        .withReuse(false);
  }

  /**
   * Creates a Redis Sentinel cluster (1 master, 2 replicas, 3 sentinels).
   *
   * <p><strong>Topology:</strong>
   *
   * <pre>
   * Network: redis-sentinel-net
   *   ├─ redis-master   (port 6379)
   *   ├─ redis-replica1 (port 6379, replicates master)
   *   ├─ redis-replica2 (port 6379, replicates master)
   *   ├─ sentinel1      (port 26379, monitors master)
   *   ├─ sentinel2      (port 26379, monitors master)
   *   └─ sentinel3      (port 26379, monitors master)
   * </pre>
   *
   * <p><strong>Startup time:</strong> ~20-30 seconds (6 containers).
   *
   * <p><strong>Sentinel config:</strong> Quorum=2, down-after=5s, failover-timeout=10s.
   *
   * @return Sentinel cluster with all containers started
   */
  public static SentinelCluster createSentinelCluster() {
    Network network = Network.newNetwork();

    // Master Redis
    GenericContainer<?> master =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(network)
            .withNetworkAliases("redis-master")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--protected-mode", "no")
            .withStartupTimeout(Duration.ofSeconds(30));

    master.start();

    // Replica 1
    GenericContainer<?> replica1 =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(network)
            .withNetworkAliases("redis-replica1")
            .withExposedPorts(6379)
            .withCommand(
                "redis-server", "--protected-mode", "no", "--replicaof", "redis-master", "6379")
            .withStartupTimeout(Duration.ofSeconds(30));

    replica1.start();

    // Replica 2
    GenericContainer<?> replica2 =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(network)
            .withNetworkAliases("redis-replica2")
            .withExposedPorts(6379)
            .withCommand(
                "redis-server", "--protected-mode", "no", "--replicaof", "redis-master", "6379")
            .withStartupTimeout(Duration.ofSeconds(30));

    replica2.start();

    // Sentinel configuration (written to file, then mounted)
    String sentinelConf =
        """
        port 26379
        sentinel monitor mymaster redis-master 6379 2
        sentinel down-after-milliseconds mymaster 5000
        sentinel parallel-syncs mymaster 1
        sentinel failover-timeout mymaster 10000
        """;

    Path sentinelConfPath;
    try {
      sentinelConfPath = Files.createTempFile("sentinel", ".conf");
      Files.writeString(sentinelConfPath, sentinelConf);
    } catch (IOException e) {
      throw new RuntimeException("Failed to create sentinel.conf", e);
    }

    // Sentinel 1
    GenericContainer<?> sentinel1 =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(network)
            .withNetworkAliases("sentinel1")
            .withExposedPorts(26379)
            .withCopyFileToContainer(
                MountableFile.forHostPath(sentinelConfPath), "/etc/sentinel.conf")
            .withCommand("redis-sentinel", "/etc/sentinel.conf")
            .withStartupTimeout(Duration.ofSeconds(30));

    sentinel1.start();

    // Sentinel 2
    GenericContainer<?> sentinel2 =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(network)
            .withNetworkAliases("sentinel2")
            .withExposedPorts(26379)
            .withCopyFileToContainer(
                MountableFile.forHostPath(sentinelConfPath), "/etc/sentinel.conf")
            .withCommand("redis-sentinel", "/etc/sentinel.conf")
            .withStartupTimeout(Duration.ofSeconds(30));

    sentinel2.start();

    // Sentinel 3
    GenericContainer<?> sentinel3 =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(network)
            .withNetworkAliases("sentinel3")
            .withExposedPorts(26379)
            .withCopyFileToContainer(
                MountableFile.forHostPath(sentinelConfPath), "/etc/sentinel.conf")
            .withCommand("redis-sentinel", "/etc/sentinel.conf")
            .withStartupTimeout(Duration.ofSeconds(30));

    sentinel3.start();

    return new SentinelCluster(
        network, master, List.of(replica1, replica2), List.of(sentinel1, sentinel2, sentinel3));
  }

  /**
   * Sentinel cluster components (master, replicas, sentinels).
   *
   * <p><strong>Usage:</strong>
   *
   * <pre>{@code
   * SentinelCluster cluster = RedisTestContainers.createSentinelCluster();
   * String sentinelHost = cluster.getSentinels().get(0).getHost();
   * Integer sentinelPort = cluster.getSentinels().get(0).getFirstMappedPort();
   * }</pre>
   *
   * @param network shared Docker network
   * @param master Redis master container
   * @param replicas Redis replica containers
   * @param sentinels Sentinel containers
   */
  public record SentinelCluster(
      Network network,
      GenericContainer<?> master,
      List<GenericContainer<?>> replicas,
      List<GenericContainer<?>> sentinels) {

    /**
     * Stops all containers and network.
     *
     * <p>Call in {@code @AfterEach} or use try-with-resources if implementing {@link
     * AutoCloseable}.
     */
    public void stop() {
      sentinels.forEach(GenericContainer::stop);
      replicas.forEach(GenericContainer::stop);
      master.stop();
      network.close();
    }

    /**
     * Returns first sentinel node (for client connection).
     *
     * @return first sentinel container
     */
    public GenericContainer<?> getFirstSentinel() {
      return sentinels.get(0);
    }
  }
}
