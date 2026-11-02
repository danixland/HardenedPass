/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.Application.Companion.screenWasOff
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.data.crypto.PGPPassphraseCache
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.features.Feature.EnablePGPPassphraseCache
import app.passwordstore.util.features.Features
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/** The dialog the entry destination shows, if any. */
enum class EntryPrompt {
  None,
  NoKeys,
  Passphrase,
  WrongPassphrase,
}

/**
 * Decrypts one entry as the old decrypt screen did: passphrase cache, passphrase prompt with
 * retries, then the auto-close timer. The decrypted [entry] lives only in memory: never in saved
 * state or a route argument. The timer drops it, and so does popping the destination (its back
 * stack entry clears this ViewModel). Holds no Context; calls take one and use the application
 * context.
 */
@HiltViewModel
class EntryViewModel
@Inject
constructor(
  private val repository: CryptoRepository,
  private val entryFactory: PasswordEntry.Factory,
  private val passphraseCache: PGPPassphraseCache,
  private val features: Features,
  @SettingsPreferences private val settings: SharedPreferences,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var entry by mutableStateOf<PasswordEntry?>(null)
    private set

  var prompt by mutableStateOf(EntryPrompt.None)
    private set

  /** Set when the destination must close: timer, cancelled prompt, too many wrong passphrases. */
  var closed by mutableStateOf(false)
    private set

  /** Initial state of the passphrase dialog's "clear on screen off" box. */
  var clearCache by mutableStateOf(true)
    private set

  val cacheEnabled: Boolean
    get() = features.isEnabled(EnablePGPPassphraseCache)

  private var started = false
  private var copyOnDecrypt = false
  private var retries = 0
  private var auth: BiometricResult = BiometricResult.CanceledByUser
  private var ids: List<PGPIdentifier> = emptyList()
  private var file: File? = null

  /** True the first time only: a recreated activity must not run the unlock flow again. */
  fun start(): Boolean {
    if (started) return false
    started = true
    return true
  }

  /** False, with the "no keys" prompt up, while no PGP key is imported. */
  suspend fun checkKeys(): Boolean {
    val hasKeys = repository.hasKeys()
    if (!hasKeys) prompt = EntryPrompt.NoKeys
    return hasKeys
  }

  /** Continues after the biometric prompt (or without one) with its [auth] result. */
  fun unlock(file: File, ids: List<PGPIdentifier>, auth: BiometricResult, context: Context) {
    this.file = file
    this.ids = ids
    this.auth = auth
    val app = context.applicationContext
    viewModelScope.launch { next(isError = false, app) }
  }

  fun submit(passphrase: String, clearCache: Boolean, context: Context) {
    prompt = EntryPrompt.None
    this.clearCache = clearCache
    val app = context.applicationContext
    viewModelScope.launch {
      if (decrypt(passphrase, app) && auth is BiometricResult.Success) {
        runCatching {
            passphraseCache.cachePassphrase(app, ids.first(), passphrase)
            settings.edit { putBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, clearCache) }
          }
          .onFailure { e -> logcat { e.asLog() } }
      }
    }
  }

  fun dismissPrompt() {
    prompt = EntryPrompt.None
  }

  fun cancel() {
    prompt = EntryPrompt.None
    closed = true
  }

  /** True once after a decryption when "Automatically copy password" is on. */
  fun takeCopyOnDecrypt(): Boolean = copyOnDecrypt.also { copyOnDecrypt = false }

  override fun onCleared() {
    entry = null
  }

  private suspend fun next(isError: Boolean, context: Context) {
    when (auth) {
      // Handled inside the biometric prompt.
      BiometricResult.Retry -> Unit
      is BiometricResult.Success -> {
        // Clear the cache on first use after app start or after the screen was off; this also
        // purges a stale cache after caching was disabled in the PGP settings.
        clearCache = settings.getBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, true)
        if (screenWasOff && clearCache) {
          passphraseCache.clearAllCachedPassphrases(context)
          screenWasOff = false
        }
        // A cached passphrase that just failed is not tried again (the old screen looped on it).
        val cached =
          if (isError) null else passphraseCache.retrieveCachedPassphrase(context, ids.first())
        if (cached != null) decrypt(cached, context) else ask(isError, context)
      }
      else -> ask(isError, context)
    }
  }

  private suspend fun ask(isError: Boolean, context: Context) {
    if (retries >= MAX_RETRIES) {
      closed = true
      return
    }
    retries++
    if (withContext(dispatcherProvider.io()) { repository.isPasswordProtected(ids) }) {
      prompt = if (isError) EntryPrompt.WrongPassphrase else EntryPrompt.Passphrase
    } else {
      decrypt(passphrase = "", context)
    }
  }

  /** Decrypts off the main thread; a failure goes back to [next] with an error. */
  private suspend fun decrypt(passphrase: String, context: Context): Boolean {
    val source = file ?: return false
    val bytes = withContext(dispatcherProvider.io()) { source.readBytes() }
    val out = ByteArrayOutputStream()
    val result = repository.decrypt(passphrase, ids, bytes.inputStream(), out)
    if (!result.isOk) {
      logcat(ERROR) { result.error.stackTraceToString() }
      next(isError = true, context)
      return false
    }
    copyOnDecrypt = settings.getBoolean(PreferenceKeys.COPY_ON_DECRYPT, false)
    entry = withContext(dispatcherProvider.io()) { entryFactory.create(out.toByteArray()) }
    startTimer()
    return true
  }

  /** Drops the entry and closes after the "Password copy timeout" setting (0 = never). */
  private fun startTimer() {
    val timeout =
      settings.getString(PreferenceKeys.GENERAL_SHOW_TIME)?.toIntOrNull()
        ?: Constants.DEFAULT_DECRYPTION_TIMEOUT
    if (timeout == 0) return
    viewModelScope.launch {
      delay(timeout.seconds)
      entry = null
      closed = true
    }
  }

  private companion object {
    const val MAX_RETRIES = 3
  }
}
