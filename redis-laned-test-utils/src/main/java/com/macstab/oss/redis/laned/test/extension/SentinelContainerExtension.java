/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.test.extension;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import com.macstab.oss.redis.laned.test.annotation.RedisSentinel;
import com.macstab.oss.redis.laned.test.extension.RedisContainerExtension.RedisConnectionInfo;
import com.macstab.oss.redis.laned.test.factory.RedisContainerFactory;

import io.lettuce.core.RedisURI;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * JUnit 5 extension that manages a full Redis Sentinel cluster for {@link RedisSentinel} annotated
 * tests.
 *
 * <p><strong>Cluster Topology:</strong>
 *
 * <pre>
 * Network: shared Docker network
 *   ├── Master:    redis-master:6379
 *   ├── Replica 1: redis-replica-1:6379
 *   ├── Replica 2: redis-replica-2:6379
 *   ├── Sentinel 1: redis-sentinel-1:26379
 *   ├── Sentinel 2: redis-sentinel-2:26379
 *   └── Sentinel 3: redis-sentinel-3:26379
 * </pre>
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <ol>
 *   <li>Create Docker network
 *   <li>Start master
 *   <li>Start replicas (configured to replicate from master)
 *   <li>Start sentinels (configured to monitor master)
 *   <li>Tests execute
 *   <li>Stop all containers + network
 * </ol>
 *
 * <p><strong>Platform Requirements:</strong> Linux host or dev container (native Docker
 * networking). Automatically disabled on macOS/Windows hosts via {@code @DisabledOnNonLinuxHost}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class SentinelContainerExtension
    implements BeforeAllCallback,
        AfterAllCallback,
        org.junit.jupiter.api.extension.ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(SentinelContainerExtension.class);

  private static final String CLUSTER_KEY_PREFIX = "sentinel-cluster-";

  /** ThreadLocal to hold current test context for INSTANCE.get() calls. */
  private static final ThreadLocal<ExtensionContext> CURRENT_CONTEXT = new ThreadLocal<>();

  /**
   * System property key for sentinel nodes (for Spring @TestPropertySource
   * / @DynamicPropertySource).
   */
  public static final String SENTINEL_NODES_PROPERTY = "sentinel.nodes";

  /**
   * Creates a Sentinel container extension.
   *
   * <p>JUnit 5 instantiates this class via reflection (default constructor required). Extension is
   * stateless; cluster state is stored in {@link ExtensionContext.Store}.
   */
  public SentinelContainerExtension() {
    // Stateless - no initialization needed
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    CURRENT_CONTEXT.set(context); // Store context for static INSTANCE.get() access

    // Register automatic ThreadLocal cleanup (primary mechanism via CloseableResource)
    context.getStore(NAMESPACE).put("threadlocal-cleanup", new ThreadLocalCleanup());

    final var annotation = context.getRequiredTestClass().getAnnotation(RedisSentinel.class);

    if (annotation == null) {
      log.warn("@RedisSentinel not found on test class, skipping cluster start");
      return;
    }

    final String id = annotation.id();
    final var cluster = createCluster(annotation);
    cluster.start();

    // Expose sentinel nodes as system property for Spring Boot @TestPropertySource /
    // @DynamicPropertySource
    final var firstSentinel = cluster.getSentinels().get(0);
    final var sentinelNodes = firstSentinel.getHost() + ":" + firstSentinel.getPort();
    System.setProperty(SENTINEL_NODES_PROPERTY, sentinelNodes);

    log.info(
        "Started Redis Sentinel cluster (id={}): master={}:{}, replicas={}, sentinels={}, sentinelNodes={}",
        id,
        cluster.getMasterHost(),
        cluster.getMasterPort(),
        annotation.replicas(),
        annotation.sentinels(),
        sentinelNodes);

    // Store with ID-specific key for multi-cluster support
    context.getStore(NAMESPACE).put(CLUSTER_KEY_PREFIX + id, cluster);
  }

  /**
   * Cleanup after all tests.
   *
   * <p><strong>Defensive Cleanup:</strong> ThreadLocal cleanup happens via both {@link
   * ThreadLocalCleanup#close()} (primary, automatic) and manual removal here (backup). This
   * "belt and suspenders" approach ensures no leaks even if JUnit lifecycle is interrupted.
   *
   * @param context test context
   */
  @Override
  public void afterAll(final ExtensionContext context) {
    CURRENT_CONTEXT.remove(); // Defensive backup cleanup (primary is ThreadLocalCleanup.close())
    System.clearProperty(SENTINEL_NODES_PROPERTY);
    // Clusters auto-stop via SentinelCluster lifecycle
  }

  /**
   * Public accessor for {@link com.macstab.oss.redis.laned.test.annotation.RedisManager}.
   *
   * <p>Called via {@code RedisSentinel.INSTANCE.get(id)}.
   *
   * <p><strong>Note:</strong> This is public for annotation access but should not be called
   * directly by tests. Use {@code RedisSentinel.INSTANCE.get(id)} instead.
   *
   * @param id cluster ID from {@code @RedisSentinel(id = "...")}
   * @return cluster info
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   */
  public static SentinelCluster getCluster(final String id) {
    final var context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException(
          "RedisSentinel.INSTANCE.get() called outside @RedisSentinel test context. "
              + "Ensure your test class is annotated with @RedisSentinel.");
    }

    final var cluster =
        context.getStore(NAMESPACE).get(CLUSTER_KEY_PREFIX + id, SentinelCluster.class);
    if (cluster == null) {
      throw new IllegalArgumentException(
          "No Sentinel cluster found with id='"
              + id
              + "'. "
              + "Did you add @RedisSentinel(id = \""
              + id
              + "\") to your test class?");
    }

    return cluster;
  }

  @Override
  public boolean supportsParameter(
      final org.junit.jupiter.api.extension.ParameterContext parameterContext,
      final ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType().equals(SentinelCluster.class);
  }

  @Override
  public Object resolveParameter(
      final org.junit.jupiter.api.extension.ParameterContext parameterContext,
      final ExtensionContext extensionContext) {
    // For backward compatibility with @BeforeAll parameter injection
    return extensionContext
        .getStore(NAMESPACE)
        .get(CLUSTER_KEY_PREFIX + "default", SentinelCluster.class);
  }

  /**
   * Create full Sentinel cluster from annotation.
   *
   * <p><strong>Delegation:</strong> Uses {@link RedisContainerFactory#createSentinelCluster()} to
   * create the cluster, then wraps it in the extension's SentinelCluster holder.
   *
   * @param annotation configuration (currently ignored, uses factory defaults)
   * @return cluster (not started)
   */
  private SentinelCluster createCluster(final RedisSentinel annotation) {
    // Delegate to centralized factory
    final var factoryCluster = RedisContainerFactory.createSentinelCluster();

    return new SentinelCluster(
        factoryCluster.network(),
        factoryCluster.master(),
        factoryCluster.replicas(),
        factoryCluster.sentinels(),
        annotation.masterName());
  }

  /**
   * Sentinel cluster holder (network + all containers).
   *
   * <p>Implements {@link ExtensionContext.Store.CloseableResource} for automatic cleanup.
   *
   * <p><strong>API Design:</strong> Provides two access patterns:
   *
   * <ul>
   *   <li><strong>Container access</strong> (monitoring/tracking): {@code getMasterContainer()},
   *       {@code getReplicaContainers()}, {@code getSentinelContainers()} return raw {@code
   *       GenericContainer<?>} for MONITOR command, log inspection, etc.
   *   <li><strong>Connection info access</strong> (common use case): {@code getMaster()}, {@code
   *       getReplicas()}, {@code getSentinels()} return {@link RedisConnectionInfo} for Lettuce
   *       client setup.
   * </ul>
   */
  public static final class SentinelCluster implements ExtensionContext.Store.CloseableResource {
    private final Network network;
    private final GenericContainer<?> master;
    private final List<GenericContainer<?>> replicas;
    private final List<GenericContainer<?>> sentinels;
    private final String masterName;

    /**
     * Creates a Sentinel cluster.
     *
     * @param network Docker network (must not be null)
     * @param master Redis master container (must not be null)
     * @param replicas Redis replica containers (must not be null, may be empty)
     * @param sentinels Sentinel containers (must not be null, typically 3 or 5)
     * @param masterName Sentinel master name (must not be null, e.g., "mymaster")
     */
    public SentinelCluster(
        final Network network,
        final GenericContainer<?> master,
        final List<GenericContainer<?>> replicas,
        final List<GenericContainer<?>> sentinels,
        final String masterName) {
      this.network = Objects.requireNonNull(network, "network");
      this.master = Objects.requireNonNull(master, "master");
      this.replicas = Objects.requireNonNull(replicas, "replicas");
      this.sentinels = Objects.requireNonNull(sentinels, "sentinels");
      this.masterName = Objects.requireNonNull(masterName, "masterName");
    }

    /** Start all containers (master → replicas → sentinels). */
    public void start() {
      master.start();
      replicas.forEach(GenericContainer::start);
      sentinels.forEach(GenericContainer::start);
    }

    /** Stop all containers + network. */
    public void stop() {
      sentinels.forEach(GenericContainer::stop);
      replicas.forEach(GenericContainer::stop);
      master.stop();
      network.close();
    }

    // ==================== Container Access (for monitoring/tracking) ====================

    /**
     * Returns master container (for monitoring, log inspection, etc.).
     *
     * @return master container
     */
    public GenericContainer<?> getMasterContainer() {
      return master;
    }

    /**
     * Returns replica containers (for monitoring, log inspection, etc.).
     *
     * @return replica containers (may be empty)
     */
    public List<GenericContainer<?>> getReplicaContainers() {
      return List.copyOf(replicas); // Defensive copy
    }

    /**
     * Returns Sentinel containers (for monitoring, log inspection, etc.).
     *
     * @return Sentinel containers (typically 3 or 5)
     */
    public List<GenericContainer<?>> getSentinelContainers() {
      return List.copyOf(sentinels); // Defensive copy
    }

    /**
     * Returns Docker network.
     *
     * @return network (shared across all containers)
     */
    public Network getNetwork() {
      return network;
    }

    /**
     * Returns Sentinel master name.
     *
     * @return master name (e.g., "mymaster")
     */
    public String getMasterName() {
      return masterName;
    }

    // ==================== Connection Info Access (common use case) ====================

    /**
     * Returns master container host.
     *
     * @return container host (typically "localhost" on Linux host, or Docker host IP)
     */
    public String getMasterHost() {
      return master.getHost();
    }

    /**
     * Returns master container port (mapped port).
     *
     * @return mapped port for Redis master (e.g., 32768 mapped to internal 6379)
     */
    public int getMasterPort() {
      return master.getMappedPort(6379);
    }

    /**
     * Returns master connection info (for Lettuce client setup).
     *
     * @return master connection info (host + port)
     */
    public RedisConnectionInfo getMaster() {
      return new RedisConnectionInfo(getMasterHost(), getMasterPort());
    }

    /**
     * Returns replica connection info list (for Lettuce client setup).
     *
     * @return replica connection info list (may be empty)
     */
    public List<RedisConnectionInfo> getReplicas() {
      return replicas.stream()
          .map(r -> new RedisConnectionInfo(r.getHost(), r.getMappedPort(6379)))
          .toList();
    }

    /**
     * Returns Sentinel connection info list (for Lettuce Sentinel client setup).
     *
     * @return Sentinel connection info list (typically 3 or 5)
     */
    public List<RedisConnectionInfo> getSentinels() {
      return sentinels.stream()
          .map(s -> new RedisConnectionInfo(s.getHost(), s.getMappedPort(26379)))
          .toList();
    }

    /**
     * Returns master as RedisURI (convenience for Lettuce client creation).
     *
     * @return RedisURI for master node
     */
    public RedisURI getMasterURI() {
      return RedisURI.builder().withHost(getMasterHost()).withPort(getMasterPort()).build();
    }

    /**
     * Returns Sentinel nodes as RedisURIs (for Lettuce Sentinel client).
     *
     * @return List of Sentinel RedisURIs
     */
    public List<RedisURI> getSentinelURIs() {
      return getSentinels().stream()
          .map(s -> RedisURI.builder().withHost(s.getHost()).withPort(s.getPort()).build())
          .toList();
    }

    @Override
    public void close() {
      stop();
    }
  }

  /**
   * ThreadLocal cleanup wrapper for automatic resource management.
   *
   * <p>Implements {@link ExtensionContext.Store.CloseableResource} to ensure ThreadLocal is
   * cleaned up automatically by JUnit 5 (primary mechanism). Manual cleanup in {@link
   * #afterAll(ExtensionContext)} serves as defensive backup.
   *
   * <p><strong>Defensive Design:</strong> Both automatic and manual cleanup ensure no ThreadLocal
   * leaks, even if JUnit lifecycle is interrupted.
   */
  private static final class ThreadLocalCleanup
      implements ExtensionContext.Store.CloseableResource {

    @Override
    public void close() {
      CURRENT_CONTEXT.remove(); // JUnit calls this automatically when test scope ends
    }
  }
}
