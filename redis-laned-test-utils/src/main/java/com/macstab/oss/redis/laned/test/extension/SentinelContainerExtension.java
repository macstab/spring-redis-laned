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
import com.macstab.oss.redis.laned.test.factory.RedisContainerFactory;

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

  private static final String CLUSTER_KEY = "sentinel-cluster";

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
    final var annotation = context.getRequiredTestClass().getAnnotation(RedisSentinel.class);

    if (annotation == null) {
      log.warn("@RedisSentinel not found on test class, skipping cluster start");
      return;
    }

    final var cluster = createCluster(annotation);
    cluster.start();

    // Expose sentinel nodes as system property for Spring Boot @TestPropertySource /
    // @DynamicPropertySource
    final var sentinelNodes =
        cluster.getSentinels().get(0).getHost()
            + ":"
            + cluster.getSentinels().get(0).getMappedPort(26379);
    System.setProperty(SENTINEL_NODES_PROPERTY, sentinelNodes);

    log.info(
        "Started Redis Sentinel cluster: master={}:{}, replicas={}, sentinels={}, sentinelNodes={}",
        cluster.getMasterHost(),
        cluster.getMasterPort(),
        annotation.replicas(),
        annotation.sentinels(),
        sentinelNodes);

    context.getStore(NAMESPACE).put(CLUSTER_KEY, cluster);
  }

  @Override
  public void afterAll(final ExtensionContext context) {
    final var cluster = context.getStore(NAMESPACE).get(CLUSTER_KEY, SentinelCluster.class);
    if (cluster != null) {
      log.info("Stopping Redis Sentinel cluster");
      cluster.stop();
      System.clearProperty(SENTINEL_NODES_PROPERTY);
    }
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
    return extensionContext.getStore(NAMESPACE).get(CLUSTER_KEY, SentinelCluster.class);
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
   */
  @Getter
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

    /**
     * Returns master container host (for direct connections).
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

    @Override
    public void close() {
      stop();
    }
  }
}
