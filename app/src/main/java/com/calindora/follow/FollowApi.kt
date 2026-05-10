package com.calindora.follow

import android.os.Build
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
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
  /**
   * User-Agent header sent on every request. Identifies the client and version so that server-side
   * log triage can distinguish this client from others, and one client version from another.
   */
  private val USER_AGENT: String =
      "calindora-follow/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"

  private val userAgentInterceptor = Interceptor { chain ->
    val request = chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
    chain.proceed(request)
  }

  private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(Config.Network.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(Config.Network.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(userAgentInterceptor)
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

  // Single-entry cache. The user has one configured service URL at a time; on the rare URL change
  // we rebuild and drop the previous proxy.
  private var cachedBaseUrl: String? = null
  private var cachedApi: FollowApi? = null

  @Synchronized
  fun create(baseUrl: String): FollowApi {
    val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    cachedApi
        ?.takeIf { cachedBaseUrl == normalizedBaseUrl }
        ?.let {
          return it
        }

    val mediaType = "application/json".toMediaType()
    val api =
        Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(httpClient)
            .addConverterFactory(FollowJson.asConverterFactory(mediaType))
            .build()
            .create(FollowApi::class.java)

    cachedBaseUrl = normalizedBaseUrl
    cachedApi = api
    return api
  }
}
