/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import android.content.ClipData
import android.content.Intent
import android.os.PersistableBundle
import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.extensions.clipboard
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.services.ClipboardService
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File

/** Gets the relative path to the repository */
private fun getRelativePath(fullPath: String, repositoryPath: String): String =
  fullPath.replace(repositoryPath, "").replace("/+".toRegex(), "/")

/** Gets the Parent path, relative to the repository */
fun getParentPath(fullPath: String, repositoryPath: String): String {
  val relativePath = getRelativePath(fullPath, repositoryPath)
  val index = relativePath.lastIndexOf("/")
  return "/${relativePath.substring(startIndex = 0, endIndex = index + 1)}/"
    .replace("/+".toRegex(), "/")
}

/** /path/to/store/social/facebook.gpg -> social/facebook */
fun getLongName(fullPath: String, repositoryPath: String, basename: String): String {
  var relativePath = getRelativePath(fullPath, repositoryPath)
  return if (relativePath.isNotEmpty() && relativePath != "/") {
    // remove preceding '/'
    relativePath = relativePath.substring(1)
    if (relativePath.endsWith('/')) {
      relativePath + basename
    } else {
      "$relativePath/$basename"
    }
  } else {
    basename
  }
}

/** Copies provided [text] to the clipboard; [sensitive] text is hidden from its preview. */
fun FragmentActivity.copyTextToClipboard(text: String?, sensitive: Boolean = true) {
  val clipboard = clipboard ?: return
  val clip = ClipData.newPlainText("pgp_handler_result_pm", text)
  if (sensitive) {
    clip.description.extras =
      PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
  }
  clipboard.setPrimaryClip(clip)
}

/**
 * Copies a provided [password] string to the clipboard. This wraps [copyTextToClipboard] and starts
 * off an instance of [ClipboardService] to provide a way of clearing the clipboard.
 */
fun FragmentActivity.copyPasswordToClipboard(password: String?) {
  copyTextToClipboard(password)
  val clearAfter =
    sharedPrefs.getString(PreferenceKeys.GENERAL_SHOW_TIME)?.toIntOrNull()
      ?: Constants.DEFAULT_DECRYPTION_TIMEOUT

  if (clearAfter != 0) {
    val service =
      Intent(this, ClipboardService::class.java).apply {
        action = ClipboardService.ACTION_START
        putExtra(ClipboardService.EXTRA_NOTIFICATION_TIME, clearAfter)
      }
    startForegroundService(service)
  }
}

/**
 * Get a list of available [PGPIdentifier]s for the current password repository. This method throws
 * when no identifiers were able to be parsed. If this method returns null, an invalid identifier
 * was encountered and its message passed to [onInvalid]: further execution must stop to let the
 * user correct the problem.
 */
fun getPGPIdentifiers(subDir: String, onInvalid: (message: Int) -> Unit): List<PGPIdentifier>? {
  val repoRoot = PasswordRepository.getRepositoryDirectory()
  val gpgIdentifierFile =
    File(repoRoot, subDir).findTillRoot(".gpg-id", repoRoot)
      ?: File(repoRoot, ".gpg-id").apply { createNewFile() }
  val gpgIdentifiers =
    gpgIdentifierFile
      .readLines()
      .filter { it.isNotBlank() }
      .map { line ->
        PGPIdentifier.fromString(line)
          ?: run {
            // The line being empty means this is most likely an empty `.gpg-id`
            // file we created. Skip the validation so we can make the user add a
            // real ID.
            if (line.isEmpty()) return@run
            // Apparently `gpg-id` being the first line is also acceptable?
            if (line == "gpg-id") return@run
            val message =
              if (line.removePrefix("0x").matches("[a-fA-F0-9]{8}".toRegex())) R.string.short_gpg_id
              else R.string.invalid_gpg_id
            onInvalid(message)
            return null
          }
      }
      .filterIsInstance<PGPIdentifier>()
  if (gpgIdentifiers.isEmpty()) {
    error("Failed to parse identifiers from .gpg-id: ${gpgIdentifierFile.readText()}")
  }
  return gpgIdentifiers
}

@Suppress("ReturnCount")
private fun File.findTillRoot(fileName: String, rootPath: File): File? {
  val gpgFile = File(this, fileName)
  if (gpgFile.exists()) return gpgFile

  if (this.absolutePath == rootPath.absolutePath) {
    return null
  }
  val parent = parentFile
  return if (parent != null && parent.exists()) {
    parent.findTillRoot(fileName, rootPath)
  } else {
    null
  }
}
