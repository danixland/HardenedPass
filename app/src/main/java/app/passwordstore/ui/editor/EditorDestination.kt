/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.copyPasswordToClipboard
import app.passwordstore.ui.crypto.getPGPIdentifiers
import app.passwordstore.ui.list.FolderPicker
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.isInsideRepository
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.integration.android.IntentIntegrator
import java.io.File
import kotlinx.coroutines.launch

/**
 * The editor: wires [EditorScreen] to the [viewModel], the generator sheet, the folder picker, OTP
 * import and the save path (checks, recipients from `.gpg-id`, encryption, write, git commit).
 * [initial] fills the form the first time; null means the entry to edit is no longer in memory
 * (process death) and the editor closes. [original] is the file being edited, null for a new entry.
 * [generate] opens the generator at once (autofill's "generate password"). [onClose] leaves without
 * saving; [onSaved] runs after the commit.
 */
@Composable
fun EditorDestination(
  activity: FragmentActivity,
  initial: EditorForm?,
  original: File?,
  onClose: () -> Unit,
  onSaved: (SaveResult.Saved) -> Unit,
  modifier: Modifier = Modifier,
  generate: Boolean = false,
  viewModel: EditorViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  // ponytail: filled once per ViewModel, so a recreated activity keeps the edits; an edit restored
  // after process death has nothing to fill from (the entry view's plaintext is gone) and closes,
  // back to the entry view's passphrase prompt.
  val first = remember { initial != null && viewModel.start(initial) }
  val lost = !viewModel.started
  LaunchedEffect(lost) { if (lost) onClose() }
  if (lost) return

  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val scope = rememberCoroutineScope()
  var sheet by rememberSaveable { mutableStateOf(first && generate) }
  var picker by rememberSaveable { mutableStateOf(false) }
  var otp by rememberSaveable { mutableStateOf(false) }
  var failure by remember { mutableStateOf<String?>(null) }
  val form = viewModel.form
  val hasOtp = remember(form.extra) { viewModel.hasOtp(form.extra) }
  val hasCamera = remember {
    activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
  }
  val showPassword = remember {
    activity.sharedPrefs.getBoolean(PreferenceKeys.SHOW_PASSWORD, true)
  }
  val classicFirst = remember {
    activity.sharedPrefs.getString(PreferenceKeys.PREF_KEY_PWGEN_TYPE) == "classic"
  }

  fun say(message: Int) {
    activity.snackbar(message = activity.getString(message))
  }

  fun addLine(line: String) {
    viewModel.form = viewModel.form.copy(extra = withLine(viewModel.form.extra, line))
  }

  // The one way out without saving: only while no save runs, keyboard and focus dropped first.
  fun close() {
    if (viewModel.saving) return
    keyboard?.hide()
    focusManager.clearFocus()
    onClose()
  }

  fun save(copy: Boolean) {
    if (viewModel.saving) return
    val current = viewModel.form
    val target = entryFile(root, current)
    val problem =
      formError(current)
        ?: R.string.message_error_destination_outside_repo.takeUnless {
          target.isInsideRepository()
        }
    if (problem != null) return say(problem)
    // Recipients from the nearest .gpg-id, as pass does; null: an invalid ID, already reported.
    val ids = activity.getPGPIdentifiers(current.dir) ?: return
    activity.lifecycleScope.launch {
      when (val result = viewModel.save(ids, target, original, activity)) {
        null -> Unit
        is SaveResult.Failed -> failure = activity.getString(result.message, result.arg)
        is SaveResult.Saved -> {
          if (copy) activity.copyPasswordToClipboard(result.form.password)
          result.staleOriginal?.let {
            activity.snackbar(
              message =
                activity.getString(
                  R.string.password_creation_file_delete_fail_message,
                  it.nameWithoutExtension,
                ),
              length = Snackbar.LENGTH_LONG,
            )
          }
          // ponytail: commit failures are not shown, as for the list's move and delete; a theme
          // or locale change in the middle of a save cancels before this commit, and the next
          // commit picks the file up.
          activity.commitChange(
            activity.getString(
              if (original != null) R.string.git_commit_edit_text else R.string.git_commit_add_text,
              target.relativeTo(root).path.removeSuffix(".gpg"),
            )
          )
          keyboard?.hide()
          focusManager.clearFocus()
          onSaved(result)
        }
      }
    }
  }

  BackHandler(onBack = ::close)

  val scan =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      val text = IntentIntegrator.parseActivityResult(result.resultCode, result.data)?.contents
      if (result.resultCode == Activity.RESULT_OK && text != null) {
        addLine("$text\n")
        say(R.string.otp_import_success)
      } else {
        say(R.string.otp_import_failure_generic)
      }
    }
  val image =
    rememberLauncherForActivityResult(GetContent()) { uri ->
      if (uri == null) {
        say(R.string.otp_import_failure_no_selection)
      } else {
        scope.launch {
          val text = viewModel.readQr(uri, activity)
          if (text != null) {
            addLine(text)
            say(R.string.otp_import_success)
          } else {
            say(R.string.otp_import_failure_generic)
          }
        }
      }
    }

  Box(modifier) {
    EditorScreen(
      form = form,
      editing = original != null,
      saving = viewModel.saving,
      showOtpActions = !hasOtp,
      initiallyRevealed = showPassword,
      focus =
        when {
          !first -> EditorFocus.None
          form.name.isEmpty() -> EditorFocus.Name
          original == null -> EditorFocus.Username
          else -> EditorFocus.None
        },
      onFormChange = { viewModel.form = it },
      onClose = ::close,
      onSave = ::save,
      onChangeFolder = { picker = true },
      onGenerate = { sheet = true },
      onScanQr =
        if (hasCamera) {
          {
            scan.launch(
              IntentIntegrator(activity)
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .createScanIntent()
            )
          }
        } else null,
      onQrImage = { image.launch("image/*") },
      onManualOtp = { otp = true },
    )
    if (sheet)
      GeneratorSheet(
        classicFirst = classicFirst,
        random = viewModel.randomSpec(activity),
        diceware = viewModel.dicewareSpec(),
        onRandom = { spec -> viewModel.generateRandom(spec, activity) },
        onDiceware = viewModel::generateDiceware,
        onUse = { password ->
          viewModel.form = viewModel.form.copy(password = password)
          sheet = false
        },
        onDismiss = { sheet = false },
      )
    if (picker)
      FolderPicker(
        root = root,
        start = form.dir,
        onPick = { dir ->
          viewModel.form = viewModel.form.copy(dir = dir)
          picker = false
        },
        onDismiss = { picker = false },
      )
    if (otp)
      OtpDialog(
        onAdd = { secret, account ->
          totpUri(secret, account)?.let(::addLine)
          otp = false
        },
        onDismiss = { otp = false },
      )
    failure?.let { message ->
      AlertDialog(
        onDismissRequest = { failure = null },
        title = { Text(stringResource(R.string.password_creation_file_fail_title)) },
        text = { Text(message) },
        confirmButton = {
          TextButton(onClick = { failure = null }) { Text(stringResource(R.string.dialog_ok)) }
        },
      )
    }
  }
}
