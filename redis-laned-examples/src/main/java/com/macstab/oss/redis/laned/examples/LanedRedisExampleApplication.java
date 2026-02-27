/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.examples;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Example Spring Boot application demonstrating laned Redis connections.
 *
 * <p>Run with: {@code ./gradlew :redis-laned-examples:bootRun}
 *
 * <p>Requires Redis running on localhost:6379 or configure via:
 *
 * <pre>{@code
 * spring.data.redis.host=your-redis-host
 * spring.data.redis.port=6379
 * }</pre>
 */
@Slf4j
@SpringBootApplication
@RequiredArgsConstructor
public class LanedRedisExampleApplication {

  public static void main(String[] args) {
    SpringApplication.run(LanedRedisExampleApplication.class, args);
  }

  @Bean
  CommandLineRunner demo(StringRedisTemplate redisTemplate) {
    return args -> {
      log.info("=== Laned Redis Connection Example ===");

      // Simple SET/GET
      redisTemplate.opsForValue().set("example:key", "Hello from laned connection!");
      final var value = redisTemplate.opsForValue().get("example:key");

      log.info("Stored and retrieved: {}", value);

      // Demonstrate concurrent operations
      for (int i = 0; i < 10; i++) {
        final var key = "example:concurrent:" + i;
        redisTemplate.opsForValue().set(key, "value-" + i);
      }

      log.info("Wrote 10 concurrent keys successfully");
      log.info("=== Example complete ===");
    };
  }
}
