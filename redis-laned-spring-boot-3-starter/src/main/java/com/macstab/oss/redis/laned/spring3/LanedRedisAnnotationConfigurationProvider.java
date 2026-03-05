/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3;

import java.util.Optional;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import com.macstab.oss.redis.laned.config.LanedRedisConfigurationSource;
import com.macstab.oss.redis.laned.config.LanedRedisConnection;

import lombok.extern.slf4j.Slf4j;

/**
 * Scans for {@link LanedRedisConnection} annotation and provides configuration with highest
 * precedence.
 *
 * <p><strong>Precedence:</strong>
 *
 * <ol>
 *   <li><strong>{@code @LanedRedisConnection}</strong> (this provider) - Highest priority
 *   <li>YAML/properties ({@code spring.redis.laned.*})
 *   <li>Defaults (lanes=4, strategy=ROUND_ROBIN)
 * </ol>
 *
 * <p><strong>Scan Strategy:</strong>
 *
 * <ol>
 *   <li>Find base package from main application class
 *   <li>Scan for {@code @LanedRedisConnection} in that package + subpackages
 *   <li>Return first match (annotation should only appear once)
 * </ol>
 *
 * <p><strong>Thread Safety:</strong> Stateless, annotation scan result is cached.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@Slf4j
@Component
public class LanedRedisAnnotationConfigurationProvider {

  private final ApplicationContext applicationContext;
  private Optional<LanedRedisConfigurationSource> cachedConfig;

  /**
   * Creates the annotation configuration provider.
   *
   * @param applicationContext Spring application context (auto-wired by Spring Boot)
   */
  public LanedRedisAnnotationConfigurationProvider(final ApplicationContext applicationContext) {
    this.applicationContext = applicationContext;
  }

  /**
   * Get configuration from {@code @LanedRedisConnection} annotation if present.
   *
   * <p>Result is cached after first scan.
   *
   * @return configuration from annotation, or empty if annotation not found
   */
  public Optional<LanedRedisConfigurationSource> getAnnotationConfiguration() {
    if (cachedConfig != null) {
      return cachedConfig;
    }

    cachedConfig = scanForAnnotation();
    return cachedConfig;
  }

  /**
   * Scan application context for {@code @LanedRedisConnection} annotation.
   *
   * @return configuration from annotation, or empty if not found
   */
  private Optional<LanedRedisConfigurationSource> scanForAnnotation() {
    try {
      // Find main application class or scan from root package
      final String basePackage = findBasePackage();

      if (log.isDebugEnabled()) {
        log.debug("Scanning for @LanedRedisConnection in package: {}", basePackage);
      }

      final var scanner = new ClassPathScanningCandidateComponentProvider(false);
      scanner.addIncludeFilter(new AnnotationTypeFilter(LanedRedisConnection.class));

      final var candidates = scanner.findCandidateComponents(basePackage);

      if (candidates.isEmpty()) {
        if (log.isDebugEnabled()) {
          log.debug("No @LanedRedisConnection annotation found");
        }
        return Optional.empty();
      }

      if (candidates.size() > 1) {
        log.warn(
            "@LanedRedisConnection found on {} classes - using first match. "
                + "Annotation should only appear once.",
            candidates.size());
      }

      // Extract configuration from first match
      final BeanDefinition candidate = candidates.iterator().next();
      return extractConfiguration(candidate);

    } catch (final Exception e) {
      log.warn("Failed to scan for @LanedRedisConnection annotation", e);
      return Optional.empty();
    }
  }

  /**
   * Extract configuration from annotated class.
   *
   * @param beanDefinition bean definition for annotated class
   * @return configuration from annotation
   */
  private Optional<LanedRedisConfigurationSource> extractConfiguration(
      final BeanDefinition beanDefinition) {
    try {
      final String className = beanDefinition.getBeanClassName();
      if (className == null) {
        return Optional.empty();
      }

      final Class<?> annotatedClass = Class.forName(className);
      final LanedRedisConnection annotation =
          annotatedClass.getAnnotation(LanedRedisConnection.class);

      if (annotation == null) {
        return Optional.empty();
      }

      final var config =
          LanedRedisConfigurationSource.builder()
              .lanes(annotation.lanes())
              .strategyType(annotation.strategy())
              .metricsEnabled(annotation.metricsEnabled())
              .sourceType(LanedRedisConfigurationSource.ConfigurationSourceType.ANNOTATION)
              .build();

      log.info(
          "@LanedRedisConnection found on {}: lanes={}, strategy={}, metrics={}",
          annotatedClass.getSimpleName(),
          config.getLanes(),
          config.getStrategyType(),
          config.isMetricsEnabled());

      return Optional.of(config);

    } catch (final ClassNotFoundException e) {
      log.warn("Failed to load annotated class", e);
      return Optional.empty();
    }
  }

  /**
   * Find base package to scan from.
   *
   * <p>Strategy:
   *
   * <ol>
   *   <li>Try to find main application class (class with {@code @SpringBootApplication})
   *   <li>Fall back to empty string (scan all packages - slower but works)
   * </ol>
   *
   * @return base package name
   */
  private String findBasePackage() {
    // Try to find @SpringBootApplication class
    final var beanNames =
        applicationContext.getBeanNamesForAnnotation(
            org.springframework.boot.autoconfigure.SpringBootApplication.class);

    if (beanNames.length > 0) {
      final Object bean = applicationContext.getBean(beanNames[0]);
      final String packageName = bean.getClass().getPackage().getName();
      if (log.isDebugEnabled()) {
        log.debug("Found main application class in package: {}", packageName);
      }
      return packageName;
    }

    // Fall back to scanning everything (slower)
    if (log.isDebugEnabled()) {
      log.debug("No @SpringBootApplication found, scanning all packages");
    }
    return "";
  }
}
