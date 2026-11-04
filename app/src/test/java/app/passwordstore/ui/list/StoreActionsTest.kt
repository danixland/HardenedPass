/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.list

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreActionsTest {

  @Test
  fun deleteDoesNotFollowSymlinks() {
    val root = Files.createTempDirectory("aps").toFile()
    val outsideDir = File(root, "outside").apply { mkdirs() }
    val outsideFile = File(outsideDir, "secret.gpg").apply { writeText("x") }
    val folder = File(root, "store/folder").apply { mkdirs() }
    File(folder, "login.gpg").writeText("x")
    Files.createSymbolicLink(File(folder, "link").toPath(), outsideDir.toPath())

    assertTrue(folder.deleteWithoutFollowingLinks())

    assertFalse(folder.exists())
    assertTrue(outsideFile.exists())
  }
}
