/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static lombok.AccessLevel.PRIVATE;

import java.util.concurrent.CopyOnWriteArrayList;

import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe tracker for Redis Pub/Sub connections.
 *
 * <p><strong>Why Pub/Sub requires dedicated connections (Redis server source):</strong>
 *
 * <p>From {@code src/pubsub.c}: {@code SUBSCRIBE} sets {@code c->flags |= CLIENT_PUBSUB}. From
 * {@code src/networking.c}, {@code processCommand()} rejects ALL commands except
 * (P|S)SUBSCRIBE/UNSUBSCRIBE, PING, QUIT, RESET when {@code CLIENT_PUBSUB} flag set. Server
 * responds {@code -ERR only (P|S)SUBSCRIBE / ... allowed in this context}. Protocol-level
 * constraint.
 *
 * <p><strong>RESP push messages (RESP3):</strong>
 *
 * <pre>
 * >3\r\n        // Push message (3 elements)
 * $7\r\nmessage  // Type
 * $7\r\nchannel  // Channel
 * $5\r\nhello    // Payload
 * </pre>
 *
 * <p>Unsolicited (no matching command in FIFO stack). Lettuce's {@code PubSubCommandHandler}
 * intercepts push, dispatches to {@code RedisPubSubListener} callbacks. Cannot coexist with regular
 * {@code CommandHandler} FIFO matching.
 *
 * <p><strong>CopyOnWriteArrayList choice (thread safety):</strong>
 *
 * <p>Read-heavy workload (most ops are reads). {@code CopyOnWriteArrayList}: lock-free reads
 * (volatile read of array reference), write = array copy + volatile swap. Read: ~5-10ns (volatile
 * read + length). Write: ~1-10μs (array copy, N=10-100). Rare writes + small N → negligible.
 *
 * <p><strong>Write path (OpenJDK source):</strong>
 *
 * <pre>{@code
 * public boolean add(E e) {
 *     lock.lock();  // ReentrantLock (writers only contend)
 *     try {
 *         Object[] elements = getArray();  // Volatile read
 *         Object[] newElements = Arrays.copyOf(elements, len + 1);  // Array copy
 *         newElements[len] = e;
 *         setArray(newElements);  // Volatile write (atomic swap, MFENCE on x86_64)
 *         return true;
 *     } finally { lock.unlock(); }
 * }
 * }</pre>
 *
 * <p>Lock held only during copy + swap (~100-500ns). Readers bypass lock (volatile read).
 *
 * <p><strong>Memory:</strong> Array copy per write. At N=100: ~16B (header) + 100 × 4B (compressed
 * OOPs) = ~416B. Write frequency: ~1-10/min → ~0.4-4 KB/min allocation (negligible vs young-gen GC,
 * typically 100-500 MB/sec). Old array = garbage, collected by next young-gen GC (&lt;1ms pause on
 * G1/ZGC).
 *
 * @see <a href="https://github.com/redis/redis/blob/unstable/src/pubsub.c">Redis Pub/Sub Source</a>
 * @see <a
 *     href="https://github.com/lettuce-io/lettuce-core/blob/main/src/main/java/io/lettuce/core/pubsub/PubSubCommandHandler.java">Lettuce
 *     PubSubCommandHandler</a>
 */
@Slf4j
@FieldDefaults(level = PRIVATE, makeFinal = true)
public final class PubSubConnectionTracker {

  private static final int WARNING_THRESHOLD = 100;

  RedisClient client;
  RedisCodec<?, ?> codec;

  /**
   * Tracked Pub/Sub connections.
   *
   * <p><strong>Volatile semantics (JMM):</strong>
   *
   * <p>{@code CopyOnWriteArrayList} backed by {@code volatile Object[]}. Write (add/remove):
   * creates new array, atomic swap (volatile write → happens-before). Read (size, iteration):
   * volatile read of array reference. Per JLS §17.4.5: volatile write happens-before every
   * subsequent volatile read. All threads see updated array after write completes (cache coherence
   * via MESI).
   */
  CopyOnWriteArrayList<StatefulRedisPubSubConnection<?, ?>> connections;

  public PubSubConnectionTracker(
      @NonNull final RedisClient client, @NonNull final RedisCodec<?, ?> codec) {
    this.client = client;
    this.codec = codec;
    this.connections = new CopyOnWriteArrayList<>();
  }

  /**
   * Creates Pub/Sub connection.
   *
   * <p>{@code client.connectPubSub(codec)}: Netty channel creation (TCP handshake + AUTH),
   * pipeline: {@code ConnectionWatchdog} → {@code CommandEncoder} → {@code PubSubCommandHandler}
   * (NOT {@code CommandHandler} — handles push messages + FIFO).
   */
  public StatefulRedisPubSubConnection<?, ?> create() {
    final var connection = client.connectPubSub(codec);
    connections.add(connection);

    final var count = connections.size();

    if (log.isDebugEnabled()) {
      log.debug("Created Pub/Sub connection (total: {})", count);
    }

    if (count > WARNING_THRESHOLD && log.isWarnEnabled()) {
      log.warn(
          "Pub/Sub connection count ({}) exceeded threshold ({}). Possible connection leak.",
          count,
          WARNING_THRESHOLD);
    }

    return connection;
  }

  /**
   * Releases Pub/Sub connection.
   *
   * <p>{@code connections.remove()}: {@code CopyOnWriteArrayList} creates new array WITHOUT
   * connection, volatile write (swap). Returns {@code true} if removed, {@code false} if already
   * released (idempotent). Then {@code close()}: QUIT + TCP FIN + FD release.
   */
  public void release(final StatefulRedisPubSubConnection<?, ?> connection) {
    if (connection == null) {
      return;
    }

    final boolean removed = connections.remove(connection);
    if (removed) {
      connection.close();

      if (log.isDebugEnabled()) {
        log.debug("Released Pub/Sub connection (remaining: {})", connections.size());
      }
    }
  }

  /**
   * Returns tracked connection count.
   *
   * <p>Lock-free volatile read: {@code getArray().length} (volatile read of array reference +
   * length field). ~5-10ns.
   */
  public int getConnectionCount() {
    return connections.size();
  }

  /**
   * Closes all tracked connections.
   *
   * <p>Iterate (snapshot-based, {@code CopyOnWriteArrayList} captures array reference at iterator
   * creation), close each (idempotent), clear (volatile write of empty array reference).
   */
  public void closeAll() {
    for (final var connection : connections) {
      connection.close();
    }

    connections.clear();

    if (log.isDebugEnabled()) {
      log.debug("Closed all Pub/Sub connections");
    }
  }
}
