/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.benchmarks.support;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Generates sustained background load on a SHARED connection to create HOL blocking.
 *
 * <p><strong>Critical Design:</strong> Uses the SAME connection being benchmarked. This ensures
 * slow commands (HGETALL 500KB) block fast commands (GET) on the shared TCP connection → HOL
 * blocking visible.
 *
 * <p><strong>Why Shared Connection Matters:</strong>
 *
 * <pre>
 * WRONG (separate connections):
 *   Background: RedisClient A → connection 1 → slow HGETALL
 *   Foreground: RedisClient B → connection 2 → fast GET
 *   Result: NO contention, NO HOL blocking visible
 *
 * RIGHT (shared connection):
 *   Background: Shared connection → slow HGETALL (blocks TCP socket)
 *   Foreground: SAME connection → fast GET (queues behind HGETALL)
 *   Result: HOL blocking visible, p95/p99 spike
 * </pre>
 *
 * <p><strong>Thread Safety:</strong> Single background thread sends commands on shared connection.
 * Foreground benchmark thread shares SAME connection → contention guaranteed.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class DirectConnectionLoadGenerator {

  private static final Logger log = LoggerFactory.getLogger(DirectConnectionLoadGenerator.class);

  private final StatefulRedisConnection<String, String> sharedConnection;
  private final ExecutorService executor;
  private final long sleepMillis;

  private volatile boolean stopFlag;

  /**
   * Creates background load generator with unlimited rate (tight loop).
   *
   * @param sharedConnection SHARED connection (foreground benchmark uses SAME connection)
   */
  public DirectConnectionLoadGenerator(
      final StatefulRedisConnection<String, String> sharedConnection) {
    this(sharedConnection, 0); // 0 = unlimited (tight loop)
  }

  /**
   * Creates background load generator with rate limiting.
   *
   * @param sharedConnection SHARED connection (foreground benchmark uses SAME connection)
   * @param commandsPerSecond target rate (0 = unlimited, tight loop)
   */
  public DirectConnectionLoadGenerator(
      final StatefulRedisConnection<String, String> sharedConnection, final int commandsPerSecond) {
    this.sharedConnection = sharedConnection;
    this.sleepMillis = commandsPerSecond > 0 ? (1000L / commandsPerSecond) : 0;
    this.executor =
        Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder()
                .setNameFormat("direct-load-%d")
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
   * Starts background load (continuous slow commands on SHARED connection).
   *
   * <p><strong>Command sent:</strong> {@code HGETALL large-hash} (10,000 fields × 50 bytes ≈ 500KB,
   * ~18ms latency)
   *
   * <p><strong>HOL blocking mechanism:</strong> Background thread continuously sends slow HGETALL
   * on SHARED connection. Foreground benchmark thread sends fast GET on SAME connection. RESP
   * protocol requires sequential response reading → fast GET queues behind slow HGETALL → p95/p99
   * spikes.
   *
   * <p><strong>Error handling:</strong> Exceptions logged but not propagated (sustains load even if
   * individual commands fail).
   *
   * <p><strong>Stabilization:</strong> Sleeps 1 second after starting to ensure background thread
   * is running before benchmark begins.
   */
  public void start() {
    log.info("Starting direct connection load generator (continuous HGETALL 500KB)...");

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
          log.info("Direct connection load generator stopped");
        });

    // Stabilize: ensure background thread running before benchmark starts
    try {
      Thread.sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    log.info("Direct connection load generator started");
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

    log.info("Stopping direct connection load generator...");
    stopFlag = true;

    executor.shutdown();

    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Direct connection load thread did not terminate gracefully, forcing shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    log.info("Direct connection load generator stopped");
  }

  /**
   * Sends slow command (HGETALL 500KB) on SHARED connection.
   *
   * <p><strong>Why HGETALL:</strong>
   *
   * <ul>
   *   <li>Realistic: Common command in production (user profiles, session data)
   *   <li>Slow: 10,000 fields × 50 bytes = 500KB response, ~18ms to transfer at 200 Mbps
   *   <li>Blocking: Response must be fully read before next command processed (RESP protocol)
   * </ul>
   */
  private void sendSlowCommand() {
    sharedConnection.sync().hgetall("large-hash");
  }
}
