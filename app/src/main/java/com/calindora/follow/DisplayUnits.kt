package com.calindora.follow

import androidx.annotation.StringRes
import com.calindora.follow.DistanceUnit.Companion.DEFAULT
import com.calindora.follow.SpeedUnit.Companion.DEFAULT

/** User-selectable display units for the main screen. */

// 1 ft = 0.3048 m (international foot, NIST SP 811)
private const val FEET_PER_METER = 1.0 / 0.3048

// Exact conversions: 1 mile = 1609.344 m, 1 nautical mile = 1852 m
private const val MPS_TO_KMH = 3.6
private const val MPS_TO_MPH = 3600.0 / 1609.344
private const val MPS_TO_KNOTS = 3600.0 / 1852.0

enum class DistanceUnit(
    @param:StringRes val labelRes: Int,
    @param:StringRes val abbreviationRes: Int,
) {
  METERS(R.string.unit_meters, R.string.unit_meters_abbr),
  FEET(R.string.unit_feet, R.string.unit_feet_abbr);

  /** Convert a value in meters to this unit. */
  fun fromMeters(meters: Double): Double =
      when (this) {
        METERS -> meters
        FEET -> meters * FEET_PER_METER
      }

  companion object {
    val DEFAULT = FEET

    /** Resolve a stored preference key back to an enum value, falling back to [DEFAULT]. */
    fun fromKey(key: String?): DistanceUnit = entries.firstOrNull { it.name == key } ?: DEFAULT
  }
}

enum class SpeedUnit(
    @param:StringRes val labelRes: Int,
    @param:StringRes val abbreviationRes: Int,
) {
  METERS_PER_SECOND(R.string.unit_meters_per_second, R.string.unit_meters_per_second_abbr),
  KILOMETERS_PER_HOUR(R.string.unit_kilometers_per_hour, R.string.unit_kilometers_per_hour_abbr),
  MILES_PER_HOUR(R.string.unit_miles_per_hour, R.string.unit_miles_per_hour_abbr),
  KNOTS(R.string.unit_knots, R.string.unit_knots_abbr);

  /** Convert a value in meters per second to this unit. */
  fun fromMetersPerSecond(metersPerSecond: Double): Double =
      when (this) {
        METERS_PER_SECOND -> metersPerSecond
        KILOMETERS_PER_HOUR -> metersPerSecond * MPS_TO_KMH
        MILES_PER_HOUR -> metersPerSecond * MPS_TO_MPH
        KNOTS -> metersPerSecond * MPS_TO_KNOTS
      }

  companion object {
    val DEFAULT = MILES_PER_HOUR

    /** Resolve a stored preference key back to an enum value, falling back to [DEFAULT]. */
    fun fromKey(key: String?): SpeedUnit = entries.firstOrNull { it.name == key } ?: DEFAULT
  }
}
