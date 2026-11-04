/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.passfile.Totp
import app.passwordstore.util.time.UserClock
import app.passwordstore.util.totp.UriTotpFinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EntryModelTest {

  private fun entry(content: String) =
    PasswordEntry(UserClock(), UriTotpFinder(), content.encodeToByteArray())

  @Test
  fun firstGroupAndExtrasInFileOrder() {
    val fields =
      entryFields(entry("hunter2\nusername: alice\nurl: https://example.org\npin: 1234\n"))
    assertEquals("hunter2", fields.password)
    assertEquals("alice", fields.username)
    assertEquals(listOf("url" to "https://example.org", "pin" to "1234"), fields.extras)
  }

  @Test
  fun blankPasswordAndMissingUsernameAreLeftOut() {
    val fields = entryFields(entry("\nnote: none\n"))
    assertNull(fields.password)
    assertNull(fields.username)
    assertEquals(listOf("note" to "none"), fields.extras)
  }

  @Test
  fun otpUriIsNotAnExtraField() {
    val fields =
      entryFields(
        entry(
          "s3cret\notpauth://totp/example.com:carol?secret=JBSWY3DPEHPK3PXP&issuer=example.com\n"
        )
      )
    assertEquals("s3cret", fields.password)
    assertTrue(fields.extras.isEmpty())
  }

  @Test
  fun fractionLeftOfTheOtpPeriod() {
    assertEquals(1f, Totp("123456", 30.seconds, 30.seconds).fractionLeft())
    assertEquals(0.5f, Totp("123456", 15.seconds, 30.seconds).fractionLeft())
  }
}
