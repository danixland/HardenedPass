/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ListModelTest {

  private val root = Files.createTempDirectory("aps").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun file(path: String) =
    File(root, path).apply {
      parentFile!!.mkdirs()
      writeText("x")
    }

  private fun folder(path: String) = File(root, path).apply { mkdirs() }

  @Test
  fun openingAChildFolderPushesIt() {
    assertEquals(0 to listOf("work"), folderMoves("", "work"))
  }

  @Test
  fun breadcrumbJumpsOnlyPop() {
    assertEquals(1 to emptyList<String>(), folderMoves("work/servers", "work"))
    assertEquals(2 to emptyList<String>(), folderMoves("work/servers", ""))
  }

  @Test
  fun searchResultsRebuildTheStackFromTheSharedParent() {
    assertEquals(1 to listOf("personal", "personal/mail"), folderMoves("work", "personal/mail"))
    assertEquals(
      0 to listOf("work/servers", "work/servers/eu"),
      folderMoves("work", "work/servers/eu"),
    )
  }

  @Test
  fun sameFolderMovesNothing() {
    assertEquals(0 to emptyList<String>(), folderMoves("work", "work"))
  }

  @Test
  fun browsingShowsNamesAndFolderCounts() {
    val work = folder("work")
    file("work/mail.gpg")
    file("work/notes.txt")
    folder("work/servers")
    val site = file("site.gpg")

    val rows = listRows(listOf(work, site), root)

    assertEquals(listOf(ListRow(work.absolutePath, "work", null, 2)), rows.folders)
    assertEquals(listOf(ListRow(site.absolutePath, "site", null, 0)), rows.entries)
  }

  @Test
  fun searchResultsShowWhereTheyAre() {
    val servers = folder("work/servers")
    val vpn = file("work/vpn.gpg")

    val rows = listRows(listOf(vpn, servers), root)

    assertEquals(listOf(ListRow(servers.absolutePath, "work/servers", null, 0)), rows.folders)
    assertEquals(listOf(ListRow(vpn.absolutePath, "vpn", "work", 0)), rows.entries)
  }

  @Test
  fun keepsTheGivenOrderInsideEachGroup() {
    val b = file("b.gpg")
    val a = file("a.gpg")
    val z = folder("z")
    val y = folder("y")

    val rows = listRows(listOf(b, z, a, y), root)

    assertEquals(listOf(z.absolutePath, y.absolutePath), rows.folders.map { it.path })
    assertEquals(listOf(b.absolutePath, a.absolutePath), rows.entries.map { it.path })
  }

  @Test
  fun pickerOffersFoldersByName() {
    folder("b")
    folder("A")
    folder(".hidden")
    folder(".git")
    file("c.gpg")
    assertEquals(listOf("A", "b"), subfolders(root, showHidden = false).map { it.name })
    assertEquals(listOf(".hidden", "A", "b"), subfolders(root, showHidden = true).map { it.name })
    assertEquals(emptyList<File>(), subfolders(File(root, "missing"), showHidden = false))
  }
}
