package com.calindora.follow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for [hmacSha256Hex].
 *
 * Both vectors are taken from RFC 4231, section 4.
 */
class SigningTest {
  /** RFC 4231 Test Case 1. */
  @Test
  fun `RFC 4231 test case 1 - 20 byte 0x0b key`() {
    val actual = hmacSha256Hex(input = "Hi There", secret = "\u000b".repeat(20))
    assertEquals(
        "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
        actual,
    )
  }

  /** RFC 4231 Test Case 2. */
  @Test
  fun `RFC 4231 test case 2 - ASCII key and data`() {
    val actual = hmacSha256Hex(input = "what do ya want for nothing?", secret = "Jefe")
    assertEquals(
        "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
        actual,
    )
  }
}
