/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
@file:Suppress("DEPRECATION")

package app.passwordstore.util.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.git.sshj.SshKey
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.runCatching
import java.io.File
import java.net.URI
import java.security.KeyStore
import logcat.LogPriority.ERROR
import logcat.LogPriority.INFO
import logcat.asLog
import logcat.logcat

private const val TAG = "Migrations"

fun runMigrations(filesDirPath: String, sharedPrefs: SharedPreferences, gitSettings: GitSettings) {
  migrateToGitUrlBasedConfig(sharedPrefs, gitSettings)
  migrateToHideAll(sharedPrefs)
  migrateToSshKey(filesDirPath, sharedPrefs)
  migrateToClipboardHistory(sharedPrefs)
  migrateToDiceware(sharedPrefs)
  removeExternalStorageProperties(sharedPrefs)
  removeCurrentBranchValue(sharedPrefs)
  migrateRemovedSortOrders(sharedPrefs)
  removeOpenKeystoreKeyId(sharedPrefs)
}

/**
 * Deletes everything androidx security-crypto stored, which this app can no longer read since it
 * moved to its own Keystore AES-GCM storage: Git and proxy credentials, password generator
 * settings, cached PGP passphrases, the Tink keysets and their Keystore master keys, and a
 * generated Ed25519 SSH key. Users re-enter credentials and regenerate an Ed25519 key once.
 */
fun removeSecurityCryptoData(context: Context, sharedPrefs: SharedPreferences) {
  if (sharedPrefs.getBoolean(SECURITY_CRYPTO_REMOVED, false)) return
  logcat(TAG, INFO) { "Removing data stored by androidx security-crypto" }
  runCatching {
      SshKey.deleteSecurityCryptoWrappedKey()
      listOf(
          "git_operation",
          "http_proxy",
          "pwgen_preferences",
          "androidx_passphrase_keyset_prefs",
          "androidx_sshkey_keyset_prefs",
          "__androidx_security_crypto_encrypted_file_pref__",
        )
        .forEach { context.deleteSharedPreferences(it) }
      KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
        deleteEntry("_androidx_security_master_key_")
        deleteEntry("passphrase")
      }
    }
    .onFailure { e -> logcat(TAG, ERROR) { e.asLog() } }
  sharedPrefs.edit { putBoolean(SECURITY_CRYPTO_REMOVED, true) }
}

private const val SECURITY_CRYPTO_REMOVED = "security_crypto_removed"

fun removeCurrentBranchValue(sharedPrefs: SharedPreferences) {
  if (!sharedPrefs.contains(PreferenceKeys.GIT_BRANCH_NAME)) {
    return
  }
  logcat(TAG, INFO) { "Deleting now unused branch name preference" }
  sharedPrefs.edit { remove(PreferenceKeys.GIT_BRANCH_NAME) }
}

private fun migrateToGitUrlBasedConfig(sharedPrefs: SharedPreferences, gitSettings: GitSettings) {
  val serverHostname = sharedPrefs.getString(PreferenceKeys.GIT_REMOTE_SERVER) ?: return
  logcat(TAG, INFO) { "Migrating to URL-based Git config" }
  val serverPort = sharedPrefs.getString(PreferenceKeys.GIT_REMOTE_PORT) ?: ""
  val serverUser = sharedPrefs.getString(PreferenceKeys.GIT_REMOTE_USERNAME) ?: ""
  val serverPath = sharedPrefs.getString(PreferenceKeys.GIT_REMOTE_LOCATION) ?: ""
  val protocol = Protocol.fromString(sharedPrefs.getString(PreferenceKeys.GIT_REMOTE_PROTOCOL))
  // Whether we need the leading ssh:// depends on the use of a custom port.
  val hostnamePart = serverHostname.removePrefix("ssh://")
  val url =
    when (protocol) {
      Protocol.Ssh -> {
        val userPart = if (serverUser.isEmpty()) "" else "${serverUser.trimEnd('@')}@"
        val portPart = if (serverPort == "22" || serverPort.isEmpty()) "" else ":$serverPort"
        if (portPart.isEmpty()) {
          "$userPart$hostnamePart:$serverPath"
        } else {
          // Only absolute paths are supported with custom ports.
          if (!serverPath.startsWith('/')) {
            null
          } else {
            // We have to specify the ssh scheme as this is the only way to pass a custom
            // port.
            "ssh://$userPart$hostnamePart$portPart$serverPath"
          }
        }
      }
      Protocol.Https -> {
        val portPart = if (serverPort == "443" || serverPort.isEmpty()) "" else ":$serverPort"
        val pathPart = serverPath.trimStart('/', ':')
        val urlWithFreeEntryScheme = "$hostnamePart$portPart/$pathPart"
        val url =
          when {
            urlWithFreeEntryScheme.startsWith("https://") -> urlWithFreeEntryScheme
            urlWithFreeEntryScheme.startsWith("http://") ->
              urlWithFreeEntryScheme.replaceFirst("http", "https")
            else -> "https://$urlWithFreeEntryScheme"
          }
        runCatching { if (URI(url).rawAuthority != null) url else null }.get()
      }
    }

  sharedPrefs.edit {
    remove(PreferenceKeys.GIT_REMOTE_LOCATION)
    remove(PreferenceKeys.GIT_REMOTE_PORT)
    remove(PreferenceKeys.GIT_REMOTE_SERVER)
    remove(PreferenceKeys.GIT_REMOTE_USERNAME)
    remove(PreferenceKeys.GIT_REMOTE_PROTOCOL)
  }
  if (
    url == null ||
      gitSettings.updateConnectionSettingsIfValid(
        newAuthMode = gitSettings.authMode,
        newUrl = url,
      ) != GitSettings.UpdateConnectionSettingsResult.Valid
  ) {
    logcat(TAG, ERROR) { "Failed to migrate to URL-based Git config, generated URL is invalid" }
  }
}

