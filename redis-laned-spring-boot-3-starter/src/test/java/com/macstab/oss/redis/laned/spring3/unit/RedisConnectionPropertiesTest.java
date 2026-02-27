/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.oss.redis.laned.spring3.RedisConnectionProperties;
import com.macstab.oss.redis.laned.spring3.RedisConnectionStrategy;

/**
 * Unit tests for {@link RedisConnectionProperties}.
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>AAA pattern (Arrange, Act, Assert)
 *   <li>@Nested classes for logical grouping
 *   <li>@DisplayName for human-readable test names
 *   <li>No Spring context (pure unit test)
 * </ul>
 *
 * <p><strong>Coverage:</strong>
 *
 * <ul>
 *   <li>Default values
 *   <li>Lane count validation (clamping to 1-64)
 *   <li>Strategy enum binding
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("RedisConnectionProperties")
class RedisConnectionPropertiesTest {

  @Nested
  @DisplayName("Default Values")
  class DefaultValuesTest {

    @Test
    @DisplayName("should have CLASSIC as default strategy")
    void shouldHaveClassicAsDefaultStrategy() {
      // Arrange & Act
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Assert
      assertThat(properties.getStrategy()).isEqualTo(RedisConnectionStrategy.CLASSIC);
    }

    @Test
    @DisplayName("should have 8 as default lane count")
    void shouldHave8AsDefaultLaneCount() {
      // Arrange & Act
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Assert
      assertThat(properties.getLanes()).isEqualTo(8);
    }
  }

  @Nested
  @DisplayName("Lane Count Validation")
  class LaneCountValidationTest {

    @Test
    @DisplayName("should accept valid lane count (8)")
    void shouldAcceptValidLaneCount() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setLanes(8);

      // Assert
      assertThat(properties.getLanes()).isEqualTo(8);
    }

    @Test
    @DisplayName("should clamp negative lane count to minimum (1)")
    void shouldClampNegativeLaneCountToMinimum() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setLanes(-5);

      // Assert
      assertThat(properties.getLanes()).isEqualTo(RedisConnectionProperties.MIN_LANES);
    }

    @Test
    @DisplayName("should clamp zero lane count to minimum (1)")
    void shouldClampZeroLaneCountToMinimum() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setLanes(0);

      // Assert
      assertThat(properties.getLanes()).isEqualTo(RedisConnectionProperties.MIN_LANES);
    }

    @Test
    @DisplayName("should accept minimum lane count (1)")
    void shouldAcceptMinimumLaneCount() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setLanes(RedisConnectionProperties.MIN_LANES);

      // Assert
      assertThat(properties.getLanes()).isEqualTo(RedisConnectionProperties.MIN_LANES);
    }

    @Test
    @DisplayName("should accept maximum lane count (64)")
    void shouldAcceptMaximumLaneCount() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setLanes(RedisConnectionProperties.MAX_LANES);

      // Assert
      assertThat(properties.getLanes()).isEqualTo(RedisConnectionProperties.MAX_LANES);
    }

    @Test
    @DisplayName("should clamp excessive lane count to maximum (64)")
    void shouldClampExcessiveLaneCountToMaximum() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setLanes(128);

      // Assert
      assertThat(properties.getLanes()).isEqualTo(RedisConnectionProperties.MAX_LANES);
    }

    @Test
    @DisplayName("should clamp lane count just above maximum (65 → 64)")
    void shouldClampLaneCountJustAboveMaximum() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setLanes(RedisConnectionProperties.MAX_LANES + 1);

      // Assert
      assertThat(properties.getLanes()).isEqualTo(RedisConnectionProperties.MAX_LANES);
    }
  }

  @Nested
  @DisplayName("Strategy Configuration")
  class StrategyConfigurationTest {

    @Test
    @DisplayName("should allow setting LANED strategy")
    void shouldAllowSettingLanedStrategy() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setStrategy(RedisConnectionStrategy.LANED);

      // Assert
      assertThat(properties.getStrategy()).isEqualTo(RedisConnectionStrategy.LANED);
    }

    @Test
    @DisplayName("should allow setting POOLED strategy")
    void shouldAllowSettingPooledStrategy() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setStrategy(RedisConnectionStrategy.POOLED);

      // Assert
      assertThat(properties.getStrategy()).isEqualTo(RedisConnectionStrategy.POOLED);
    }

    @Test
    @DisplayName("should allow setting CLASSIC strategy")
    void shouldAllowSettingClassicStrategy() {
      // Arrange
      final RedisConnectionProperties properties = new RedisConnectionProperties();

      // Act
      properties.setStrategy(RedisConnectionStrategy.CLASSIC);

      // Assert
      assertThat(properties.getStrategy()).isEqualTo(RedisConnectionStrategy.CLASSIC);
    }
  }

  @Nested
  @DisplayName("Constants")
  class ConstantsTest {

    @Test
    @DisplayName("MIN_LANES should be 1")
    void minLanesShouldBe1() {
      // Assert
      assertThat(RedisConnectionProperties.MIN_LANES).isEqualTo(1);
    }

    @Test
    @DisplayName("MAX_LANES should be 64")
    void maxLanesShouldBe64() {
      // Assert
      assertThat(RedisConnectionProperties.MAX_LANES).isEqualTo(64);
    }

    @Test
    @DisplayName("DEFAULT_LANES should be 8")
    void defaultLanesShouldBe8() {
      // Assert
      assertThat(RedisConnectionProperties.DEFAULT_LANES).isEqualTo(8);
    }
  }
}
