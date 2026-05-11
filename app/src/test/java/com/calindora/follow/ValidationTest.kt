package com.calindora.follow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationTest {
  @Test
  fun `empty input returns Empty error`() {
    assertEquals(UrlValidationError.Empty, validateServiceUrl(""))
    assertEquals(UrlValidationError.Empty, validateServiceUrl("   "))
    assertEquals(UrlValidationError.Empty, validateServiceUrl("/"))
  }

  @Test
  fun `input with no scheme is Malformed`() {
    assertEquals(UrlValidationError.Malformed, validateServiceUrl("example.com"))
    assertEquals(UrlValidationError.Malformed, validateServiceUrl("not a url"))
  }

  @Test
  fun `non-https schemes are rejected`() {
    assertEquals(UrlValidationError.NotHttps, validateServiceUrl("http://example.com"))
    assertEquals(UrlValidationError.NotHttps, validateServiceUrl("ftp://example.com"))
  }

  @Test
  fun `https without a host is rejected`() {
    assertEquals(UrlValidationError.NoHost, validateServiceUrl("https:///path"))
    assertEquals(UrlValidationError.NoHost, validateServiceUrl("https://"))
  }

  @Test
  fun `valid https URLs pass`() {
    assertNull(validateServiceUrl("https://example.com"))
    assertNull(validateServiceUrl("https://example.com/"))
    assertNull(validateServiceUrl("https://example.com/api/v1"))
    assertNull(validateServiceUrl("  https://example.com  "))
    assertNull(validateServiceUrl("https://follow.calindora.com"))
  }

  @Test
  fun `https URLs with ports and queries pass`() {
    assertNull(validateServiceUrl("https://example.com:8443"))
    assertNull(validateServiceUrl("https://example.com/api?v=1"))
  }

  @Test
  fun `URLs with malformed authority are rejected`() {
    // HttpUrl is stricter than java.net.URL — a non-numeric port slips past `URL(...)` but is
    // rejected here, well before Retrofit would have to handle it.
    assertEquals(UrlValidationError.Malformed, validateServiceUrl("https://example.com:abc"))
  }
}
