package com.calindora.follow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PreferencesFlowTest {

  // credentialStatusFlow

  @Test
  fun `credentialStatusFlow emits INITIAL when prefs are empty`() = runTest {
    val store = StateFakeDataStore()
    assertEquals(CredentialStatus.INITIAL, credentialStatusFlow(store).first())
  }

  @Test
  fun `credentialStatusFlow emits isBlocked=true when KEY_SUBMISSIONS_BLOCKED is true`() = runTest {
    val store = StateFakeDataStore(preferencesOf(AppPreferences.KEY_SUBMISSIONS_BLOCKED to true))
    val status = credentialStatusFlow(store).first()
    assertEquals(true, status.isBlocked)
    assertEquals(0, status.consecutiveAuthFailures)
  }

  @Test
  fun `credentialStatusFlow surfaces consecutiveAuthFailures from prefs`() = runTest {
    val store = StateFakeDataStore(preferencesOf(AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES to 4))
    val status = credentialStatusFlow(store).first()
    assertEquals(false, status.isBlocked)
    assertEquals(4, status.consecutiveAuthFailures)
  }

  @Test
  fun `credentialStatusFlow emits a new value when underlying prefs change`() = runTest {
    val store = StateFakeDataStore()
    val emissions = mutableListOf<CredentialStatus>()
    // Set the next value before subscribing so both snapshots are observable from a finite take.
    store.state.value = preferencesOf(AppPreferences.KEY_SUBMISSIONS_BLOCKED to true)
    credentialStatusFlow(store).take(1).toList(emissions)
    assertEquals(1, emissions.size)
    assertEquals(true, emissions[0].isBlocked)
  }

  @Test
  fun `credentialStatusFlow catches IOException upstream and emits defaults`() = runTest {
    val store = ErrorFakeDataStore(IOException("disk full"))
    assertEquals(CredentialStatus.INITIAL, credentialStatusFlow(store).first())
  }

  @Test
  fun `credentialStatusFlow rethrows non-IOException`() = runTest {
    val store = ErrorFakeDataStore(IllegalStateException("boom"))
    try {
      credentialStatusFlow(store).first()
      fail("expected IllegalStateException")
    } catch (e: IllegalStateException) {
      assertEquals("boom", e.message)
    }
  }

  @Test
  fun `credentialStatusFlow distinctUntilChanged suppresses duplicate emissions`() = runTest {
    // Push two distinct Preferences instances whose mapped CredentialStatus values are equal, then
    // a genuinely different value. take(2) only completes when distinctUntilChanged lets a second
    // value through, proving the duplicate was suppressed.
    val store =
        ListFakeDataStore(
            listOf(
                preferencesOf(AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES to 1),
                preferencesOf(AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES to 1),
                preferencesOf(AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES to 2),
            )
        )
    val emissions = credentialStatusFlow(store).take(2).toList()
    assertEquals(listOf(1, 2), emissions.map { it.consecutiveAuthFailures })
  }

  // displayPreferencesFlow

  @Test
  fun `displayPreferencesFlow emits DEFAULT when prefs are empty`() = runTest {
    val store = StateFakeDataStore()
    assertEquals(DisplayPreferences.DEFAULT, displayPreferencesFlow(store).first())
  }

  @Test
  fun `displayPreferencesFlow falls back to DEFAULT for unrecognized unit keys`() = runTest {
    val store =
        StateFakeDataStore(
            preferencesOf(
                AppPreferences.KEY_DISTANCE_UNIT to "no_such_unit",
                AppPreferences.KEY_SPEED_UNIT to "also_bogus",
            )
        )
    assertEquals(DisplayPreferences.DEFAULT, displayPreferencesFlow(store).first())
  }

  @Test
  fun `displayPreferencesFlow surfaces saved DistanceUnit and SpeedUnit`() = runTest {
    val store =
        StateFakeDataStore(
            preferencesOf(
                AppPreferences.KEY_DISTANCE_UNIT to DistanceUnit.FEET.name,
                AppPreferences.KEY_SPEED_UNIT to SpeedUnit.KNOTS.name,
            )
        )
    val prefs = displayPreferencesFlow(store).first()
    assertEquals(DistanceUnit.FEET, prefs.distanceUnit)
    assertEquals(SpeedUnit.KNOTS, prefs.speedUnit)
  }

  @Test
  fun `displayPreferencesFlow catches IOException upstream and emits DEFAULT`() = runTest {
    val store = ErrorFakeDataStore(IOException("read failure"))
    assertEquals(DisplayPreferences.DEFAULT, displayPreferencesFlow(store).first())
  }

  // Test doubles

  /**
   * [DataStore] backed by a [MutableStateFlow]; lets tests poke updates by assigning [state]. Used
   * for happy-path tests where only the latest snapshot matters.
   */
  private class StateFakeDataStore(initial: Preferences = emptyPreferences()) :
      DataStore<Preferences> {
    val state = MutableStateFlow(initial)

    override val data: Flow<Preferences>
      get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      state.value = transform(state.value)
      return state.value
    }
  }

  /**
   * [DataStore] whose [data] flow throws [error] on collection. Used to verify the production
   * code's `.catch` block.
   */
  private class ErrorFakeDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw UnsupportedOperationException("not used in error fake")
  }

  /**
   * [DataStore] whose [data] flow emits a fixed sequence of snapshots, then completes. Used to
   * exercise [kotlinx.coroutines.flow.distinctUntilChanged] without StateFlow conflation.
   */
  private class ListFakeDataStore(private val emissions: List<Preferences>) :
      DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { emissions.forEach { emit(it) } }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw UnsupportedOperationException("not used in list fake")
  }
}
