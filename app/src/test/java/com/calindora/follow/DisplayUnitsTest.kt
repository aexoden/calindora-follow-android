package com.calindora.follow

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayUnitsTest {
  private val tolerance = 1e-9

  @Test
  fun `meters distance unit is identify`() {
    assertEquals(0.0, DistanceUnit.METERS.fromMeters(0.0), tolerance)
    assertEquals(123.456, DistanceUnit.METERS.fromMeters(123.456), tolerance)
  }

  @Test
  fun `feet conversion uses exact 0_3048 m per foot`() {
    // 1 m = 1 / 0.3048 ft ≈ 3.280839895
    assertEquals(3.280839895013123, DistanceUnit.FEET.fromMeters(1.0), tolerance)
    assertEquals(0.0, DistanceUnit.FEET.fromMeters(0.0), tolerance)
    // A round number for sanity: 100 m ≈ 328.084 ft
    assertEquals(328.0839895013123, DistanceUnit.FEET.fromMeters(100.0), tolerance)
  }

  @Test
  fun `meters per second is identity`() {
    assertEquals(7.5, SpeedUnit.METERS_PER_SECOND.fromMetersPerSecond(7.5), tolerance)
  }

  @Test
  fun `km per hour is exactly 3_6 times m per s`() {
    assertEquals(36.0, SpeedUnit.KILOMETERS_PER_HOUR.fromMetersPerSecond(10.0), tolerance)
  }

  @Test
  fun `mph uses exact 1609_344 m per mile`() {
    // 1 m/s = 3600 / 1609.344 mph ≈ 2.2369362920544
    assertEquals(2.2369362920544025, SpeedUnit.MILES_PER_HOUR.fromMetersPerSecond(1.0), tolerance)
  }

  @Test
  fun `knots use exact 1852 m per nautical mile`() {
    // 1 m/s = 3600 / 1852 kn ≈ 1.9438444924406
    assertEquals(1.9438444924406046, SpeedUnit.KNOTS.fromMetersPerSecond(1.0), tolerance)
  }

  @Test
  fun `fromKey returns default for unknown or null inputs`() {
    assertEquals(DistanceUnit.DEFAULT, DistanceUnit.fromKey(null))
    assertEquals(DistanceUnit.DEFAULT, DistanceUnit.fromKey("CUBITS"))
    assertEquals(SpeedUnit.DEFAULT, SpeedUnit.fromKey(null))
    assertEquals(SpeedUnit.DEFAULT, SpeedUnit.fromKey("FURLONGS_PER_FORTNIGHT"))
  }

  @Test
  fun `fromKey round-trips every enum value`() {
    DistanceUnit.entries.forEach { assertEquals(it, DistanceUnit.fromKey(it.name)) }
    SpeedUnit.entries.forEach { assertEquals(it, SpeedUnit.fromKey(it.name)) }
  }
}
