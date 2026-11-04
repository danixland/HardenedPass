/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import app.passwordstore.util.settings.DirectoryStructure
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AutofillModelTest {

  private val root = File("/store")

  private fun rows(structure: DirectoryStructure, vararg paths: String) =
    autofillRows(paths.map { File(root, it) }, root, structure).map {
      Triple(it.path, it.label, it.subtitle)
    }

  @Test
  fun fileBasedNamesTheIdentifier() {
    assertEquals(
      listOf(
        Triple("/store/work/example.org/john@doe.org.gpg", "example.org", "john@doe.org · work"),
        Triple("/store/example.org/john.gpg", "example.org", "john"),
        Triple("/store/example.org.gpg", "example.org", null),
      ),
      rows(
        DirectoryStructure.FileBased,
        "work/example.org/john@doe.org.gpg",
        "example.org/john.gpg",
        "example.org.gpg",
      ),
    )
  }

  @Test
  fun directoryBasedFallsBackToTheAccountPart() {
    assertEquals(
      listOf(
        Triple(
          "/store/example.org/john@doe.org/password.gpg",
          "example.org",
          "john@doe.org/password",
        ),
        Triple("/store/Temporary PIN.gpg", "Temporary PIN", null),
      ),
      rows(
        DirectoryStructure.DirectoryBased,
        "example.org/john@doe.org/password.gpg",
        "Temporary PIN.gpg",
      ),
    )
  }

  @Test
  fun encryptedUsernameShowsTheFolders() {
    assertEquals(
      listOf(Triple("/store/work/example.org.gpg", "example.org", "work")),
      rows(DirectoryStructure.EncryptedUsername, "work/example.org.gpg"),
    )
  }

  @Test
  fun rowsKeepTheRankingOrder() {
    assertEquals(
      listOf("b.org", "a.org"),
      rows(DirectoryStructure.FileBased, "b.org.gpg", "a.org.gpg").map { it.second },
    )
  }
}
