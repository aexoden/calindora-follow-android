package com.calindora.follow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.calindora.follow.EncryptedSecretStoreTest.ThrowingAead.encrypt
import com.google.crypto.tink.Aead
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EncryptedSecretStoreTest {
  // EncryptedSecretStore writes ciphertext under this key; tests reach into the fake DataStore to
  // seed corrupt values or assert what was written.
  private val ciphertextKey = stringPreferencesKey("device_secret_ciphertext")

  // migrateFromLegacyIfNeeded

  @Test
  fun `migrate copies legacy secret into DataStore and clears legacy`() = runTest {
    val dataStore = FakeDataStore()
    val legacy = FakeLegacySource(initial = "legacy-secret")
    val store = EncryptedSecretStore(dataStore, legacy, ::MarkerAead)

    store.migrateFromLegacyIfNeeded()

    assertEquals("legacy-secret", store.get())
    assertNull(legacy.value)
  }

  @Test
  fun `migrate is a no-op when no legacy secret exists`() = runTest {
    val dataStore = FakeDataStore()
    val legacy = FakeLegacySource(initial = null)
    val store = EncryptedSecretStore(dataStore, legacy, ::MarkerAead)

    store.migrateFromLegacyIfNeeded()

    assertFalse(dataStore.state.value.contains(ciphertextKey))
    assertEquals(0, legacy.clearCount)
  }

  @Test
  fun `migrate is idempotent across repeated calls`() = runTest {
    val dataStore = FakeDataStore()
    val legacy = FakeLegacySource(initial = "legacy-secret")
    val store = EncryptedSecretStore(dataStore, legacy, ::MarkerAead)

    store.migrateFromLegacyIfNeeded()
    val writesAfterFirst = dataStore.writeCount
    val clearsAfterFirst = legacy.clearCount

    repeat(3) { store.migrateFromLegacyIfNeeded() }

    assertEquals(writesAfterFirst, dataStore.writeCount)
    assertEquals(clearsAfterFirst, legacy.clearCount)
    assertEquals("legacy-secret", store.get())
  }

  @Test
  fun `migrate short-circuits without reading legacy when DataStore already has ciphertext`() =
      runTest {
        val dataStore = FakeDataStore()
        val legacy = FakeLegacySource(initial = "stale-but-irrelevant")
        val store = EncryptedSecretStore(dataStore, legacy, ::MarkerAead)

        // Simulate a prior successful migration.
        store.set("current-secret")
        val readsBefore = legacy.readCount

        store.migrateFromLegacyIfNeeded()

        assertEquals(readsBefore, legacy.readCount)
        assertEquals(0, legacy.clearCount)
      }

  @Test
  fun `migrate returns early without throwing when DataStore read fails`() = runTest {
    val dataStore = ThrowingReadDataStore(IOException("disk gone"))
    val legacy = FakeLegacySource(initial = "legacy-secret")
    val store = EncryptedSecretStore(dataStore, legacy, ::MarkerAead)

    // Production contract: log + return, do not propagate. Test passes if no exception escapes.
    store.migrateFromLegacyIfNeeded()

    // Legacy is preserved so the next launch can retry the migration.
    assertEquals("legacy-secret", legacy.value)
    assertEquals(0, legacy.clearCount)
  }

  @Test
  fun `migrate leaves legacy intact when encryption fails`() = runTest {
    val dataStore = FakeDataStore()
    val legacy = FakeLegacySource(initial = "legacy-secret")
    val store = EncryptedSecretStore(dataStore, legacy) { ThrowingAead }

    var caught: Throwable? = null
    try {
      store.migrateFromLegacyIfNeeded()
    } catch (e: GeneralSecurityException) {
      caught = e
    }

    assertNotNull("encrypt() failure should propagate", caught)
    assertEquals("legacy-secret", legacy.value)
    assertEquals(0, legacy.clearCount)
    assertFalse(dataStore.state.value.contains(ciphertextKey))
  }

  // get/set

  @Test
  fun `get returns null when nothing is stored`() = runTest {
    val store = EncryptedSecretStore(FakeDataStore(), FakeLegacySource(), ::MarkerAead)
    assertNull(store.get())
  }

  @Test
  fun `set then get round-trips through encryption`() = runTest {
    val dataStore = FakeDataStore()
    val store = EncryptedSecretStore(dataStore, FakeLegacySource(), ::MarkerAead)

    store.set("hunter2")

    val stored = dataStore.state.value[ciphertextKey]
    assertNotNull(stored)
    assertNotEquals("hunter2", stored)
    assertEquals("hunter2", store.get())
  }

  @Test
  fun `get returns null when stored ciphertext is corrupt`() = runTest {
    val dataStore = FakeDataStore()
    val store = EncryptedSecretStore(dataStore, FakeLegacySource(), ::MarkerAead)

    // Valid base64 but doesn't carry MarkerAead's expected prefix → decrypt throws → null.
    dataStore.edit {
      it[ciphertextKey] = Base64.getEncoder().encodeToString("not real ciphertext".toByteArray())
    }

    assertNull(store.get())
  }

  @Test
  fun `get returns null when stored value is not valid base64`() = runTest {
    val dataStore = FakeDataStore()
    val store = EncryptedSecretStore(dataStore, FakeLegacySource(), ::MarkerAead)

    dataStore.edit { it[ciphertextKey] = "definitely-not-base64-!!!" }

    assertNull(store.get())
  }

  @Test(expected = IOException::class)
  fun `get propagates IOException from DataStore`(): Unit = runTest {
    val store =
        EncryptedSecretStore(
            ThrowingReadDataStore(IOException("disk gone")),
            FakeLegacySource(),
            ::MarkerAead,
        )

    store.get()
  }

  // Test Doubles

  /** In-memory [DataStore], with a write counter for idempotency assertions. */
  private class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
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

  /** [DataStore] whose reads and writes both throw. */
  private class ThrowingReadDataStore(private val error: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw error }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw error
  }

  /** In-memory [LegacySecretSource] with read/clear counters. */
  private class FakeLegacySource(initial: String? = null) : LegacySecretSource {
    var value: String? = initial
      private set

    var readCount = 0
      private set

    var clearCount = 0
      private set

    override fun read(): String? {
      readCount++
      return value
    }

    override fun clear() {
      clearCount++
      value = null
    }
  }

  /**
   * Trivial reversible "encryption" for tests. Encrypt prepends a 4-byte marker; decrypt strips it
   * and throws on anything else. Models Tink's contract that arbitrary input fails decryption with
   * a [GeneralSecurityException].
   */
  private class MarkerAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        MARKER + plaintext

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
      if (
          ciphertext.size < MARKER.size ||
              !ciphertext.copyOfRange(0, MARKER.size).contentEquals(MARKER)
      ) {
        throw GeneralSecurityException("marker missing")
      }
      return ciphertext.copyOfRange(MARKER.size, ciphertext.size)
    }

    companion object {
      private val MARKER = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
    }
  }

  /** [Aead] whose [encrypt] always throws. */
  private object ThrowingAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        throw GeneralSecurityException("encrypt unavailable")

    override fun decrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        throw GeneralSecurityException("decrypt unavailable")
  }
}
