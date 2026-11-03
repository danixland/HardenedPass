/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import app.passwordstore.ui.compose.crumbsFor
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * One list row. [path] is the absolute path and the row's key; [count] is a folder's number of
 * children, 0 for entries.
 */
data class ListRow(val path: String, val label: String, val subtitle: String?, val count: Int)

/** The rows of one folder, or of a search inside it: folders above entries. */
data class ListRows(
  val folders: ImmutableList<ListRow> = persistentListOf(),
  val entries: ImmutableList<ListRow> = persistentListOf(),
)

/**
 * Splits [files] (already sorted or ranked) into folder and entry rows for [dir], keeping their
 * order. Labels and subtitles are relative to [dir]: browsing shows plain names, search results
 * show where they are. Entries get no username or OTP subtitle: both live inside the encrypted
 * file, and the list never decrypts.
 */
fun listRows(files: List<File>, dir: File): ListRows {
  val (folders, entries) = files.partition { it.isDirectory }
  return ListRows(
    folders
      .map { ListRow(it.absolutePath, it.relativeTo(dir).path, null, childCount(it)) }
      .toImmutableList(),
    entries
      .map {
        val parent = it.parentFile?.relativeTo(dir)?.path.orEmpty()
        ListRow(it.absolutePath, it.name.removeSuffix(".gpg"), parent.ifEmpty { null }, 0)
      }
      .toImmutableList(),
  )
}

/** Children counted for a folder: subfolders and .gpg files, as the old list counted them. */
private fun childCount(folder: File): Int =
  folder.listFiles { f -> f.isDirectory || f.extension == "gpg" }?.size ?: 0

/**
 * Folders directly inside [dir] that the folder picker offers, by name: never `.git`, other hidden
 * folders only with "Show hidden contents" ([showHidden]), as the list filters them.
 */
fun subfolders(dir: File, showHidden: Boolean): List<File> =
  dir
    .listFiles { f -> f.isDirectory && !f.name.startsWith(".git") && (showHidden || !f.isHidden) }
    .orEmpty()
    .sortedBy { it.name.lowercase() }

/**
 * How to get from the list at [from] to the list at [to] (both relative to the store root) while
 * the back stack holds one entry per folder level: how many entries to pop, then which paths to
 * push. Breadcrumb jumps only pop; opening a folder from search results may pop and push.
 */
fun folderMoves(from: String, to: String): Pair<Int, List<String>> {
  val current = crumbsFor(from)
  val target = crumbsFor(to)
  val shared = current.zip(target).takeWhile { (a, b) -> a == b }.size
  return (current.size - shared) to target.drop(shared).map { it.path }
}
