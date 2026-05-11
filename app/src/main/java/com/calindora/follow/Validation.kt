package com.calindora.follow

import androidx.annotation.StringRes
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed class UrlValidationError(@param:StringRes val errorRes: Int, val description: String) {
  data object Empty :
      UrlValidationError(R.string.error_url_required, "Service URL is not configured")

  data object Malformed :
      UrlValidationError(R.string.error_url_malformed, "Service URL is not a valid URL")

  data object NotHttps : UrlValidationError(R.string.error_url_https, "Service URL must use HTTPS")

  data object NoHost :
      UrlValidationError(R.string.error_url_no_host, "Service URL must have a valid host")
}

/**
 * RFC 3986 scheme grammar, used to split the scheme off up front. This allows for more specific
 * error messages than we could do with [okhttp3.HttpUrl] alone.
 */
private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/**
 * Validate a service URL entered by the user. Returns null if the URL is acceptable, otherwise a
 * specific [UrlValidationError].
 */
fun validateServiceUrl(input: String): UrlValidationError? {
  val trimmed = input.trim()
  if (trimmed.all { it == '/' }) return UrlValidationError.Empty

  val schemeMatch = SCHEME_REGEX.find(trimmed) ?: return UrlValidationError.Malformed
  if (!schemeMatch.groupValues[1].equals("https", ignoreCase = true)) {
    return UrlValidationError.NotHttps
  }

  // Scheme is https. Check for an empty host.
  val afterSeparator = trimmed.substring(schemeMatch.range.last + 1)
  if (afterSeparator.isEmpty() || afterSeparator.startsWith("/")) return UrlValidationError.NoHost

  return if (trimmed.toHttpUrlOrNull() != null) null else UrlValidationError.Malformed
}
