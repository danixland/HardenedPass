/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.R
import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.ui.list.deleteWithoutFollowingLinks
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.settings.GitSettings
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The git server screen's state: URL and mode being edited, the clone's [progress] and outcome
 * ([cloned]), the host key and SSH key state. The clone itself runs in the destination (it needs
 * the activity); this ViewModel keeps what must survive a recreation.
 */
@HiltViewModel
class GitServerViewModel
@Inject
constructor(
  private val gitSettings: GitSettings,
  private val keyManager: PGPKeyManager,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var url by mutableStateOf(gitSettings.url.orEmpty())
    private set

  var authMode by mutableStateOf(fitAuthMode(gitSettings.url.orEmpty(), gitSettings.authMode))

  /** What a running clone does, as a string resource; null when idle. */
  var progress by mutableStateOf<Int?>(null)

  /** Null until a clone succeeded; then whether the store still needs a PGP key imported. */
  var cloned by mutableStateOf<Boolean?>(null)
    private set

  var hasHostKey by mutableStateOf(false)
    private set

  var sshKey by mutableIntStateOf(R.string.ssh_key_state_none)
    private set

  init {
    refresh()
  }

  /** Re-reads what other screens change: the saved host key and the SSH key. */
  fun refresh() {
    hasHostKey = gitSettings.hasSavedHostKey()
    sshKey = sshKeySummary()
  }

  fun changeUrl(value: String) {
    url = value
    authMode = fitAuthMode(value.trim(), authMode)
  }

  fun clearHostKey() {
    gitSettings.clearSavedHostKey()
    hasHostKey = false
  }

  /** Validates and stores URL and mode (unchanged rules in [GitSettings]). */
  fun save(): GitSettings.UpdateConnectionSettingsResult =
    gitSettings.updateConnectionSettingsIfValid(newAuthMode = authMode, newUrl = url.trim())

  /**
   * Empties the store folder at [root] for a clone, unless it is empty already. Never follows a
   * symlink: `deleteRecursively()` would, and a `store/link -> ../keys` link would take the PGP
   * keys with it. Returns null, or why it failed as a string resource: then the clone must not
   * start.
   */
  suspend fun clearStore(root: File): Int? {
    if (withContext(dispatcherProvider.io()) { cloneTarget(root) } == CloneTarget.Empty) return null
    progress = R.string.delete_directory_progress_text
    if (!withContext(dispatcherProvider.io()) { root.deleteWithoutFollowingLinks() })
      return R.string.git_clone_delete_failed
    progress = R.string.git_clone_progress
    return null
  }

  /** Set while [finishClone]'s key check runs: the clone's [progress] stays up until it is done. */
  private var checking = false

  /**
   * After a successful clone into [root], checks in [viewModelScope] whether its key is missing and
   * records the answer in [cloned]: no recipient of the root `.gpg-id` is an imported secret key
   * (without recipients: no secret key is imported at all). A public key alone cannot decrypt. In
   * [viewModelScope], so a recreation during the check cannot lose the answer.
   */
  fun finishClone(root: File) {
    checking = true
    viewModelScope.launch {
      try {
        cloned =
          withContext(dispatcherProvider.io()) {
            val ids = readGpgId(root)?.let(::storeRecipients).orEmpty()
            if (ids.isEmpty()) keyManager.getAllKeys().get().orEmpty().none(KeyUtils::isSecretKey)
            else
              ids.none { id -> keyManager.getKeyById(id).get()?.let(KeyUtils::isSecretKey) == true }
          }
      } finally {
        checking = false
        progress = null
      }
    }
  }

  /** The clone's coroutine ended (done, failed or cancelled): free the screen, re-read state. */
  fun cloneEnded() {
    if (!checking) progress = null
    refresh()
  }
}
