/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.Keep
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.snackbar
import com.github.michaelbull.result.onFailure
import com.google.android.material.snackbar.Snackbar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What the PGP keys screen is for. A route argument: kept from minification. */
@Keep
@Serializable
enum class KeysMode {
  /** Import and delete keys (Settings). */
  Manage,
  /** Pick the key a new local store is encrypted for. */
  Choose,
  /** A key is needed (after a clone, or by an entry): import it, or go on without. */
  Need,
}

/**
 * The imported [keys] with the import FAB. [mode] decides what a row does (select in
 * [KeysMode.Choose], delete in [KeysMode.Manage]) and the guidance shown; [recipients] are the
 * store's `.gpg-id` entries, listed in [KeysMode.Need].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgpKeysScreen(
  mode: KeysMode,
  keys: ImmutableList<KeyRow>,
  recipients: ImmutableList<String>,
  busy: Boolean,
  onImport: () -> Unit,
  onSelect: (KeyRow) -> Unit,
  onDelete: (KeyRow) -> Unit,
  onLater: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var deleting by remember { mutableStateOf<KeyRow?>(null) }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(
              when (mode) {
                KeysMode.Manage -> R.string.activity_label_pgp_key_manager
                KeysMode.Choose -> R.string.gpg_key_select
                KeysMode.Need -> R.string.pref_import_pgp_key_title
              }
            )
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
        actions = {
          if (mode == KeysMode.Need)
            TextButton(onClick = onLater) { Text(stringResource(R.string.ssh_keygen_later)) }
        },
      )
    },
    floatingActionButton = {
      // M3 clears the label's semantics; the FAB takes its description from the icon.
      ExtendedFloatingActionButton(
        text = { Text(stringResource(R.string.pref_import_pgp_key_title)) },
        icon = { Icon(Icons.Filled.Add, stringResource(R.string.pref_import_pgp_key_title)) },
        onClick = { if (!busy) onImport() },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      val message =
        when (mode) {
          KeysMode.Manage -> null
          KeysMode.Choose -> R.string.select_gpg_key_message
          KeysMode.Need -> R.string.pgp_key_needed_message
        }
      if (message != null)
        Text(
          stringResource(message),
          Modifier.padding(SpacingLarge),
          style = MaterialTheme.typography.bodyLarge,
        )
      if (mode == KeysMode.Need && recipients.isNotEmpty())
        GroupedSurface {
          Column(Modifier.padding(SpacingLarge)) {
            Text(
              stringResource(R.string.pgp_key_needed_recipients),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recipients.forEach { Text(it, fontFamily = FontFamily.Monospace) }
          }
        }
      if (keys.isEmpty())
        Text(
          stringResource(R.string.pgp_key_manager_no_keys_guidance),
          Modifier.padding(SpacingLarge),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      else
        GroupedSurface {
          keys.forEach { row ->
            KeyItem(row = row, mode = mode, onSelect = onSelect, onDelete = { deleting = row })
          }
        }
      // Room for the FAB below the last row.
      Spacer(Modifier.height(88.dp))
    }
  }
  deleting?.let { row ->
    SetupDialog(
      title = stringResource(R.string.pgp_key_manager_delete_confirmation_dialog_title),
      text = row.userId ?: row.keyId.toString(),
      confirm = stringResource(R.string.dialog_yes),
      onConfirm = {
        deleting = null
        onDelete(row)
      },
      onDismiss = { deleting = null },
      dismiss = stringResource(R.string.dialog_no),
    )
  }
}

@Composable
private fun KeyItem(
  row: KeyRow,
  mode: KeysMode,
  onSelect: (KeyRow) -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier
      .fillMaxWidth()
      .then(if (mode == KeysMode.Choose) Modifier.clickable { onSelect(row) } else Modifier)
      .padding(horizontal = SpacingLarge, vertical = SpacingMedium),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        row.userId ?: row.keyId.toString(),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (row.userId != null)
        Text(
          row.keyId.toString(),
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (mode == KeysMode.Manage)
      IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
  }
}

/**
 * The PGP keys screen: wires [PgpKeysScreen] to the [viewModel], the document picker and the import
 * dialogs. [onImported] runs after every successful import; [onDone] ends the flow: after an
 * import, "Later" or back in [KeysMode.Need], after the new store is created in [KeysMode.Choose].
 * [onClose] leaves the other modes.
 */
@Composable
fun PgpKeysDestination(
  activity: MainActivity,
  mode: KeysMode,
  onClose: () -> Unit,
  onDone: () -> Unit,
  onImported: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PgpKeysViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  // Read off the main thread, symlink- and size-checked (readGpgId).
  LaunchedEffect(mode) { if (mode == KeysMode.Need) viewModel.loadRecipients(root) }
  // A created store moves on from here, also when the screen was recreated in between.
  val done = viewModel.done
  LaunchedEffect(done) { if (done) onDone() }

  fun leave() {
    if (viewModel.busy) return
    if (mode == KeysMode.Need) onDone() else onClose()
  }
  BackHandler(onBack = ::leave)

  val picker =
    rememberLauncherForActivityResult(OpenDocument()) { uri ->
      if (uri != null) viewModel.import(uri, activity)
    }

  // ponytail: a theme or locale change in the middle cancels the commit; the next commit picks up
  // the new `.gpg-id`, and choosing the key again re-creates nothing that would conflict.
  fun choose(row: KeyRow) {
    if (viewModel.busy) return
    viewModel.busy = true
    activity.lifecycleScope.launch {
      try {
        val error = viewModel.createStore(root, row)
        if (error != null) {
          activity.snackbar(
            message = activity.getString(R.string.store_create_failed, error),
            length = Snackbar.LENGTH_LONG,
          )
        } else {
          // A failed commit is reported like any git error; the store exists either way, and
          // the next commit picks up the `.gpg-id`.
          activity
            .commitChange(
              activity.getString(R.string.git_commit_gpg_id, activity.getString(R.string.app_name))
            )
            .onFailure { activity.promptOnErrorHandler(it) }
          viewModel.markDone()
        }
      } finally {
        viewModel.busy = false
      }
    }
  }

  Box(modifier) {
    PgpKeysScreen(
      mode = mode,
      keys = viewModel.keys,
      recipients = viewModel.recipients,
      busy = viewModel.busy,
      onImport = { picker.launch(arrayOf("*/*")) },
      onSelect = ::choose,
      onDelete = viewModel::deleteKey,
      onLater = onDone,
      onBack = ::leave,
    )
    when (val result = viewModel.importResult) {
      null -> Unit
      is KeyImport.Imported -> {
        fun accept() {
          viewModel.dismissImport()
          onImported()
          if (mode == KeysMode.Need) onDone()
        }
        SetupDialog(
          title = stringResource(R.string.pgp_key_import_succeeded),
          text = stringResource(R.string.pgp_key_import_succeeded_message, result.keyId),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = ::accept,
          onDismiss = ::accept,
        )
      }
      KeyImport.Exists ->
        SetupDialog(
          title = stringResource(R.string.pgp_key_import_failed),
          text = stringResource(R.string.pgp_key_import_failed_replace_message),
          confirm = stringResource(R.string.dialog_yes),
          onConfirm = viewModel::replace,
          onDismiss = viewModel::dismissImport,
          dismiss = stringResource(R.string.dialog_no),
        )
      is KeyImport.Failed ->
        SetupDialog(
          title = stringResource(R.string.pgp_key_import_failed),
          text = result.message.orEmpty(),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = viewModel::dismissImport,
          onDismiss = viewModel::dismissImport,
        )
    }
  }
}
