/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.data.password

import android.content.Context
import android.content.Intent
import app.passwordstore.ui.crypto.getLongName
import app.passwordstore.ui.main.LaunchActivity
import java.io.File

data class PasswordItem(val name: String, val type: Char, val file: File, val rootDir: File) :
  Comparable<PasswordItem> {

  val fullPathToParent = file.absolutePath.replace(rootDir.absolutePath, "").replace(file.name, "")

  val longName = getLongName(fullPathToParent, rootDir.absolutePath, toString())

  override fun equals(other: Any?): Boolean {
    return (other is PasswordItem) && (other.file == file)
  }

  override fun compareTo(other: PasswordItem): Int {
    return (type + name).compareTo(other.type + other.name, ignoreCase = true)
  }

  override fun toString(): String {
    return name.replace("\\.gpg$".toRegex(), "")
  }

  override fun hashCode(): Int {
    return 0
  }

  /** Creates an [Intent] to launch this [PasswordItem] through the authentication process. */
  fun createAuthEnabledIntent(context: Context): Intent {
    val intent = Intent(context, LaunchActivity::class.java)
    intent.putExtra(LaunchActivity.EXTRA_FILE_PATH, file.absolutePath)
    intent.action = LaunchActivity.ACTION_DECRYPT_PASS
    return intent
  }

  companion object {

    const val TYPE_CATEGORY = 'c'
    const val TYPE_PASSWORD = 'p'

    fun newCategory(name: String, file: File, rootDir: File): PasswordItem {
      return PasswordItem(name, TYPE_CATEGORY, file, rootDir)
    }

    fun newPassword(name: String, file: File, rootDir: File): PasswordItem {
      return PasswordItem(name, TYPE_PASSWORD, file, rootDir)
    }
  }
}
