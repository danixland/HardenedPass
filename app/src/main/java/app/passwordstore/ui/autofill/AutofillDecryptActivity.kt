/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import app.passwordstore.Application.Companion.screenWasOff
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.PGPPassphraseCache
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.getPGPIdentifiers
import app.passwordstore.ui.entry.PassphraseDialog
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupStart
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.autofill.AutofillResponseBuilder
import app.passwordstore.util.extensions.asLog
import app.passwordstore.util.features.Feature.EnablePGPPassphraseCache
import app.passwordstore.util.features.Features
import app.passwordstore.util.settings.DirectoryStructure
import app.passwordstore.util.settings.PreferenceKeys
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.Credentials
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/**
 * Decrypts one entry for the autofill service and returns the dataset to fill. Its transparent,
 * FLAG_SECURE window shows only the "no keys" and passphrase dialogs.
 */
@AndroidEntryPoint
class AutofillDecryptActivity : BasePGPActivity() {

  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory
  @Inject lateinit var features: Features
  @Inject lateinit var passphraseCache: PGPPassphraseCache

  private lateinit var directoryStructure: DirectoryStructure
  private lateinit var filePath: String
  private lateinit var clientState: Bundle
  private lateinit var action: AutofillAction
  private var clearCache = true

  /** The screen lock prompt's outcome: the passphrase is cached only after a success. */
  private var auth: BiometricResult = BiometricResult.CanceledByUser

  /** Set once the flow started; reset after a key import, so the next window focus restarts it. */
  private var started = false

  /** Shows the "No keys imported" dialog. */
  private var noKeys by mutableStateOf(false)

  /** The recipients the passphrase dialog asks for; null while it is not shown. */
  private var passphraseFor by mutableStateOf<List<PGPIdentifier>?>(null)

