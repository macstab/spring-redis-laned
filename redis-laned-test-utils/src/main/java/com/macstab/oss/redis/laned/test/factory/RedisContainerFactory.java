/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.test.factory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Factory for creating Testcontainers-based Redis instances.
 *
 * <p><strong>Design:</strong> Provides pre-configured Redis containers for common test scenarios:
 *
 * <ul>
 *   <li>Standalone Redis (no auth, no SSL)
 *   <li>Standalone Redis with TLS (mutual TLS, client certificates)
 *   <li>Sentinel cluster (1 master + 2 replicas + 3 sentinels)
 * </ul>
 *
 * <p><strong>Lifecycle:</strong> All containers are returned in {@code started} state. Callers must
 * {@code stop()} when done, or use {@code @Container} annotation for auto-cleanup.
 *
 * <p><strong>Thread Safety:</strong> This factory is stateless and thread-safe.
 *
 * <p><strong>Preferred Usage:</strong> Use annotations ({@code @RedisStandalone},
 * {@code @RedisSentinel}) instead of manual factory calls. This factory is for advanced/custom use
 * cases.
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * // Manual usage (for custom test setup)
 * GenericContainer<?> redis = RedisContainerFactory.createStandalone();
 * String host = redis.getHost();
 * Integer port = redis.getFirstMappedPort();
 *
 * // SSL/TLS Redis
 * GenericContainer<?> redisSSL = RedisContainerFactory.createStandaloneWithSSL();
 *
 * // Sentinel cluster
 * SentinelCluster cluster = RedisContainerFactory.createSentinelCluster();
 * GenericContainer<?> sentinel = cluster.firstSentinel();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.oss.redis.laned.test.annotation.RedisStandalone
 * @see com.macstab.oss.redis.laned.test.annotation.RedisSentinel
 */
public final class RedisContainerFactory {

