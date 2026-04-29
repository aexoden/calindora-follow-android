package com.calindora.follow

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** Shared JSON format for wire payloads and persisted bodies. */
internal val FollowJson: Json = Json

internal interface FollowApi {
  @POST("api/v1/devices/{key}/reports")
  suspend fun submitReport(
      @Path("key") deviceKey: String,
      @Header("X-Signature") signature: String,
      @Body payload: LocationReportPayload,
  ): Response<Unit>
}

internal object FollowApiFactory {

  private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(Config.Network.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Config.Network.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply {
          if (BuildConfig.DEBUG) {
            // HEADERS, not BODY - bodies contain location data we don't want to log
            addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }
            )
          }
        }
        .build()
  }

  fun create(baseUrl: String): FollowApi {
    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    val mediaType = "application/json".toMediaType()
    return Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(httpClient)
        .addConverterFactory(FollowJson.asConverterFactory(mediaType))
        .build()
        .create(FollowApi::class.java)
  }
}
