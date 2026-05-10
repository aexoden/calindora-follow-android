package com.calindora.follow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The slice of [FollowService] that [FollowServiceController] consumes through the bound
 * connection. [FollowService] implements this interface directory; tests can substitute a fake.
 */
interface FollowServiceHandle {
  val state: StateFlow<FollowService.ServiceState>
  var tracking: Boolean

  fun setLogging(enabled: Boolean): Boolean
}

/**
 * Indirection over the four [Context] calls [FollowServiceController] needs to bind, unbind, start,
 * and stop the foreground service. Production wiring is [ContextFollowServiceConnector]; tests can
 * substitute a fake so the controller can run on the JVM.
 */
interface FollowServiceConnector {
  /** @return true if the system accepted the bind request and will deliver an async callback. */
  fun bind(connection: ServiceConnection): Boolean

  fun unbind(connection: ServiceConnection)

  fun startForeground()

  fun stop()
}

/** [Context]-backed [FollowServiceConnector]. */
class ContextFollowServiceConnector(private val context: Context) : FollowServiceConnector {
  override fun bind(connection: ServiceConnection): Boolean {
    val intent = Intent(context, FollowService::class.java)
    return context.bindService(intent, connection, 0)
  }

  override fun unbind(connection: ServiceConnection) {
    context.unbindService(connection)
  }

  override fun startForeground() {
    val intent = Intent(context, FollowService::class.java)
    context.startForegroundService(intent)
  }

  override fun stop() {
    val intent = Intent(context, FollowService::class.java)
    context.stopService(intent)
  }
}

/**
 * Manages a bound connection to [FollowService] for an Activity, owning the [ServiceConnection],
 * the bound [FollowServiceHandle], and the state-collection coroutine.
 */
class FollowServiceController(
    private val connector: FollowServiceConnector,
    private val scope: CoroutineScope,
) {
  private var handle: FollowServiceHandle? = null
  private var stateCollectionJob: Job? = null
  private var bound = false

  private val _state = MutableStateFlow<FollowService.ServiceState?>(null)

  /** Latest snapshot from the bound service, or `null` if not bound. */
  val state: StateFlow<FollowService.ServiceState?> = _state.asStateFlow()

  private val connection =
      object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
          onConnected((service as FollowService.FollowBinder).getService())
        }

        override fun onServiceDisconnected(name: ComponentName) {
          onDisconnected()
        }
      }

  /** Bind to the service. Safe to call multiple times; will no-op if already bound. */
  fun bind() {
    if (bound) return
    if (connector.bind(connection)) {
      bound = true
    }
  }

  /** Unbind from the service. Safe to call multiple times; will no-op if already unbound. */
  fun unbind() {
    if (!bound) return
    connector.unbind(connection)
    bound = false
    clearHandle()
  }

  /** Start the foreground service and bind to it. */
  fun start() {
    connector.startForeground()
    bind()
  }

  /**
   * Turn off tracking and logging on the bound service, unbind, and ask the system to stop the
   * foreground service.
   */
  fun stop() {
    handle?.let {
      it.setLogging(false)
      it.tracking = false
    }
    unbind()
    connector.stop()
  }

  /** Set the bound service's tracking flag. Safe to call when not bound; will no-op. */
  fun setTracking(enabled: Boolean) {
    handle?.tracking = enabled
  }

  /**
   * Forward a logging state change to the bound service.
   *
   * @return `true` if the request succeeded, or if no service is bound. `false` only when the bound
   *   service refused the request.
   */
  fun setLogging(enabled: Boolean): Boolean {
    val service = handle ?: return true
    return service.setLogging(enabled)
  }

  @VisibleForTesting
  internal fun onConnected(handle: FollowServiceHandle) {
    this.handle = handle
    stateCollectionJob = scope.launch { handle.state.collect { _state.value = it } }
  }

  @VisibleForTesting
  internal fun onDisconnected() {
    clearHandle()
  }

  private fun clearHandle() {
    handle = null
    stateCollectionJob?.cancel()
    stateCollectionJob = null
    _state.value = null
  }
}
