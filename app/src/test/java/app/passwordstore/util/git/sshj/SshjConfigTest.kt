/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git.sshj

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils

class SshjConfigTest {

  // Android Keystore keys have no encoding, so Bouncy Castle cannot sign with them. sshj must
  // leave provider selection to the JCA, which hands Keystore keys to the AndroidKeyStore
  // provider.
  @Test
  fun sshjDoesNotForceBouncyCastle() {
    setUpBouncyCastleForSshj()
    assertNull(SecurityUtils.getSecurityProvider())
  }

  // Conscrypt's Ed25519 keys map to KeyType.UNKNOWN in sshj, so Ed25519 keys must come from
  // Bouncy Castle whatever provider the platform puts first.
  @Test
  fun ed25519KeysComeFromBouncyCastle() {
    setUpBouncyCastleForSshj()
    val key = Ed25519KeyFactory.getPublicKey(ByteArray(32) { 1 })
    assertTrue(key.javaClass.name.startsWith("org.bouncycastle."), key.javaClass.name)
    assertEquals(KeyType.ED25519, KeyType.fromKey(key))
  }
}
