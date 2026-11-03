/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.passwordstore.R
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.crypto.copyTextToClipboard
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.extensions.keyguardManager
import app.passwordstore.util.git.sshj.SshKey

/**
 * The SSH key: its state ([summary], a string resource), the public key with copy and share when
 * the device generated it, import and generation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyScreen(
  @StringRes summary: Int,
  publicKey: String?,
  busy: Boolean,
  onImport: () -> Unit,
  onGenerate: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.ssh_keygen_existing_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      GroupedSurface {
        Text(
          stringResource(summary),
          Modifier.padding(SpacingLarge),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
      if (publicKey != null)
        GroupedSurface {
          Column(
            Modifier.padding(SpacingLarge),
            verticalArrangement = Arrangement.spacedBy(SpacingMedium),
          ) {
            Text(
              stringResource(R.string.your_public_key),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
              Text(
                publicKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
            }
            Text(
              stringResource(R.string.ssh_key_public_hint),
              style = MaterialTheme.typography.bodySmall,
            )
            Row {
              TextButton(onClick = onCopy) { Text(stringResource(R.string.copy_label)) }
              TextButton(onClick = onShare) { Text(stringResource(R.string.ssh_keygen_share)) }
            }
          }
        }
      Column(
        Modifier.padding(SpacingLarge),
        verticalArrangement = Arrangement.spacedBy(SpacingMedium),
      ) {
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.pref_import_ssh_key_title))
        }
        OutlinedButton(onClick = onGenerate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.pref_ssh_keygen_title))
        }
      }
    }
  }
}

/** A chooser sharing [publicKey] as text. */
private fun shareKey(publicKey: String): Intent =
  Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicKey),
    null,
  )

/**
 * The SSH key screen: wires [SshKeyScreen] to the [viewModel], the document picker and the replace
 * confirmation. [onGenerate] opens the generation screen.
 */
@Composable
fun SshKeyDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  onGenerate: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SshKeyViewModel = hiltViewModel(),
) {
  var resumes by remember { mutableIntStateOf(0) }
  // Back from the generation screen (or another instance) with a new key: read it again.
  LifecycleResumeEffect(Unit) {
    resumes++
    onPauseOrDispose {}
  }
  val version = viewModel.version
  val summary = remember(resumes, version) { sshKeySummary() }
  val publicKey =
    remember(resumes, version) { if (SshKey.canShowSshPublicKey) SshKey.sshPublicKey else null }
  var replace by remember { mutableStateOf(false) }
  val picker =
    rememberLauncherForActivityResult(OpenDocument()) { uri ->
      if (uri != null) viewModel.import(uri)
    }

  LaunchedEffect(viewModel.imported) {
    if (viewModel.imported) {
      Toast.makeText(activity, R.string.ssh_key_success_dialog_title, Toast.LENGTH_LONG).show()
      viewModel.announced()
    }
  }
  BackHandler(enabled = viewModel.busy) {}

  Box(modifier) {
    SshKeyScreen(
      summary = summary,
      publicKey = publicKey,
      busy = viewModel.busy,
      onImport = { if (SshKey.exists) replace = true else picker.launch(arrayOf("*/*")) },
      onGenerate = onGenerate,
      onCopy = { publicKey?.let { activity.copyTextToClipboard(it) } },
      onShare = { publicKey?.let { activity.startActivity(shareKey(it)) } },
      onBack = { if (!viewModel.busy) onClose() },
    )
    if (replace)
      SetupDialog(
        title = stringResource(R.string.ssh_keygen_existing_title),
        text = stringResource(R.string.ssh_keygen_existing_message),
        confirm = stringResource(R.string.ssh_keygen_existing_replace),
        onConfirm = {
          replace = false
          picker.launch(arrayOf("*/*"))
        },
        onDismiss = { replace = false },
        dismiss = stringResource(R.string.ssh_keygen_existing_keep),
      )
    viewModel.failure?.let { message ->
      SetupDialog(
        title = stringResource(R.string.ssh_key_error_dialog_title),
        text = message,
        confirm = stringResource(R.string.dialog_ok),
        onConfirm = viewModel::dismissFailure,
        onDismiss = viewModel::dismissFailure,
      )
    }
  }
}

