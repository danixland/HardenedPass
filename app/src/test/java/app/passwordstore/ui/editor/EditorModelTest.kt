/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import app.passwordstore.R
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.passgen.random.PasswordOption
import app.passwordstore.util.time.UserClock
import app.passwordstore.util.totp.UriTotpFinder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorModelTest {

  private val root = Files.createTempDirectory("aps").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun entry(content: String) =
    PasswordEntry(UserClock(), UriTotpFinder(), content.encodeToByteArray())

  @Test
  fun editingWithoutChangesKeepsThePassFile() {
    val files =
      listOf(
        "hunter2\nusername: alice\nurl: https://example.org\n",
        "s3cret\notpauth://totp/example.com:carol?secret=JBSWY3DPEHPK3PXP&issuer=example.com\n",
      )
    for (content in files) assertEquals(content, passContent(editorForm(entry(content), "", "x")))
  }

  @Test
  fun formFromAnEntrySplitsItsFields() {
    val form =
      editorForm(entry("hunter2\nusername: alice\nurl: https://example.org\n"), "work", "mail")
    assertEquals(EditorForm("mail", "work", "hunter2", "alice", "url: https://example.org\n"), form)
  }

  @Test
  fun newEntryContent() {
    assertEquals(
      "pw\nusername: bob\nnote: x",
      passContent(EditorForm(name = "n", password = "pw", username = "bob", extra = "note: x")),
    )
    assertEquals("pw\n", passContent(EditorForm(name = "n", password = "pw")))
  }

  @Test
  fun formErrors() {
    assertEquals(R.string.file_toast_text, formError(EditorForm(name = " ", password = "pw")))
    assertEquals(
      R.string.invalid_filename_text,
      formError(EditorForm(name = "a/b", password = "pw")),
    )
    assertEquals(R.string.empty_toast_text, formError(EditorForm(name = "a", username = "bob")))
    assertNull(formError(EditorForm(name = "a", extra = "note")))
    assertNull(formError(EditorForm(name = "a", password = "pw")))
  }

  @Test
  fun entryFileTrimsTheNameAndUsesTheFolder() {
    assertEquals(
      File(root, "work/mail.gpg"),
      entryFile(root, EditorForm(name = " mail ", dir = "work")),
    )
    assertEquals(File(root, "mail.gpg"), entryFile(root, EditorForm(name = "mail")))
  }

  @Test
  fun overwritesOnlyAnotherFile() {
    val taken = File(root, "a.gpg").apply { writeText("x") }
    assertFalse(overwrites(File(root, "work/b.gpg"), null))
    assertTrue(overwrites(taken, null))
    assertFalse(overwrites(taken, taken))
    assertTrue(overwrites(taken, File(root, "work/b.gpg")))
  }

  @Test
  fun writeEntryCreatesTheFolderAndReplacesTheFile() {
    val target = File(root, "new/dir/a.gpg")
    writeEntry(target, byteArrayOf(1))
    writeEntry(target, byteArrayOf(2, 3))
    assertContentEquals(byteArrayOf(2, 3), target.readBytes())
    assertEquals(listOf("a.gpg"), target.parentFile!!.list()!!.toList())
  }

  @Test
  fun linesGoOnTheirOwnLine() {
    assertEquals("otpauth://x", withLine("", "otpauth://x"))
    assertEquals("a\notpauth://x", withLine("a", "otpauth://x"))
    assertEquals("a\notpauth://x", withLine("a\n", "otpauth://x"))
  }

  @Test
  fun manualTotpUriIsReadBackByTheParser() {
    val uri = totpUri(" JBSW Y3DP EHPK 3PXP ", "Example Co")!!
    assertEquals("JBSWY3DPEHPK3PXP", UriTotpFinder().findSecret(uri))
    assertEquals("Example Co", UriTotpFinder().findIssuer(uri))
    assertTrue(entry("PLACEHOLDER\n$uri").hasTotp())
    assertEquals("otpauth://totp?secret=JBSWY3DPEHPK3PXP", totpUri("JBSWY3DPEHPK3PXP", " "))
    assertNull(totpUri("  ", "Example Co"))
  }

  @Test
  fun randomOptionsFollowTheCheckboxes() {
    assertEquals(listOf(PasswordOption.FullyRandom), RandomSpec().options())
    assertEquals(
      listOf(
        PasswordOption.NoDigits,
        PasswordOption.AtLeastOneSymbol,
        PasswordOption.NoUppercaseLetters,
        PasswordOption.NoLowercaseLetters,
        PasswordOption.NoAmbiguousCharacters,
      ),
      RandomSpec(
          numerals = false,
          symbols = true,
          uppercase = false,
          lowercase = false,
          ambiguous = false,
          pronounceable = true,
        )
        .options(),
    )
  }

  @Test
  fun randomPasswordOrWhyNot() {
    assertEquals(20, randomPassword(RandomSpec()).value.length)
    assertEquals(
      R.string.pwgen_no_chars_error,
      randomPassword(RandomSpec(numerals = false, uppercase = false, lowercase = false)).error,
    )
    assertEquals(
      R.string.pwgen_length_too_short_error,
      randomPassword(RandomSpec(length = 2)).error,
    )
  }
}
