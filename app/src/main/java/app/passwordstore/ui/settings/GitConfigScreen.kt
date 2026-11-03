/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupDialog
import app.passwordstore.util.extensions.snackbar
import com.github.michaelbull.result.fold
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Author name and email with Save, then the repository utilities: HEAD ([head], already a
 * sentence), commit log, abort rebase ([canAbort]), hard reset, gc. [busy] disables everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitConfigScreen(
  name: String,
  email: String,
  head: String,
  canAbort: Boolean,
  busy: Boolean,
  onNameChange: (String) -> Unit,
  onEmailChange: (String) -> Unit,
  onSave: () -> Unit,
  onLog: () -> Unit,
  onAbort: () -> Unit,
  onReset: () -> Unit,
  onGc: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_activity_git_config)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
    ) {
      val field = Modifier.fillMaxWidth()
      OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = field,
        enabled = !busy,
        label = { Text(stringResource(R.string.git_user_name_hint)) },
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
      )
      OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        modifier = field,
        enabled = !busy,
        label = { Text(stringResource(R.string.git_user_email)) },
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
          ),
      )
      Button(onClick = onSave, modifier = Modifier.align(Alignment.End), enabled = !busy) {
        Text(stringResource(R.string.crypto_save))
      }
      Text(stringResource(R.string.git_tools), style = MaterialTheme.typography.titleLarge)
      Text(head)
      if (busy) LinearProgressIndicator(field)
      OutlinedButton(onClick = onLog, modifier = field, enabled = !busy) {
        Text(stringResource(R.string.git_log))
      }
      OutlinedButton(onClick = onAbort, modifier = field, enabled = canAbort && !busy) {
        Text(stringResource(R.string.abort_rebase))
      }
      OutlinedButton(onClick = onReset, modifier = field, enabled = !busy) {
        Text(stringResource(R.string.reset_to_remote))
      }
      OutlinedButton(onClick = onGc, modifier = field, enabled = !busy) {
        Text(stringResource(R.string.git_run_gc_job))
      }
    }
  }
}

/** Asks before a hard reset, for the remote [branch] to reset to. */
@Composable
fun ResetDialog(
  branch: String,
  onBranchChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(R.string.reset_to_remote)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(SpacingLarge)) {
        Text(stringResource(R.string.git_reset_confirm_message))
        OutlinedTextField(
          value = branch,
          onValueChange = onBranchChange,
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.git_utils_reset_remote_branch_title)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm, enabled = branch.isNotBlank()) {
        Text(stringResource(R.string.dialog_ok))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}

private enum class GitConfigDialog {
  InvalidEmail,
  Reset,
}

/**
 * The git config screen: wires [GitConfigScreen] to the [viewModel] and runs the utilities as git
 * operations of [activity]. A successful reset or gc stays here with a short snackbar; an abort
 * shows its report, which closes the screen; a failure shows the git error dialog and the screen
 * stays.
 */
@Composable
fun GitConfigDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  onLog: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: GitConfigViewModel = hiltViewModel(),
) {
  var dialog by rememberSaveable { mutableStateOf<GitConfigDialog?>(null) }
  var branch by rememberSaveable { mutableStateOf("") }

  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  // A running operation holds the screen: leaving would cancel it half way.
  BackHandler(enabled = viewModel.busy) {}
  // A finished reset or gc is announced once, also when the screen was recreated in between:
  // the result stays in the ViewModel until the snackbar was shown.
  val done = viewModel.done
  LaunchedEffect(done) {
    val label =
      when (done) {
        GitOp.RESET -> R.string.reset_to_remote
        GitOp.GC -> R.string.git_run_gc_job
        else -> return@LaunchedEffect
      }
    activity.snackbar(
      message = activity.getString(R.string.git_tool_done, activity.getString(label)),
      length = Snackbar.LENGTH_LONG,
    )
    viewModel.done = null
  }

  // ponytail: the activity's scope, like every git operation of the app: a theme or locale change
  // cancels it, and the finally block frees the screen.
  fun run(op: GitOp, remote: String = "") {
    if (viewModel.busy) return
    viewModel.busy = true
    activity.lifecycleScope.launch {
      try {
        activity
          .gitOperation(op, remote)
          .fold(
            success = {
              if (op == GitOp.BREAK_OUT_OF_DETACHED)
                viewModel.abortedBranch = PasswordRepository.getCurrentBranch()
              viewModel.done = op
            },
            failure = { activity.promptOnErrorHandler(it) },
          )
      } finally {
        viewModel.busy = false
        viewModel.refresh()
      }
    }
  }

  val head =
    when (val status = viewModel.head) {
      is HeadStatus.Branch -> stringResource(R.string.git_head_on_branch, status.name)
      is HeadStatus.Detached -> stringResource(R.string.git_head_detached, status.hash)
      HeadStatus.Missing -> stringResource(R.string.git_head_missing)
      null -> ""
    }
  Box(modifier) {
    GitConfigScreen(
      name = viewModel.name,
      email = viewModel.email,
      head = head,
      canAbort = viewModel.canAbort,
      busy = viewModel.busy,
      onNameChange = { viewModel.name = it },
      onEmailChange = { viewModel.email = it },
      onSave = {
        if (viewModel.save()) {
          Toast.makeText(activity, R.string.git_server_config_save_success, Toast.LENGTH_SHORT)
            .show()
          onClose()
        } else dialog = GitConfigDialog.InvalidEmail
      },
      onLog = onLog,
      onAbort = {
        // Checked again: BreakOutOfDetached reports success without doing anything otherwise.
        if (PasswordRepository.repository?.let(::needsAbort) == true)
          run(GitOp.BREAK_OUT_OF_DETACHED)
        else viewModel.refresh()
      },
      onReset = {
        branch = PasswordRepository.getCurrentBranch().orEmpty()
        dialog = GitConfigDialog.Reset
      },
      onGc = { run(GitOp.GC) },
      onBack = { if (!viewModel.busy) onClose() },
    )
    when (dialog) {
      null -> Unit
      GitConfigDialog.InvalidEmail ->
        SetupDialog(
          text = stringResource(R.string.invalid_email_dialog_text),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = { dialog = null },
          onDismiss = { dialog = null },
        )
      GitConfigDialog.Reset ->
        ResetDialog(
          branch = branch,
          onBranchChange = { branch = it },
          onConfirm = {
            dialog = null
            run(GitOp.RESET, branch.trim())
          },
          onDismiss = { dialog = null },
        )
    }
    if (done == GitOp.BREAK_OUT_OF_DETACHED) {
      val aborted = viewModel.abortedBranch.orEmpty()
      SetupDialog(
        title = stringResource(R.string.git_abort_and_push_title),
        text =
          stringResource(
            R.string.git_break_out_of_detached_success,
            aborted,
            "conflicting-$aborted-...",
          ),
        confirm = stringResource(R.string.dialog_ok),
        onConfirm = onClose,
        onDismiss = onClose,
      )
    }
  }
}
