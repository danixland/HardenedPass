/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.data.crypto.PGPPassphraseCache
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.injection.prefs.GitPreferences
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.ui.list.deleteWithoutFollowingLinks
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.features.Feature
import app.passwordstore.util.git.sshj.SshKey
import app.passwordstore.util.settings.DirectoryStructure
import app.passwordstore.util.settings.GitSettings
import app.passwordstore.util.settings.PasswordSortOrder
import app.passwordstore.util.settings.PreferenceKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/** What the settings sections show: stored values, and the state rows depend on. */
data class SettingsState(
  val theme: String,
  val directoryStructure: String,
  val sortOrder: String,
  val disableSync: Boolean,
  val filterRecursively: Boolean,
  val searchOnStart: Boolean,
  val showHidden: Boolean,
  val biometric: Boolean,
  val defaultUsername: String,
  val customSuffixes: String,
  val pwgenType: String,
  val clipboardTimeout: String,
  val showPassword: Boolean,
  val copyOnDecrypt: Boolean,
  val rebaseOnPull: Boolean,
  val pushAfterCommit: Boolean,
  val isGitRepo: Boolean,
  val httpsRemote: Boolean,
  val canShowSshKey: Boolean,
  /** Title of the "clear saved credential" row, or null when none is saved. */
  @StringRes val savedSecret: Int?,
  val clearClipboardHistory: Boolean,
  val debugLogging: Boolean,
  val asciiArmor: Boolean,
  val passphraseCache: Boolean,
  val autoClearCache: Boolean,
)

/**
 * One settings section's state over the existing preference keys. [state] is re-read on every
 * change of the settings and on [refresh] (the destination calls it on resume, so changes made on
 * other screens, such as a new SSH key, show at once).
 */
