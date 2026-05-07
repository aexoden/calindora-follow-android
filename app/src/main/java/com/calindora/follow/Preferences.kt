package com.calindora.follow

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed preferences for Calindora Follow.
 *
 * Two stores:
 * - [settingsDataStore] holds plaintext settings plus operational state.
 * - The device secret lives in a separate Tink-encrypted store; see [EncryptedSecretStore].
 */
object Preferences {
  // Plaintext settings
  val KEY_SERVICE_URL = stringPreferencesKey("preference_url")
  val KEY_DEVICE_KEY = stringPreferencesKey("preference_device_key")

  // Runtime state
  val KEY_SUBMISSIONS_BLOCKED = booleanPreferencesKey("submissions_blocked_credential_issue")
  val KEY_CONSECUTIVE_AUTH_FAILURES = intPreferencesKey("consecutive_auth_failures")

  // Defaults
  const val DEFAULT_SERVICE_URL = "https://follow.calindora.com"

  internal fun legacyPrefsName(context: Context): String = "${context.packageName}_preferences"

  internal fun legacyMigrationKeys(): Set<String> =
      setOf(
          "preference_url",
          "preference_device_key",
          "submissions_blocked_credential_issue",
          "consecutive_auth_failures",
      )
}

val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(
        name = "settings",
        produceMigrations = { context ->
          listOf(
              SharedPreferencesMigration(
                  context = context,
                  sharedPreferencesName = com.calindora.follow.Preferences.legacyPrefsName(context),
                  keysToMigrate = com.calindora.follow.Preferences.legacyMigrationKeys(),
              )
          )
        },
    )

/** Snapshot of submission auth state, derived from [settingsDataStore]. */
data class CredentialStatus(
    val isBlocked: Boolean,
    val consecutiveAuthFailures: Int,
) {
  companion object {
    val INITIAL = CredentialStatus(isBlocked = false, consecutiveAuthFailures = 0)
  }
}

/**
 * Live [CredentialStatus] view of [settingsDataStore]. Emits a new value whenever either of the
 * underlying preference keys changes, with `distinctUntilChanged` semantics.
 */
val Context.credentialStatusFlow: Flow<CredentialStatus>
  get() =
      settingsDataStore.data
          .map {
            CredentialStatus(
                isBlocked = it[com.calindora.follow.Preferences.KEY_SUBMISSIONS_BLOCKED] == true,
                consecutiveAuthFailures =
                    it[com.calindora.follow.Preferences.KEY_CONSECUTIVE_AUTH_FAILURES] ?: 0,
            )
          }
          .distinctUntilChanged()
