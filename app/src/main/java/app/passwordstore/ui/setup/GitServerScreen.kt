/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.GitSettings.UpdateConnectionSettingsResult
import app.passwordstore.util.settings.Protocol
import com.github.michaelbull.result.fold
import kotlinx.coroutines.launch

/**
 * Server URL and authentication mode; the SSH key's state ([sshKey], a string resource) with Manage
 * while the mode is SSH key; "Clear saved host key" when one is saved. [progress] (a string
 * resource) shows while a clone runs, and disables everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitServerScreen(
  clone: Boolean,
  url: String,
  authMode: AuthMode,
  @StringRes sshKey: Int,
  hasHostKey: Boolean,
  @StringRes progress: Int?,
  onUrlChange: (String) -> Unit,
  onAuthModeChange: (AuthMode) -> Unit,
  onSshKey: () -> Unit,
  onClearHostKey: () -> Unit,
  onSubmit: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val idle = progress == null
  val modes = remember(url) { authModes(url.trim()) }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_activity_git_clone)) },
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
      OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = idle,
        label = { Text(stringResource(R.string.server_url)) },
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
          ),
      )
      Text(stringResource(R.string.connection_mode), style = MaterialTheme.typography.labelLarge)
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
          SegmentedButton(
            selected = mode == authMode,
            onClick = { onAuthModeChange(mode) },
            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            enabled = idle,
          ) {
            Text(stringResource(mode.label()))
          }
        }
      }
      if (authMode == AuthMode.SshKey)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
              stringResource(R.string.connection_mode_ssh_key),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(sshKey))
          }
          TextButton(onClick = onSshKey, enabled = idle) {
            Text(stringResource(R.string.ssh_key_manage))
          }
        }
      if (hasHostKey)
        OutlinedButton(onClick = onClearHostKey, enabled = idle) {
          Text(stringResource(R.string.clear_saved_host_key))
        }
      if (progress != null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(progress))
      }
      Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), enabled = idle) {
        Text(stringResource(if (clone) R.string.clone_button else R.string.crypto_save))
      }
    }
  }
}

@StringRes
private fun AuthMode.label(): Int =
  when (this) {
    AuthMode.SshKey -> R.string.connection_mode_ssh_key
    AuthMode.Password -> R.string.connection_mode_basic_authentication
    AuthMode.None -> R.string.connection_mode_none
  }

private sealed interface ServerDialog {
  data class Url(val problem: UrlProblem) : ServerDialog

  data class Username(val protocol: Protocol) : ServerDialog

  data object DeleteStore : ServerDialog
}

/**
 * The git server screen: wires [GitServerScreen] to the [viewModel], the URL checks, the save and,
 * with [clone], the clone into the store folder. [onCloned] gets whether the store still needs a
 * PGP key. A failed clone stays here, so URL or SSH key can be fixed and the clone retried.
 */