  private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");
  private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);

  private RedisContainerFactory() {
    throw new UnsupportedOperationException("Utility class - not instantiable");
  }

  /**
   * Creates a standalone Redis container (no authentication, no SSL).
   *
   * <p><strong>Configuration:</strong>
   *
   * <ul>
   *   <li>Image: {@code redis:7-alpine}
   *   <li>Port: 6379 (mapped to random host port)
   *   <li>Auth: none
   *   <li>SSL: disabled
   *   <li>Protected mode: enabled (standard)
   * </ul>
   *
   * <p><strong>Startup time:</strong> ~2-3 seconds.
   *
   * @return started Redis container
   */
  public static GenericContainer<?> createStandalone() {
    return new GenericContainer<>(REDIS_IMAGE)
        .withExposedPorts(6379)
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT)
        .withReuse(false); // Fresh instance per test
  }

  /**
   * Creates a standalone Redis container with TLS/SSL enabled (mutual TLS).
   *
   * <p><strong>Configuration:</strong>
   *
   * <ul>
   *   <li>Image: {@code redis:7-alpine}
   *   <li>Port: 6380 (TLS)
   *   <li>Auth: mutual TLS (client certificates required)
   *   <li>Certificates: {@code src/test/resources/certs/} (valid until 2036)
   * </ul>
   *
   * <p><strong>Certificate structure:</strong>
   *
   * <pre>
   * src/test/resources/certs/
   *   ├── ca.crt         (Certificate Authority)
   *   ├── server.crt     (Server certificate)
   *   ├── server.key     (Server private key)
   *   ├── client.crt     (Client certificate)
   *   └── client.key     (Client private key)
   * </pre>
   *
   * <p><strong>Startup time:</strong> ~2-3 seconds.
   *
   * @return started Redis container with TLS on port 6380
   */
  public static GenericContainer<?> createStandaloneWithSSL() {
    final Path certsDir = Paths.get("src/test/resources/certs");

    return new GenericContainer<>(REDIS_IMAGE)
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
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT)
        .withReuse(false);
  }

  /**
   * Creates a Redis Sentinel cluster for high-availability testing.
   *
   * <p><strong>Topology:</strong>
   *
   * <pre>
   * Network: redis-sentinel-net
   *   ├─ redis-master   (port 6379, accepts writes)
   *   ├─ redis-replica1 (port 6379, replicates master, read-only)
   *   ├─ redis-replica2 (port 6379, replicates master, read-only)
   *   ├─ sentinel1      (port 26379, monitors master)
   *   ├─ sentinel2      (port 26379, monitors master)
   *   └─ sentinel3      (port 26379, monitors master)
   * </pre>
   *
   * <p><strong>Sentinel configuration:</strong>
   *
   * <ul>
   *   <li>Quorum: 2 (majority of 3 sentinels)
   *   <li>Down-after-milliseconds: 5000 (5 seconds)
   *   <li>Failover-timeout: 10000 (10 seconds)
   *   <li>Parallel-syncs: 1 (safe for testing)
   * </ul>
   *
   * <p><strong>Startup time:</strong> ~20-30 seconds (6 containers).
   *
   * <p><strong>Network requirements:</strong> Requires Docker host networking (Linux host or dev
   * container). Auto-disabled on macOS/Windows hosts via {@code @DisabledOnNonLinuxHost}.
   *
   * @return Sentinel cluster with all containers started
   * @throws RuntimeException if container configuration fails
   */
  public static SentinelCluster createSentinelCluster() {
    final Network network = Network.newNetwork();

    // 1. Start master
    final GenericContainer<?> master = createMasterNode(network);
    master.start();

    // 2. Configure master to announce externally-accessible address
    configureMasterAnnouncement(master);

    // 3. Start replicas
    final GenericContainer<?> replica1 = createReplicaNode(network, "redis-replica1");
    final GenericContainer<?> replica2 = createReplicaNode(network, "redis-replica2");
    replica1.start();
    replica2.start();

    // 4. Get master IP for Sentinel configuration
    final String masterIp = getMasterIpAddress(master);
    final Integer masterMappedPort = master.getMappedPort(6379);

    // 5. Start sentinels
    final String sentinelCommand = buildSentinelCommand(masterIp, masterMappedPort);
    final GenericContainer<?> sentinel1 = createSentinelNode(network, "sentinel1", sentinelCommand);
    final GenericContainer<?> sentinel2 = createSentinelNode(network, "sentinel2", sentinelCommand);
    final GenericContainer<?> sentinel3 = createSentinelNode(network, "sentinel3", sentinelCommand);

    sentinel1.start();
    sentinel2.start();
    sentinel3.start();

    // 6. Wait for Sentinel stabilization (internal state synchronization)
    waitForSentinelStabilization();

    return new SentinelCluster(
        network, master, List.of(replica1, replica2), List.of(sentinel1, sentinel2, sentinel3));
  }

  /**
   * Creates Redis master node container.
   *
   * @param network Docker network
   * @return configured master container (not started)
   */
  private static GenericContainer<?> createMasterNode(final Network network) {
    return new GenericContainer<>(REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases("redis-master")
        .withExposedPorts(6379)
        .withCommand("redis-server", "--protected-mode", "no")
        .withCreateContainerCmdModifier(
            cmd ->
                cmd.withHostConfig(
                    cmd.getHostConfig()
                        .withExtraHosts("host.testcontainers.internal:host-gateway")))
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Creates Redis replica node container.
   *
   * @param network Docker network
   * @param alias container network alias
   * @return configured replica container (not started)
   */
  private static GenericContainer<?> createReplicaNode(final Network network, final String alias) {
    return new GenericContainer<>(REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases(alias)
        .withExposedPorts(6379)
        .withCommand(
            "redis-server", "--protected-mode", "no", "--replicaof", "redis-master", "6379")
        .waitingFor(Wait.forLogMessage(".*MASTER <-> REPLICA sync: Finished with success.*\\n", 1))
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Creates Sentinel node container.
   *
   * @param network Docker network
   * @param alias container network alias
   * @param sentinelCommand Sentinel startup command
   * @return configured sentinel container (not started)
   */
  private static GenericContainer<?> createSentinelNode(
      final Network network, final String alias, final String sentinelCommand) {
    return new GenericContainer<>(REDIS_IMAGE)
        .withNetwork(network)
        .withNetworkAliases(alias)
        .withExposedPorts(26379)
        .withCreateContainerCmdModifier(
            cmd ->
                cmd.withHostConfig(
                    cmd.getHostConfig()
                        .withExtraHosts("host.testcontainers.internal:host-gateway")))
        .withCommand("sh", "-c", sentinelCommand)
        .waitingFor(
            Wait.forSuccessfulCommand("redis-cli -p 26379 SENTINEL master mymaster")
                .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT))
        .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
  }

  /**
   * Configures master to announce its externally-accessible address.
   *
   * <p>This allows Sentinel to return the correct (mapped) address to clients outside the Docker
   * network.
   *
   * @param master master container
   * @throws RuntimeException if configuration fails
   */
  private static void configureMasterAnnouncement(final GenericContainer<?> master) {
    final Integer masterMappedPort = master.getMappedPort(6379);
    try {
      master.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-ip", "host.testcontainers.internal");
      master.execInContainer(
          "redis-cli", "CONFIG", "SET", "replica-announce-port", String.valueOf(masterMappedPort));
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Failed to configure master announce address", e);
    }
  }

  /**
   * Extracts master container IP address from Docker network.
   *
   * @param master master container
   * @return IP address (e.g., "172.18.0.2")
   * @throws RuntimeException if IP cannot be determined
   */
  private static String getMasterIpAddress(final GenericContainer<?> master) {
    return master.getContainerInfo().getNetworkSettings().getNetworks().values().stream()
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Master container has no network"))
        .getIpAddress();
  }

  /**
   * Builds Sentinel startup command.
   *
   * <p>Creates inline Sentinel configuration and starts Sentinel process.
   *
   * @param masterIp master container IP
   * @param masterMappedPort master externally-mapped port
   * @return shell command string
   */
  private static String buildSentinelCommand(
      final String masterIp, final Integer masterMappedPort) {
    return "printf \"port 26379\\n"
        + "sentinel monitor mymaster "
        + masterIp
        + " 6379 2\\n"
        + "sentinel down-after-milliseconds mymaster 5000\\n"
        + "sentinel parallel-syncs mymaster 1\\n"
        + "sentinel failover-timeout mymaster 10000\\n"
        + "sentinel announce-ip host.docker.internal\\n"
        + "sentinel announce-port "
        + masterMappedPort
        + "\\n\" > /tmp/sentinel.conf && "
        + "redis-server /tmp/sentinel.conf --sentinel";
  }

  /**
   * Waits for Sentinel cluster to stabilize.
   *
   * <p>Even after +monitor event, Sentinels need time (~2s) to synchronize internal state and
   * become fully queryable.
   */
  private static void waitForSentinelStabilization() {
    try {
      Thread.sleep(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for Sentinel stabilization", e);
    }
  }

  /**
   * Sentinel cluster holder (network + all containers).
   *
   * <p><strong>Lifecycle:</strong> Caller must call {@code stop()} when done, or implement {@link
   * AutoCloseable} and use try-with-resources.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * SentinelCluster cluster = RedisContainerFactory.createSentinelCluster();
   * try {
   *   // Use cluster
   *   GenericContainer<?> sentinel = cluster.firstSentinel();
   *   String host = sentinel.getHost();
   *   Integer port = sentinel.getMappedPort(26379);
   * } finally {
   *   cluster.stop();
   * }
   * }</pre>
   *
   * @param network Docker network (shared by all containers)
   * @param master Redis master container
   * @param replicas Redis replica containers (typically 2)
   * @param sentinels Sentinel containers (typically 3)
   */
  public record SentinelCluster(
      Network network,
      GenericContainer<?> master,
      List<GenericContainer<?>> replicas,
      List<GenericContainer<?>> sentinels) {

    public SentinelCluster {
      Objects.requireNonNull(network, "network");
      Objects.requireNonNull(master, "master");
      Objects.requireNonNull(replicas, "replicas");
      Objects.requireNonNull(sentinels, "sentinels");
    }

    /**
     * Stops all containers and closes the Docker network.
     *
     * <p>Call in {@code @AfterEach} or {@code @AfterAll}.
     */
    public void stop() {
      sentinels.forEach(GenericContainer::stop);
      replicas.forEach(GenericContainer::stop);
      master.stop();
      network.close();
    }

    /**
     * Returns first Sentinel container (for client connection).
     *
     * <p>Use this to get Sentinel connection details:
     *
     * <pre>{@code
     * GenericContainer<?> sentinel = cluster.firstSentinel();
     * String host = sentinel.getHost();
     * Integer port = sentinel.getMappedPort(26379);
     * }</pre>
     *
     * @return first sentinel container
     */
    public GenericContainer<?> firstSentinel() {
      return sentinels.get(0);
    }
  }
}
