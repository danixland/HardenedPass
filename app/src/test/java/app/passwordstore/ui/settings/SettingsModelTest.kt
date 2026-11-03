/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import app.passwordstore.util.git.GitCommit
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.FS

class SettingsModelTest {

  private val root = Files.createTempDirectory("aps-settings").toFile()
  private val identity = PersonIdent("test", "test@example.org")

  init {
    // Ignore the developer's ~/.gitconfig (default branch, commit signing).
    FS.DETECTED.setUserHome(root)
  }

  @AfterTest fun cleanUp() = root.deleteRecursively().let {}

  private fun repo(): Git =
    Git.init().setInitialBranch("master").setDirectory(File(root, "store")).call()

  private fun Git.commitFile(name: String, message: String): RevCommit {
    File(repository.workTree, name).writeText(message)
    add().addFilepattern(name).call()
    return commit()
      .setMessage(message)
      .setAuthor(identity)
      .setCommitter(identity)
      .setSign(false)
      .call()
  }

  @Test
  fun proxyPorts() {
    assertEquals(-1, proxyPort(""))
    assertEquals(-1, proxyPort("  "))
    assertEquals(8080, proxyPort("8080"))
    assertEquals(8080, proxyPort(" 8080 "))
    assertEquals(1, proxyPort("1"))
    assertEquals(65535, proxyPort("65535"))
    assertNull(proxyPort("0"))
    assertNull(proxyPort("65536"))
    assertNull(proxyPort("99999999999"))
    assertNull(proxyPort("-1"))
    assertNull(proxyPort("80a"))
  }

  @Test
  fun headStatusFollowsHead() {
    repo().use { git ->
      // A new repository: HEAD names a branch that has no commit yet.
      assertEquals(HeadStatus.Branch("master"), headStatus(git.repository))
      val first = git.commitFile("a.gpg", "Add a")
      git.commitFile("b.gpg", "Add b")
      assertEquals(HeadStatus.Branch("master"), headStatus(git.repository))
      assertFalse(needsAbort(git.repository))
      git.checkout().setName(first.name).call()
      assertEquals(HeadStatus.Detached(first.name.take(8)), headStatus(git.repository))
    }
  }

  @Test
  fun gitLogIsNewestFirst() {
    assertEquals(emptyList<GitCommit>(), gitLog(null))
    repo().use { git ->
      // No commit yet: JGit's NoHeadException is an empty log, not a crash.
      assertEquals(emptyList<GitCommit>(), gitLog(git.repository))
      val first = git.commitFile("a.gpg", "Add a")
      val second = git.commitFile("b.gpg", "Add b")
      val log = gitLog(git.repository)
      assertEquals(listOf("Add b", "Add a"), log.map(GitCommit::shortMessage))
      assertEquals(listOf(second.name, first.name), log.map(GitCommit::hash))
      assertEquals("test", log[0].authorName)
    }
  }
}
