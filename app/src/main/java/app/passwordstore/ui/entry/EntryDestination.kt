/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import app.passwordstore.R
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.passfile.Totp
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.BasePGPActivity.Companion.getParentPath
import app.passwordstore.ui.crypto.PasswordCreationActivity
import app.passwordstore.ui.crypto.copyPasswordToClipboard
import app.passwordstore.ui.crypto.copyTextToClipboard
import app.passwordstore.ui.crypto.getPGPIdentifiers
import app.passwordstore.ui.main.LaunchActivity
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.pgp.PGPKeyImportActivity
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File

/**
 * One entry: wires [EntryScreen] to the [viewModel], the biometric and passphrase prompts, the
 * clipboard and the legacy editor. [onClose] leaves the destination; [onSaved] runs after the
 * editor saved the entry (the host returns to the list).
 */
@Composable
fun EntryDestination(
  activity: MainActivity,
  file: File,
  onClose: () -> Unit,
  onSaved: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: EntryViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()

  // Secrets stay out of screenshots and recents while this destination is composed.
  // ponytail: plain add/clear, fine while no other secret-showing destination can overlap this
  // one in a transition; count in MainActivity once the editor (step 4) becomes a destination.
  DisposableEffect(Unit) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
  }

  // Routes come from the app's own listing or from LaunchActivity's check; check once more.
  val inRepo = remember(file) { LaunchActivity.isFileInRepo(file.path, root) }
  LaunchedEffect(inRepo) { if (!inRepo) onClose() }
  if (!inRepo) return

  fun unlock() {
    val ids =
      activity.getPGPIdentifiers(getParentPath(file.absolutePath, root.absolutePath)) ?: return
    if (viewModel.cacheEnabled && BiometricAuthenticator.canAuthenticate(activity)) {
      BiometricAuthenticator.authenticate(
        activity,
        R.string.biometric_prompt_title_gpg_passphrase_cache,
      ) { result ->
        viewModel.unlock(file, ids, result, activity)
      }
    } else {
      viewModel.unlock(file, ids, BiometricResult.CanceledByUser, activity)
    }
  }

  // ponytail: after process death during key import or the editor, the unlock flow can start
  // twice or a prompt can be left over the list; rare, no secret exposed. Fix by tracking
  // whether the flow already started if it shows up.
  val keyImport =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) unlock() else onClose()
    }
  val editor =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) onSaved()
    }

  // ponytail: a theme or locale change while the biometric prompt is up loses that prompt's
  // callback (the flow already counts as started); back out and reopen the entry.
  LaunchedEffect(Unit) { if (viewModel.start() && viewModel.checkKeys()) unlock() }
  LaunchedEffect(viewModel.closed) { if (viewModel.closed) onClose() }

  val entry = viewModel.entry
  LaunchedEffect(entry) {
    val password = entry?.password
    if (viewModel.takeCopyOnDecrypt() && !password.isNullOrBlank()) {
      activity.copyPasswordToClipboard(password)
    }
  }
  val fields = remember(entry) { entry?.let(::entryFields) }
  val totp by
    produceState<Totp?>(null, entry) {
      if (entry != null && entry.hasTotp()) entry.totp.collect { value = it }
    }
  val folder =
    remember(file) { file.canonicalFile.parentFile?.relativeTo(root.canonicalFile)?.path.orEmpty() }
  val showPassword = remember {
    activity.sharedPrefs.getBoolean(PreferenceKeys.SHOW_PASSWORD, true)
  }

  Box(modifier) {
    EntryScreen(
      name = file.nameWithoutExtension,
      folder = folder,
      fields = fields,
      otp = totp?.value,
      otpSecondsLeft = totp?.remainingTime?.inWholeSeconds ?: 0L,
      otpFractionLeft = totp?.fractionLeft() ?: 0f,
      showPassword = showPassword,
      onBack = onClose,
      onEdit = { entry?.let { editor.launch(editIntent(activity, file, root, it)) } },
      onShare = { fields?.password?.let { activity.startActivity(shareIntent(activity, it)) } },
      onCopyName = { activity.copyTextToClipboard(file.nameWithoutExtension) },
      onCopySecret = { activity.copyPasswordToClipboard(it) },
      onCopy = { activity.copyTextToClipboard(it) },
    )

    when (viewModel.prompt) {
      EntryPrompt.None -> Unit
      EntryPrompt.NoKeys ->
        AlertDialog(
          onDismissRequest = viewModel::cancel,
          title = { Text(stringResource(R.string.no_keys_imported_dialog_title)) },
          text = { Text(stringResource(R.string.no_keys_imported_dialog_message)) },
          confirmButton = {
            TextButton(
              onClick = {
                viewModel.dismissPrompt()
                keyImport.launch(Intent(activity, PGPKeyImportActivity::class.java))
              }
            ) {
              Text(stringResource(R.string.button_label_import))
            }
          },
        )
      EntryPrompt.Passphrase,
      EntryPrompt.WrongPassphrase ->
        key(viewModel.retries) {
          PassphraseDialog(
            wrong = viewModel.prompt == EntryPrompt.WrongPassphrase,
            cacheEnabled = viewModel.cacheEnabled,
            clearCache = viewModel.clearCache,
            onSubmit = { passphrase, clear -> viewModel.submit(passphrase, clear, activity) },
            onCancel = viewModel::cancel,
          )
        }
    }
  }
}

