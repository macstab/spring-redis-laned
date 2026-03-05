/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import java.io.File;

import lombok.extern.slf4j.Slf4j;

/**
 * Testcontainers configuration for both local development and Docker-in-Docker (DinD) environments.
 *
 * <p><strong>Problem:</strong> Testcontainers auto-detection fails in DinD (CI containers). It
 * tries to find Docker daemon but can't detect it properly in containerized environments.
 *
 * <p><strong>Solution:</strong> Static initializer detects DinD and configures Testcontainers
 * before any tests run. Configuration happens via system properties (checked by Testcontainers
 * before env vars).
 *
 * <p><strong>Detection strategy:</strong>
 *
 * <ul>
 *   <li>Check {@code /.dockerenv} (exists in Docker containers)
 *   <li>If present → configure for DinD
 *   <li>If absent → local dev (auto-detection works)
 * </ul>
 *
 * <p><strong>DinD configuration:</strong>
 *
 * <ul>
 *   <li>Set {@code DOCKER_HOST=unix:///var/run/docker.sock} (system property)
 *   <li>Set {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock} (system property)
 *   <li>Set {@code TESTCONTAINERS_RYUK_DISABLED=true} (Ryuk may not work in DinD)
 * </ul>
 *
 * <p><strong>Usage:</strong> Extend this class in integration tests:
 *
 * <pre>{@code
 * class MyIntegrationTest extends TestcontainersSupport {
 *   @Container
 *   private GenericContainer<?> redis = new GenericContainer<>("redis:7");
 *   // ... test methods
 * }
 * }</pre>
 *
 * <p><strong>CRITICAL:</strong> This class MUST be extended, not just referenced. The static block
 * must run before any {@code GenericContainer} fields are initialized. If you see "Previous
 * attempts to find Docker failed", add this to your test class:
 *
 * <pre>{@code
 * static {
 *   TestcontainersSupport.configure(); // Force early init
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
public abstract class TestcontainersSupport {

  private static final String DOCKER_ENV_FILE = "/.dockerenv";
  private static final String DOCKER_SOCK = "/var/run/docker.sock";
  private static volatile boolean configured = false;

  // Static block runs when class is loaded (before any instance creation)
  static {
    configure();
  }

  /**
   * Configure Testcontainers for DinD if running inside a Docker container.
   *
   * <p>This method is called automatically via static initializer. Can also be called explicitly
   * from tests that don't extend this class.
   *
   * <p><strong>Thread-safe and idempotent</strong> - safe to call multiple times.
   */
  public static synchronized void configure() {
    if (configured) {
      return; // Already configured
    }

    log.info("TestcontainersSupport: Initializing (checking for DinD environment)...");

    if (isRunningInDocker()) {
      log.info("Detected Docker-in-Docker environment, configuring Testcontainers...");
      configureDinD();
    } else {
      log.info("Running in local development environment, using Testcontainers auto-detection");
    }

    configured = true;
  }

  /**
   * Detect if running inside a Docker container.
   *
   * @return {@code true} if {@code /.dockerenv} exists
   */
  private static boolean isRunningInDocker() {
    return new File(DOCKER_ENV_FILE).exists();
  }

  /**
   * Configure Testcontainers for Docker-in-Docker.
   *
   * <p>Sets system properties that Testcontainers reads during initialization:
   *
   * <ul>
   *   <li>{@code DOCKER_HOST} → Docker daemon socket
   *   <li>{@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE} → Force socket path
   *   <li>{@code TESTCONTAINERS_RYUK_DISABLED} → Disable Ryuk (cleanup container)
   * </ul>
   */
  private static void configureDinD() {
    // Check if Docker socket exists
    final var dockerSock = new File(DOCKER_SOCK);
    if (!dockerSock.exists()) {
      log.warn("Docker socket not found at {}. Tests may fail.", DOCKER_SOCK);
    } else {
      log.info("Docker socket found at {}", DOCKER_SOCK);
    }

    // Set DOCKER_HOST if not already set (check both env var and system property)
    if (System.getenv("DOCKER_HOST") == null && System.getProperty("DOCKER_HOST") == null) {
      setEnv("DOCKER_HOST", "unix://" + DOCKER_SOCK);
      log.info("Set DOCKER_HOST=unix://{}", DOCKER_SOCK);
    } else {
      log.info(
          "DOCKER_HOST already set: {} (not overriding)",
          System.getenv("DOCKER_HOST") != null
              ? System.getenv("DOCKER_HOST")
              : System.getProperty("DOCKER_HOST"));
    }

    // Override socket path (Testcontainers-specific)
    setEnv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", DOCKER_SOCK);
    log.info("Set TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE={}", DOCKER_SOCK);

    // Disable Ryuk (may not work reliably in DinD)
    setEnv("TESTCONTAINERS_RYUK_DISABLED", "true");
    log.info("Set TESTCONTAINERS_RYUK_DISABLED=true");
  }

  /**
   * Set environment variable at runtime.
   *
   * <p><strong>Strategy:</strong> Set as system property (Testcontainers checks system properties
   * before env vars). More reliable than reflection hacks.
   *
   * @param key environment variable name
   * @param value environment variable value
   */
  private static void setEnv(String key, String value) {
    // Testcontainers checks system properties first, then env vars
    // System properties work on all JVMs, no reflection needed
    System.setProperty(key, value);
    log.debug("Set system property: {}={}", key, value);
  }
}
