package com.calindora.follow

import kotlinx.serialization.Serializable

/**
 * Wire format for location reports submitted to the server.
 *
 * All numeric fields are sent as preformatted strings: floats use 12 decimal places, the timestamp
 * is ISO-8601 with millisecond precision and a UTC offset.
 *
 * The HMAC signature input is a separate concatenation built independently of this body - see
 * [FollowService.Report.formatSignatureInput].
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
)
