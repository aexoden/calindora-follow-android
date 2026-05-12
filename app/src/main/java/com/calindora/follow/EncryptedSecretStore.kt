package com.calindora.follow

import android.content.Context
import android.content.SharedPreferences
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
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val LEGACY_KEY_DEVICE_SECRET = "preference_device_secret"
private val ENCRYPTED_SECRET_KEY = stringPreferencesKey("device_secret_ciphertext")

private const val TINK_KEYSET_NAME = "follow_keyset"
private const val TINK_KEYSET_PREFS = "follow_keyset_prefs"
private const val MASTER_KEY_URI = "android-keystore://follow_master_key"

/**
 * Read/write access to the device secret. Implementations may persist plaintext, ciphertext, or
 * both; consumers don't care. Extracted so tests can substitute an in-memory fake.
 */
interface SecretStore {
  suspend fun migrateFromLegacyIfNeeded()

  suspend fun get(): String?

  suspend fun set(value: String)
}

/**
 * Source of a pre-encryption plaintext secret to migrate into [EncryptedSecretStore].
 *
 * Abstracted away from [SharedPreferences] so the migration logic can be tested without an Android
 * context.
 */
interface LegacySecretSource {
  /** Returns the legacy plaintext secret, or null if none is stored. */
  fun read(): String?

  /** Remove the legacy secret. Idempotent and safe to call when nothing is stored. */
  fun clear()
}

/**
 * Tink-encrypted storage for the device secret.
 *
 * Wraps a regular preferences DataStore. The plaintext is encrypted with AES-GCM via a Tink keyset
 * whose master key lives in the Android Keystore. Ciphertext is base64-encoded for storage as a
 * string preference.
 *
 * Decryption failures are reported as a null secret rather than thrown. If the keystore key has
 * been destroyed (e.g. after a full backup restore) or the ciphertext is corrupted, the user
 * re-enters the secret in Settings rather than the app crashing on read.
 */
class EncryptedSecretStore
internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val legacy: LegacySecretSource,
    aeadProvider: () -> Aead,
) : SecretStore {
  /**
   * Built on first use so the Android Keystore handshake doesn't run on the main thread at app
   * startup.
   */
  private val aead: Aead by lazy(aeadProvider)

  /** Production constructor: assembles the real DataStore, legacy SharedPreferences, and Tink. */
  constructor(
      context: Context
  ) : this(
      dataStore = context.applicationContext.encryptedSecretDataStore,
      legacy =
          SharedPreferencesLegacySecretSource(
              context.applicationContext.getSharedPreferences(
                  AppPreferences.legacyPrefsName(context.applicationContext),
                  Context.MODE_PRIVATE,
              )
          ),
      aeadProvider = { buildAndroidKeystoreAead(context.applicationContext) },
  )

  /**
   * Migrate a plaintext secret from legacy SharedPreferences if present and the encrypted store is
   * empty.
   *
   * Call this from app startup before any consumer reads the secret. The call is idempotent and
   * best-effort: if the DataStore read fails, we log and return so callers don't crash on a
   * transient I/O error.
   *
   * We do this explicitly rather than via a `produceMigrations` callback so failure surfaces in
   * logs and can be retried, rather than corrupting the DataStore file.
   */
  override suspend fun migrateFromLegacyIfNeeded() {
    val alreadyMigrated =
        try {
          dataStore.data.first().contains(ENCRYPTED_SECRET_KEY)
        } catch (e: IOException) {
          Log.w("EncryptedSecretStore", "Failed to read encrypted store during migration", e)
          return
        }
    if (alreadyMigrated) {
      // The encrypted copy is authoritative; sweep any residual plaintext from an interrupted
      // migration.
      withContext(Dispatchers.IO) { legacy.clear() }
      return
    }

    withContext(Dispatchers.IO) {
      val legacySecret = legacy.read() ?: return@withContext
      set(legacySecret)
      legacy.clear()
    }
  }

  override suspend fun get(): String? =
      withContext(Dispatchers.IO) { decrypt(dataStore.data.first()[ENCRYPTED_SECRET_KEY]) }

  override suspend fun set(value: String) {
    withContext(Dispatchers.IO) {
      val ciphertext = encrypt(value)
      dataStore.edit { it[ENCRYPTED_SECRET_KEY] = ciphertext }
    }
  }

  private fun encrypt(plaintext: String): String {
    val ct = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), EMPTY_AAD)
    return Base64.getEncoder().encodeToString(ct)
  }

  private fun decrypt(ciphertext: String?): String? {
    if (ciphertext.isNullOrEmpty()) return null

    return try {
      val bytes = Base64.getDecoder().decode(ciphertext)
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

private class SharedPreferencesLegacySecretSource(private val prefs: SharedPreferences) :
    LegacySecretSource {
  override fun read(): String? = prefs.getString(LEGACY_KEY_DEVICE_SECRET, null)

  override fun clear() {
    prefs.edit().remove(LEGACY_KEY_DEVICE_SECRET).apply()
  }
}

private fun buildAndroidKeystoreAead(context: Context): Aead {
  AeadConfig.register()
  return AndroidKeysetManager.Builder()
      .withSharedPref(context, TINK_KEYSET_NAME, TINK_KEYSET_PREFS)
      .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
      .withMasterKeyUri(MASTER_KEY_URI)
      .build()
      .keysetHandle
      .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}

private val Context.encryptedSecretDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "credentials")
