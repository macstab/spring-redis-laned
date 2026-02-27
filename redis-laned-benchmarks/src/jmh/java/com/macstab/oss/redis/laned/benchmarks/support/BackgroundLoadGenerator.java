/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.benchmarks.support;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.macstab.oss.redis.laned.LanedConnectionManager;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Generates sustained background load (slow Redis commands) to create HOL blocking scenarios.
 *
 * <p><strong>Purpose:</strong> Benchmarks need SUSTAINED HOL blocking (not random spikes) to
 * measure lane-based HOL reduction. Background thread continuously sends slow commands (HGETALL
 * 500KB) to create predictable head-of-line blocking.
 *
 * <p><strong>Design Pattern:</strong>
 *
 * <pre>
 * Background Thread (not measured):
 *   while (!stopped) {
 *     conn.sync().hgetall("large-hash");  // 18ms command
 *   }
 *
 * Foreground Thread (measured by JMH):
 *   conn.sync().get("key-0");  // Fast command, queues behind slow HGETALL
 * </pre>
 *
 * <p><strong>Thread Safety:</strong> {@code volatile stopFlag} ensures memory visibility. Daemon
 * thread prevents JVM hang if shutdown fails.
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <pre>
 * start() → Background thread spawned → Continuous slow commands
 * stop()  → stopFlag = true → Background thread exits → ExecutorService shutdown
 * </pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class BackgroundLoadGenerator {

  private static final Logger log = LoggerFactory.getLogger(BackgroundLoadGenerator.class);

  private final LanedConnectionManager manager;
  private final ExecutorService executor;
  private final long sleepMillis;

  private volatile boolean stopFlag;

  /**
   * Creates background load generator with unlimited rate (tight loop).
   *
   * @param manager connection manager (provides connections for background commands)
   */
  public BackgroundLoadGenerator(final LanedConnectionManager manager) {
    this(manager, 0); // 0 = unlimited (tight loop)
  }

  /**
   * Creates background load generator with rate limiting.
   *
   * @param manager connection manager (provides connections for background commands)
   * @param commandsPerSecond target rate (0 = unlimited, tight loop)
   */
  public BackgroundLoadGenerator(
      final LanedConnectionManager manager, final int commandsPerSecond) {
    this.manager = manager;
    this.sleepMillis = commandsPerSecond > 0 ? (1000L / commandsPerSecond) : 0;
    this.executor =
        Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("background-load-%d")
                .setDaemon(true) // Don't block JVM shutdown
                .build());
    this.stopFlag = false;

    if (commandsPerSecond > 0) {
      log.info(
          "Rate limiting configured: {} commands/sec (sleep {}ms between commands)",
          commandsPerSecond,
          sleepMillis);
    } else {
      log.info("Rate limiting disabled: tight loop (unlimited commands/sec)");
    }
  }

  /**
   * Starts background load (continuous slow commands).
   *
   * <p><strong>Command sent:</strong> {@code HGETALL large-hash} (10,000 fields × 50 bytes ≈ 500KB,
   * ~18ms latency)
   *
   * <p><strong>Error handling:</strong> Exceptions logged but not propagated (sustains load even if
   * individual commands fail).
   *
   * <p><strong>Stabilization:</strong> Sleeps 1 second after starting to ensure background thread
   * is running before benchmark begins.
   */
  public void start() {
    log.info("Starting background load generator (continuous HGETALL 500KB)...");

    stopFlag = false;

    executor.submit(
        () -> {
          while (!stopFlag) {
            try {
              sendSlowCommand();

              // Rate limiting: sleep between commands if configured
              if (sleepMillis > 0) {
                Thread.sleep(sleepMillis);
              }

            } catch (final InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            } catch (final Exception e) {
              // Log but don't crash (sustain load)
              log.warn("Background load error (continuing): {}", e.getMessage());
              try {
                Thread.sleep(100); // Brief pause before retry
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
              }
            }
          }
          log.info("Background load generator stopped");
        });

    // Stabilize: ensure background thread running before benchmark starts
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    log.info("Background load generator started");
  }

  /**
   * Stops background load.
   *
   * <p><strong>Graceful shutdown:</strong> Sets stop flag, waits up to 5 seconds for thread to
   * exit, then forces shutdown.
   *
   * <p><strong>Idempotent:</strong> Safe to call multiple times.
   */
  public void stop() {
    if (stopFlag) {
      return; // Already stopped
    }

    log.info("Stopping background load generator...");
    stopFlag = true;

    executor.shutdown();

    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Background load thread did not terminate gracefully, forcing shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    log.info("Background load generator stopped");
  }

  /**
   * Sends slow command (HGETALL 500KB).
   *
   * <p><strong>Why HGETALL:</strong>
   *
   * <ul>
   *   <li>Realistic: Common command in production (user profiles, session data)
   *   <li>Slow: 10,000 fields × 50 bytes = 500KB response, ~18ms to transfer at 200 Mbps
   *   <li>Blocking: Response must be fully read before next command processed (RESP protocol)
   * </ul>
   */
  @SuppressWarnings("unchecked")
  private void sendSlowCommand() {
    try (var conn = manager.getConnection()) {
      ((StatefulRedisConnection<String, String>) conn).sync().hgetall("large-hash");
    }
  }
}
