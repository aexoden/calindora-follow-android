package com.calindora.follow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  private val dispatcher = UnconfinedTestDispatcher()

  @Before
  fun setUpMainDispatcher() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDownMainDispatcher() {
    Dispatchers.resetMain()
  }

  // Initial state load

  @Test
  fun `init loads serviceUrl, deviceKey, units, and credential state from DataStore`() = runTest {
    val initial =
        mutablePreferencesOf().apply {
          set(AppPreferences.KEY_SERVICE_URL, "https://example.org")
          set(AppPreferences.KEY_DEVICE_KEY, "device-123")
          set(AppPreferences.KEY_DISTANCE_UNIT, DistanceUnit.FEET.name)
          set(AppPreferences.KEY_SPEED_UNIT, SpeedUnit.KNOTS.name)
          set(AppPreferences.KEY_SUBMISSIONS_BLOCKED, true)
          set(AppPreferences.KEY_CONSECUTIVE_AUTH_FAILURES, 3)
        }
    val (vm, _) =
        newViewModel(
            prefs = initial,
            secret = "the-secret",
            // The runtime observer is a derived view of the same prefs; pass a flow that matches
            // the seed so it doesn't immediately overwrite the loaded state.
            credentialStatusFlow =
                MutableStateFlow(CredentialStatus(isBlocked = true, consecutiveAuthFailures = 3)),
        )
    advanceUntilIdle()

    val state = vm.uiState.value
    assertEquals("https://example.org", state.serviceUrl)
    assertEquals("device-123", state.deviceKey)
    assertEquals("the-secret", state.deviceSecret)
    assertEquals(DistanceUnit.FEET, state.distanceUnit)
    assertEquals(SpeedUnit.KNOTS, state.speedUnit)
    assertEquals(true, state.isCredentialBlocked)
    assertEquals(3, state.consecutiveAuthFailures)
    assertFalse(state.isLoading)
  }

  @Test
  fun `init falls back to defaults when DataStore throws IOException on first read`() = runTest {
    val store = ThrowingFirstReadDataStore(IOException("disk gone"))
    val secret = MutableSecretStore("ignored")
    val dao = FakeLocationReportDao()
    val actions = StubSettingsActions()
    val vm =
        SettingsViewModel(
            locationReportDao = dao,
            settingsDataStore = store,
            encryptedSecretStore = secret,
            credentialStatusFlow = MutableStateFlow(CredentialStatus.INITIAL),
            settingsRepository = actions,
        )
    advanceUntilIdle()

    val state = vm.uiState.value
    assertEquals(AppPreferences.DEFAULT_SERVICE_URL, state.serviceUrl)
    assertEquals("", state.deviceKey)
    assertEquals(DistanceUnit.DEFAULT, state.distanceUnit)
    assertEquals(SpeedUnit.DEFAULT, state.speedUnit)
    assertFalse(state.isCredentialBlocked)
    assertFalse(state.isLoading)
  }

  @Test
  fun `init falls back to empty secret when EncryptedSecretStore throws IOException`() = runTest {
    val (vm, _) =
        newViewModel(
            prefs = preferencesOf(AppPreferences.KEY_DEVICE_KEY to "k"),
            secretStore = ThrowingSecretStore(IOException("keystore unavailable")),
        )
    advanceUntilIdle()

    assertEquals("", vm.uiState.value.deviceSecret)
    assertEquals("k", vm.uiState.value.deviceKey)
  }

  @Test
  fun `init writes DEFAULT_SERVICE_URL on first run when KEY_SERVICE_URL is absent`() = runTest {
    val (_, store) = newViewModel(prefs = emptyPreferences())
    advanceUntilIdle()

    assertEquals(
        AppPreferences.DEFAULT_SERVICE_URL,
        store.state.value[AppPreferences.KEY_SERVICE_URL],
    )
  }

  @Test
  fun `init does not overwrite an existing service URL`() = runTest {
    val existing = "https://different.example.com"
    val (_, store) = newViewModel(prefs = preferencesOf(AppPreferences.KEY_SERVICE_URL to existing))
    advanceUntilIdle()

    assertEquals(existing, store.state.value[AppPreferences.KEY_SERVICE_URL])
  }

  // Debounced save collectors

  @Test
  fun `updateServiceUrl debounces and writes once after SAVE_DEBOUNCE_MS`() = runTest {
    val (vm, store) = newViewModel()
    advanceUntilIdle()

    vm.updateServiceUrl("https://new.example.com")
    advanceTimeBy(Config.Ui.SAVE_DEBOUNCE_MS - 1)
    runCurrent()
    // Still on the original default — not yet written.
    assertEquals(
        AppPreferences.DEFAULT_SERVICE_URL,
        store.state.value[AppPreferences.KEY_SERVICE_URL],
    )

    advanceTimeBy(1)
    runCurrent()
    assertEquals(
        "https://new.example.com",
        store.state.value[AppPreferences.KEY_SERVICE_URL],
    )
  }

  @Test
  fun `rapid updateServiceUrl coalesces into a single write`() = runTest {
    val (vm, store) = newViewModel()
    advanceUntilIdle()
    val writesBefore = store.writeCount

    vm.updateServiceUrl("https://a.example.com")
    advanceTimeBy(100)
    vm.updateServiceUrl("https://b.example.com")
    advanceTimeBy(100)
    vm.updateServiceUrl("https://c.example.com")
    advanceUntilIdle()

    assertEquals(
        "https://c.example.com",
        store.state.value[AppPreferences.KEY_SERVICE_URL],
    )
    // Exactly one write triggered by the debounced save (the implicit first-run default URL write
    // happened during init, before writesBefore was sampled).
    assertEquals(1, store.writeCount - writesBefore)
  }

  @Test
  fun `updateDeviceKey writes after debounce`() = runTest {
    val (vm, store) = newViewModel()
    advanceUntilIdle()

    vm.updateDeviceKey("dev-42")
    advanceUntilIdle()

    assertEquals("dev-42", store.state.value[AppPreferences.KEY_DEVICE_KEY])
  }

  @Test
  fun `updateDeviceSecret writes through encryptedSecretStore after debounce`() = runTest {
    val secret = MutableSecretStore("")
    val (vm, _) = newViewModel(secretStore = secret)
    advanceUntilIdle()

    vm.updateDeviceSecret("hunter2")
    advanceUntilIdle()

    assertEquals("hunter2", secret.value)
  }

  @Test
  fun `updateDistanceUnit writes immediately without debounce`() = runTest {
    // METERS, not FEET — DistanceUnit.DEFAULT is FEET, so picking it would be a no-op state set.
    val (vm, store) = newViewModel()
    advanceUntilIdle()
    val writesBefore = store.writeCount

    vm.updateDistanceUnit(DistanceUnit.METERS)
    advanceUntilIdle()

    assertEquals(DistanceUnit.METERS.name, store.state.value[AppPreferences.KEY_DISTANCE_UNIT])
    assertEquals(1, store.writeCount - writesBefore)
  }

  @Test
  fun `updateSpeedUnit writes immediately without debounce`() = runTest {
    val (vm, store) = newViewModel()
    advanceUntilIdle()

    vm.updateSpeedUnit(SpeedUnit.KNOTS)
    advanceUntilIdle()

    assertEquals(SpeedUnit.KNOTS.name, store.state.value[AppPreferences.KEY_SPEED_UNIT])
  }

  @Test
  fun `successful save emits on savedEvents`() = runTest {
    val (vm, _) = newViewModel()
    advanceUntilIdle()

    val received = mutableListOf<Unit>()
    val collector = launch { vm.savedEvents.toList(received) }
    // savedEvents has replay=0; ensure the collector subscribes before the save fires.
    runCurrent()
    vm.updateDistanceUnit(DistanceUnit.METERS)
    advanceUntilIdle()

    assertEquals(1, received.size)
    collector.cancel()
  }

  // Runtime observers

  @Test
  fun `credentialStatusFlow updates uiState credential fields`() = runTest {
    val flow = MutableStateFlow(CredentialStatus.INITIAL)
    val (vm, _) = newViewModel(credentialStatusFlow = flow)
    advanceUntilIdle()

    flow.value = CredentialStatus(isBlocked = true, consecutiveAuthFailures = 5)
    advanceUntilIdle()

    val state = vm.uiState.value
    assertTrue(state.isCredentialBlocked)
    assertEquals(5, state.consecutiveAuthFailures)
  }

  @Test
  fun `failedReportCount in uiState reflects DAO flow`() = runTest {
    val dao = FakeLocationReportDao()
    val (vm, _) = newViewModel(dao = dao)
    advanceUntilIdle()

    dao.failedCount.value = 7
    advanceUntilIdle()

    assertEquals(7, vm.uiState.value.failedReportCount)
  }

  // Action snackbar routing

  @Test
  fun `resetCredentialBlock success sets success snackbar and dismisses dialog`() = runTest {
    val actions = StubSettingsActions(resetResult = Result.success(Unit))
    val (vm, _) = newViewModel(actions = actions)
    advanceUntilIdle()

    val events = mutableListOf<UiText>()
    val collector = launch { vm.snackbarEvents.toList(events) }
    runCurrent()

    vm.showResetDialog()
    vm.resetCredentialBlock()
    advanceUntilIdle()

    assertFalse(vm.uiState.value.showResetDialog)
    assertEquals(listOf(UiText.Simple(R.string.message_credential_reset_success)), events)
    collector.cancel()
  }

  @Test
  fun `resetCredentialBlock failure sets failure snackbar`() = runTest {
    val actions = StubSettingsActions(resetResult = Result.failure(RuntimeException("nope")))
    val (vm, _) = newViewModel(actions = actions)
    advanceUntilIdle()

    val events = mutableListOf<UiText>()
    val collector = launch { vm.snackbarEvents.toList(events) }
    runCurrent()

    vm.resetCredentialBlock()
    advanceUntilIdle()

    assertEquals(listOf(UiText.Simple(R.string.message_credential_reset_failure)), events)
    collector.cancel()
  }

  @Test
  fun `retryFailedReports success uses Plural snackbar with current failedReportCount`() = runTest {
    val dao = FakeLocationReportDao(initialFailedCount = 4)
    val actions = StubSettingsActions(retryResult = Result.success(Unit))
    val (vm, _) = newViewModel(dao = dao, actions = actions)
    advanceUntilIdle()

    val events = mutableListOf<UiText>()
    val collector = launch { vm.snackbarEvents.toList(events) }
    runCurrent()

    vm.retryFailedReports()
    advanceUntilIdle()

    assertEquals(listOf(UiText.Plural(R.plurals.message_reports_queued_for_retry, 4)), events)
    collector.cancel()
  }

  @Test
  fun `retryFailedReports failure sets retry failure snackbar`() = runTest {
    val actions = StubSettingsActions(retryResult = Result.failure(RuntimeException()))
    val (vm, _) = newViewModel(actions = actions)
    advanceUntilIdle()

    val events = mutableListOf<UiText>()
    val collector = launch { vm.snackbarEvents.toList(events) }
    runCurrent()

    vm.retryFailedReports()
    advanceUntilIdle()

    assertEquals(listOf(UiText.Simple(R.string.message_retry_failure)), events)
    collector.cancel()
  }

  @Test
  fun `exportFailedReports success and failure route to correct snackbar`() = runTest {
    // Success
    val successActions = StubSettingsActions(exportResult = Result.success(Unit))
    val (successVm, _) = newViewModel(actions = successActions)
    advanceUntilIdle()
    val successEvents = mutableListOf<UiText>()
    val successCollector = launch { successVm.snackbarEvents.toList(successEvents) }
    runCurrent()
    successVm.exportFailedReports()
    advanceUntilIdle()
    assertEquals(listOf(UiText.Simple(R.string.message_export_success)), successEvents)
    successCollector.cancel()

    // Failure
    val failureActions = StubSettingsActions(exportResult = Result.failure(RuntimeException()))
    val (failureVm, _) = newViewModel(actions = failureActions)
    advanceUntilIdle()
    val failureEvents = mutableListOf<UiText>()
    val failureCollector = launch { failureVm.snackbarEvents.toList(failureEvents) }
    runCurrent()
    failureVm.exportFailedReports()
    advanceUntilIdle()
    assertEquals(listOf(UiText.Simple(R.string.message_export_failure)), failureEvents)
    failureCollector.cancel()
  }

  @Test
  fun `deleteFailedReports success and failure route to correct snackbar`() = runTest {
    val successActions = StubSettingsActions(deleteResult = Result.success(Unit))
    val (successVm, _) = newViewModel(actions = successActions)
    advanceUntilIdle()
    val successEvents = mutableListOf<UiText>()
    val successCollector = launch { successVm.snackbarEvents.toList(successEvents) }
    runCurrent()
    successVm.deleteFailedReports()
    advanceUntilIdle()
    assertEquals(listOf(UiText.Simple(R.string.message_delete_success)), successEvents)
    successCollector.cancel()

    val failureActions = StubSettingsActions(deleteResult = Result.failure(RuntimeException()))
    val (failureVm, _) = newViewModel(actions = failureActions)
    advanceUntilIdle()
    val failureEvents = mutableListOf<UiText>()
    val failureCollector = launch { failureVm.snackbarEvents.toList(failureEvents) }
    runCurrent()
    failureVm.deleteFailedReports()
    advanceUntilIdle()
    assertEquals(listOf(UiText.Simple(R.string.message_delete_failure)), failureEvents)
    failureCollector.cancel()
  }

  // Helpers

  /**
   * Construct a [SettingsViewModel] with default fakes, returning the VM together with the
   * underlying [SettingsFakeDataStore] so individual tests can poke its state. Callers may override
   * any collaborator.
   */
  private fun newViewModel(
      prefs: Preferences = emptyPreferences(),
      secret: String = "",
      dao: FakeLocationReportDao = FakeLocationReportDao(),
      secretStore: SecretStore = MutableSecretStore(secret),
      credentialStatusFlow: Flow<CredentialStatus> = MutableStateFlow(CredentialStatus.INITIAL),
      actions: SettingsActions = StubSettingsActions(),
  ): Pair<SettingsViewModel, SettingsFakeDataStore> {
    val store = SettingsFakeDataStore(prefs)
    val vm =
        SettingsViewModel(
            locationReportDao = dao,
            settingsDataStore = store,
            encryptedSecretStore = secretStore,
            credentialStatusFlow = credentialStatusFlow,
            settingsRepository = actions,
        )
    return vm to store
  }

  // Test doubles

  /** [DataStore] backed by a [MutableStateFlow], with a write counter. */
  private class SettingsFakeDataStore(initial: Preferences) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)
    var writeCount = 0
      private set

    override val data: Flow<Preferences>
      get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
      val updated = transform(state.value)
      state.value = updated
      writeCount++
      return updated
    }
  }

  /**
   * [DataStore] whose first read and writes both throw the supplied error. Models a totally-broken
   * DataStore: production code catches IOException on both the initial read and the first-run
   * default-URL write, so this fake throws on both surfaces.
   */
  private class ThrowingFirstReadDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw error
  }

  /** In-memory [SecretStore]. */
  private class MutableSecretStore(initial: String) : SecretStore {
    var value: String = initial
      private set

    override suspend fun migrateFromLegacyIfNeeded() = Unit

    override suspend fun get(): String = value

    override suspend fun set(value: String) {
      this.value = value
    }
  }

  /** [SecretStore] whose [get] throws — used to test init-time IOException tolerance. */
  private class ThrowingSecretStore(private val error: Throwable) : SecretStore {
    override suspend fun migrateFromLegacyIfNeeded() = Unit

    override suspend fun get(): String = throw error

    override suspend fun set(value: String) = Unit
  }

  /**
   * Minimal [LocationReportDao] fake. Only the one method the ViewModel reads is implemented; every
   * other DAO method throws [NotImplementedError] so accidental use is loud.
   */
  private class FakeLocationReportDao(initialFailedCount: Int = 0) : LocationReportDao {
    val failedCount = MutableStateFlow(initialFailedCount)

    override fun getPermanentlyFailedReportCount(): Flow<Int> = failedCount

    override suspend fun insert(report: LocationReportEntity): Long = unused()

    override suspend fun getUnsubmittedReports(limit: Int): List<LocationReportEntity> = unused()

    override suspend fun markAsPermanentlyFailed(id: Long, code: Int, reason: String) = unused()

    override suspend fun getPermanentlyFailedReports(limit: Int): List<LocationReportEntity> =
        unused()

    override suspend fun retryPermanentlyFailedReports(): Int = unused()

    override suspend fun deletePermanentlyFailedReports() = unused()

    override suspend fun deleteUnsubmittedReports() = unused()

    override suspend fun deleteOldestUnsubmittedReport() = unused()

    override suspend fun markAsSubmitted(id: Long, timestamp: Long) = unused()

    override suspend fun incrementSubmissionAttempts(id: Long) = unused()

    override fun getUnsubmittedReportCount(): Flow<Int> = unused()

    override fun getLastSubmissionTime(): Flow<Long> = unused()

    override suspend fun deleteOldSubmittedReports(timestamp: Long) = unused()

    private fun unused(): Nothing =
        throw NotImplementedError("DAO method not expected during this test")
  }

  /** Canned-result [SettingsActions]. */
  private class StubSettingsActions(
      private val resetResult: Result<Unit> = Result.success(Unit),
      private val retryResult: Result<Unit> = Result.success(Unit),
      private val exportResult: Result<Unit> = Result.success(Unit),
      private val deleteResult: Result<Unit> = Result.success(Unit),
  ) : SettingsActions {
    override suspend fun resetCredentialBlock(): Result<Unit> = resetResult

    override suspend fun retryFailedReports(): Result<Unit> = retryResult

    override suspend fun exportFailedReports(): Result<Unit> = exportResult

    override suspend fun deleteFailedReports(): Result<Unit> = deleteResult
  }
}