/** Key type with its explanation, the screen lock switch and Generate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyGenScreen(
  type: SshKeyType,
  protect: Boolean,
  canProtect: Boolean,
  busy: Boolean,
  onTypeChange: (SshKeyType) -> Unit,
  onProtectChange: (Boolean) -> Unit,
  onGenerate: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val types = SshKeyType.entries
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.pref_ssh_keygen_title)) },
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
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        types.forEachIndexed { index, entry ->
          SegmentedButton(
            selected = entry == type,
            onClick = { onTypeChange(entry) },
            shape = SegmentedButtonDefaults.itemShape(index, types.size),
            enabled = !busy,
          ) {
            Text(stringResource(entry.label))
          }
        }
      }
      // ponytail: stringResource drops the explanations' bold title; plain text reads fine.
      Text(stringResource(type.explanation), style = MaterialTheme.typography.bodyMedium)
      Row(
        Modifier.fillMaxWidth()
          .toggleable(
            value = protect,
            enabled = canProtect && !busy,
            role = Role.Switch,
            onValueChange = onProtectChange,
          ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.ssh_keygen_require_authentication), Modifier.weight(1f))
        Switch(checked = protect, onCheckedChange = null, enabled = canProtect && !busy)
      }
      Button(onClick = onGenerate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(
          stringResource(
            if (busy) R.string.ssh_key_gen_generating_progress else R.string.ssh_keygen_generate
          )
        )
      }
    }
  }
}

/**
 * The key generation screen: replace confirmation, screen lock prompt when protected, then the
 * generation in the [viewModel] and the public key (or error) dialog; both dialogs close it.
 */
@Composable
fun SshKeyGenDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SshKeyViewModel = hiltViewModel(),
) {
  val canProtect = remember { activity.keyguardManager.isDeviceSecure }
  var replace by remember { mutableStateOf(false) }
  // While the screen lock prompt is up. Plain remember: a recreation drops the prompt's callback,
  // and the screen must not stay disabled for it.
  var asking by remember { mutableStateOf(false) }

  fun generate() {
    if (!(canProtect && viewModel.protect)) return viewModel.generate(requireAuthentication = false)
    asking = true
    BiometricAuthenticator.authenticate(activity, R.string.biometric_prompt_title_ssh_keygen) {
      result ->
      // Failed attempts are handled by the prompt itself.
      if (result is BiometricAuthenticator.Result.Retry) return@authenticate
      asking = false
      if (result is BiometricAuthenticator.Result.Success)
        viewModel.generate(requireAuthentication = true)
      else viewModel.fail(activity.getString(R.string.biometric_auth_generic_failure))
    }
  }

  BackHandler(enabled = viewModel.busy) {}

  Box(modifier) {
    SshKeyGenScreen(
      type = viewModel.type,
      protect = canProtect && viewModel.protect,
      canProtect = canProtect,
      busy = viewModel.busy || asking,
      onTypeChange = { viewModel.type = it },
      onProtectChange = { viewModel.protect = it },
      onGenerate = { if (SshKey.exists) replace = true else generate() },
      onBack = { if (!viewModel.busy) onClose() },
    )
    if (replace)
      SetupDialog(
        title = stringResource(R.string.ssh_keygen_existing_title),
        text = stringResource(R.string.ssh_keygen_existing_message),
        confirm = stringResource(R.string.ssh_keygen_existing_replace),
        onConfirm = {
          replace = false
          generate()
        },
        onDismiss = { replace = false },
        dismiss = stringResource(R.string.ssh_keygen_existing_keep),
        onDismissButton = onClose,
      )
    viewModel.generated?.let { key ->
      SetupDialog(
        title = stringResource(R.string.your_public_key),
        text = stringResource(R.string.ssh_keygen_message, key),
        confirm = stringResource(R.string.ssh_keygen_share),
        onConfirm = {
          activity.startActivity(shareKey(key))
          onClose()
        },
        onDismiss = onClose,
        dismiss = stringResource(R.string.ssh_keygen_later),
      )
    }
    viewModel.failure?.let { message ->
      SetupDialog(
        title = stringResource(R.string.error_generate_ssh_key),
        text = stringResource(R.string.ssh_key_error_dialog_text) + message,
        confirm = stringResource(R.string.dialog_ok),
        onConfirm = onClose,
        onDismiss = onClose,
      )
    }
  }
}
