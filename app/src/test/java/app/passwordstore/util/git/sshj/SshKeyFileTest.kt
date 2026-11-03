/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git.sshj

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshKeyFileTest {

  private fun pem(type: String) =
    listOf("-----BEGIN $type-----", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmU", "-----END $type-----")

  @Test
  fun acceptsSshPrivateKeys() {
    assertTrue(isSshPrivateKey(pem("OPENSSH PRIVATE KEY")))
    assertTrue(isSshPrivateKey(pem("RSA PRIVATE KEY")))
    assertTrue(isSshPrivateKey(pem("EC PRIVATE KEY")))
  }

  @Test
  fun refusesOtherFiles() {
    // The step 4 finding: an OpenPGP key block has the same markers and was accepted.
    assertFalse(isSshPrivateKey(pem("PGP PRIVATE KEY BLOCK")))
    assertFalse(isSshPrivateKey(pem("PGP PUBLIC KEY BLOCK")))
    assertFalse(isSshPrivateKey(listOf("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA test@example.org")))
    assertFalse(isSshPrivateKey(emptyList()))
    assertFalse(isSshPrivateKey(pem("OPENSSH PRIVATE KEY").dropLast(1)))
  }
}
