/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the path helpers' behavior across their move out of BasePGPActivity. */
class CryptoExtensionsTest {

  @Test
  fun parentPathIsRelativeToTheStore() {
    assertEquals("/social/", getParentPath("/store/social/facebook.gpg", "/store"))
    assertEquals("/", getParentPath("/store/example.org.gpg", "/store"))
  }

  @Test
  fun longNameJoinsFolderAndName() {
    // StoreActions passes absolute folders, PasswordItem its path relative to the store.
    assertEquals("work/x", getLongName("/store/work", "/store", "x"))
    assertEquals("social/facebook", getLongName("/social/", "/store", "facebook"))
    assertEquals("x", getLongName("/store", "/store", "x"))
  }
}