/** Asks for the PGP passphrase, as the old `PasswordDialog` did. */
@Composable
private fun PassphraseDialog(
  wrong: Boolean,
  cacheEnabled: Boolean,
  clearCache: Boolean,
  onSubmit: (passphrase: String, clearCache: Boolean) -> Unit,
  onCancel: () -> Unit,
) {
  // remember, never rememberSaveable: the passphrase must not reach saved state.
  var passphrase by remember { mutableStateOf("") }
  var clear by remember { mutableStateOf(clearCache) }
  var error by remember { mutableStateOf(wrong) }
  val focus = remember { FocusRequester() }
  AlertDialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    title = { Text(stringResource(R.string.password)) },
    text = {
      Column {
        OutlinedTextField(
          value = passphrase,
          onValueChange = {
            passphrase = it
            error = false
          },
          modifier = Modifier.focusRequester(focus),
          label = { Text(stringResource(R.string.password)) },
          supportingText =
            if (error) {
              { Text(stringResource(R.string.git_operation_wrong_password)) }
            } else null,
          isError = error,
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { onSubmit(passphrase, clear) }),
        )
        if (cacheEnabled)
          Row(
            Modifier.toggleable(
              value = clear,
              role = Role.Checkbox,
              onValueChange = { clear = it },
            ),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(checked = clear, onCheckedChange = null)
            Text(stringResource(R.string.clear_cached_password_on_screen_off))
          }
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = {
      TextButton(onClick = { onSubmit(passphrase, clear) }) {
        Text(stringResource(R.string.dialog_ok))
      }
    },
  )
}

/** The legacy editor, filled exactly as the old decrypt screen filled it. */
private fun editIntent(activity: Activity, file: File, root: File, entry: PasswordEntry): Intent =
  Intent(activity, PasswordCreationActivity::class.java)
    .setAction(Intent.ACTION_VIEW)
    .putExtra(BasePGPActivity.EXTRA_FILE_PATH, getParentPath(file.absolutePath, root.absolutePath))
    .putExtra(BasePGPActivity.EXTRA_REPO_PATH, root.absolutePath)
    .putExtra(PasswordCreationActivity.EXTRA_FILE_NAME, file.nameWithoutExtension)
    .putExtra(PasswordCreationActivity.EXTRA_USERNAME, entry.username)
    .putExtra(PasswordCreationActivity.EXTRA_PASSWORD, entry.password)
    .putExtra(PasswordCreationActivity.EXTRA_EXTRA_CONTENT, entry.extraContentWithoutUsername)
    .putExtra(PasswordCreationActivity.EXTRA_EDITING, true)

/** Always a chooser, so the user can still cancel. */
private fun shareIntent(activity: Activity, password: String): Intent =
  Intent.createChooser(
    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, password).setType("text/plain"),
    activity.getText(R.string.send_plaintext_password_to),
  )
