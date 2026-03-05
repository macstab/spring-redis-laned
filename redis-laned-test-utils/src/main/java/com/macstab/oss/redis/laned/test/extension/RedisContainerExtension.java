/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.test.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.macstab.oss.redis.laned.test.annotation.RedisStandalone;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * JUnit 5 extension that manages a standalone Redis container lifecycle for {@link RedisStandalone}
 * annotated tests.
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <ol>
 *   <li>{@code @BeforeAll}: Start Redis container
 *   <li>Tests execute
 *   <li>{@code @AfterAll}: Stop Redis container (automatic via Testcontainers)
 * </ol>
 *
 * <p><strong>Container Configuration:</strong>
 *
 * <ul>
 *   <li>Image: {@code redis:<version>} (configurable via annotation)
 *   <li>Port: Random available port (or fixed via annotation)
 *   <li>Command: {@code redis-server} + custom args
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> This extension is stateless; container state is stored in
 * JUnit's {@link ExtensionContext.Store}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public final class RedisContainerExtension implements BeforeAllCallback, AfterAllCallback {

  /** ExtensionContext namespace for storing container. */
  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(RedisContainerExtension.class);

  /** Store key for Redis container. */
  private static final String CONTAINER_KEY = "redis-container";

  /**
   * Creates a Redis container extension.
   *
   * <p>JUnit 5 instantiates this class via reflection (default constructor required). Extension is
   * stateless; container state is stored in {@link ExtensionContext.Store}.
   */
  public RedisContainerExtension() {
    // Stateless - no initialization needed
  }

  /**
   * Start Redis container before all tests.
   *
   * @param context test context
   */
  @Override
  public void beforeAll(final ExtensionContext context) {
    final var annotation = context.getRequiredTestClass().getAnnotation(RedisStandalone.class);

    if (annotation == null) {
      log.warn("@RedisStandalone not found on test class, skipping container start");
      return;
    }

    final var container = createContainer(annotation);
    container.start();

    final var connectionInfo =
        new RedisConnectionInfo(container.getHost(), container.getMappedPort(6379));

    log.info(
        "Started Redis {} container: {}:{}",
        annotation.version(),
        connectionInfo.getHost(),
        connectionInfo.getPort());

    // Store in ExtensionContext for access by tests + cleanup
    context.getStore(NAMESPACE).put(CONTAINER_KEY, new Store(container, connectionInfo));
  }

  /**
   * Stop Redis container after all tests (automatic via Testcontainers AutoCloseable).
   *
   * @param context test context
   */
  @Override
  public void afterAll(final ExtensionContext context) {
    final var store = context.getStore(NAMESPACE).get(CONTAINER_KEY, Store.class);
    if (store != null) {
      log.info("Stopping Redis container");
      store.container.stop();
    }
  }

  /**
   * Create and configure Redis container from annotation.
   *
   * @param annotation configuration
   * @return configured container (not started)
   */
  private GenericContainer<?> createContainer(final RedisStandalone annotation) {
    final var imageName = DockerImageName.parse("redis:" + annotation.version());
    final var container = new GenericContainer<>(imageName).withExposedPorts(6379);

    // Build redis-server command
    final var command = new ArrayList<String>();
    command.add("redis-server");

    // Add custom args
    if (annotation.args().length > 0) {
      command.addAll(List.of(annotation.args()));
    }

    container.withCommand(command.toArray(new String[0]));

    // Fixed port (if specified)
    if (annotation.port() > 0) {
      container.setPortBindings(List.of(annotation.port() + ":6379"));
    }

    return container;
  }

  /**
   * Container + connection info holder (stored in ExtensionContext).
   *
   * <p>Implements {@link ExtensionContext.Store.CloseableResource} for automatic cleanup.
   */
  @Getter
  public static final class Store implements ExtensionContext.Store.CloseableResource {
    private final GenericContainer<?> container;
    private final RedisConnectionInfo connectionInfo;

    /**
     * Creates a store with container and connection info.
     *
     * @param container Testcontainers Redis container (must not be null)
     * @param connectionInfo connection details (must not be null)
     */
    public Store(final GenericContainer<?> container, final RedisConnectionInfo connectionInfo) {
      this.container = Objects.requireNonNull(container, "container");
      this.connectionInfo = Objects.requireNonNull(connectionInfo, "connectionInfo");
    }

    @Override
    public void close() {
      container.stop();
    }
  }

  /**
   * Redis connection details (host + port).
   *
   * <p>Can be injected into test methods via parameter resolution (future enhancement).
   */
  @Getter
  public static final class RedisConnectionInfo {
    private final String host;
    private final int port;

    /**
     * Creates connection info.
     *
     * @param host Redis host (must not be null, typically "localhost" or container hostname)
     * @param port Redis port (mapped port from Testcontainers)
     */
    public RedisConnectionInfo(final String host, final int port) {
      this.host = Objects.requireNonNull(host, "host");
      this.port = port;
    }

    @Override
    public String toString() {
      return host + ":" + port;
    }
  }
}
