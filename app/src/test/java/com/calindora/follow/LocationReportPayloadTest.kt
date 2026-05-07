package com.calindora.follow

import java.util.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocationReportPayloadTest {
  private lateinit var originalLocale: Locale

  @Before
  fun saveLocale() {
    originalLocale = Locale.getDefault()

    // Force a locale that uses a comma as the decimal separator to catch any accidental loss of
    // `Locale.US` in the formatter.
    Locale.setDefault(Locale.GERMAN)
  }

  @After
  fun restoreLocale() {
    Locale.setDefault(originalLocale)
  }

  // 2025-01-02T03:04:05.678Z, chosen so every component is distinct.
  private val sampleTimestampMillis = 1_735_787_045_678L

  @Test
  fun `build formats body timestamp with millisecond precision and UTC offset`() {
    val payload =
        LocationReportPayload.build(
            timestampMillis = sampleTimestampMillis,
            latitude = 47.6588,
            longitude = -117.4260,
            altitude = 600.0,
            speed = 1.5,
            bearing = 90.0,
            accuracy = 5.0,
        )
    assertEquals("2025-01-02T03:04:05.678+00:00", payload.timestamp)
  }

  @Test
  fun `build formats numbers with twelve decimal places regardless of locale`() {
    val payload =
        LocationReportPayload.build(
            timestampMillis = sampleTimestampMillis,
            latitude = 47.6588,
            longitude = -117.426,
            altitude = 600.0,
            speed = 1.5,
            bearing = 90.0,
            accuracy = 5.0,
        )
    assertEquals("47.658800000000", payload.latitude)
    assertEquals("-117.426000000000", payload.longitude)
    assertEquals("600.000000000000", payload.altitude)
    assertEquals("1.500000000000", payload.speed)
    assertEquals("90.000000000000", payload.bearing)
    assertEquals("5.000000000000", payload.accuracy)
  }

  @Test
  fun `signatureInput uses second-precision timestamp with no separators`() {
    val input =
        LocationReportPayload.signatureInput(
            timestampMillis = sampleTimestampMillis,
            latitude = 1.0,
            longitude = 2.0,
            altitude = 3.0,
            speed = 4.0,
            bearing = 5.0,
            accuracy = 6.0,
        )
    assertEquals(
        "2025-01-02T03:04:05+00:00" +
            "1.000000000000" +
            "2.000000000000" +
            "3.000000000000" +
            "4.000000000000" +
            "5.000000000000" +
            "6.000000000000",
        input,
    )
  }

  @Test
  fun `signatureInput normalizes negative zero to positive zero`() {
    val withNegativeZero =
        LocationReportPayload.signatureInput(
            timestampMillis = sampleTimestampMillis,
            latitude = -0.0,
            longitude = 0.0,
            altitude = 0.0,
            speed = 0.0,
            bearing = 0.0,
            accuracy = 0.0,
        )
    val withPositiveZero =
        LocationReportPayload.signatureInput(
            timestampMillis = sampleTimestampMillis,
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            speed = 0.0,
            bearing = 0.0,
            accuracy = 0.0,
        )
    assertEquals(withPositiveZero, withNegativeZero)
  }

  @Test
  fun `signatureInput normalizes tiny negative values that round to negative zero`() {
    // -1e-15 formats as "-0.000000000000" at 12-place precision.
    val input =
        LocationReportPayload.signatureInput(
            timestampMillis = sampleTimestampMillis,
            latitude = -1e-15,
            longitude = 0.0,
            altitude = 0.0,
            speed = 0.0,
            bearing = 0.0,
            accuracy = 0.0,
        )
    // The latitude segment immediately follows the timestamp.
    val latitudeSegment = input.substring("2025-01-02T03:04:05+00:00".length, input.length)
    assertEquals("0.000000000000", latitudeSegment.substring(0, "0.000000000000".length))
  }

  @Test
  fun `body and signature timestamp formats differ only in subsecond precision`() {
    val payload =
        LocationReportPayload.build(
            timestampMillis = sampleTimestampMillis,
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            speed = 0.0,
            bearing = 0.0,
            accuracy = 0.0,
        )
    val signatureInput =
        LocationReportPayload.signatureInput(
            timestampMillis = sampleTimestampMillis,
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            speed = 0.0,
            bearing = 0.0,
            accuracy = 0.0,
        )
    assertEquals("2025-01-02T03:04:05.678+00:00", payload.timestamp)
    assertEquals(true, signatureInput.startsWith("2025-01-02T03:04:05+00:00"))
  }
}
