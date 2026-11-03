/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import app.passwordstore.util.extensions.hash
import app.passwordstore.util.extensions.time
import app.passwordstore.util.git.GitCommit
import java.io.IOException
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState

private const val TAG = "SettingsModel"

/**
 * The proxy port typed as [text]: -1 when empty (no port, as stored before any was set), null when
 * it is not a port number from 1 to 65535.
 */
fun proxyPort(text: String): Int? {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return -1
  return trimmed.toIntOrNull()?.takeIf { it in 1..65535 }
}

/** Where HEAD points, as the git config screen reports it. */
sealed interface HeadStatus {
  data class Branch(val name: String) : HeadStatus

  data class Detached(val hash: String) : HeadStatus

  data object Missing : HeadStatus
}

/** [repo]'s HEAD: a branch (also one without commits yet), a detached commit, or missing. */
fun headStatus(repo: Repository): HeadStatus =
  try {
    val head = repo.findRef(Constants.HEAD)
    when {
      head == null -> HeadStatus.Missing
      head.isSymbolic -> HeadStatus.Branch(Repository.shortenRefName(head.target.name))
      else ->
        head.objectId?.let { HeadStatus.Detached(it.abbreviate(8).name()) } ?: HeadStatus.Missing
    }
  } catch (e: IOException) {
    logcat(TAG, ERROR) { e.asLog() }
    HeadStatus.Missing
  }

/** Whether a rebase or a merge is in progress, so "Abort rebase" has something to abort. */
fun needsAbort(repo: Repository): Boolean =
  repo.repositoryState.let { it.isRebasing || it == RepositoryState.MERGING }

/**
 * The commits reachable from HEAD, newest first; empty without a repository or a commit. Blocking:
 * call it off the main thread.
 */
// ponytail: the whole log at once, as the old screen did (tested there with 1000 commits); page it
// if a store's history ever makes this slow.
fun gitLog(repo: Repository?): List<GitCommit> {
  if (repo == null) return emptyList()
  return try {
    Git(repo).log().call().map { GitCommit(it.hash, it.shortMessage, it.authorIdent.name, it.time) }
  } catch (e: GitAPIException) {
    logcat(TAG, ERROR) { e.asLog() }
    emptyList()
  } catch (e: JGitInternalException) {
    logcat(TAG, ERROR) { e.asLog() }
    emptyList()
  }
}
