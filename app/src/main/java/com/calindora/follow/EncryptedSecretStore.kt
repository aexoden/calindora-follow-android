package com.calindora.follow

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val LEGACY_KEY_DEVICE_SECRET = "preference_device_secret"
private val ENCRYPTED_SECRET_KEY = stringPreferencesKey("device_secret_ciphertext")

private const val TINK_KEYSET_NAME = "follow_keyset"
private const val TINK_KEYSET_PREFS = "follow_keyset_prefs"
private const val MASTER_KEY_URI = "android-keystore://follow_master_key"

/**
 * Tink-encrypted storage for the device secret.
 *
 * Wraps a regular preferences DataSTore. The plaintext is encrypted with AES-GCM via a Tink keyset
 * whose master key lives in the Android Keystore. Ciphertext is base64-encoded for storage as a
 * string preference.
 *
 * Decryption failures are reported as a null secret rather than thrown. If the keystore key has
 * been destroyed (e.g. after a full backup restore) or the ciphertext is corrupted, the user
 * re-enters the secret in Settings rather than the app crashing on read.
 */
class EncryptedSecretStore(context: Context) {
  private val appContext = context.applicationContext
  private val dataStore: DataStore<Preferences> = appContext.encryptedSecretDataStore

  private val aead: Aead by lazy {
    AeadConfig.register()
    AndroidKeysetManager.Builder()
        .withSharedPref(appContext, TINK_KEYSET_NAME, TINK_KEYSET_PREFS)
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withMasterKeyUri(MASTER_KEY_URI)
        .build()
        .keysetHandle
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
  }

  /**
   * Migrate a plaintext secret from legacy SharedPreferences if present and the encrypted store is
   * empty.
   *
   * Call this from app startup before any consumer reads the secret. We do this explicitly rather
   * than via a `produceMigrations` callback so failure surfaces loudly and can be retried, rather
   * than corrupting the DataStore file.
   */
  suspend fun migrateFromLegacyIfNeeded() {
    if (dataStore.data.first().contains(ENCRYPTED_SECRET_KEY)) return

    withContext(Dispatchers.IO) {
      val legacyPrefs =
          appContext.getSharedPreferences(
              com.calindora.follow.Preferences.legacyPrefsName(appContext),
              Context.MODE_PRIVATE,
          )

      val legacySecret = legacyPrefs.getString(LEGACY_KEY_DEVICE_SECRET, null) ?: return@withContext

      set(legacySecret)
      legacyPrefs.edit().remove(LEGACY_KEY_DEVICE_SECRET).apply()
    }
  }

  suspend fun get(): String? =
      withContext(Dispatchers.IO) { decrypt(dataStore.data.first()[ENCRYPTED_SECRET_KEY]) }

  suspend fun set(value: String) {
    withContext(Dispatchers.IO) {
      val ciphertext = encrypt(value)
      dataStore.edit { it[ENCRYPTED_SECRET_KEY] = ciphertext }
    }
  }

  private fun encrypt(plaintext: String): String {
    val ct = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), EMPTY_AAD)
    return Base64.encodeToString(ct, Base64.NO_WRAP)
  }

  private fun decrypt(ciphertext: String?): String? {
    if (ciphertext.isNullOrEmpty()) return null

    return try {
      val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
      String(aead.decrypt(bytes, EMPTY_AAD), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
      Log.w("EncryptedSecretStore", "Failed to decrypt device secret", e)
      null
    } catch (e: IllegalArgumentException) {
      Log.w("EncryptedSecretStore", "Stored ciphertext is malformed", e)
      null
    }
  }

  companion object {
    private val EMPTY_AAD = ByteArray(0)
  }
}

private val Context.encryptedSecretDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "credentials")
