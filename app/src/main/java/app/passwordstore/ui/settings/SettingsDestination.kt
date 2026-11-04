/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupDialog
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result
import app.passwordstore.util.extensions.autofillManager
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.features.Feature
import app.passwordstore.util.services.PasswordExportService
import app.passwordstore.util.settings.PreferenceKeys
import com.github.androidpasswordstore.autofillparser.BrowserAutofillSupportLevel
import com.github.androidpasswordstore.autofillparser.getInstalledBrowsersWithAutofillSupportLevel

private enum class SettingsDialog {
  Autofill,
  ClearSecret,
  DeleteRepository,
}

/** The export's folder picker, with the persistable write grant `PasswordExportService` needs. */
private class ExportFolder : ActivityResultContracts.OpenDocumentTree() {
  override fun createIntent(context: Context, input: Uri?): Intent =
    super.createIntent(context, input).apply {
      flags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
          Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
          Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}

private fun MainActivity.autofillEnabled() = autofillManager?.hasEnabledAutofillServices() == true

/**
 * One settings category: wires [SettingsSectionScreen] to the [viewModel] and to what needs the
 * activity: screen lock prompts, the autofill service, the export picker, confirmations. [onOpen]
 * gets the actions that open another screen; [onDeleted] runs once the store was deleted.
 */
@Composable
fun SettingsSectionDestination(
  activity: MainActivity,
  section: SettingsSection,
  onBack: () -> Unit,
  onOpen: (SettingsAction) -> Unit,
  onDeleted: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
  var canAuthenticate by remember {
    mutableStateOf(BiometricAuthenticator.canAuthenticate(activity))
  }
  var autofillEnabled by remember { mutableStateOf(activity.autofillEnabled()) }

  fun refresh() {
    viewModel.refresh()
    canAuthenticate = BiometricAuthenticator.canAuthenticate(activity)
    autofillEnabled = activity.autofillEnabled()
  }

  // Back from another screen (git server, SSH key, key generation, the system's autofill
  // settings): rows that depend on it follow, e.g. "View generated public SSH key".
  LifecycleResumeEffect(Unit) {
    refresh()
    onPauseOrDispose {}
  }
  // A finished deletion moves on, also when the screen was recreated in between.
  val deleted = viewModel.deleted
  LaunchedEffect(deleted) {
    if (deleted == null) return@LaunchedEffect
    if (!deleted) activity.snackbar(message = activity.getString(R.string.store_delete_failed))
    onDeleted()
  }
  BackHandler(enabled = viewModel.deleting) {}

  val export =
    rememberLauncherForActivityResult(ExportFolder()) { uri ->
      if (uri != null && DocumentFile.fromTreeUri(activity, uri) != null)
        activity.startForegroundService(
          Intent(activity, PasswordExportService::class.java)
            .setAction(PasswordExportService.ACTION_EXPORT_PASSWORD)
            .putExtra("uri", uri)
        )
    }

  // One screen lock prompt at a time: a double tap must not open two. A recreation resets it
  // (remember), and the old activity's prompt is gone with it.
  var prompting by remember { mutableStateOf(false) }

  fun authenticate(@StringRes title: Int, onResult: (Result) -> Unit) {
    if (prompting) return
    prompting = true
    BiometricAuthenticator.authenticate(activity, title) { result ->
      // Retry: a finger was not recognized, the prompt stays open. Errors close it and are
      // terminal (Failure, Canceled*, HardwareUnavailableOrDisabled).
      if (result !is Result.Retry) prompting = false
      onResult(result)
    }
  }

  // Values are written only once allowed: a failed or cancelled prompt leaves the stored value,
  // which the switch shows.
  fun setBoolean(key: String, value: Boolean) {
    when (key) {
      PreferenceKeys.AUTOFILL_ENABLE ->
        if (value) dialog = SettingsDialog.Autofill
        else {
          activity.autofillManager?.disableAutofillServices()
          // hasEnabledAutofillServices() still answers true right after this call.
          autofillEnabled = false
        }
      PreferenceKeys.BIOMETRIC_AUTH_2 -> {
        fun write() {
          viewModel.putBoolean(key, value)
          // Shortcuts were built for the old lock setting, as the old screen knew.
          activity.getSystemService<ShortcutManager>()?.removeAllDynamicShortcuts()
        }
        // Without a screen lock no prompt can open: turning the app lock off needs none.
        if (!value && !BiometricAuthenticator.canAuthenticate(activity)) write()
        else
          authenticate(R.string.biometric_prompt_title) { result ->
            if (result is Result.Success) write()
          }
      }
      Feature.EnablePGPPassphraseCache.configKey ->
        if (!BiometricAuthenticator.canAuthenticate(activity)) viewModel.setPassphraseCache(false)
        else
          authenticate(
            if (value) R.string.pref_passphrase_cache_authenticate_enable
            else R.string.pref_passphrase_cache_authenticate_disable
          ) { result ->
            if (result is Result.Success) viewModel.setPassphraseCache(value)
          }
      PreferenceKeys.CLEAR_PASSPHRASE_CACHE ->
        when {
          value -> viewModel.putBoolean(key, true)
          !BiometricAuthenticator.canAuthenticate(activity) -> viewModel.resetAutoClear()
          else ->
            authenticate(R.string.pref_passphrase_cache_auto_clear_authenticate_disable) { result ->
              when (result) {
                is Result.Success -> viewModel.disableAutoClear()
                is Result.Retry -> Unit
                else -> viewModel.resetAutoClear()
              }
            }
        }
      else -> viewModel.putBoolean(key, value)
    }
  }

  Box(modifier) {
    SettingsSectionScreen(
      section = section,
      state = viewModel.state,
      canAuthenticate = canAuthenticate,
      autofillEnabled = autofillEnabled,
      deleting = viewModel.deleting,
      // Nothing else starts while the store is being deleted.
      onBoolean = { key, value -> if (!viewModel.deleting) setBoolean(key, value) },
      onString = { key, value -> if (!viewModel.deleting) viewModel.putString(key, value) },
      onAction = { action ->
        if (!viewModel.deleting)
          when (action) {
            SettingsAction.ClearSecret -> dialog = SettingsDialog.ClearSecret
            SettingsAction.DeleteRepository -> dialog = SettingsDialog.DeleteRepository
            SettingsAction.Export -> export.launch(null)
            else -> onOpen(action)
          }
      },
      onBack = { if (!viewModel.deleting) onBack() },
    )
    when (dialog) {
      null -> Unit
      SettingsDialog.Autofill ->
        AutofillDialog(
          onConfirm = {
            dialog = null
            activity.startActivity(
              Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                .setData("package:${BuildConfig.APPLICATION_ID}".toUri())
            )
          },
          onDismiss = { dialog = null },
        )
      SettingsDialog.ClearSecret ->
        SetupDialog(
          title = viewModel.state.savedSecret?.let { stringResource(it) },
          text = stringResource(R.string.clear_saved_secret_message),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = {
            dialog = null
            viewModel.clearSavedSecret()
          },
          onDismiss = { dialog = null },
          dismiss = stringResource(R.string.dialog_cancel),
        )
      SettingsDialog.DeleteRepository ->
        SetupDialog(
          title = stringResource(R.string.pref_dialog_delete_title),
          text = stringResource(R.string.store_delete_confirm),
          confirm = stringResource(R.string.dialog_delete),
          onConfirm = {
            dialog = null
            viewModel.deleteRepository()
          },
          onDismiss = { dialog = null },
          dismiss = stringResource(R.string.dialog_do_not_delete),
        )
    }
  }
}

/** The old autofill instructions: what it does, how to enable it, and the installed browsers. */
@Composable
private fun AutofillDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val browsers = remember { getInstalledBrowsersWithAutofillSupportLevel(context) }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(R.string.pref_autofill_enable_title)) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SpacingLarge),
      ) {
        Text(stringResource(R.string.oreo_autofill_enable_dialog_description))
        Text(stringResource(R.string.oreo_autofill_enable_dialog_instructions))
        Column {
          Text(
            stringResource(R.string.oreo_autofill_enable_dialog_installed_browsers),
            fontWeight = FontWeight.Bold,
          )
          browsers.forEach { (label, level) -> Text("$label: ${stringResource(level.label())}") }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text(stringResource(R.string.dialog_ok)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}

@StringRes
private fun BrowserAutofillSupportLevel.label(): Int =
  when (this) {
    BrowserAutofillSupportLevel.None -> R.string.oreo_autofill_no_support
    BrowserAutofillSupportLevel.FlakyFill -> R.string.oreo_autofill_flaky_fill_support
    BrowserAutofillSupportLevel.PasswordFill -> R.string.oreo_autofill_password_fill_support
    BrowserAutofillSupportLevel.PasswordFillAndSaveIfNoAccessibility ->
      R.string.oreo_autofill_password_fill_and_conditional_save_support
    BrowserAutofillSupportLevel.GeneralFill -> R.string.oreo_autofill_general_fill_support
    BrowserAutofillSupportLevel.GeneralFillAndSave ->
      R.string.oreo_autofill_general_fill_and_save_support
  }
