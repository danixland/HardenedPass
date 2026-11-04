/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.settings

import app.passwordstore.util.extensions.getString
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitSettingsTest {

  @Test
  fun proxyUsernameIsReadWhereItIsWritten() {
    val settings = SPMockBuilder().createSharedPreferences()
    val proxy = SPMockBuilder().createSharedPreferences()
    val gitSettings =
      GitSettings(settings, SPMockBuilder().createSharedPreferences(), proxy, "/nonexistent")
    gitSettings.proxyUsername = "proxy-user"
    assertEquals("proxy-user", gitSettings.proxyUsername)
    assertEquals("proxy-user", proxy.getString(PreferenceKeys.PROXY_USERNAME))
    assertNull(settings.getString(PreferenceKeys.PROXY_USERNAME))
  }
}
