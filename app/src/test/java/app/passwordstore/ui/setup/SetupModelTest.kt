/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.util.settings.AuthMode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SetupModelTest {

  private val root = Files.createTempDirectory("aps").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun urlProblems() {
    assertEquals(UrlProblem.HttpsPort, urlProblem("https://example.org:8443/store.git"))
    assertEquals(UrlProblem.SshScheme, urlProblem("git@example.org:2222/srv/store.git"))
    assertEquals(UrlProblem.GitScheme, urlProblem("git://example.org/store.git"))
    assertEquals(UrlProblem.GitScheme, urlProblem("git://example.org:9418/store.git"))
    assertNull(urlProblem("ssh://git@example.org:2222/srv/store.git"))
    assertNull(urlProblem("git@example.org:srv/store.git"))
    assertNull(urlProblem("https://example.org/store.git"))
    assertNull(urlProblem("http://example.org:8080/store.git"))
  }

  @Test
  fun urlFixes() {
    assertEquals(
      "https://example.org/store.git",
      fixUrl("https://example.org:8443/store.git", UrlProblem.HttpsPort),
    )
    assertEquals(
      "ssh://git@example.org:2222/srv/store.git",
      fixUrl("git@example.org:2222/srv/store.git", UrlProblem.SshScheme),
    )
    assertNull(fixUrl("git://example.org/store.git", UrlProblem.GitScheme))
  }

  @Test
  fun authModesFollowTheUrl() {
    val ssh = listOf(AuthMode.SshKey, AuthMode.Password)
    val http = listOf(AuthMode.None, AuthMode.Password)
    assertEquals(ssh, authModes("ssh://git@example.org/store.git"))
    assertEquals(ssh, authModes("git@example.org:store.git"))
    assertEquals(ssh, authModes(""))
    assertEquals(http, authModes("https://example.org/store.git"))
    assertEquals(http, authModes("http://example.org/store.git"))
    assertEquals(AuthMode.None, fitAuthMode("https://example.org/x", AuthMode.SshKey))
    assertEquals(AuthMode.Password, fitAuthMode("https://example.org/x", AuthMode.Password))
    assertEquals(AuthMode.SshKey, fitAuthMode("git@example.org:x", AuthMode.None))
    assertEquals(AuthMode.Password, fitAuthMode("git@example.org:x", AuthMode.Password))
  }

  @Test
  fun cloneTargets() {
    val store = File(root, "store")
    assertEquals(CloneTarget.Empty, cloneTarget(store))
    store.mkdirs()
    assertEquals(CloneTarget.Empty, cloneTarget(store))
    File(store, ".git").mkdirs()
    assertEquals(CloneTarget.OnlyGit, cloneTarget(store))
    File(store, "a.gpg").writeText("x")
    assertEquals(CloneTarget.Occupied, cloneTarget(store))
    File(store, ".git").deleteRecursively()
    assertEquals(CloneTarget.Occupied, cloneTarget(store))
  }

  @Test
  fun storeRecipientsSkipBlankAndInvalidLines() {
    val ids =
      storeRecipients(
        "4882E8257A3700D43119B8D3F8993A789CDFB014\n\n  Alice <alice@example.org>  \nnot an id\n"
      )
    assertEquals(listOf("f8993a789cdfb014", "alice@example.org"), ids.map(PGPIdentifier::toString))
    assertEquals(emptyList(), storeRecipients(""))
  }

  @Test
  fun storeRecipientsDropCommentsAndExactSubkeyMarks() {
    val ids =
      storeRecipients(
        "# store key\nF8993A789CDFB014! # laptop\n79E8208280490C77!\nalice@example.org#work\n  # \n"
      )
    assertEquals(
      listOf("f8993a789cdfb014", "79e8208280490c77", "alice@example.org"),
      ids.map(PGPIdentifier::toString),
    )
  }

  @Test
  fun readAtMostRefusesMoreThanTheLimit() {
    assertEquals("abc", readAtMost("abc".byteInputStream(), 3)?.decodeToString())
    assertEquals("", readAtMost("".byteInputStream(), 3)?.decodeToString())
    assertNull(readAtMost("abcd".byteInputStream(), 3))
  }

  @Test
  fun gpgIdTextIsUppercaseKeyIdsOnePerLine() {
    val ids =
      listOf("f8993a789cdfb014", "0x79E8208280490C77").map {
        assertIs<PGPIdentifier.KeyId>(PGPIdentifier.fromString(it))
      }
    val text = gpgIdText(ids)
    assertEquals("F8993A789CDFB014\n79E8208280490C77\n", text)
    // pass and this app read it back as the same key IDs.
    assertEquals(ids, storeRecipients(text))
  }

  @Test
  fun gpgIdIsReadOnlyFromASmallRegularFile() {
    assertNull(readGpgId(root))
    File(root, ".gpg-id").writeText("F8993A789CDFB014\n")
    assertEquals("F8993A789CDFB014\n", readGpgId(root))

    val linked = File(root, "linked").apply { mkdirs() }
    val outside = File(root, "outside").apply { writeText("F8993A789CDFB014\n") }
    Files.createSymbolicLink(File(linked, ".gpg-id").toPath(), outside.toPath())
    assertNull(readGpgId(linked))

    val big = File(root, "big").apply { mkdirs() }
    File(big, ".gpg-id").writeText("A".repeat(64 * 1024 + 1))
    assertNull(readGpgId(big))

    val folder = File(root, "folder").apply { File(this, ".gpg-id").mkdirs() }
    assertNull(readGpgId(folder))
  }
}