private fun migrateToHideAll(sharedPrefs: SharedPreferences) {
  sharedPrefs.all[PreferenceKeys.SHOW_HIDDEN_FOLDERS] ?: return
  val isHidden = sharedPrefs.getBoolean(PreferenceKeys.SHOW_HIDDEN_FOLDERS, false)
  sharedPrefs.edit {
    remove(PreferenceKeys.SHOW_HIDDEN_FOLDERS)
    putBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, isHidden)
  }
}

private fun migrateToSshKey(filesDirPath: String, sharedPrefs: SharedPreferences) {
  val privateKeyFile = File(filesDirPath, ".ssh_key")
  if (
    sharedPrefs.contains(PreferenceKeys.USE_GENERATED_KEY) &&
      !SshKey.exists &&
      privateKeyFile.exists()
  ) {
    // Currently uses a private key imported or generated with an old version of Password Store.
    // Generated keys come with a public key which the user should still be able to view after
    // the migration (not possible for regular imported keys), hence the special case.
    val isGeneratedKey = sharedPrefs.getBoolean(PreferenceKeys.USE_GENERATED_KEY, false)
    SshKey.useLegacyKey(isGeneratedKey)
    sharedPrefs.edit { remove(PreferenceKeys.USE_GENERATED_KEY) }
  }
}

private fun migrateToClipboardHistory(sharedPrefs: SharedPreferences) {
  if (sharedPrefs.contains(PreferenceKeys.CLEAR_CLIPBOARD_20X)) {
    sharedPrefs.edit {
      putBoolean(
        PreferenceKeys.CLEAR_CLIPBOARD_HISTORY,
        sharedPrefs.getBoolean(PreferenceKeys.CLEAR_CLIPBOARD_20X, false),
      )
      remove(PreferenceKeys.CLEAR_CLIPBOARD_20X)
    }
  }
}

private fun migrateToDiceware(sharedPrefs: SharedPreferences) {
  if (sharedPrefs.contains(PreferenceKeys.PREF_KEY_PWGEN_TYPE)) {
    sharedPrefs.edit {
      if (sharedPrefs.getString(PreferenceKeys.PREF_KEY_PWGEN_TYPE) == "xkpasswd") {
        putString(PreferenceKeys.PREF_KEY_PWGEN_TYPE, "diceware")
      }
    }
  }
}

private fun removeExternalStorageProperties(prefs: SharedPreferences) {
  prefs.edit {
    if (prefs.contains(PreferenceKeys.GIT_EXTERNAL)) {
      if (prefs.getBoolean(PreferenceKeys.GIT_EXTERNAL, false)) {
        putBoolean(PreferenceKeys.GIT_EXTERNAL_MIGRATED, true)
      }
      remove(PreferenceKeys.GIT_EXTERNAL)
    }
    if (prefs.contains(PreferenceKeys.GIT_EXTERNAL_REPO)) {
      remove(PreferenceKeys.GIT_EXTERNAL_REPO)
    }
  }
}

/**
 * "Files first" and "type independent" were removed with the Compose list, which shows folders and
 * entries in separate groups: a stored one becomes folders first, the list's default.
 */
private fun migrateRemovedSortOrders(sharedPrefs: SharedPreferences) {
  val stored = sharedPrefs.getString(PreferenceKeys.SORT_ORDER) ?: return
  if (stored != "FILE_FIRST" && stored != "INDEPENDENT") return
  logcat(TAG, INFO) { "Replacing the removed sort order $stored" }
  sharedPrefs.edit { putString(PreferenceKeys.SORT_ORDER, PasswordSortOrder.FOLDER_FIRST.name) }
}

/** OpenKeychain SSH support is gone and nothing reads the remembered key ID any more. */
private fun removeOpenKeystoreKeyId(sharedPrefs: SharedPreferences) {
  if (!sharedPrefs.contains(PreferenceKeys.SSH_OPENKEYSTORE_KEYID)) return
  sharedPrefs.edit { remove(PreferenceKeys.SSH_OPENKEYSTORE_KEYID) }
}
