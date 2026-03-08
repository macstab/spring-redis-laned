/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import java.util.ServiceLoader;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;

/**
 * Debug utility to test Testcontainers Docker detection. Run with: ./gradlew :redis-laned-core:test
 * --tests TestcontainersDebug --no-daemon
 */
public class TestcontainersDebug {

  public static void main(String[] args) {
    System.out.println("=== Testcontainers Debug ===");
    System.out.println("DOCKER_HOST env: " + System.getenv("DOCKER_HOST"));
    System.out.println("docker.host prop: " + System.getProperty("docker.host"));
    System.out.println(
        "testcontainers.ryuk.disabled: " + System.getProperty("testcontainers.ryuk.disabled"));

    System.out.println("\n=== Available Provider Strategies ===");
    ServiceLoader.load(DockerClientProviderStrategy.class)
        .forEach(
            strategy -> {
              System.out.println("  - " + strategy.getClass().getSimpleName());
            });

    System.out.println("\n=== Attempting Docker Connection ===");
    try {
      var factory = DockerClientFactory.instance();
      System.out.println("Factory created");

      var client = factory.client();
      System.out.println("Client obtained");

      var info = client.infoCmd().exec();
      System.out.println("✓ SUCCESS!");
      System.out.println("  Docker version: " + info.getServerVersion());
      System.out.println("  OS/Arch: " + info.getOperatingSystem() + "/" + info.getArchitecture());
      System.out.println("  Containers: " + info.getContainers());
    } catch (Exception e) {
      System.err.println("✗ FAILED!");
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace(System.err);
    }
  }
}
