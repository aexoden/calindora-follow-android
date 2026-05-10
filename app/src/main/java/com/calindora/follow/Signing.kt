package com.calindora.follow

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Compute the lowercase hex HMAC-SHA-256 of [input] keyed by [secret]. */
internal fun hmacSha256Hex(input: String, secret: String): String {
  val mac = Mac.getInstance("HmacSHA256")
  mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), mac.algorithm))
  val digest = mac.doFinal(input.toByteArray(Charsets.UTF_8))
  return digest.toHexString()
}
