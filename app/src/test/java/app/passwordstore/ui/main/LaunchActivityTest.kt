/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.main

import app.passwordstore.ui.main.LaunchActivity.Companion.isFileInRepo
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchActivityTest {

  @Test
  fun onlyAcceptsFilesInsideTheRepository() {
    val root = Files.createTempDirectory("aps").toFile()
    val repo = File(root, "store").apply { mkdirs() }
    val inside =
      File(repo, "site/login.gpg").apply {
        parentFile!!.mkdirs()
        writeText("x")
      }
    val outside = File(root, "secret.gpg").apply { writeText("x") }
    val sibling =
      File(root, "store-evil/login.gpg").apply {
        parentFile!!.mkdirs()
        writeText("x")
      }
    val link = File(repo, "link.gpg").toPath()
    Files.createSymbolicLink(link, outside.toPath())

    assertTrue(isFileInRepo(inside.absolutePath, repo))
    assertFalse(isFileInRepo(outside.absolutePath, repo))
    assertFalse(isFileInRepo("${repo.absolutePath}/../secret.gpg", repo))
    assertFalse(isFileInRepo(sibling.absolutePath, repo))
    assertFalse(isFileInRepo(link.toString(), repo))
    assertFalse(isFileInRepo(repo.absolutePath, repo))
    assertFalse(isFileInRepo(File(repo, "missing.gpg").absolutePath, repo))
    assertFalse(isFileInRepo(null, repo))
    assertFalse(isFileInRepo("", repo))
    root.deleteRecursively()
  }
}
