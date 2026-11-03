/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.crypto

import app.passwordstore.crypto.KeyUtils.isKeyUsable
import app.passwordstore.crypto.KeyUtils.isSecretKey
import app.passwordstore.crypto.KeyUtils.tryGetEmail
import app.passwordstore.crypto.KeyUtils.tryGetId
import app.passwordstore.crypto.KeyUtils.tryParseKeyring
import app.passwordstore.crypto.TestUtils.AllKeys
import app.passwordstore.crypto.TestUtils.getArmoredSecretKeyWithMultipleIdentities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.bouncycastle.openpgp.PGPSecretKeyRing

class KeyUtilsTest {
  @Test
  fun parseKeyWithMultipleIdentities() {
    val key = PGPKey(getArmoredSecretKeyWithMultipleIdentities())
    val keyring = tryParseKeyring(key)
    assertNotNull(keyring)
    assertIs<PGPSecretKeyRing>(keyring)
    val keyId = tryGetId(key)
    assertNotNull(keyId)
    assertIs<PGPIdentifier.KeyId>(keyId)
    assertEquals("b950ae2813841585", keyId.toString())
  }

  @Test
  fun isKeyUsable() {
    val params = AllKeys.entries.map { it to (it != AllKeys.SIGN_ONLY_PUB) }
    params.forEach { (allKeys, isUsable) ->
      val key = PGPKey(allKeys.keyMaterial)
      assertEquals(isUsable, isKeyUsable(key), "${allKeys.name} failed expectation:")
    }
  }

  @Test
  fun secretKeysAreTold() {
    assertTrue(isSecretKey(PGPKey(AllKeys.ARMORED_SEC.keyMaterial)))
    assertTrue(isSecretKey(PGPKey(AllKeys.GNUPG_OCB_SEC.keyMaterial)))
    assertFalse(isSecretKey(PGPKey(AllKeys.ARMORED_PUB.keyMaterial)))
    assertFalse(isSecretKey(PGPKey("not a key".encodeToByteArray())))
  }

  @Test
  fun firstUserIdIsReturned() {
    val key = PGPKey(getArmoredSecretKeyWithMultipleIdentities())
    assertNotNull(tryGetEmail(key))
    assertEquals(null, tryGetEmail(PGPKey("not a key".encodeToByteArray())))
  }
}
