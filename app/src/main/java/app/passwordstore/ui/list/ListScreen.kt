/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.passwordstore.R
import app.passwordstore.ui.compose.BreadcrumbBar
import app.passwordstore.ui.compose.EntryRow
import app.passwordstore.ui.compose.FolderRow
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.crumbsFor
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

/**
 * The password list: search bar, breadcrumbs, folders above entries, pull to sync, long-press
 * selection and the "New entry" button. Row callbacks take absolute paths; [onCrumb] takes a path
 * relative to the store root, like [path].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
  path: String,
  query: String,
  rows: ListRows,
  selected: ImmutableSet<String>,
  refreshing: Boolean,
  canSync: Boolean,
  gitOps: ImmutableList<GitOp>,
  onQueryChange: (String) -> Unit,
  onCrumb: (relativePath: String) -> Unit,
  onOpenFolder: (path: String) -> Unit,
  onOpenEntry: (path: String) -> Unit,
  onToggle: (path: String) -> Unit,
  onClearSelection: () -> Unit,
  onSync: () -> Unit,
  onGitOp: (GitOp) -> Unit,
  onSettings: () -> Unit,
  onNew: () -> Unit,
  onMove: () -> Unit,
  onDelete: () -> Unit,
  onRename: () -> Unit,
  onPin: () -> Unit,
  modifier: Modifier = Modifier,
  focusSearch: Boolean = false,
) {
  val selecting = selected.isNotEmpty()
  val listState = rememberLazyListState()
  val pullState = rememberPullToRefreshState()
  val searchFocus = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  // Saveable, so it survives this destination leaving composition while a folder is open.
  var launched by rememberSaveable { mutableStateOf(false) }
  var returned by remember { mutableStateOf(false) }
  LifecycleResumeEffect(Unit) {
    returned = true
    onPauseOrDispose {}
  }
  // Out of touch mode (keys, D-pad) the view system hands its default focus to the first
  // focusable, the search field, which keeps it in touch mode too and brings the keyboard back
  // when the list returns from a screen that had it up. So every return drops focus, except the
  // first, where search on start asks for it. Done once the window has focus: before that the
  // view still holds the touch mode it last saw, and out of it clearing focus refocuses the field.
  val windowFocused = LocalWindowInfo.current.isWindowFocused
  LaunchedEffect(returned, windowFocused) {
    if (returned && windowFocused) {
      returned = false
      if (focusSearch && !launched) searchFocus.requestFocus() else focusManager.clearFocus()
      launched = true
    }
  }
  // Filtered results always start at the top, where the best match is.
  LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }
  Scaffold(
    modifier = modifier,
    topBar = {
      if (selecting) {
        SelectionBar(rows, selected, onClearSelection, onMove, onDelete, onRename, onPin)
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          SearchField(query, gitOps, onQueryChange, onGitOp, onSettings, searchFocus)
          BreadcrumbBar(
            crumbsFor(path),
            stringResource(R.string.list_store_root),
            onCrumb,
            Modifier.fillMaxWidth().padding(vertical = SpacingMedium),
          )
        }
      }
    },
    floatingActionButton = {
      if (!selecting)
      // M3 clears the label's semantics; the FAB takes its description from the icon.
      ExtendedFloatingActionButton(
          text = { Text(stringResource(R.string.list_new_entry)) },
          icon = { Icon(Icons.Filled.Add, stringResource(R.string.list_new_entry)) },
          onClick = onNew,
        )
    },
  ) { padding ->
    Box(
      Modifier.padding(padding)
        .fillMaxSize()
        .pullToRefresh(refreshing, pullState, enabled = canSync && !selecting, onRefresh = onSync)
    ) {
      // ponytail: each group is one lazy item, fine for hundreds of rows; split rows into their
      // own items (with per-row corner shapes) if a store ever has thousands in one folder.
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
      ) {
        if (rows.folders.isNotEmpty())
          item(key = "folders") {
            GroupedSurface(Modifier.fillMaxWidth()) {
              rows.folders.forEach { row ->
                key(row.path) {
                  FolderRow(
                    name = row.label,
                    count = row.count,
                    onClick = { if (selecting) onToggle(row.path) else onOpenFolder(row.path) },
                    selected = row.path in selected,
                    onLongClick = { onToggle(row.path) },
                  )
                }
              }
            }
          }
        if (rows.entries.isNotEmpty())
          item(key = "entries") {
            GroupedSurface(Modifier.fillMaxWidth()) {
              rows.entries.forEach { row ->
                key(row.path) {
                  EntryRow(
                    name = row.label,
                    subtitle = row.subtitle,
                    onClick = { if (selecting) onToggle(row.path) else onOpenEntry(row.path) },
                    selected = row.path in selected,
                    onLongClick = { onToggle(row.path) },
                  )
                }
              }
            }
          }
      }
      PullToRefreshDefaults.Indicator(pullState, refreshing, Modifier.align(Alignment.TopCenter))
    }
  }
}

/** Bottom sheet behind the "New entry" button: a password entry or a folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSheet(
  onDismiss: () -> Unit,
  onNewEntry: () -> Unit,
  onNewFolder: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
    ListItem(
      headlineContent = { Text(stringResource(R.string.bottom_sheet_create_new_password)) },
      modifier = Modifier.clickable(onClick = onNewEntry),
      leadingContent = {
        Icon(painterResource(R.drawable.ic_action_new_password), contentDescription = null)
      },
    )
    ListItem(
      headlineContent = { Text(stringResource(R.string.bottom_sheet_create_new_folder)) },
      modifier = Modifier.clickable(onClick = onNewFolder),
      leadingContent = {
        Icon(painterResource(R.drawable.ic_action_new_folder), contentDescription = null)
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
  query: String,
  gitOps: ImmutableList<GitOp>,
  onQueryChange: (String) -> Unit,
  onGitOp: (GitOp) -> Unit,
  onSettings: () -> Unit,
  focus: FocusRequester,
) {
  val focusManager = LocalFocusManager.current
  var menu by remember { mutableStateOf(false) }
  SearchBar(
    inputField = {
      SearchBarDefaults.InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { focusManager.clearFocus() },
        expanded = false,
        onExpandedChange = {},
        modifier = Modifier.focusRequester(focus),
        placeholder = { Text(stringResource(R.string.action_search)) },
        leadingIcon = {
          Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null)
        },
        trailingIcon = {
          Row {
            if (query.isNotEmpty())
              IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, stringResource(R.string.list_clear_search))
              }
            Box {
              IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.list_more_options))
              }
              DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                gitOps.forEach { op ->
                  DropdownMenuItem(
                    text = { Text(stringResource(op.label)) },
                    onClick = {
                      menu = false
                      onGitOp(op)
                    },
                  )
                }
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.action_settings)) },
                  onClick = {
                    menu = false
                    onSettings()
                  },
                )
              }
            }
          }
        },
      )
    },
    expanded = false,
    onExpandedChange = {},
  ) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
  rows: ListRows,
  selected: ImmutableSet<String>,
  onClear: () -> Unit,
  onMove: () -> Unit,
  onDelete: () -> Unit,
  onRename: () -> Unit,
  onPin: () -> Unit,
) {
  val single = selected.singleOrNull()
  TopAppBar(
    title = { Text(pluralStringResource(R.plurals.delete_title, selected.size, selected.size)) },
    navigationIcon = {
      IconButton(onClick = onClear) {
        Icon(Icons.Filled.Close, stringResource(R.string.dialog_cancel))
      }
    },
    actions = {
      ActionIcon(R.drawable.ic_move_folder_24dp, R.string.move, onMove)
      ActionIcon(R.drawable.ic_delete_24dp, R.string.delete, onDelete)
      // ponytail: rename one folder at a time; the old list queued one dialog per folder.
      if (rows.folders.any { it.path == single })
        ActionIcon(R.drawable.ic_edit_24dp, R.string.title_rename_folder, onRename)
      if (rows.entries.any { it.path == single })
        ActionIcon(R.drawable.ic_push_pin_24dp, R.string.place_shortcut_on_home_screen, onPin)
    },
  )
}

@Composable
private fun ActionIcon(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(painterResource(icon), contentDescription = stringResource(label))
  }
}

private val GitOp.label: Int
  get() =
    when (this) {
      GitOp.SYNC -> R.string.git_sync
      GitOp.PUSH -> R.string.git_push
      else -> R.string.git_pull
    }
