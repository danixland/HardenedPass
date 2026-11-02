/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import android.content.Context
import androidx.core.content.edit
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.BasePGPActivity.Companion.getLongName
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.isInsideRepository
import app.passwordstore.util.extensions.listFilesRecursively
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PasswordSortOrder
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.logcat

/** Shared preferences file of the "recently used" sort order, keyed by base64 of the path. */
private const val HISTORY = "recent_password_history"

/** Password files in [files], counting a folder by everything below it. */
fun countFiles(files: List<File>): Int =
  files.sumOf { if (it.isFile) 1 else it.listFilesRecursively().size }

/** Why [name] cannot become the folder [target], as a string resource, or null when it can. */
fun folderNameError(name: String, target: File): Int? =
  when {
    name.isBlank() -> R.string.message_category_error_empty_field
    target.isFile -> R.string.folder_creation_err_file_exists
    target.isDirectory -> R.string.folder_creation_err_folder_exists
    !target.isInsideRepository() -> R.string.message_error_destination_outside_repo
    else -> null
  }

/** Records that [file] was opened, for the "recently used" sort order. */
fun Context.recordUse(file: File) {
  if (sharedPrefs.getString(PreferenceKeys.SORT_ORDER) != PasswordSortOrder.RECENTLY_USED.name)
    return
  getSharedPreferences(HISTORY, Context.MODE_PRIVATE).edit {
    putString(file.absolutePath.base64(), System.currentTimeMillis().toString())
  }
}

/** Deletes [files] (entries or whole folders), drops their autofill matches and commits. */
suspend fun BaseGitActivity.deleteItems(files: List<File>) {
  val root = PasswordRepository.getRepositoryDirectory()
  val deleted = files.flatMap { if (it.isDirectory) it.listFilesRecursively() else listOf(it) }
  files.forEach { it.deleteRecursively() }
  AutofillMatcher.updateMatches(applicationContext, delete = deleted)
  commitChange(
    getString(
      R.string.git_commit_remove_text,
      files.joinToString(", ") { it.toRelativeString(root) },
    )
  )
}

/** Moves [files] into the folder [target], asking before overwriting, then commits. */
suspend fun BaseGitActivity.moveItems(files: List<File>, target: File) {
  val repo = PasswordRepository.getRepositoryDirectory().absolutePath
  for (source in files) {
    if (!source.exists()) {
      logcat(ERROR) { "Tried moving something that appears non-existent." }
      continue
    }
    val destination = File(target, source.name)
    val name = source.nameWithoutExtension
    if (
      destination.exists() &&
        !confirm(
          getString(R.string.password_exists_title),
          getString(
            R.string.password_exists_message,
            getLongName(target.absolutePath, repo, name),
            getLongName(source.parent.orEmpty(), repo, name),
          ),
        )
    )
      continue
    moveFile(source, destination)
  }
  val message =
    if (files.size == 1) {
      val name = files[0].nameWithoutExtension
      getString(
        R.string.git_commit_move_text,
        getLongName(files[0].parent.orEmpty(), repo, name),
        getLongName(target.absolutePath, repo, name),
      )
    } else {
      val relative = "${target.absolutePath}/".replace(repo, "").replace("/+".toRegex(), "/")
      getString(R.string.git_commit_move_multiple_text, relative)
    }
  commitChange(message)
}

/** Renames the folder [from] to [to], keeps its recently-used time, then commits. */
suspend fun BaseGitActivity.renameFolder(from: File, to: File) {
  moveFile(from, to)
  val history = getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
  val time = history.getString(from.absolutePath.base64())
  if (time != null)
    history.edit {
      remove(from.absolutePath.base64())
      putString(to.absolutePath.base64(), time)
    }
  commitChange(getString(R.string.git_commit_move_text, from.name, to.name))
}

private suspend fun BaseGitActivity.moveFile(source: File, destination: File) {
  val moved =
    withContext(dispatcherProvider.io()) {
      val map =
        if (source.isDirectory) {
          destination.mkdirs()
          source.listFilesRecursively().associateWith { destination.resolve(it.relativeTo(source)) }
        } else {
          mapOf(source to destination)
        }
      source.renameTo(destination).also { ok ->
        if (ok) AutofillMatcher.updateMatches(this@moveFile, map)
      }
    }
  if (!moved) {
    logcat(ERROR) { "Something went wrong while moving $source to $destination." }
    // ponytail: View dialog like BaseGitActivity's git prompts; both move to Compose together.
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.password_move_error_title)
      .setMessage(getString(R.string.password_move_error_message, source, destination))
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }
}

/** Shows [message] under [title] and suspends until answered; true when confirmed. */
// ponytail: View dialog, see moveFile.
private suspend fun Context.confirm(title: String, message: String): Boolean =
  suspendCancellableCoroutine { cont ->
    MaterialAlertDialogBuilder(this)
      .setTitle(title)
      .setMessage(message)
      .setPositiveButton(R.string.dialog_ok) { _, _ -> cont.resume(true) }
      .setNegativeButton(R.string.dialog_cancel) { _, _ -> cont.resume(false) }
      .setOnCancelListener { cont.resume(false) }
      .show()
  }