@Composable
fun GitServerDestination(
  activity: MainActivity,
  clone: Boolean,
  onClose: () -> Unit,
  onSshKey: () -> Unit,
  onCloned: (keyNeeded: Boolean) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: GitServerViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  val keyboard = LocalSoftwareKeyboardController.current
  var dialog by remember { mutableStateOf<ServerDialog?>(null) }

  // Back from the SSH key screen (or from an instance it opened) with another key.
  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  // A finished clone moves on from here, also when the screen was recreated in between.
  val cloned = viewModel.cloned
  LaunchedEffect(cloned) { if (cloned != null) onCloned(cloned) }
  // A running clone holds the screen: leaving would cancel it half way.
  BackHandler(enabled = viewModel.progress != null) {}

  fun say(message: String) {
    activity.snackbar(message = message, length = SnackbarDuration.Long)
  }

  // ponytail: a theme or locale change cancels the clone with the activity's scope, like every
  // git operation of the app; the finally block frees the screen, and a new clone first offers to
  // delete what was left. Once the clone succeeded, the key check runs in the ViewModel's scope
  // (finishClone), so a recreation from then on loses nothing.
  fun startClone() {
    viewModel.progress = R.string.git_clone_progress
    activity.lifecycleScope.launch {
      try {
        // A folder that could not be emptied is reported as such, not as a vague clone error.
        viewModel.clearStore(root)?.let { failure ->
          say(activity.getString(failure))
          return@launch
        }
        activity
          .launchGitOperation(GitOp.CLONE)
          .fold(
            success = { viewModel.finishClone(root) },
            failure = { activity.promptOnErrorHandler(it) },
          )
      } finally {
        viewModel.cloneEnded()
      }
    }
  }

  fun submit() {
    if (viewModel.progress != null) return
    keyboard?.hide()
    val problem = urlProblem(viewModel.url.trim())
    if (problem != null) {
      dialog = ServerDialog.Url(problem)
      return
    }
    when (val result = viewModel.save()) {
      UpdateConnectionSettingsResult.FailedToParseUrl ->
        say(activity.getString(R.string.git_server_config_save_error))
      is UpdateConnectionSettingsResult.MissingUsername ->
        dialog = ServerDialog.Username(result.newProtocol)
      is UpdateConnectionSettingsResult.AuthModeMismatch ->
        say(
          activity.getString(
            R.string.git_server_config_save_auth_mode_mismatch,
            result.newProtocol,
            result.validModes.joinToString(", "),
          )
        )
      UpdateConnectionSettingsResult.Valid ->
        if (!clone) {
          Toast.makeText(activity, R.string.git_server_config_save_success, Toast.LENGTH_SHORT)
            .show()
          onClose()
        } else {
          if (PasswordRepository.repository == null) PasswordRepository.initialize()
          if (cloneTarget(root) == CloneTarget.Occupied) dialog = ServerDialog.DeleteStore
          else startClone()
        }
    }
  }

  Box(modifier) {
    GitServerScreen(
      clone = clone,
      url = viewModel.url,
      authMode = viewModel.authMode,
      sshKey = viewModel.sshKey,
      hasHostKey = viewModel.hasHostKey,
      progress = viewModel.progress,
      onUrlChange = viewModel::changeUrl,
      onAuthModeChange = { viewModel.authMode = it },
      onSshKey = onSshKey,
      onClearHostKey = {
        viewModel.clearHostKey()
        say(activity.getString(R.string.clear_saved_host_key_success))
      },
      onSubmit = ::submit,
      onBack = { if (viewModel.progress == null) onClose() },
    )
    when (val current = dialog) {
      null -> Unit
      is ServerDialog.Url -> {
        val (title, text) =
          when (current.problem) {
            UrlProblem.HttpsPort ->
              R.string.https_scheme_with_port_title to R.string.https_scheme_with_port_message
            UrlProblem.SshScheme ->
              R.string.ssh_scheme_needed_title to R.string.ssh_scheme_needed_message
            UrlProblem.GitScheme ->
              R.string.git_scheme_disallowed_title to R.string.git_scheme_disallowed_message
          }
        val fixed = fixUrl(viewModel.url.trim(), current.problem)
        SetupDialog(
          title = stringResource(title),
          text = stringResource(text),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = {
            dialog = null
            fixed?.let(viewModel::changeUrl)
          },
          onDismiss = { dialog = null },
          dismiss = if (fixed != null) stringResource(R.string.dialog_cancel) else null,
        )
      }
      is ServerDialog.Username ->
        SetupDialog(
          title = stringResource(R.string.ssh_scheme_needed_title),
          text =
            stringResource(
              if (current.protocol == Protocol.Https)
                R.string.git_server_config_save_missing_username_https
              else R.string.git_server_config_save_missing_username_ssh
            ),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = { dialog = null },
          onDismiss = { dialog = null },
        )
      ServerDialog.DeleteStore ->
        SetupDialog(
          title = stringResource(R.string.dialog_delete_title),
          text = stringResource(R.string.dialog_delete_msg, root.toString()),
          confirm = stringResource(R.string.dialog_delete),
          onConfirm = {
            dialog = null
            startClone()
          },
          onDismiss = { dialog = null },
          dismiss = stringResource(R.string.dialog_do_not_delete),
        )
    }
  }
}
