/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.testconfig;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Shared test configuration for all integration tests.
 *
 * <p>Enables Spring Boot autoconfiguration including {@link
 * com.macstab.oss.redis.laned.spring3.LanedRedisAutoConfiguration}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@SpringBootApplication
public class TestApplication {}
