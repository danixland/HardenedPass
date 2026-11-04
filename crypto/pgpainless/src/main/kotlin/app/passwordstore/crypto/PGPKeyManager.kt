/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
@file:Suppress("BlockingMethodInNonBlockingContext")

package app.passwordstore.crypto

import androidx.annotation.VisibleForTesting
import app.passwordstore.crypto.KeyUtils.isKeyUsable
import app.passwordstore.crypto.KeyUtils.tryGetId
import app.passwordstore.crypto.KeyUtils.tryParseKeyring
import app.passwordstore.crypto.errors.InvalidKeyException
import app.passwordstore.crypto.errors.KeyAlreadyExistsException
import app.passwordstore.crypto.errors.KeyDeletionFailedException
import app.passwordstore.crypto.errors.KeyDirectoryUnavailableException
import app.passwordstore.crypto.errors.KeyDowngradeException
import app.passwordstore.crypto.errors.KeyNotFoundException
import app.passwordstore.crypto.errors.NoKeysAvailableException
import app.passwordstore.crypto.errors.UnusableKeyException
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.runSuspendCatching
import com.github.michaelbull.result.unwrap
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.pgpainless.PGPainless
import org.pgpainless.util.selection.userid.SelectUserId

public class PGPKeyManager
@Inject
constructor(filesDir: String, private val dispatcher: CoroutineDispatcher) :
  KeyManager<PGPKey, PGPIdentifier> {

  private val keyDir = File(filesDir, KEY_DIR_NAME)

  /** @see KeyManager.addKey */
  override suspend fun addKey(key: PGPKey, replace: Boolean): Result<PGPKey, Throwable> =
    withContext(dispatcher) {
      runSuspendCatching {
        if (!keyDirExists()) throw KeyDirectoryUnavailableException
        val incomingKeyRing = tryParseKeyring(key) ?: throw InvalidKeyException
        if (!isKeyUsable(key)) throw UnusableKeyException
        val keyFile = File(keyDir, "${tryGetId(key)}.$KEY_EXTENSION")
        if (keyFile.exists()) {
          val existingKeyBytes = keyFile.readBytes()
          val existingKeyRing =
            tryParseKeyring(PGPKey(existingKeyBytes)) ?: throw InvalidKeyException
          when {
            existingKeyRing is PGPPublicKeyRing && incomingKeyRing is PGPSecretKeyRing -> {
              writeAtomically(keyFile, key.contents)
              return@runSuspendCatching key
            }
            existingKeyRing is PGPPublicKeyRing && incomingKeyRing is PGPPublicKeyRing -> {
              val updatedPublicKey = PGPainless.mergeCertificate(existingKeyRing, incomingKeyRing)
              val keyBytes = PGPainless.asciiArmor(updatedPublicKey).encodeToByteArray()
              writeAtomically(keyFile, keyBytes)
              return@runSuspendCatching key
            }
            // Never with replace either: it would delete the only copy of the private key.
            existingKeyRing is PGPSecretKeyRing && incomingKeyRing is PGPPublicKeyRing ->
              throw KeyDowngradeException(tryGetId(key)?.toString() ?: "Failed to retrieve key ID")
          }
          // Check for replace flag first and if it is false, throw an error
          if (!replace)
            throw KeyAlreadyExistsException(
              tryGetId(key)?.toString() ?: "Failed to retrieve key ID"
            )
        }

        writeAtomically(keyFile, key.contents)

        key
      }
    }

  /** @see KeyManager.removeKey */
  override suspend fun removeKey(identifier: PGPIdentifier): Result<Unit, Throwable> =
    withContext(dispatcher) {
      runSuspendCatching {
        if (!keyDirExists()) throw KeyDirectoryUnavailableException
        val key = getKeyById(identifier).unwrap()
        val keyFile = File(keyDir, "${tryGetId(key)}.$KEY_EXTENSION")
        if (keyFile.exists()) {
          if (!keyFile.delete()) throw KeyDeletionFailedException
        }
      }
    }

  /** @see KeyManager.getKeyById */
  override suspend fun getKeyById(id: PGPIdentifier): Result<PGPKey, Throwable> =
    withContext(dispatcher) {
      runSuspendCatching {
        if (!keyDirExists()) throw KeyDirectoryUnavailableException
        val keyFiles = keyDir.listFiles { file -> file.extension == KEY_EXTENSION }
        if (keyFiles.isNullOrEmpty()) throw NoKeysAvailableException
        val keys = keyFiles.map { file -> PGPKey(file.readBytes()) }

        val matchResult =
          when (id) {
            is PGPIdentifier.KeyId -> {
              val keyIdMatch =
                keys
                  .map { key -> key to tryGetId(key) }
                  .firstOrNull { (_, keyId) -> keyId?.id == id.id }
              keyIdMatch?.first
            }
            is PGPIdentifier.UserId -> {
              val selector = SelectUserId.byEmail(id.email)
              val userIdMatch =
                keys
                  .map { key -> key to tryParseKeyring(key) }
                  .firstOrNull { (_, keyRing) ->
                    keyRing?.publicKey?.userIDs?.asSequence()?.any(selector) == true
                  }
              userIdMatch?.first
            }
          }

        if (matchResult != null) {
          return@runSuspendCatching matchResult
        }

        throw KeyNotFoundException("$id")
      }
    }

  /** @see KeyManager.getAllKeys */
  override suspend fun getAllKeys(): Result<List<PGPKey>, Throwable> =
    withContext(dispatcher) {
      runSuspendCatching {
        if (!keyDirExists()) throw KeyDirectoryUnavailableException
        val keyFiles = keyDir.listFiles { file -> file.extension == KEY_EXTENSION }
        if (keyFiles.isNullOrEmpty()) return@runSuspendCatching emptyList()
        keyFiles.map { keyFile -> PGPKey(keyFile.readBytes()) }.toList()
      }
    }

  /** @see KeyManager.getKeyById */
  override suspend fun getKeyId(key: PGPKey): PGPIdentifier? = tryGetId(key)

  /**
   * Writes [bytes] to a temporary file next to [file] and moves it over [file] in one step, so a
   * failed write keeps the key that was there.
   */
  private fun writeAtomically(file: File, bytes: ByteArray) {
    val temp = File(file.parentFile, ".${file.name}.tmp")
    try {
      temp.writeBytes(bytes)
      Files.move(
        temp.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      temp.delete()
    }
  }

  /** Checks if [keyDir] exists and attempts to create it if not. */
  private fun keyDirExists(): Boolean {
    return keyDir.exists() || keyDir.mkdirs()
  }

  public companion object {

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal const val KEY_DIR_NAME: String = "keys"
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal const val KEY_EXTENSION: String = "key"
  }
}
