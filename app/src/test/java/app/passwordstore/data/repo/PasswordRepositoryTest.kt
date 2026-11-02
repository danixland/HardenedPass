/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.data.repo

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PasswordRepositoryTest {

  @Test
  fun rebasedBranchReadsTheBranchOfAStoppedRebase() {
    val gitDir = Files.createTempDirectory("aps-git").toFile()
    assertNull(PasswordRepository.rebasedBranch(gitDir))

    val headName = gitDir.resolve("rebase-merge").apply { mkdirs() }.resolve("head-name")
    headName.writeText("refs/heads/master\n")
    assertEquals("master", PasswordRepository.rebasedBranch(gitDir))

    // A rebase of a detached HEAD records "detached HEAD", which is not a branch.
    headName.writeText("detached HEAD\n")
    assertNull(PasswordRepository.rebasedBranch(gitDir))
    gitDir.deleteRecursively()
  }
}
