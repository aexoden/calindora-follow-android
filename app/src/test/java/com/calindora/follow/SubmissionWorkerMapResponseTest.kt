package com.calindora.follow

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SubmissionWorkerMapResponseTest {
  @Test
  fun `201 Created maps to Success`() {
    val result = mapResponseToResult(Response.success(201, Unit))
    assertEquals(SubmissionResult.Success, result)
  }

  @Test
  fun `200 OK is unexpected and maps to TransientError`() {
    // 200 isn't part of the v1 contract; the server returns 201 on success. Anything else routes
    // through the unexpected branch.
    val result = mapResponseToResult(Response.success(200, Unit))
    assertTrue(result is SubmissionResult.TransientError)
    assertEquals(200, (result as SubmissionResult.TransientError).errorCode)
    assertEquals("Unexpected error", result.errorMessage)
  }

  @Test
  fun `401 Unauthorized maps to ConfigurationError carrying the body text`() {
    val result = mapResponseToResult(errorResponse(401, "The provided signature was invalid"))
    assertTrue(result is SubmissionResult.ConfigurationError)
    val err = result as SubmissionResult.ConfigurationError
    assertEquals(401, err.errorCode)
    assertEquals("The provided signature was invalid", err.errorMessage)
  }

  @Test
  fun `401 with empty body falls back to default Unauthorized message`() {
    val result = mapResponseToResult(errorResponse(401, ""))
    assertTrue(result is SubmissionResult.ConfigurationError)
    assertEquals("Unauthorized", (result as SubmissionResult.ConfigurationError).errorMessage)
  }

  @Test
  fun `404 Not Found maps to ConfigurationError with Unknown device key`() {
    // Body content is intentionally ignored for 404 — message is constant.
    val result = mapResponseToResult(errorResponse(404, "anything goes here"))
    assertTrue(result is SubmissionResult.ConfigurationError)
    val err = result as SubmissionResult.ConfigurationError
    assertEquals(404, err.errorCode)
    assertEquals("Unknown device key", err.errorMessage)
  }

  @Test
  fun `400 Bad Request maps to PermanentError`() {
    val result = mapResponseToResult(errorResponse(400, "bad request"))
    assertTrue(result is SubmissionResult.PermanentError)
    val err = result as SubmissionResult.PermanentError
    assertEquals(400, err.errorCode)
    assertEquals("bad request", err.errorMessage)
  }

  @Test
  fun `400 with empty body falls back to default client error message`() {
    val result = mapResponseToResult(errorResponse(400, ""))
    assertTrue(result is SubmissionResult.PermanentError)
    assertEquals("Client error 400", (result as SubmissionResult.PermanentError).errorMessage)
  }

  @Test
  fun `413 Entity Too Large maps to PermanentError`() {
    val result = mapResponseToResult(errorResponse(413, "too big"))
    assertTrue(result is SubmissionResult.PermanentError)
    assertEquals(413, (result as SubmissionResult.PermanentError).errorCode)
  }

  @Test
  fun `422 Unprocessable Entity maps to PermanentError`() {
    val result = mapResponseToResult(errorResponse(422, "validation failed"))
    assertTrue(result is SubmissionResult.PermanentError)
    val err = result as SubmissionResult.PermanentError
    assertEquals(422, err.errorCode)
    assertEquals("validation failed", err.errorMessage)
  }

  @Test
  fun `408 Request Timeout maps to TransientError`() {
    val result = mapResponseToResult(errorResponse(408, ""))
    assertTrue(result is SubmissionResult.TransientError)
    assertEquals(408, (result as SubmissionResult.TransientError).errorCode)
    assertEquals("Server error", result.errorMessage)
  }

  @Test
  fun `429 Too Many Requests maps to TransientError`() {
    val result = mapResponseToResult(errorResponse(429, ""))
    assertTrue(result is SubmissionResult.TransientError)
    assertEquals(429, (result as SubmissionResult.TransientError).errorCode)
  }

  @Test
  fun `500 503 and 599 each map to TransientError`() {
    for (code in listOf(500, 503, 599)) {
      val result = mapResponseToResult(errorResponse(code, ""))
      assertTrue("code=$code", result is SubmissionResult.TransientError)
      assertEquals(code, (result as SubmissionResult.TransientError).errorCode)
      assertEquals("Server error", result.errorMessage)
    }
  }

  @Test
  fun `418 falls through to TransientError as unexpected`() {
    val result = mapResponseToResult(errorResponse(418, ""))
    assertTrue(result is SubmissionResult.TransientError)
    assertEquals(418, (result as SubmissionResult.TransientError).errorCode)
    assertEquals("Unexpected error", result.errorMessage)
  }

  private fun errorResponse(code: Int, body: String): Response<Unit> =
      Response.error(code, body.toResponseBody(CONTENT_TYPE))

  private companion object {
    val CONTENT_TYPE = "text/plain; charset=utf-8".toMediaTypeOrNull()
  }
}