  // The import's activity is still on top when its result arrives: the flow restarts on the next
  // window focus, so the screen lock prompt is not shown (and evicted) behind it.
  private val keyImport =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) started = false else finish()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ponytail: a recreation (theme or locale change; rotation and resizing do not recreate, see
    // the manifest) cancels the fill instead of resuming a half-done flow; the user taps the field
    // again. Keep the flow's state in a ViewModel if this ever matters.
    if (savedInstanceState != null) {
      finish()
      return
    }
    filePath =
      intent?.getStringExtra(EXTRA_FILE_PATH)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_FILE_PATH" }
          finish()
          return
        }
    clientState =
      intent?.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_CLIENT_STATE" }
          finish()
          return
        }
    val isSearchAction = intent.getBooleanExtra(EXTRA_SEARCH_ACTION, true)
    action = if (isSearchAction) AutofillAction.Search else AutofillAction.Match
    directoryStructure = AutofillPreferences.directoryStructure(this)
    logcat { action.toString() }
    setContent {
      APSTheme {
        if (noKeys)
          AlertDialog(
            onDismissRequest = { finish() },
            title = { Text(stringResource(R.string.no_keys_imported_dialog_title)) },
            text = { Text(stringResource(R.string.no_keys_imported_dialog_message)) },
            confirmButton = {
              TextButton(
                onClick = {
                  noKeys = false
                  keyImport.launch(
                    MainActivity.setupIntent(this@AutofillDecryptActivity, SetupStart.PgpImport)
                  )
                }
              ) {
                Text(stringResource(R.string.button_label_import))
              }
            },
          )
        val identifiers = passphraseFor
        if (identifiers != null)
          PassphraseDialog(
            wrong = false,
            cacheEnabled = features.isEnabled(EnablePGPPassphraseCache),
            clearCache = clearCache,
            onSubmit = onSubmit@{ passphrase, clear ->
                // Cleared before decrypting: a second submit in the same frame does nothing.
                if (passphraseFor == null) return@onSubmit
                passphraseFor = null
                clearCache = clear
                lifecycleScope.launch(dispatcherProvider.main()) {
                  decryptWithPassphrase(identifiers, passphrase)
                }
              },
            onCancel = { finish() },
          )
      }
    }
  }

  // The flow waits for window focus, as LaunchActivity's prompt does: Android 14's SystemUI
  // dismisses a screen lock prompt shown before its activity is on top. It runs once (again only
  // after a key import); the old onStart start ran it on every return to this activity.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus || started || isFinishing) return
    started = true
    start()
  }

  /** Asks for a key import first while no PGP key is imported. */
  private fun start() {
    if (isFinishing) return
    lifecycleScope.launch { if (repository.hasKeys()) unlock() else noKeys = true }
  }

  private fun unlock() {
    if (
      features.isEnabled(EnablePGPPassphraseCache) && BiometricAuthenticator.canAuthenticate(this)
    ) {
      BiometricAuthenticator.authenticate(
        this,
        R.string.biometric_prompt_title_gpg_passphrase_cache,
      ) { authResult ->
        decrypt(authResult)
      }
    } else {
      decrypt(BiometricResult.CanceledByUser)
    }
  }

  private fun decrypt(authResult: BiometricResult) {
    auth = authResult
    val gpgIdentifiers =
      getPGPIdentifiers(
        getParentPath(filePath, PasswordRepository.getRepositoryDirectory().toString())
      ) ?: return
    lifecycleScope.launch(dispatcherProvider.main()) {
      when (authResult) {
        // Internally handled by the prompt dialog
        is BiometricResult.Retry -> {}
        // If the dialog is dismissed for any reason, prompt for passphrase
        is BiometricResult.CanceledBySystem,
        is BiometricResult.CanceledByUser,
        is BiometricResult.Failure,
        is BiometricResult.HardwareUnavailableOrDisabled -> askPassphrase(gpgIdentifiers)
        is BiometricResult.Success -> {
          /* clear passphrase cache on first use after application startup or if screen was off;
          also make sure to purge a stale cache after caching has been disabled via PGP settings */
          clearCache = settings.getBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, true)
          if (screenWasOff && clearCache) {
            passphraseCache.clearAllCachedPassphrases(this@AutofillDecryptActivity)
            screenWasOff = false
          }
          val cachedPassphrase =
            passphraseCache.retrieveCachedPassphrase(
              this@AutofillDecryptActivity,
              gpgIdentifiers.first(),
            )
          if (cachedPassphrase != null) {
            decryptWithPassphrase(gpgIdentifiers, cachedPassphrase)
          } else {
            askPassphrase(gpgIdentifiers)
          }
        }
      }
    }
  }

  private suspend fun askPassphrase(identifiers: List<PGPIdentifier>) {
    if (!repository.isPasswordProtected(identifiers)) {
      decryptWithPassphrase(identifiers, password = "")
      return
    }
    passphraseFor = identifiers
  }

  private suspend fun decryptWithPassphrase(identifiers: List<PGPIdentifier>, password: String) {
    val credentials = decryptCredential(File(filePath), password, identifiers)
    if (credentials == null) {
      setResult(RESULT_CANCELED)
    } else {
      val fillInDataset =
        AutofillResponseBuilder.makeFillInDataset(
          this@AutofillDecryptActivity,
          credentials,
          clientState,
          action,
        )
      withContext(dispatcherProvider.main()) {
        setResult(
          RESULT_OK,
          Intent().apply { putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillInDataset) },
        )
      }
    }
    withContext(dispatcherProvider.main()) { finish() }
  }

  private suspend fun decryptCredential(
    file: File,
    password: String,
    identifiers: List<PGPIdentifier>,
  ): Credentials? {
    runCatching { file.readBytes().inputStream() }
      .onFailure { e ->
        logcat(ERROR) { e.asLog("File to decrypt not found") }
        return null
      }
      .onSuccess { encryptedInput ->
        val outputStream = ByteArrayOutputStream()
        repository
          .decrypt(password, identifiers, encryptedInput, outputStream)
          .onFailure { e ->
            logcat(ERROR) { e.asLog("Decryption failed") }
            return null
          }
          .onSuccess { result ->
            return runCatching {
                runCatching {
                    if (cachesPassphrase(features.isEnabled(EnablePGPPassphraseCache), auth)) {
                      passphraseCache.cachePassphrase(this, identifiers.first(), password)
                      settings.edit {
                        putBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, clearCache)
                      }
                    }
                  }
                  .onFailure { e -> logcat { e.asLog() } }
                val entry = passwordEntryFactory.create(result.toByteArray())
                AutofillPreferences.credentialsFromStoreEntry(this, file, entry, directoryStructure)
              }
              .getOrElse { e ->
                logcat(ERROR) { e.asLog("Failed to parse password entry") }
                return null
              }
          }
      }
    return null
  }

  companion object {

    private const val EXTRA_FILE_PATH = "app.passwordstore.autofill.oreo.EXTRA_FILE_PATH"
    private const val EXTRA_SEARCH_ACTION = "app.passwordstore.autofill.oreo.EXTRA_SEARCH_ACTION"

    private var decryptFileRequestCode = 1

    fun makeDecryptFileIntent(file: File, forwardedExtras: Bundle, context: Context): Intent {
      return Intent(context, AutofillDecryptActivity::class.java).apply {
        putExtras(forwardedExtras)
        putExtra(EXTRA_SEARCH_ACTION, true)
        putExtra(EXTRA_FILE_PATH, file.absolutePath)
      }
    }

    fun makeDecryptFileIntentSender(file: File, context: Context): IntentSender {
      val intent =
        Intent(context, AutofillDecryptActivity::class.java).apply {
          putExtra(EXTRA_SEARCH_ACTION, false)
          putExtra(EXTRA_FILE_PATH, file.absolutePath)
        }
      return PendingIntent.getActivity(
          context,
          decryptFileRequestCode++,
          intent,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
          } else {
            PendingIntent.FLAG_CANCEL_CURRENT
          },
        )
        .intentSender
    }
  }
}
