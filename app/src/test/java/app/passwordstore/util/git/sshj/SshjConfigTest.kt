/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git.sshj

import kotlin.test.Test
import kotlin.test.assertNull
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
}
