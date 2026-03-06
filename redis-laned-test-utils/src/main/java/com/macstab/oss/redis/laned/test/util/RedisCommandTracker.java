/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.test.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import org.testcontainers.containers.GenericContainer;

/**
 * Tracks Redis commands in real-time using the MONITOR command.
 *
 * <p><strong>Purpose:</strong> Verify command routing in integration tests (e.g., reads go to
 * replicas, writes go to master).
 *
 * <p><strong>Design:</strong> Runs {@code redis-cli MONITOR} in a background thread and captures
 * all commands. Provides filtering to exclude replication traffic and count specific command types.
 *
 * <p><strong>Thread Safety:</strong> This class is thread-safe. Command capture and counting use
 * {@link CopyOnWriteArrayList} for safe concurrent access.
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <ol>
 *   <li>Create tracker: {@code new RedisCommandTracker(container)}
 *   <li>Start monitoring: {@code tracker.start()}
 *   <li>Execute Redis commands from application
 *   <li>Stop monitoring: {@code tracker.stop()}
 *   <li>Query results: {@code tracker.countCommand("GET")}
 * </ol>
 *
 * <p><strong>Example:</strong>
 *
 * <pre>{@code
 * // Track commands on replica
 * RedisCommandTracker tracker = new RedisCommandTracker(replicaContainer);
 * tracker.start();
 *
 * // Execute 1000 reads
 * for (int i = 0; i < 1000; i++) {
 *   redisTemplate.opsForValue().get("key:" + i);
 * }
 *
 * tracker.stop();
 *
 * // Verify replica handled reads
 * long getCount = tracker.countCommand("GET");
 * assertThat(getCount).isGreaterThan(900);
 * }</pre>
 *
 * <p><strong>Filtering Replication Traffic:</strong>
 *
 * <p>By default, replication commands (source port :6379) are filtered out. This prevents false
 * positives when testing Sentinel routing:
 *
 * <pre>
 * Client command:      [0 172.17.0.1:54321] "GET" "key"  ✅ Tracked
 * Replication command: [0 172.18.0.2:6379] "SET" "key"   ❌ Filtered
 * </pre>
 *
 * <p><strong>Custom Filtering:</strong>
 *
 * <pre>{@code
 * // Track only GET and SET commands
 * RedisCommandTracker tracker = RedisCommandTracker.builder()
 *     .container(replicaContainer)
 *     .trackCommands(Set.of("GET", "SET"))
 *     .build();
 *
 * // Track all commands (no filtering)
 * RedisCommandTracker tracker = RedisCommandTracker.builder()
 *     .container(masterContainer)
 *     .filter(line -> true)
 *     .build();
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see <a href="https://redis.io/commands/monitor">Redis MONITOR Command</a>
 */
public final class RedisCommandTracker {

  private final GenericContainer<?> container;
  private final Predicate<String> lineFilter;
  private final List<String> capturedCommands = new CopyOnWriteArrayList<>();
  private Thread monitorThread;
  private volatile boolean running = false;

  /**
   * Creates a command tracker with default filtering (excludes replication traffic).
   *
   * @param container Redis container to monitor
   */
  public RedisCommandTracker(final GenericContainer<?> container) {
    this(container, RedisCommandTracker::isClientCommand);
  }

  /**
   * Creates a command tracker with custom filtering.
   *
   * @param container Redis container to monitor
   * @param lineFilter predicate to filter MONITOR output lines (true = include, false = exclude)
   */
  public RedisCommandTracker(
      final GenericContainer<?> container, final Predicate<String> lineFilter) {
    this.container = Objects.requireNonNull(container, "container");
    this.lineFilter = Objects.requireNonNull(lineFilter, "lineFilter");
  }

