/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import logcat.asLog
import logcat.logcat

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_SIZE = 12
private const val TAG_BITS = 128

/** Encrypts [plaintext] with AES-GCM under [key]; the output is the random IV then ciphertext. */
fun seal(key: SecretKey, plaintext: ByteArray, aad: ByteArray): ByteArray {
  val cipher = Cipher.getInstance(TRANSFORMATION)
  // The provider picks a random IV; Android Keystore refuses caller-chosen ones.
  cipher.init(Cipher.ENCRYPT_MODE, key)
  cipher.updateAAD(aad)
  return cipher.iv + cipher.doFinal(plaintext)
}

/** Reverses [seal]; throws [GeneralSecurityException] if [blob] or [aad] was altered. */
fun unseal(key: SecretKey, blob: ByteArray, aad: ByteArray): ByteArray {
  if (blob.size < IV_SIZE) throw GeneralSecurityException("Ciphertext too short")
  val cipher = Cipher.getInstance(TRANSFORMATION)
  cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
  cipher.updateAAD(aad)
  return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
}

/**
 * Returns the AES-256 key stored in Android Keystore under [alias], generating it with [configure]
 * applied if it does not exist. [configure] has no effect on an existing key.
 */
fun keystoreAesKey(
  alias: String,
  strongBox: Boolean = false,
  configure: KeyGenParameterSpec.Builder.() -> Unit = {},
): SecretKey {
  val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  (keyStore.getKey(alias, null) as? SecretKey)?.let {
    return it
  }
  fun generate(useStrongBox: Boolean): SecretKey {
    val spec =
      KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setKeySize(256)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .apply(configure)
        .setIsStrongBoxBacked(useStrongBox)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(spec)
      generateKey()
    }
  }
  if (!strongBox) return generate(false)
  return try {
    generate(true)
  } catch (_: StrongBoxUnavailableException) {
    generate(false)
  }
}

/**
 * Requires the device credential or a Class 3 biometric within the last [seconds] before the key
 * can be used, the only two factors Android Keystore accepts for authentication-bound keys.
 */
fun KeyGenParameterSpec.Builder.requireUserAuthentication(seconds: Int) {
  setUserAuthenticationRequired(true)
  setUserAuthenticationParameters(
    seconds,
    KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
  )
}

/**
 * [SharedPreferences] whose values are encrypted with AES-GCM under [key], each bound to its entry
 * name so values cannot be swapped between entries. A value that fails to decrypt (tampered, or its
 * key was invalidated or needs authentication) reads as absent.
 *
 * ponytail: entry names are stored in plaintext, and string sets and [getAll] are unsupported; the
 * app stores only scalar values under fixed names. Add them if a caller needs them.
 */
class KeystorePreferences(
  private val backing: SharedPreferences,
  private val key: () -> SecretKey,
) : SharedPreferences by backing {

  private fun read(name: String): String? {
    val stored = backing.getString(name, null) ?: return null
    return try {
      unseal(key(), Base64.getDecoder().decode(stored), name.encodeToByteArray()).decodeToString()
    } catch (e: GeneralSecurityException) {
      logcat { e.asLog() }
      null
    } catch (e: IllegalArgumentException) {
      logcat { e.asLog() }
      null
    }
  }

  override fun getString(name: String, defValue: String?) = read(name) ?: defValue

  override fun getInt(name: String, defValue: Int) = read(name)?.toIntOrNull() ?: defValue

  override fun getLong(name: String, defValue: Long) = read(name)?.toLongOrNull() ?: defValue

  override fun getFloat(name: String, defValue: Float) = read(name)?.toFloatOrNull() ?: defValue

  override fun getBoolean(name: String, defValue: Boolean) =
    read(name)?.toBooleanStrictOrNull() ?: defValue

  override fun getStringSet(name: String, defValues: Set<String>?): Set<String>? =
    throw UnsupportedOperationException()

  override fun getAll(): Map<String, *> = throw UnsupportedOperationException()

  override fun edit(): SharedPreferences.Editor = Editor(backing.edit())

  private inner class Editor(private val editor: SharedPreferences.Editor) :
    SharedPreferences.Editor by editor {

    private fun put(name: String, value: Any?) = apply {
      if (value == null) {
        editor.remove(name)
      } else {
        val blob = seal(key(), value.toString().encodeToByteArray(), name.encodeToByteArray())
        editor.putString(name, Base64.getEncoder().encodeToString(blob))
      }
    }

    override fun putString(name: String, value: String?) = put(name, value)

    override fun putInt(name: String, value: Int) = put(name, value)

    override fun putLong(name: String, value: Long) = put(name, value)

    override fun putFloat(name: String, value: Float) = put(name, value)

    override fun putBoolean(name: String, value: Boolean) = put(name, value)

    override fun putStringSet(name: String, values: Set<String>?) =
      throw UnsupportedOperationException()

    override fun remove(name: String) = apply { editor.remove(name) }

    override fun clear() = apply { editor.clear() }
  }
}

/**
 * [KeystorePreferences] in the private preferences file [name], under a Keystore key of the same
 * alias.
 */
fun Context.keystorePreferences(name: String): SharedPreferences {
  val key by lazy { keystoreAesKey(name) }
  return KeystorePreferences(applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)) {
    key
  }
}
