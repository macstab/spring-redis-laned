/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import java.io.File;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Disables tests on non-Linux hosts (macOS/Windows), but enables them on Linux hosts or in any
 * container.
 *
 * <p><strong>Use case:</strong> Testcontainers + Sentinel requires native Docker networking. Works
 * on:
 *
 * <ul>
 *   <li>Linux host (native Docker)
 *   <li>Any dev container (Linux-based, native Docker)
 * </ul>
 *
 * <p>Does NOT work on:
 *
 * <ul>
 *   <li>macOS host (Docker Desktop uses VM)
 *   <li>Windows host (Docker Desktop uses WSL2 VM)
 * </ul>
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * @DisabledOnNonLinuxHost
 * class SentinelIntegrationTest {
 *   // ... tests
 * }
 * }</pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DisabledOnNonLinuxHost.Condition.class)
public @interface DisabledOnNonLinuxHost {

  /** Custom reason (optional). */
  String value() default
      "Disabled on macOS/Windows host (Docker networking not native). "
          + "Enabled on Linux host or any dev container.";

  /** Execution condition that checks OS + container. */
  class Condition implements ExecutionCondition {

    private static final String DOCKER_ENV_FILE = "/.dockerenv";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      // In container? Always enable (Linux-based)
      if (isRunningInContainer()) {
        return ConditionEvaluationResult.enabled("Running in container (Linux-based)");
      }

      // On host: Check OS
      final var os = System.getProperty("os.name").toLowerCase();
      if (os.contains("linux")) {
        return ConditionEvaluationResult.enabled("Running on Linux host (native Docker)");
      }

      // macOS/Windows host → disable
      final var reason =
          context
              .getElement()
              .flatMap(
                  e -> java.util.Optional.ofNullable(e.getAnnotation(DisabledOnNonLinuxHost.class)))
              .map(DisabledOnNonLinuxHost::value)
              .orElse("Disabled on non-Linux host");

      return ConditionEvaluationResult.disabled(reason + " (OS: " + os + ")");
    }

    /**
     * Check if running inside a container.
     *
     * @return {@code true} if {@code /.dockerenv} exists
     */
    private boolean isRunningInContainer() {
      return new File(DOCKER_ENV_FILE).exists();
    }
  }
}
