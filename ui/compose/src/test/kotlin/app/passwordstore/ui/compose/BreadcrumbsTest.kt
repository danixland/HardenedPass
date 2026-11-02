/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class BreadcrumbsTest {

  @Test
  fun rootOnly() {
    assertEquals(listOf(Crumb("", "")), crumbsFor(""))
  }

  @Test
  fun nestedPath() {
    assertEquals(
      listOf(Crumb("", ""), Crumb("work", "work"), Crumb("servers", "work/servers")),
      crumbsFor("work/servers"),
    )
  }

  @Test
  fun ignoresStraySlashes() {
    assertEquals(
      listOf(Crumb("", ""), Crumb("work", "work"), Crumb("servers", "work/servers")),
      crumbsFor("/work//servers/"),
    )
  }
}