  /**
   * Starts Redis MONITOR in background thread.
   *
   * <p>MONITOR output is captured and filtered in real-time. Call {@link #stop()} when done.
   *
   * @throws IllegalStateException if already started
   */
  public void start() {
    if (running) {
      throw new IllegalStateException("Tracker already started");
    }

    running = true;
    monitorThread =
        new Thread(
            () -> {
              try {
                final var exec =
                    container
                        .getDockerClient()
                        .execCreateCmd(container.getContainerId())
                        .withCmd("redis-cli", "MONITOR")
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .exec();

                final var execId = exec.getId();
                container
                    .getDockerClient()
                    .execStartCmd(execId)
                    .exec(
                        new com.github.dockerjava.api.async.ResultCallback.Adapter<
                            com.github.dockerjava.api.model.Frame>() {
                          @Override
                          public void onNext(com.github.dockerjava.api.model.Frame frame) {
                            if (running) {
                              final var line = new String(frame.getPayload()).trim();
                              if (lineFilter.test(line)) {
                                capturedCommands.add(line);
                              }
                            }
                          }
                        })
                    .awaitStarted();
              } catch (Exception e) {
                if (running) {
                  // Only log if not intentionally stopped
                  e.printStackTrace();
                }
              }
            });

    monitorThread.setDaemon(true);
    monitorThread.setName("redis-monitor-" + container.getContainerId());
    monitorThread.start();

    // Wait for MONITOR to start (~500ms is sufficient)
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while starting MONITOR", e);
    }
  }

  /**
   * Stops Redis MONITOR and background thread.
   *
   * <p>After calling stop(), command counts are frozen and can be queried.
   */
  public void stop() {
    running = false;
    if (monitorThread != null) {
      monitorThread.interrupt();
      try {
        monitorThread.join(1000); // Wait max 1s for thread to finish
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Counts occurrences of a specific Redis command.
   *
   * <p>Example MONITOR line: {@code 1234567890.123456 [0 172.17.0.1:54321] "GET" "key"}
   *
   * <p>This method searches for {@code "GET"} (quoted) in captured lines.
   *
   * @param command Redis command name (e.g., "GET", "SET", "HGETALL")
   * @return number of times command appeared
   */
  public long countCommand(final String command) {
    Objects.requireNonNull(command, "command");
    final String quoted = "\"" + command.toUpperCase() + "\"";
    return capturedCommands.stream().filter(line -> line.contains(quoted)).count();
  }

  /**
   * Returns all captured command lines.
   *
   * <p>Useful for debugging or custom analysis.
   *
   * @return immutable copy of captured lines
   */
  public List<String> getCapturedCommands() {
    return new ArrayList<>(capturedCommands);
  }

  /**
   * Returns number of captured command lines.
   *
   * @return total commands captured
   */
  public int size() {
    return capturedCommands.size();
  }

  /**
   * Clears all captured commands.
   *
   * <p>Useful when reusing tracker across multiple test phases.
   */
  public void reset() {
    capturedCommands.clear();
  }

  /**
   * Default filter: includes client commands, excludes replication traffic.
   *
   * <p>Replication traffic has source port :6379 (Redis nodes communicate on standard port). Client
   * traffic uses random high ports.
   *
   * @param line MONITOR output line
   * @return true if line is a client command (not replication)
   */
  private static boolean isClientCommand(final String line) {
    // Include only lines with common commands AND not from :6379 (replication)
    return (line.contains("\"GET\"")
            || line.contains("\"SET\"")
            || line.contains("\"HGET\"")
            || line.contains("\"HSET\"")
            || line.contains("\"DEL\"")
            || line.contains("\"INCR\"")
            || line.contains("\"DECR\""))
        && !line.contains(":6379]");
  }

  /**
   * Builder for custom RedisCommandTracker configurations.
   *
   * <p><strong>Example:</strong>
   *
   * <pre>{@code
   * RedisCommandTracker tracker = RedisCommandTracker.builder()
   *     .container(replicaContainer)
   *     .trackCommands(Set.of("GET", "HGETALL"))
   *     .build();
   * }</pre>
   */
  public static final class Builder {
    private GenericContainer<?> container;
    private Set<String> commands = Set.of("GET", "SET", "HGET", "HSET", "DEL", "INCR", "DECR");
    private boolean filterReplication = true;

    /**
     * Sets the Redis container to monitor.
     *
     * @param container container
     * @return builder
     */
    public Builder container(final GenericContainer<?> container) {
      this.container = container;
      return this;
    }

    /**
     * Sets which commands to track (case-insensitive).
     *
     * @param commands set of command names
     * @return builder
     */
    public Builder trackCommands(final Set<String> commands) {
      this.commands = Objects.requireNonNull(commands, "commands");
      return this;
    }

    /**
     * Enables/disables replication traffic filtering.
     *
     * @param filter true = filter out replication (default), false = include all traffic
     * @return builder
     */
    public Builder filterReplication(final boolean filter) {
      this.filterReplication = filter;
      return this;
    }

    /**
     * Builds the RedisCommandTracker.
     *
     * @return configured tracker
     * @throws NullPointerException if container not set
     */
    public RedisCommandTracker build() {
      Objects.requireNonNull(container, "container not set");

      final Predicate<String> filter =
          line -> {
            // Check if line contains any tracked command
            final boolean hasCommand =
                commands.stream().anyMatch(cmd -> line.contains("\"" + cmd.toUpperCase() + "\""));
            if (!hasCommand) {
              return false;
            }

            // Check replication filter
            if (filterReplication && line.contains(":6379]")) {
              return false; // Exclude replication traffic
            }

            return true;
          };

      return new RedisCommandTracker(container, filter);
    }
  }

  /**
   * Creates a builder for custom configurations.
   *
   * @return new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }
}
