package com.calindora.follow.ui.format

import com.calindora.follow.DistanceUnit
import com.calindora.follow.SpeedUnit
import java.util.*

/**
 * Pure, locale-aware formatters for location status display values. UI code passes the active
 * [Locale] and resolved unit abbreviation strings; these helpers do the conversion and number
 * formatting.
 */

/** Format a coordinate (latitude or longitude) as five decimal places with a degree suffix. */
fun formatCoordinate(degrees: Double, locale: Locale): String =
    String.format(locale, "%.5f°", degrees)

/** Format a bearing as two decimal places with a degree suffix. */
fun formatBearing(degrees: Double, locale: Locale): String = String.format(locale, "%.2f°", degrees)

/**
 * Format a distance value (e.g. altitude or accuracy) in the user's chosen [unit], with
 * [abbreviation] appended.
 */
fun formatDistance(
    meters: Double,
    unit: DistanceUnit,
    abbreviation: String,
    locale: Locale,
): String = String.format(locale, "%.2f %s", unit.fromMeters(meters), abbreviation)

/** Format a speed value in the user's chosen [unit], with [abbreviation] appended. */
fun formatSpeed(
    metersPerSecond: Double,
    unit: SpeedUnit,
    abbreviation: String,
    locale: Locale,
): String =
    String.format(locale, "%.2f %s", unit.fromMetersPerSecond(metersPerSecond), abbreviation)
