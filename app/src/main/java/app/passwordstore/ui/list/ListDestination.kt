/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.folderselect.SelectFolderActivity
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.ui.git.config.GitServerConfigActivity
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.PreferenceKeys
import app.passwordstore.util.viewmodel.SearchableRepositoryViewModel
import java.io.File
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.launch

private enum class ListDialog {
  None,
  Create,
  NewFolder,
  Rename,
  Delete,
  Clone,
  NotInitialized,
}

/**
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * legacy activities and the store operations. [onOpenFolder] takes a path relative to the root.
 */
@Composable
fun ListDestination(
  activity: MainActivity,
  path: String,
  onOpenFolder: (relativePath: String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SearchableRepositoryViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  val dir = File(root, path)
  val prefs = activity.sharedPrefs
  var query by rememberSaveable { mutableStateOf("") }
  var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
  var dialog by rememberSaveable { mutableStateOf(ListDialog.None) }
  var deleteCount by rememberSaveable { mutableIntStateOf(0) }
  var refreshing by remember { mutableStateOf(false) }
  var resumes by remember { mutableIntStateOf(0) }

  // Declared before the collection below on purpose: effects start in order, so the ViewModel
  // already points at this folder when the first rows are built (no flash of the store root).
  LaunchedEffect(path, query) {
    viewModel.navigateTo(dir, pushPreviousLocation = false)
    if (query.isNotBlank()) viewModel.search(query.trim())
  }
  val rows by viewModel.rows.collectAsStateWithLifecycle(ListRows())
  val gitOps = remember(resumes) { activity.gitOps() }
  val canSync = remember(resumes) { !prefs.getBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, false) }

  fun refresh() {
    if (path.isNotEmpty() && !dir.isDirectory) onOpenFolder("") else viewModel.forceRefresh()
  }

  // Every return to this folder (back from a child, or from a legacy activity) re-reads it.
  LifecycleResumeEffect(Unit) {
    if (activity.checkStore()) refresh()
    resumes++
    onPauseOrDispose {}
  }
  BackHandler(enabled = selected.isNotEmpty()) { selected = emptyList() }
  BackHandler(enabled = selected.isEmpty() && query.isNotEmpty()) { query = "" }

  val moveLauncher =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      val data = result.data
      val target = data?.getStringExtra("SELECTED_FOLDER_PATH")?.let(::File)
      val files = data?.getStringArrayExtra("Files")?.map(::File)
      if (result.resultCode == Activity.RESULT_OK && target?.isDirectory == true && files != null) {
        selected = emptyList()
        activity.lifecycleScope.launch {
          activity.moveItems(files, target)
          refresh()
        }
      }
    }

  fun ifInitialized(action: () -> Unit) {
    if (PasswordRepository.isInitialized) action() else dialog = ListDialog.NotInitialized
  }

  fun runGit(op: GitOp) {
    refreshing = true
    activity.lifecycleScope.launch {
      activity.runGit(op)
      refreshing = false
      refresh()
    }
  }

  Box(modifier) {
    ListScreen(
      path = path,
      query = query,
      rows = rows,
      selected = selected.toImmutableSet(),
      refreshing = refreshing,
      canSync = canSync,
      gitOps = gitOps,
      onQueryChange = { query = it },
      onCrumb = onOpenFolder,
      onOpenFolder = { folder ->
        activity.recordUse(File(folder))
        query = ""
        onOpenFolder(File(folder).relativeTo(root).path)
      },
      onOpenEntry = { entry ->
        activity.recordUse(File(entry))
        activity.openEntry(File(entry))
      },
      onToggle = { p -> selected = if (p in selected) selected - p else selected + p },
      onClearSelection = { selected = emptyList() },
      onSync = {
        when {
          root.resolve(".git").listFiles().isNullOrEmpty() -> viewModel.forceRefresh()
          !PasswordRepository.isGitRepo() -> dialog = ListDialog.Clone
          activity.gitSettings.authMode == AuthMode.None -> runGit(GitOp.PULL)
          else -> runGit(GitOp.SYNC)
        }
      },
      onGitOp = { op -> ifInitialized { runGit(op) } },
      onSettings = activity::openSettings,
      onNew = { dialog = ListDialog.Create },
      onMove = {
        moveLauncher.launch(
          Intent(activity, SelectFolderActivity::class.java)
            .putExtra("Files", selected.toTypedArray())
        )
      },
      onDelete = {
        val files = selected.map(::File)
        val count = countFiles(files)
        if (count == 0) {
          files.forEach { it.deleteWithoutFollowingLinks() }
          selected = emptyList()
          refresh()
        } else {
          deleteCount = count
          dialog = ListDialog.Delete
        }
      },
      onRename = { dialog = ListDialog.Rename },
      onPin = {
        activity.pinEntry(File(selected.single()))
        selected = emptyList()
      },
      focusSearch = path.isEmpty() && prefs.getBoolean(PreferenceKeys.SEARCH_ON_START, false),
    )

    when (dialog) {
      ListDialog.None -> Unit
      ListDialog.Create ->
        CreateSheet(
          onDismiss = { dialog = ListDialog.None },
          onNewEntry = {
            dialog = ListDialog.None
            ifInitialized { activity.createEntry(dir) }
          },
          onNewFolder = {
            dialog = ListDialog.None
            ifInitialized { dialog = ListDialog.NewFolder }
          },
        )
      ListDialog.NewFolder ->
        FolderNameDialog(
          title = stringResource(R.string.title_create_folder),
          message = null,
          confirm = stringResource(R.string.button_create),
          validate = { folderNameError(it, File(dir, it)) },
          onConfirm = { name ->
            dialog = ListDialog.None
            val folder = File(dir, name).canonicalFile
            folder.mkdirs()
            onOpenFolder(folder.relativeTo(root.canonicalFile).path)
          },
          onDismiss = { dialog = ListDialog.None },
        )
      ListDialog.Rename -> {
        val folder = File(selected.single())
        FolderNameDialog(
          title = stringResource(R.string.title_rename_folder),
          message = stringResource(R.string.message_rename_folder, folder.name),
          confirm = stringResource(R.string.dialog_ok),
          validate = { folderNameError(it, File(folder.parentFile, it)) },
          onConfirm = { name ->
            dialog = ListDialog.None
            selected = emptyList()
            activity.lifecycleScope.launch {
              activity.renameFolder(folder, File(folder.parentFile, name))
              refresh()
            }
          },
          onDismiss = { dialog = ListDialog.None },
        )
      }
      ListDialog.Delete ->
        Prompt(
          text = pluralStringResource(R.plurals.delete_dialog_text, deleteCount, deleteCount),
          confirm = stringResource(R.string.dialog_yes),
          onConfirm = {
            val files = selected.map(::File)
            dialog = ListDialog.None
            selected = emptyList()
            activity.lifecycleScope.launch {
              activity.deleteItems(files)
              refresh()
            }
          },
          onDismiss = { dialog = ListDialog.None },
          dismiss = stringResource(R.string.dialog_no),
        )
      ListDialog.Clone ->
        Prompt(
          text = stringResource(R.string.clone_git_repo),
          confirm = stringResource(R.string.clone_button),
          onConfirm = {
            dialog = ListDialog.None
            activity.startActivity(GitServerConfigActivity.createCloneIntent(activity))
          },
          onDismiss = { dialog = ListDialog.None },
          dismiss = stringResource(R.string.dialog_cancel),
        )
      ListDialog.NotInitialized ->
        Prompt(
          text = stringResource(R.string.creation_dialog_text),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = { dialog = ListDialog.None },
          onDismiss = { dialog = ListDialog.None },
        )
    }
  }
}

/** Asks for a folder name; [validate] returns an error string resource or null. */
@Composable
private fun FolderNameDialog(
  title: String,
  message: String?,
  confirm: String,
  validate: (String) -> Int?,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by rememberSaveable { mutableStateOf("") }
  var error by remember { mutableStateOf<Int?>(null) }
  val focus = remember { FocusRequester() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      val err = error
      Column {
        if (message != null) Text(message, Modifier.padding(bottom = SpacingMedium))
        OutlinedTextField(
          value = name,
          onValueChange = {
            name = it
            error = null
          },
          modifier = Modifier.focusRequester(focus),
          label = { Text(stringResource(R.string.crypto_name_hint)) },
          supportingText =
            if (err != null) {
              { Text(stringResource(err)) }
            } else null,
          isError = err != null,
          singleLine = true,
        )
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val e = validate(name)
          error = e
          if (e == null) onConfirm(name)
        }
      ) {
        Text(confirm)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}

/** A one-message dialog; [dismiss] adds a second button that only closes it. */
@Composable
private fun Prompt(
  text: String,
  confirm: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  dismiss: String? = null,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton =
      if (dismiss != null) {
        { TextButton(onClick = onDismiss) { Text(dismiss) } }
      } else null,
  )
}
