package com.calindora.follow.ui.format

import com.calindora.follow.DistanceUnit
import com.calindora.follow.SpeedUnit
import java.util.*
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFormattersTest {
  @Test
  fun `formatCoordinate uses five decimal places with degree suffix in US locale`() {
    assertEquals("47.65880°", formatCoordinate(47.6588, Locale.US))
    assertEquals("-117.42600°", formatCoordinate(-117.426, Locale.US))
  }

  @Test
  fun `formatCoordinate honors the locale's decimal separator`() {
    // German locale uses ',' as the decimal separator.
    assertEquals("47,65880°", formatCoordinate(47.6588, Locale.GERMAN))
  }

  @Test
  fun `formatBearing uses two decimal places with degree suffix`() {
    assertEquals("90.00°", formatBearing(90.0, Locale.US))
    assertEquals("123.46°", formatBearing(123.456, Locale.US))
  }

  @Test
  fun `formatDistance applies the chosen unit conversion`() {
    // 100 m in feet = 100 / 0.3048 ≈ 328.0839895
    assertEquals("328.08 ft", formatDistance(100.0, DistanceUnit.FEET, "ft", Locale.US))
    assertEquals("100.00 m", formatDistance(100.0, DistanceUnit.METERS, "m", Locale.US))
  }

  @Test
  fun `formatDistance honors the locale's decimal separator`() {
    assertEquals("100,00 m", formatDistance(100.0, DistanceUnit.METERS, "m", Locale.GERMAN))
  }

  @Test
  fun `formatSpeed applies the chosen unit conversion`() {
    // 10 m/s = 36 km/h
    assertEquals("36.00 km/h", formatSpeed(10.0, SpeedUnit.KILOMETERS_PER_HOUR, "km/h", Locale.US))
    // 1 m/s ≈ 2.2369 mph
    assertEquals("2.24 mph", formatSpeed(1.0, SpeedUnit.MILES_PER_HOUR, "mph", Locale.US))
    // 1 m/s ≈ 1.9438 kn
    assertEquals("1.94 kn", formatSpeed(1.0, SpeedUnit.KNOTS, "kn", Locale.US))
    assertEquals("7.50 m/s", formatSpeed(7.5, SpeedUnit.METERS_PER_SECOND, "m/s", Locale.US))
  }

  @Test
  fun `formatSpeed honors the locale's decimal separator`() {
    assertEquals("7,50 m/s", formatSpeed(7.5, SpeedUnit.METERS_PER_SECOND, "m/s", Locale.GERMAN))
  }

  @Test
  fun `formatDistance uses the distance unit not the speed unit`() {
    // Regression guard: altitude/accuracy must never accidentally route through speed conversion.
    // With FEET, 1 m -> 3.28 ft; with KNOTS as a (wrong) speed unit, 1 m would not be 3.28.
    assertEquals("3.28 ft", formatDistance(1.0, DistanceUnit.FEET, "ft", Locale.US))
  }
}
