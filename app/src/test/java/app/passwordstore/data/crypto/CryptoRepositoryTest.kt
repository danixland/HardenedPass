/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.data.crypto

import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPKey
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.crypto.PGPainlessCryptoHandler
import app.passwordstore.crypto.errors.KeyNotFoundException
import app.passwordstore.util.coroutines.DispatcherProvider
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import com.github.michaelbull.result.getError
import com.github.michaelbull.result.unwrap
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.pgpainless.PGPainless

class CryptoRepositoryTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val dispatchers =
    object : DispatcherProvider {
      override fun io() = Dispatchers.Unconfined
    }

  private fun newKey(uid: String) =
    PGPKey(PGPainless.asciiArmor(PGPainless.generateKeyRing().modernKeyRing(uid)).toByteArray())

  private suspend fun repository(keys: List<PGPKey>): CryptoRepository {
    val keyManager = PGPKeyManager(temporaryFolder.root.absolutePath, Dispatchers.Unconfined)
    keys.forEach { keyManager.addKey(it).unwrap() }
    return CryptoRepository(
      keyManager,
      PGPainlessCryptoHandler(),
      dispatchers,
      SPMockBuilder().createSharedPreferences(),
    )
  }

  @Test
  fun encryptFailsWhenARecipientKeyIsMissing() = runBlocking {
    val repository = repository(listOf(newKey("present <present@example.org>")))
    val out = ByteArrayOutputStream()

    val result =
      repository.encrypt(
        listOf(
          PGPIdentifier.UserId("present@example.org"),
          PGPIdentifier.UserId("missing@example.org"),
        ),
        "secret".byteInputStream(),
        out,
      )

    assertIs<KeyNotFoundException>(result.getError())
    assertTrue(out.size() == 0, "nothing may be written for a partial recipient set")
  }

  @Test
  fun encryptSucceedsWhenAllRecipientKeysArePresent() = runBlocking {
    val repository = repository(listOf(newKey("a <a@example.org>"), newKey("b <b@example.org>")))
    val out = ByteArrayOutputStream()

    repository
      .encrypt(
        listOf(PGPIdentifier.UserId("a@example.org"), PGPIdentifier.UserId("b@example.org")),
        "secret".byteInputStream(),
        out,
      )
      .unwrap()

    assertTrue(out.size() > 0)
  }
}
