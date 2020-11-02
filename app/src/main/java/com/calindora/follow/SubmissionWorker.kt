package com.calindora.follow

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class SubmissionWorker(appContext: Context, workerParams: WorkerParameters): Worker(appContext, workerParams) {
    override fun doWork(): Result {
        val url = inputData.getString("url")
        val parameters = inputData.getString("parameters")

        var connection: HttpsURLConnection? = null
        var success = false

        try {
            connection = (URL(url)).openConnection() as? HttpsURLConnection
            connection?.doOutput = true

            val out = BufferedWriter(OutputStreamWriter(connection?.outputStream))

            out.write(parameters)
            out.close()

            return if (connection?.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(workDataOf("submission_time" to System.currentTimeMillis()))
            } else {
                Result.retry()
            }
        } catch (e: IOException) {
            return Result.retry()
        } finally {
            connection?.disconnect()
        }
    }
}