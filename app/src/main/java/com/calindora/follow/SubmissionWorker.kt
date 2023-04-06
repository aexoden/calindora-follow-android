package com.calindora.follow

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

class SubmissionWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val url = formatUrl()
            val signature = formatSignature()
            val body = inputData.getString("body")

            var connection: HttpsURLConnection? = null

            try {
                connection = (URL(url)).openConnection() as? HttpsURLConnection
                connection?.doInput = true
                connection?.doOutput = true
                connection?.connectTimeout = 5000
                connection?.readTimeout = 5000

                connection?.setRequestProperty("Content-Type", "application/json")
                connection?.setRequestProperty("Accept", "application/json")
                connection?.setRequestProperty("X-Signature", signature)

                val out = OutputStreamWriter(connection?.outputStream)
                out.write(body)
                out.close()

                return@withContext if (connection?.responseCode == HttpURLConnection.HTTP_CREATED) {
                    Result.success(workDataOf("submission_time" to System.currentTimeMillis()))
                } else {
                    error()
                }
            } catch (e: IOException) {
                return@withContext error()
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun error(): Result {
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        return if (preferences.getBoolean("preference_cancel_on_failure", false)) {
            val editor = preferences.edit()
            editor.putBoolean("preference_cancel_on_failure", false)
            editor.apply()

            Result.success()
        } else {
            Result.retry()
        }
    }

    private fun formatSignature(): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val secret = preferences.getString("preference_device_secret", "") ?: return ""

        val input = inputData.getString("signatureInput")?.replace("-0.000000000000", "0.000000000000")
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), mac.algorithm)
        mac.init(key)

        val digest = mac.doFinal(input.toString().toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun formatUrl(): String {
        val preferences = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val url = preferences.getString("preference_url", "") ?: return ""
        val key = preferences.getString("preference_device_key", "") ?: return ""
        return String.format("%s/api/v1/devices/%s/reports", url, key)
    }
}
