/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.github.androidpasswordstore.autofillparser

import kotlin.test.Test
import kotlin.test.assertEquals

class FormFieldLogTest {

  @Test
  fun onlyDescriptiveAttributesAreLogged() {
    val attributes =
      mapOf("type" to "email", "value" to "me@example.org", "placeholder" to "me@example.org")
    assertEquals("type=email", htmlAttributesForLog(attributes))
  }
}
