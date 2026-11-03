/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.settings

import androidx.core.content.edit
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordSortOrderTest {

  @Test
  fun unknownAndRemovedValuesReadAsFoldersFirst() {
    val prefs = SPMockBuilder().createSharedPreferences()
    assertEquals(PasswordSortOrder.FOLDER_FIRST, PasswordSortOrder.getSortOrder(prefs))
    for (value in listOf("FILE_FIRST", "INDEPENDENT", "bogus")) {
      prefs.edit { putString(PreferenceKeys.SORT_ORDER, value) }
      assertEquals(PasswordSortOrder.FOLDER_FIRST, PasswordSortOrder.getSortOrder(prefs))
    }
    prefs.edit { putString(PreferenceKeys.SORT_ORDER, "RECENTLY_USED") }
    assertEquals(PasswordSortOrder.RECENTLY_USED, PasswordSortOrder.getSortOrder(prefs))
  }
}
