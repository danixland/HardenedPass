/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.crypto

import app.passwordstore.crypto.PGPIdentifier.KeyId
import app.passwordstore.crypto.PGPIdentifier.UserId
import com.github.michaelbull.result.get
import com.github.michaelbull.result.runCatching
import org.bouncycastle.openpgp.PGPKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.pgpainless.PGPainless
import org.pgpainless.key.parsing.KeyRingReader

/** Utility methods to deal with [PGPKey]s. */
public object KeyUtils {
  /**
   * Attempts to parse a [PGPKeyRing] from a given [key]. The key is first tried as a secret key and
   * then as a public one before the method gives up and returns null.
   */
  public fun tryParseKeyring(key: PGPKey): PGPKeyRing? {
    return runCatching { KeyRingReader.readKeyRing(key.contents.inputStream()) }.get()
  }

  /** Parses a [PGPKeyRing] from the given [key] and calculates its long key ID */
  public fun tryGetId(key: PGPKey): KeyId? {
    val keyRing = tryParseKeyring(key) ?: return null
    return KeyId(keyRing.publicKey.keyID)
  }

  /**
   * Attempts to parse the given [PGPKey] into a [PGPKeyRing] and obtains the [UserId] of the
   * corresponding public key.
   */
  public fun tryGetEmail(key: PGPKey): UserId? {
    val keyRing = tryParseKeyring(key) ?: return null
    // A key may carry no user ID at all: next() threw NoSuchElementException there.
    return keyRing.publicKey.userIDs.asSequence().firstOrNull()?.let(::UserId)
  }

  /** True when [key] parses as a secret key ring, the only kind that can decrypt. */
  // ponytail: GnuPG stub and smartcard keyrings (no usable secret material) count as secret too.
  // Upgrade path: check for a secret subkey usable for decryption.
  public fun isSecretKey(key: PGPKey): Boolean = tryParseKeyring(key) is PGPSecretKeyRing

  /**
   * Tests if the given [key] can be used for encryption, which is a bare minimum necessity for the
   * app.
   */
  public fun isKeyUsable(key: PGPKey): Boolean {
    return runCatching {
        val keyRing = tryParseKeyring(key) ?: return false
        PGPainless.inspectKeyRing(keyRing).isUsableForEncryption
      }
      .get() == true
  }
}
