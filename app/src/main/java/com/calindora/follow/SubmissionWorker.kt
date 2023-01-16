package com.calindora.follow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class SubmissionWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val url = inputData.getString("url")
            val signature = inputData.getString("signature")
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
                    Result.retry()
                }
            } catch (e: IOException) {
                return@withContext Result.retry()
            } finally {
                connection?.disconnect()
            }
        }
    }
}
