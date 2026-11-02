/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git.sshj

import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import org.bouncycastle.jce.provider.BouncyCastleProvider

class Ed25519KeyPairTest {

  private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  // RFC 8032, section 7.1, test 1. Wrapped keys store the 32-byte seed, so a seed must keep
  // mapping to the same key pair across library changes.
  private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
  private val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

  @Test
  fun derivesTheRfc8032PublicKeyFromTheSeed() {
    val keyPair = ed25519KeyPair(seed)
    assertEquals(KeyType.ED25519, KeyType.fromKey(keyPair.public))
    val sshBlob = Buffer.PlainBuffer().putPublicKey(keyPair.public).compactData
    // ssh-ed25519 wire format: string "ssh-ed25519", then string <32-byte public key>.
    assertContentEquals(publicKey, sshBlob.copyOfRange(sshBlob.size - 32, sshBlob.size))
  }

  @Test
  fun privateKeySignsForThePublicKey() {
    val keyPair = ed25519KeyPair(seed)
    val message = "hardenedpass".encodeToByteArray()
    val signature =
      Signature.getInstance("Ed25519", BouncyCastleProvider()).run {
        initSign(keyPair.private)
        update(message)
        sign()
      }
    val verified =
      Signature.getInstance("Ed25519", BouncyCastleProvider()).run {
        initVerify(keyPair.public)
        update(message)
        verify(signature)
      }
    assertTrue(verified)
  }
}
