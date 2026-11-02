/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.git

import app.passwordstore.data.repo.PasswordRepository
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.GC
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.util.FS

/**
 * Runs the JGit command chains the git operations in `util/git/operation` use, against a local bare
 * repository, and checks the results GitCommandExecutor relies on. Guards JGit upgrades: the
 * operations themselves are tied to an Activity and are covered by the device run.
 */
class JGitBehaviourTest {

  private val root = Files.createTempDirectory("aps-jgit").toFile()
  private val remote = File(root, "remote.git")
  private val identity = PersonIdent("test", "test@example.org")

  init {
    // Ignore the developer's ~/.gitconfig (e.g. commit.gpgsign); the app has no global config.
    FS.DETECTED.setUserHome(root)
    Git.init().setBare(true).setDirectory(remote).call().close()
    clone("seed").use { git ->
      git.write("example.org.gpg", "initial")
      git.commitAll("Initial commit")
      git.push().setRemote("origin").setPushAll().call()
    }
  }

  @AfterTest fun cleanUp() = root.deleteRecursively().let {}

  private fun clone(name: String): Git =
    Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(File(root, name)).call()

  private fun Git.write(path: String, content: String) =
    File(repository.workTree, path).writeText(content)

  private fun Git.read(path: String) = File(repository.workTree, path).readText()

  /** The add, status, commit steps of SyncOperation; returns the number of changes seen. */
  private fun Git.commitAll(message: String): Int {
    add().addFilepattern(".").call()
    val changes = status().call().uncommittedChanges.size
    if (changes > 0) {
      commit().setAll(true).setMessage(message).setAuthor(identity).setCommitter(identity).call()
    }
    return changes
  }

  private fun Git.pushStatuses() =
    push().setPushAll().setRemote("origin").call().flatMap { it.remoteUpdates }.map { it.status }

  private fun remoteBranches() =
    Git.open(remote).use { git ->
      git.branchList().call().map { it.name.removePrefix("refs/heads/") }
    }

  @Test
  fun syncPullsRebasesAndPushes() {
    clone("a").use { a ->
      a.write("a.gpg", "from a")
      a.commitAll("a")
      assertEquals(listOf(RemoteRefUpdate.Status.OK), a.pushStatuses())
    }
    clone("b").use { b ->
      b.write("b.gpg", "from b")
      assertEquals(1, b.commitAll("b"))
      val pull = b.pull().setRebase(true).setRemote("origin").call()
      assertNotNull(pull.rebaseResult)
      assertTrue(pull.rebaseResult.status.isSuccessful)
      assertEquals("from a", b.read("a.gpg"))
      assertEquals(listOf(RemoteRefUpdate.Status.OK), b.pushStatuses())
      // Nothing left to push: the executor shows "up to date".
      assertEquals(listOf(RemoteRefUpdate.Status.UP_TO_DATE), b.pushStatuses())
      // Nothing to commit: the executor skips the commit.
      assertEquals(0, b.commitAll("noop"))
    }
  }

  @Test
  fun pushWithoutPullIsRejectedAsNonFastForward() {
    val a = clone("a")
    val b = clone("b")
    a.write("a.gpg", "from a")
    a.commitAll("a")
    a.pushStatuses()
    b.write("b.gpg", "from b")
    b.commitAll("b")
    assertEquals(listOf(RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD), b.pushStatuses())
    a.close()
    b.close()
  }

  @Test
  fun conflictStopsTheRebaseAndBreakOutOfDetachedRecoversIt() {
    val a = clone("a")
    val b = clone("b")
    a.write("example.org.gpg", "edited by a")
    a.commitAll("a")
    a.pushStatuses()
    b.write("example.org.gpg", "edited by b")
    b.commitAll("b")

    val pull = b.pull().setRebase(true).setRemote("origin").call()
    assertFalse(pull.rebaseResult.status.isSuccessful)
    assertTrue(b.repository.repositoryState.isRebasing)
    assertEquals("master", PasswordRepository.rebasedBranch(b.repository.directory))

    // BreakOutOfDetached, non-merging branch.
    b.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
    assertEquals(RepositoryState.SAFE, b.repository.repositoryState)
    b.checkout().setCreateBranch(true).setName("conflicting-master-1").call()
    b.push().setRemote("origin").call()
    b.checkout().setName("master").call()

    assertEquals(setOf("master", "conflicting-master-1"), remoteBranches().toSet())
    assertEquals("master", b.repository.branch)
    assertEquals("edited by b", b.read("example.org.gpg"))
    a.close()
    b.close()
  }

  @Test
  fun resetToRemoteDiscardsLocalCommits() {
    val a = clone("a")
    val b = clone("b")
    a.write("example.org.gpg", "edited by a")
    a.commitAll("a")
    a.pushStatuses()
    b.write("example.org.gpg", "edited by b")
    b.commitAll("b")

    // ResetToRemoteOperation
    b.fetch().setRemote("origin").setRemoveDeletedRefs(true).call()
    b.branchCreate().setName("master").setForce(true).call()
    b.checkout().setName("master").setForceRefUpdate(true).setUpstreamMode(TRACK).call()
    b.reset().setRef("origin/master").setMode(ResetCommand.ResetType.HARD).call()

    assertEquals("edited by a", b.read("example.org.gpg"))
    assertTrue(b.status().call().isClean)
    a.close()
    b.close()
  }

  @Test
  fun gcStepsPackObjectsWithoutThePidLock() {
    clone("a").use { a ->
      repeat(3) {
        a.write("e$it.gpg", "entry $it")
        a.commitAll("e$it")
      }
      val repo = a.repository as FileRepository
      // GcOperation
      GC(repo).run {
        setExpire(null)
        packRefs()
        repack()
        prune(emptySet())
      }
      assertEquals(0, GC(repo).statistics.numberOfLooseObjects)
      assertTrue(GC(repo).statistics.numberOfPackedObjects > 0)
      assertEquals("entry 2", a.read("e2.gpg"))
    }
  }
}
