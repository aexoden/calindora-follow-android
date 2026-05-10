package com.calindora.follow

import android.content.ServiceConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FollowServiceControllerTest {
  @Test
  fun `bind invokes connector and is idempotent`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)

    controller.bind()
    controller.bind()

    assertEquals(1, connector.bindCount)
  }

  @Test
  fun `unbind without prior bind is a no-op`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)

    controller.unbind()

    assertEquals(0, connector.unbindCount)
  }

  @Test
  fun `unbind after bind invokes connector exactly once`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)

    controller.bind()
    controller.unbind()
    controller.unbind()

    assertEquals(1, connector.unbindCount)
  }

  @Test
  fun `bind that the connector rejects does not flip the bound flag`() = runTest {
    val connector = FakeConnector(bindResult = false)
    val controller = FollowServiceController(connector = connector, scope = this)

    controller.bind()
    controller.unbind()

    // bind was attempted but rejected; we must not call unbind on the system after a failed bind.
    assertEquals(1, connector.bindCount)
    assertEquals(0, connector.unbindCount)
  }

  @Test
  fun `start calls startForeground and binds`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)

    controller.start()

    assertEquals(1, connector.startForegroundCount)
    assertEquals(1, connector.bindCount)
  }

  @Test
  fun `stop unbinds and asks the system to stop the service when bound`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)
    controller.bind()

    controller.stop()

    assertEquals(1, connector.unbindCount)
    assertEquals(1, connector.stopCount)
  }

  @Test
  fun `stop only asks the system to stop the service when not bound`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)

    controller.stop()

    assertEquals(0, connector.unbindCount)
    assertEquals(1, connector.stopCount)
  }

  @Test
  fun `state stays null until onConnected fires`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)

    controller.bind()

    assertNull(controller.state.value)
  }

  @Test
  fun `onConnected starts collecting state from the bound service`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)
    val handle = FakeHandle()

    controller.onConnected(handle)
    advanceUntilIdle()

    assertEquals(handle.state.value, controller.state.value)

    handle.state.value = FollowService.ServiceState(tracking = true)
    advanceUntilIdle()

    assertEquals(true, controller.state.value?.tracking)

    controller.onDisconnected()
  }

  @Test
  fun `onDisconnected clears state and stops collecting`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)
    val handle = FakeHandle()

    controller.onConnected(handle)
    handle.state.value = FollowService.ServiceState(tracking = true)
    advanceUntilIdle()

    controller.onDisconnected()
    assertNull(controller.state.value)

    // Subsequent emissions on the (now-orphaned) handle must not bleed back into our state.
    handle.state.value = FollowService.ServiceState(logging = true)
    advanceUntilIdle()
    assertNull(controller.state.value)
  }

  @Test
  fun `unbind clears state collection`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)
    val handle = FakeHandle()

    controller.bind()
    controller.onConnected(handle)
    handle.state.value = FollowService.ServiceState(tracking = true)
    advanceUntilIdle()

    controller.unbind()

    assertNull(controller.state.value)
  }

  @Test
  fun `setTracking forwards to the handle when bound`() = runTest {
    val controller = FollowServiceController(connector = FakeConnector(), scope = this)
    val handle = FakeHandle()
    controller.onConnected(handle)

    controller.setTracking(true)

    assertTrue(handle.tracking)

    controller.onDisconnected()
  }

  @Test
  fun `setTracking is a silent no-op when not bound`() = runTest {
    val controller = FollowServiceController(connector = FakeConnector(), scope = this)

    controller.setTracking(true) // must not throw
  }

  @Test
  fun `setLogging returns true when not bound`() = runTest {
    val controller = FollowServiceController(connector = FakeConnector(), scope = this)

    assertTrue(controller.setLogging(true))
  }

  @Test
  fun `setLogging forwards the handle's result when bound`() = runTest {
    val controller = FollowServiceController(connector = FakeConnector(), scope = this)
    val handle = FakeHandle(loggingResult = false)
    controller.onConnected(handle)

    assertFalse(controller.setLogging(true))
    assertEquals(listOf(true), handle.loggingCalls)

    controller.onDisconnected()
  }

  @Test
  fun `stop disables logging and tracking on the handle before unbinding`() = runTest {
    val connector = FakeConnector()
    val controller = FollowServiceController(connector = connector, scope = this)
    val handle = FakeHandle()
    handle.tracking = true
    controller.bind()
    controller.onConnected(handle)

    controller.stop()

    assertEquals(listOf(false), handle.loggingCalls)
    assertFalse(handle.tracking)
    assertEquals(1, connector.unbindCount)
    assertEquals(1, connector.stopCount)
  }
}

// Test Doubles

private class FakeConnector(private val bindResult: Boolean = true) : FollowServiceConnector {
  var bindCount = 0
    private set

  var unbindCount = 0
    private set

  var startForegroundCount = 0
    private set

  var stopCount = 0
    private set

  override fun bind(connection: ServiceConnection): Boolean {
    bindCount++
    return bindResult
  }

  override fun unbind(connection: ServiceConnection) {
    unbindCount++
  }

  override fun startForeground() {
    startForegroundCount++
  }

  override fun stop() {
    stopCount++
  }
}

private class FakeHandle(private val loggingResult: Boolean = true) : FollowServiceHandle {
  override val state: MutableStateFlow<FollowService.ServiceState> =
      MutableStateFlow(FollowService.ServiceState())

  override var tracking: Boolean = false

  val loggingCalls = mutableListOf<Boolean>()

  override fun setLogging(enabled: Boolean): Boolean {
    loggingCalls.add(enabled)
    return loggingResult
  }
}
