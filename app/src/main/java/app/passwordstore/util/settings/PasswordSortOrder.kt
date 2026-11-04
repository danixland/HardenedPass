/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.settings

import android.content.Context
import android.content.SharedPreferences
import app.passwordstore.Application
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.getString

enum class PasswordSortOrder(val comparator: java.util.Comparator<PasswordItem>) {
  FOLDER_FIRST(
    Comparator { p1: PasswordItem, p2: PasswordItem ->
      (p1.type + p1.name).compareTo(p2.type + p2.name, ignoreCase = true)
    }
  ),
  RECENTLY_USED(
    Comparator { p1: PasswordItem, p2: PasswordItem ->
      val recentHistory =
        Application.instance.getSharedPreferences("recent_password_history", Context.MODE_PRIVATE)
      val timeP1 = recentHistory.getString(p1.file.absolutePath.base64())
      val timeP2 = recentHistory.getString(p2.file.absolutePath.base64())
      when {
        timeP1 != null && timeP2 != null -> timeP2.compareTo(timeP1)
        timeP1 != null && timeP2 == null -> return@Comparator -1
        timeP1 == null && timeP2 != null -> return@Comparator 1
        else -> p1.name.compareTo(p2.name, ignoreCase = true)
      }
    }
  );

  companion object {

    /**
     * The stored sort order; anything else (the removed "files first" and "type independent", a
     * value from another version) reads as [FOLDER_FIRST].
     */
    @JvmStatic
    fun getSortOrder(settings: SharedPreferences): PasswordSortOrder {
      val stored = settings.getString(PreferenceKeys.SORT_ORDER)
      return entries.firstOrNull { it.name == stored } ?: FOLDER_FIRST
    }
  }
}
