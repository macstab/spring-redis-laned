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

  /** Store key prefix for Redis containers (suffixed with ID). */
  private static final String CONTAINER_KEY_PREFIX = "redis-container-";

  /** ThreadLocal to hold current test context for INSTANCE.get() calls. */
  private static final ThreadLocal<ExtensionContext> CURRENT_CONTEXT = new ThreadLocal<>();

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
    CURRENT_CONTEXT.set(context); // Store context for static INSTANCE.get() access

    // Register automatic ThreadLocal cleanup (primary mechanism via CloseableResource)
    context.getStore(NAMESPACE).put("threadlocal-cleanup", new ThreadLocalCleanup());

    final var annotation = context.getRequiredTestClass().getAnnotation(RedisStandalone.class);

    if (annotation == null) {
      log.warn("@RedisStandalone not found on test class, skipping container start");
      return;
    }

    final String id = annotation.id();
    final var container = createContainer(annotation);
    container.start();

    final var connectionInfo =
        new RedisConnectionInfo(container.getHost(), container.getMappedPort(6379));

    log.info(
        "Started Redis {} container (id={}): {}:{}",
        annotation.version(),
        id,
        connectionInfo.getHost(),
        connectionInfo.getPort());

    // Store with ID-specific key for multi-container support
    context
        .getStore(NAMESPACE)
        .put(CONTAINER_KEY_PREFIX + id, new Store(container, connectionInfo));
  }

  /**
   * Stop Redis container after all tests (automatic via Testcontainers AutoCloseable).
   *
   * <p><strong>Defensive Cleanup:</strong> ThreadLocal cleanup happens via both {@link
   * ThreadLocalCleanup#close()} (primary, automatic) and manual removal here (backup). This "belt
   * and suspenders" approach ensures no leaks even if JUnit lifecycle is interrupted.
   *
   * @param context test context
   */
  @Override
  public void afterAll(final ExtensionContext context) {
    CURRENT_CONTEXT.remove(); // Defensive backup cleanup (primary is ThreadLocalCleanup.close())
    // Containers auto-stop via Store.CloseableResource
  }

  /**
   * Public accessor for {@link com.macstab.oss.redis.laned.test.annotation.RedisManager}.
   *
   * <p>Called via {@code RedisStandalone.INSTANCE.get(id)}.
   *
   * <p><strong>Note:</strong> This is public for annotation access but should not be called
   * directly by tests. Use {@code RedisStandalone.INSTANCE.get(id)} instead.
   *
   * @param id container ID from {@code @RedisStandalone(id = "...")}
   * @return connection info
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   */
  public static RedisConnectionInfo getContainer(final String id) {
    final var context = CURRENT_CONTEXT.get();
    if (context == null) {
      throw new IllegalStateException(
          "RedisStandalone.INSTANCE.get() called outside @RedisStandalone test context. "
              + "Ensure your test class is annotated with @RedisStandalone.");
    }

    final var store = context.getStore(NAMESPACE).get(CONTAINER_KEY_PREFIX + id, Store.class);
    if (store == null) {
      throw new IllegalArgumentException(
          "No Redis container found with id='"
              + id
              + "'. "
              + "Did you add @RedisStandalone(id = \""
              + id
              + "\") to your test class?");
    }

    return store.getConnectionInfo();
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

  /**
   * ThreadLocal cleanup wrapper for automatic resource management.
   *
   * <p>Implements {@link ExtensionContext.Store.CloseableResource} to ensure ThreadLocal is cleaned
   * up automatically by JUnit 5 (primary mechanism). Manual cleanup in {@link
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
