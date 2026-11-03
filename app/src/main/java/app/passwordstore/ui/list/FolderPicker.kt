/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.BreadcrumbBar
import app.passwordstore.ui.compose.FolderRow
import app.passwordstore.ui.compose.crumbsFor
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks a folder of the store at [root], starting at [start] (relative to the root, "" is the
 * root): breadcrumbs jump up, rows open subfolders, "New folder" creates one inside the folder
 * shown (the list's validation) and opens it, Select reports the folder shown, relative to the
 * root. Used by the list's move action and by the editor. Shows no secrets; its window inherits
 * FLAG_SECURE from the screen below.
 */
@Composable
fun FolderPicker(
  root: File,
  start: String,
  onPick: (relativePath: String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var path by rememberSaveable { mutableStateOf(start) }
  var naming by rememberSaveable { mutableStateOf(false) }
  val showHidden = remember {
    context.sharedPrefs.getBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, false)
  }
  val rows by
    produceState<ImmutableList<ListRow>>(persistentListOf(), path, naming) {
      val dir = File(root, path)
      // ponytail: a composable has no injected DispatcherProvider; plain IO for a directory read.
      @Suppress("RawDispatchersUse")
      value = withContext(Dispatchers.IO) { listRows(subfolders(dir, showHidden), dir).folders }
    }
  if (naming) {
    // The list's own dialog and checks: blank, already a file or folder, outside the store
    // (component-wise isInsideRepository).
    val dir = File(root, path)
    FolderNameDialog(
      title = stringResource(R.string.title_create_folder),
      message = null,
      confirm = stringResource(R.string.button_create),
      validate = { folderNameError(it, File(dir, it)) },
      onConfirm = { name ->
        val folder = File(dir, name).canonicalFile
        folder.mkdirs()
        naming = false
        path = folder.relativeTo(root.canonicalFile).path
      },
      onDismiss = { naming = false },
    )
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(R.string.folder_picker_title)) },
    text = {
      Column {
        BreadcrumbBar(
          crumbsFor(path),
          stringResource(R.string.list_store_root),
          onCrumbClick = { path = it },
          modifier = Modifier.fillMaxWidth().padding(bottom = SpacingMedium),
        )
        // ponytail: a plain scrolling column, fine for the folders of one level; a LazyColumn
        // if a store ever has thousands of subfolders in one folder.
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
          rows.forEach { row ->
            key(row.path) {
              FolderRow(
                name = row.label,
                count = row.count,
                onClick = { path = File(row.path).relativeTo(root).path },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onPick(path) }) { Text(stringResource(R.string.crypto_select)) }
    },
    dismissButton = {
      Row {
        TextButton(onClick = { naming = true }) { Text(stringResource(R.string.folder_picker_new)) }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
      }
    },
  )
}
