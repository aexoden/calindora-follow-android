package com.calindora.follow

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches for the device gaining validated, non-VPN internet and resets the [SubmissionWorker]
 * unique-work backoff so a queued retry runs immediately rather than waiting out the remainder of
 * its linear backoff window.
 *
 * Background: WorkManager's [androidx.work.NetworkType.CONNECTED] constraint counts a VPN tunnel
 * with no underlying transport as "connected", so the worker can run and fail repeatedly while the
 * device is in a real dead zone, accumulating backoff. When real connectivity returns, the next
 * scheduled retry can be many minutes out. This monitor attempts to short-circuit that wait without
 * altering the WorkManager constraint itself, as past attempts to use a stricter [NetworkRequest]
 * as the constraint produced cases where work never ran at all for no obvious reason.
 */
class SubmissionBackoffManager(private val context: Context) {
  private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  private val activeNetworks = mutableSetOf<Network>()

  private val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          val gained =
              synchronized(activeNetworks) {
                val wasEmpty = activeNetworks.isEmpty()
                activeNetworks.add(network)
                wasEmpty
              }

          if (gained) {
            Log.d(TAG, "Validated connectivity available; checking for stalled submission work")
            scope.launch { resetIfBackedOff() }
          }
        }

        override fun onLost(network: Network) {
          synchronized(activeNetworks) { activeNetworks.remove(network) }
        }
      }

  fun start() {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
    val request =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

    try {
      cm.registerNetworkCallback(request, callback)
    } catch (e: SecurityException) {
      Log.w(TAG, "Failed to register network callback; backoff rests disabled", e)
    } catch (e: RuntimeException) {
      Log.w(TAG, "Network callback registration rejected", e)
    }
  }

  private suspend fun resetIfBackedOff() {
    val wm = WorkManager.getInstance(context)
    val info =
        wm.getWorkInfosForUniqueWorkFlow(SubmissionWorker.UNIQUE_WORK_NAME).first().firstOrNull()
            ?: return

    if (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0) {
      Log.d(
          TAG,
          "Resetting submission backoff after connectivity recovery " +
              "(runAttemptCount=${info.runAttemptCount})",
      )
    }
    wm.enqueueUniqueWork(
        SubmissionWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        SubmissionWorker.buildWorkRequest(),
    )
  }

  private companion object {
    const val TAG = "SubmissionBackoffManager"
  }
}
