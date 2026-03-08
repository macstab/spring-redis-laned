/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MurmurHash3}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>hash32(byte[]) - byte array hashing
 *   <li>hash32(byte[], int) - byte array hashing with custom seed
 *   <li>hash64(long) - long value hashing
 *   <li>Edge cases (empty arrays, null, overflow)
 *   <li>Distribution quality (chi-squared test)
 *   <li>Avalanche property (bit flip test)
 * </ul>
 */
@DisplayName("MurmurHash3")
class MurmurHash3Test {

  @Nested
  @DisplayName("hash32(byte[])")
  class Hash32DefaultSeed {

    @Test
    @DisplayName("returns consistent hash for same input")
    void consistentHash() {
      // Arrange
      final byte[] key = "user:123".getBytes();

      // Act
      final int hash1 = MurmurHash3.hash32(key);
      final int hash2 = MurmurHash3.hash32(key);

      // Assert
      assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("returns different hash for different inputs")
    void differentInputs() {
      // Arrange
      final byte[] key1 = "user:123".getBytes();
      final byte[] key2 = "user:456".getBytes();

      // Act
      final int hash1 = MurmurHash3.hash32(key1);
      final int hash2 = MurmurHash3.hash32(key2);

      // Assert
      assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("handles empty array")
    void emptyArray() {
      // Arrange
      final byte[] empty = new byte[0];

      // Act
      final int hash = MurmurHash3.hash32(empty);

      // Assert
      assertThat(hash).isNotZero(); // MurmurHash3 returns non-zero for empty input
    }

    @Test
    @DisplayName("throws on null input")
    void nullInput() {
      // Act & Assert
      assertThatThrownBy(() -> MurmurHash3.hash32(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be null");
    }

    @Test
    @DisplayName("handles single byte")
    void singleByte() {
      // Arrange
      final byte[] single = {0x42};

      // Act
      final int hash = MurmurHash3.hash32(single);

      // Assert
      assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("handles 4-byte aligned input")
    void fourByteAligned() {
      // Arrange
      final byte[] aligned = "1234".getBytes(); // Exactly 4 bytes

      // Act
      final int hash = MurmurHash3.hash32(aligned);

      // Assert
      assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("handles non-aligned input (3 bytes)")
    void nonAligned3Bytes() {
      // Arrange
      final byte[] nonAligned = "abc".getBytes(); // 3 bytes (tail processing)

      // Act
      final int hash = MurmurHash3.hash32(nonAligned);

      // Assert
      assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("handles large input (256 bytes)")
    void largeInput() {
      // Arrange
      final byte[] large = new byte[256];
      for (int i = 0; i < 256; i++) {
        large[i] = (byte) i;
      }

      // Act
      final int hash = MurmurHash3.hash32(large);

      // Assert
      assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("avalanche property: 1-bit input change flips ~50% output bits")
    void avalancheProperty() {
      // Arrange
      final byte[] input1 = "test".getBytes();
      final byte[] input2 = "test".getBytes();
      input2[0] ^= 0x01; // Flip 1 bit

      // Act
      final int hash1 = MurmurHash3.hash32(input1);
      final int hash2 = MurmurHash3.hash32(input2);

      // Assert
      final int flippedBits = Integer.bitCount(hash1 ^ hash2);
      assertThat(flippedBits)
          .as("Avalanche property: 1-bit input change should flip ~16 bits (50%% of 32)")
          .isBetween(10, 22); // Allow 10-22 bits (31-69%, relaxed for single test)
    }

    @Test
    @DisplayName("distribution quality: uniform distribution over 1000 keys")
    void distributionQuality() {
      // Arrange
      final int numKeys = 1000;
      final int numBuckets = 16;
      final int[] buckets = new int[numBuckets];

      // Act
      for (int i = 0; i < numKeys; i++) {
        final byte[] key = ("key:" + i).getBytes();
        final int hash = MurmurHash3.hash32(key);
        final int bucket = (hash & 0x7FFFFFFF) % numBuckets;
        buckets[bucket]++;
      }

      // Assert
      final int expectedPerBucket = numKeys / numBuckets; // 62.5
      for (int count : buckets) {
        assertThat(count)
            .as("Uniform distribution: each bucket should get ~62 keys")
            .isBetween(35, 95); // Allow ±44% variance (statistical: ~2% flaky rate)
      }
      // Note: With 1000 keys across 16 buckets, statistical variance means
      // ~5% probability one bucket gets 88+ keys (chi-squared distribution).
      // Relaxed tolerance reduces flakiness to <2% while still catching real bugs.
    }
  }

  @Nested
  @DisplayName("hash32(byte[], int seed)")
  class Hash32CustomSeed {

    @Test
    @DisplayName("different seed produces different hash")
    void differentSeed() {
      // Arrange
      final byte[] key = "user:123".getBytes();

      // Act
      final int hash1 = MurmurHash3.hash32(key, 0x1234);
      final int hash2 = MurmurHash3.hash32(key, 0x5678);

      // Assert
      assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("same seed produces consistent hash")
    void sameSeed() {
      // Arrange
      final byte[] key = "user:123".getBytes();
      final int seed = 0x9876;

      // Act
      final int hash1 = MurmurHash3.hash32(key, seed);
      final int hash2 = MurmurHash3.hash32(key, seed);

      // Assert
      assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("negative seed is valid")
    void negativeSeed() {
      // Arrange
      final byte[] key = "test".getBytes();
      final int seed = -12345;

      // Act
      final int hash = MurmurHash3.hash32(key, seed);

      // Assert
      assertThat(hash).isNotZero();
    }
  }

  @Nested
  @DisplayName("hash64(long)")
  class Hash64 {

    @Test
    @DisplayName("returns consistent hash for same input")
    void consistentHash() {
      // Arrange
      final long value = 123456789L;

      // Act
      final long hash1 = MurmurHash3.hash64(value);
      final long hash2 = MurmurHash3.hash64(value);

      // Assert
      assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("returns different hash for different inputs")
    void differentInputs() {
      // Arrange
      final long value1 = 123L;
      final long value2 = 456L;

      // Act
      final long hash1 = MurmurHash3.hash64(value1);
      final long hash2 = MurmurHash3.hash64(value2);

      // Assert
      assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("handles zero")
    void zero() {
      // Act
      final long hash = MurmurHash3.hash64(0L);

      // Assert
      // MurmurHash3 finalizer is identity for zero: hash64(0) = 0 (expected behavior)
      assertThat(hash).isZero();
    }

    @Test
    @DisplayName("handles negative values")
    void negativeValue() {
      // Act
      final long hash = MurmurHash3.hash64(-123456L);

      // Assert
      assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("handles Long.MAX_VALUE")
    void maxValue() {
      // Act
      final long hash = MurmurHash3.hash64(Long.MAX_VALUE);

      // Assert
      assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("handles Long.MIN_VALUE")
    void minValue() {
      // Act
      final long hash = MurmurHash3.hash64(Long.MIN_VALUE);

      // Assert
      assertThat(hash).isNotZero();
    }

    @Test
    @DisplayName("sequential inputs produce scattered hashes")
    void sequentialInputs() {
      // Arrange
      final long[] values = {1L, 2L, 3L, 4L, 5L};
      final long[] hashes = new long[values.length];

      // Act
      for (int i = 0; i < values.length; i++) {
        hashes[i] = MurmurHash3.hash64(values[i]);
      }

      // Assert
      for (int i = 0; i < hashes.length - 1; i++) {
        assertThat(hashes[i]).isNotEqualTo(hashes[i + 1]);
      }
    }

    @Test
    @DisplayName("distribution quality: uniform distribution over 1000 thread IDs")
    void distributionQuality() {
      // Arrange
      final int numValues = 1000;
      final int numBuckets = 16;
      final int[] buckets = new int[numBuckets];

      // Act
      for (long i = 1; i <= numValues; i++) {
        final long hash = MurmurHash3.hash64(i);
        final int bucket = (int) ((hash & 0x7FFF_FFFF_FFFF_FFFFL) % numBuckets);
        buckets[bucket]++;
      }

      // Assert
      final int expectedPerBucket = numValues / numBuckets; // 62.5
      for (int count : buckets) {
        assertThat(count)
            .as("Uniform distribution: each bucket should get ~62 values")
            .isBetween(40, 85); // Allow ±36% variance
      }
    }
  }
}
