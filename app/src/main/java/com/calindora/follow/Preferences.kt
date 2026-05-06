package com.calindora.follow

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

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
