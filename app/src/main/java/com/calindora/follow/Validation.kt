package com.calindora.follow

import androidx.annotation.StringRes
import java.net.MalformedURLException
import java.net.URL

sealed class UrlValidationError(@param:StringRes val errorRes: Int, val description: String) {
  data object Empty :
      UrlValidationError(R.string.error_url_required, "Service URL is not configured")

  data object Malformed :
      UrlValidationError(R.string.error_url_malformed, "Service URL is not a valid URL")

  data object NotHttps : UrlValidationError(R.string.error_url_https, "Service URL must use HTTPS")

  data object NoHost :
      UrlValidationError(R.string.error_url_no_host, "Service URL must have a valid host")
}

fun validateServiceUrl(input: String): UrlValidationError? {
  val trimmed = input.trim().trimEnd('/')
  if (trimmed.isEmpty()) return UrlValidationError.Empty

  val parsed =
      try {
        URL(trimmed)
      } catch (_: MalformedURLException) {
        return UrlValidationError.Malformed
      }

  if (parsed.protocol != "https") return UrlValidationError.NotHttps
  if (parsed.host.isNullOrEmpty()) return UrlValidationError.NoHost
  return null
}