@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  application: Application,
  @SettingsPreferences private val prefs: SharedPreferences,
  @GitPreferences private val gitPrefs: SharedPreferences,
  private val gitSettings: GitSettings,
  private val passphraseCache: PGPPassphraseCache,
  private val dispatcherProvider: DispatcherProvider,
) : AndroidViewModel(application) {

  var state by mutableStateOf(read())
    private set

  /** True while "Delete repository" runs. */
  var deleting by mutableStateOf(false)
    private set

  /** Null until "Delete repository" ran; then whether the whole store folder was deleted. */
  var deleted by mutableStateOf<Boolean?>(null)
    private set

  // A field: SharedPreferences holds its listeners weakly.
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

  init {
    prefs.registerOnSharedPreferenceChangeListener(listener)
  }

  override fun onCleared() {
    prefs.unregisterOnSharedPreferenceChangeListener(listener)
  }

  fun refresh() {
    state = read()
  }

  /** Saves the app's log to [uri]; false when that failed. */
  suspend fun exportLog(uri: Uri): Boolean =
    withContext(dispatcherProvider.io()) {
      val header =
        "HardenedPass ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
          "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MODEL}\n\n"
      try {
        val out =
          getApplication<Application>().contentResolver.openOutputStream(uri)
            ?: throw IOException("No stream for the chosen file")
        out.use { writeAppLog(header, it) }
        true
      } catch (e: IOException) {
        logcat(ERROR) { e.asLog() }
        false
      } catch (e: SecurityException) {
        logcat(ERROR) { e.asLog() }
        false
      }
    }

  fun putBoolean(key: String, value: Boolean) {
    prefs.edit { putBoolean(key, value) }
  }

  /** Stores [value] under [key]; an empty value removes the key, so readers use their default. */
  fun putString(key: String, value: String) {
    prefs.edit { if (value.isEmpty()) remove(key) else putString(key, value) }
  }

  /** Turns the passphrase cache on or off (after the screen lock prompt); it starts empty. */
  fun setPassphraseCache(enabled: Boolean) {
    prefs.edit {
      putBoolean(Feature.EnablePGPPassphraseCache.configKey, enabled)
      if (!enabled) remove(PreferenceKeys.CLEAR_PASSPHRASE_CACHE)
    }
    clearCachedPassphrases()
  }

  /** Stops clearing the cache when the screen goes off; the cache is emptied once now. */
  fun disableAutoClear() {
    putBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, false)
    clearCachedPassphrases()
  }

  /** Back to the default: the cache is cleared when the screen goes off. */
  fun resetAutoClear() {
    prefs.edit { remove(PreferenceKeys.CLEAR_PASSPHRASE_CACHE) }
  }

  /** Forgets the credential the "clear" row names: the HTTPS password, else the SSH passphrase. */
  fun clearSavedSecret() {
    val key =
      if (gitPrefs.contains(PreferenceKeys.HTTPS_PASSWORD)) PreferenceKeys.HTTPS_PASSWORD
      else PreferenceKeys.SSH_KEY_LOCAL_PASSPHRASE
    gitPrefs.edit { remove(key) }
    refresh()
  }

  /**
   * Empties the store folder and marks setup as not done, then sets [deleted]. In [viewModelScope]
   * and [NonCancellable], so neither a recreation nor the ViewModel's end can stop it half way (a
   * half-deleted store still marked as set up). Never follows a symlink: `deleteRecursively()`
   * would, and a `store/link -> ..` link would take the PGP and SSH keys with it.
   */
  fun deleteRepository() {
    if (deleting) return
    deleting = true
    viewModelScope.launch {
      withContext(NonCancellable) {
        val root = PasswordRepository.getRepositoryDirectory()
        val gone =
          withContext(dispatcherProvider.io()) {
            PasswordRepository.closeRepository()
            val ok =
              Files.notExists(root.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                root.deleteWithoutFollowingLinks()
            root.mkdirs()
            ok
          }
        getApplication<Application>()
          .getSystemService<ShortcutManager>()
          ?.removeAllDynamicShortcuts()
        prefs.edit { putBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, false) }
        deleted = gone
      }
    }
  }

  // NonCancellable: leaving the screen right after the switch must not skip the clearing.
  private fun clearCachedPassphrases() {
    viewModelScope.launch(NonCancellable) {
      passphraseCache.clearAllCachedPassphrases(getApplication())
    }
  }

  private fun read(): SettingsState {
    val app = getApplication<Application>()
    return SettingsState(
      theme = prefs.getString(PreferenceKeys.APP_THEME) ?: app.getString(R.string.app_theme_def),
      directoryStructure =
        prefs.getString(PreferenceKeys.DIRECTORY_STRUCTURE) ?: DirectoryStructure.DEFAULT.value,
      sortOrder = PasswordSortOrder.getSortOrder(prefs).name,
      disableSync = prefs.getBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, false),
      filterRecursively = prefs.getBoolean(PreferenceKeys.FILTER_RECURSIVELY, true),
      searchOnStart = prefs.getBoolean(PreferenceKeys.SEARCH_ON_START, false),
      showHidden = prefs.getBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, false),
      biometric = prefs.getBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, false),
      defaultUsername = prefs.getString(PreferenceKeys.OREO_AUTOFILL_DEFAULT_USERNAME).orEmpty(),
      customSuffixes =
        prefs.getString(PreferenceKeys.OREO_AUTOFILL_CUSTOM_PUBLIC_SUFFIXES).orEmpty(),
      pwgenType = prefs.getString(PreferenceKeys.PREF_KEY_PWGEN_TYPE) ?: "diceware",
      clipboardTimeout = prefs.getString(PreferenceKeys.GENERAL_SHOW_TIME).orEmpty(),
      showPassword = prefs.getBoolean(PreferenceKeys.SHOW_PASSWORD, true),
      copyOnDecrypt = prefs.getBoolean(PreferenceKeys.COPY_ON_DECRYPT, false),
      rebaseOnPull = gitSettings.rebaseOnPull,
      pushAfterCommit = gitSettings.pushAfterCommit,
      isGitRepo = PasswordRepository.isGitRepo(),
      httpsRemote = gitSettings.url?.startsWith("https") == true,
      canShowSshKey = SshKey.canShowSshPublicKey,
      // contains, never getString: the secret is not decrypted to decide whether to show a row.
      savedSecret =
        when {
          gitPrefs.contains(PreferenceKeys.HTTPS_PASSWORD) -> R.string.clear_saved_passphrase_https
          gitPrefs.contains(PreferenceKeys.SSH_KEY_LOCAL_PASSPHRASE) ->
            R.string.clear_saved_passphrase_ssh
          else -> null
        },
      clearClipboardHistory = prefs.getBoolean(PreferenceKeys.CLEAR_CLIPBOARD_HISTORY, false),
      debugLogging = prefs.getBoolean(PreferenceKeys.ENABLE_DEBUG_LOGGING, false),
      asciiArmor = prefs.getBoolean(PreferenceKeys.ASCII_ARMOR, false),
      passphraseCache = prefs.getBoolean(Feature.EnablePGPPassphraseCache.configKey, false),
      autoClearCache = prefs.getBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, true),
    )
  }
}
