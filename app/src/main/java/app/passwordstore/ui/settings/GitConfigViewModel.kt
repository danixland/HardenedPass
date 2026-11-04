/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.settings.GitSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The git config screen's state: author name and email being edited, HEAD, and the git operation
 * that runs or succeeded. The operation itself runs in the destination (it needs the activity);
 * this ViewModel keeps what must survive a recreation.
 */
@HiltViewModel
class GitConfigViewModel
@Inject
constructor(
  private val gitSettings: GitSettings,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var name by mutableStateOf(gitSettings.authorName)
  var email by mutableStateOf(gitSettings.authorEmail)

  var head by mutableStateOf<HeadStatus?>(null)
    private set

  var canAbort by mutableStateOf(false)
    private set

  /** A git operation runs; everything waits. */
  var busy by mutableStateOf(false)

  /**
   * The operation that succeeded and is not reported yet: a reset or gc gets a snackbar (then this
   * goes back to null), an abort its dialog.
   */
  var done by mutableStateOf<GitOp?>(null)

  /** The branch an abort pushed from, for its report. */
  var abortedBranch by mutableStateOf<String?>(null)

  init {
    refresh()
  }

  /** Re-reads HEAD and the rebase state, off the main thread. */
  fun refresh() {
    viewModelScope.launch {
      val repo = PasswordRepository.repository
      val (status, abort) =
        withContext(dispatcherProvider.io()) {
          if (repo == null) HeadStatus.Missing to false else headStatus(repo) to needsAbort(repo)
        }
      head = status
      canAbort = abort
    }
  }

  /** Saves name and email; false, saving nothing, when the email is not an address. */
  fun save(): Boolean {
    val address = email.trim()
    if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) return false
    gitSettings.authorEmail = address
    gitSettings.authorName = name.trim()
    return true
  }
}
