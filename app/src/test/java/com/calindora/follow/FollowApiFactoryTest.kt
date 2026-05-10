package com.calindora.follow

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class FollowApiFactoryTest {
  @Test
  fun `create returns the same instance for repeated calls with the same base URL`() {
    val first = FollowApiFactory.create("https://example.com")
    val second = FollowApiFactory.create("https://example.com")
    assertSame(first, second)
  }

  @Test
  fun `create normalizes trailing slashes for the cache key`() {
    val withSlash = FollowApiFactory.create("https://example.org/")
    val withoutSlash = FollowApiFactory.create("https://example.org")
    assertSame(withSlash, withoutSlash)
  }

  @Test
  fun `create rebuilds when the base URL changes and then caches the new URL`() {
    val first = FollowApiFactory.create("https://a.example.com")
    val second = FollowApiFactory.create("https://b.example.com")
    assertNotSame(first, second)

    // The cache now tracks the most recent URL.
    val third = FollowApiFactory.create("https://b.example.com")
    assertSame(second, third)
  }
}
