/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.git.GitCommit
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The store's commits, read once off the main thread. */
@HiltViewModel
class GitLogViewModel @Inject constructor(dispatcherProvider: DispatcherProvider) : ViewModel() {

  /** Null while loading. */
  var commits by mutableStateOf<ImmutableList<GitCommit>?>(null)
    private set

  init {
    viewModelScope.launch {
      commits =
        withContext(dispatcherProvider.io()) {
          gitLog(PasswordRepository.repository).toImmutableList()
        }
    }
  }
}

/**
 * The commit log, newest first: message, short hash, date. A progress bar while [commits] is null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitLogScreen(
  commits: ImmutableList<GitCommit>?,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val formatter = remember {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
      .withLocale(Locale.getDefault())
      .withZone(ZoneId.systemDefault())
  }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_activity_git_log)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    if (commits == null) LinearProgressIndicator(Modifier.padding(padding).fillMaxWidth())
    else
      LazyColumn(Modifier.padding(padding).fillMaxSize()) {
        items(commits, key = GitCommit::hash) { commit ->
          ListItem(
            headlineContent = { Text(commit.shortMessage) },
            overlineContent = { Text(commit.hash.take(8), fontFamily = FontFamily.Monospace) },
            supportingContent = { Text(formatter.format(commit.time)) },
          )
          HorizontalDivider()
        }
      }
  }
}

@Composable
fun GitLogDestination(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: GitLogViewModel = hiltViewModel(),
) {
  GitLogScreen(commits = viewModel.commits, onBack = onClose, modifier = modifier)
}
