/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.data.crypto

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.core.content.edit
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.crypto.KeystorePreferences
import app.passwordstore.util.crypto.keystoreAesKey
import app.passwordstore.util.crypto.requireUserAuthentication
import app.passwordstore.util.extensions.getString
import java.security.KeyStore
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * Caches PGP passphrases in [KeystorePreferences] under a key that needs the screen lock or a
 * strong biometric within the last 60 seconds.
 */
class PGPPassphraseCache @Inject constructor(private val dispatcherProvider: DispatcherProvider) {

  suspend fun cachePassphrase(context: Context, identifier: PGPIdentifier, passphrase: String) {
    withContext(dispatcherProvider.io()) {
      try {
        getPreferences(context).edit { putString(identifier.toString(), passphrase) }
      } catch (_: KeyPermanentlyInvalidatedException) {
        // Enrolling a new biometric or removing the screen lock invalidates the key for good;
        // start over with a fresh key, dropping passphrases that can no longer be decrypted.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(PREFS_NAME)
        getPreferences(context).edit {
          clear()
          putString(identifier.toString(), passphrase)
        }
      }
    }
  }

  suspend fun retrieveCachedPassphrase(context: Context, identifier: PGPIdentifier): String? {
    return withContext(dispatcherProvider.io()) {
      getPreferences(context).getString(identifier.toString())
    }
  }

  suspend fun clearCachedPassphrase(context: Context, identifier: PGPIdentifier) {
    withContext(dispatcherProvider.io()) {
      getPreferences(context).edit { remove(identifier.toString()) }
    }
  }

  suspend fun clearAllCachedPassphrases(context: Context) {
    withContext(dispatcherProvider.io()) { getPreferences(context).edit { clear() } }
  }

  private fun getPreferences(context: Context) =
    KeystorePreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)) {
      keystoreAesKey(PREFS_NAME, strongBox = true) { requireUserAuthentication(60) }
    }

  companion object {

    const val PREFS_NAME = "passphrase_cache"
  }
}
