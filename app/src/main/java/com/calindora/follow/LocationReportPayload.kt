package com.calindora.follow

import com.calindora.follow.LocationReportPayload.Companion.build
import com.calindora.follow.LocationReportPayload.Companion.signatureInput
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import kotlinx.serialization.Serializable

/**
 * Wire format for location reports submitted to the server.
 *
 * All numeric fields are sent as preformatted strings: floats use 12 decimal places, the timestamp
 * is ISO-8601 with millisecond precision and a UTC offset.
 *
 * The HMAC signature input is a separate concatenation built independently of this body - see
 * [signatureInput].
 */
@Serializable
data class LocationReportPayload(
    val timestamp: String,
    val latitude: String,
    val longitude: String,
    val altitude: String,
    val speed: String,
    val bearing: String,
    val accuracy: String,
) {
  companion object {
    private val BODY_TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC)
    private val SIGNATURE_TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(ZoneOffset.UTC)

    /** Build the wire payload from the underlying numeric fields. */
    fun build(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speed: Double,
        bearing: Double,
        accuracy: Double,
    ): LocationReportPayload =
        LocationReportPayload(
            timestamp = formatBodyTimestamp(timestampMillis),
            latitude = formatNumber(latitude),
            longitude = formatNumber(longitude),
            altitude = formatNumber(altitude),
            speed = formatNumber(speed),
            bearing = formatNumber(bearing),
            accuracy = formatNumber(accuracy),
        )

    /**
     * Build the HMAC signature input from the underlying numeric fields.
     *
     * The format differs from [build] in two ways: the timestamp omits sub-second precision, and
     * the values are concatenated with no separators or JSON envelope. The difference is a relic of
     * earlier versions of the API, which sent the actual values as query parameters rather than as
     * JSON.
     */
    fun signatureInput(
        timestampMillis: Long,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speed: Double,
        bearing: Double,
        accuracy: Double,
    ): String = buildString {
      append(formatSignatureTimestamp(timestampMillis))
      append(formatNumber(latitude))
      append(formatNumber(longitude))
      append(formatNumber(altitude))
      append(formatNumber(speed))
      append(formatNumber(bearing))
      append(formatNumber(accuracy))
    }

    private fun formatNumber(number: Double): String {
      val output = String.format(Locale.US, "%.12f", number)

      // Normalize signed-zero to positive zero to ensure consistent signature input. The comparison
      // is intentionally done on the formatted string to ensure that anything that would be
      // formatted as "-0.000000000000" is normalized, such as tiny negative numbers. The server
      // formats the numbers independently and calculates the HMAC over its own output, so a sign
      // mismatch breaks signature validation. Revisit when the v2 API is implemented.
      if (output == "-0.000000000000") {
        return "0.000000000000"
      }

      return output
    }

    private fun formatBodyTimestamp(timestamp: Long): String =
        BODY_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp))

    private fun formatSignatureTimestamp(timestamp: Long): String =
        SIGNATURE_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestamp))
  }
}
