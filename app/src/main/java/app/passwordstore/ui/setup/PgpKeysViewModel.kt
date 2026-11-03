/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPKey
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.crypto.errors.KeyAlreadyExistsException
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.coroutines.DispatcherProvider
import com.github.michaelbull.result.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException

/** One imported key: its first user ID, when it has one, and its long key ID. */
@Immutable data class KeyRow(val userId: String?, val keyId: PGPIdentifier.KeyId)

/** The outcome of a PGP key import, shown until dismissed. */
sealed interface KeyImport {
  data class Imported(val keyId: String) : KeyImport

  /** A key with the same ID is imported already: the screen asks whether to replace it. */
  data object Exists : KeyImport

  data class Failed(val message: String?) : KeyImport
}

/**
 * The PGP keys screen's state and work: the imported [keys], import with the replace question,
 * deletion by key ID, and the creation of a new local store for a chosen key. Holds no Context;
 * calls take one and use the application context.
 */
@HiltViewModel
class PgpKeysViewModel
@Inject
constructor(
  private val keyManager: PGPKeyManager,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var keys: ImmutableList<KeyRow> by mutableStateOf(persistentListOf())
    private set

  /** Set while an import or a store creation runs: further taps wait. */
  var busy by mutableStateOf(false)

  var importResult by mutableStateOf<KeyImport?>(null)
    private set

  /** Set once a new store is created and committed: the screen moves on. */
  var done by mutableStateOf(false)
    private set

  /** The root `.gpg-id` recipients, for the `Need` screen; see [loadRecipients]. */
  var recipients: ImmutableList<String> by mutableStateOf(persistentListOf())
    private set

  /**
   * The picked file while the replace question is open. Memory only, zeroed as soon as it is not
   * needed ([dropPending]): it may be a private key.
   */
  private var pending: ByteArray? = null

  init {
    updateKeySet()
  }

  /** Reads the recipients of the store at [root] off the main thread. */
  fun loadRecipients(root: File) {
    viewModelScope.launch {
      recipients =
        withContext(dispatcherProvider.io()) {
            readGpgId(root)?.let(::storeRecipients).orEmpty().map(PGPIdentifier::toString)
          }
          .toPersistentList()
    }
  }

  private fun dropPending() {
    pending?.fill(0)
    pending = null
  }

  fun updateKeySet() {
    viewModelScope.launch {
      keyManager.getAllKeys().onSuccess { all ->
        keys =
          withContext(dispatcherProvider.default()) { all.mapNotNull(::row) }.toPersistentList()
      }
    }
  }

  private fun row(key: PGPKey): KeyRow? {
    val id = KeyUtils.tryGetId(key) ?: return null
    return KeyRow(KeyUtils.tryGetEmail(key)?.email, id)
  }

  fun deleteKey(row: KeyRow) {
    viewModelScope.launch {
      keyManager.removeKey(row.keyId)
      updateKeySet()
    }
  }

  /** Reads the key file at [uri] and imports it. */
  fun import(uri: Uri, context: Context) {
    if (busy) return
    busy = true
    val resolver = context.applicationContext.contentResolver
    viewModelScope.launch {
      try {
        val bytes =
          withContext(dispatcherProvider.io()) {
            resolver.openInputStream(uri)?.use { it.readBytes() }
          }
        if (bytes == null) importResult = KeyImport.Failed(null) else add(bytes, replace = false)
      } catch (e: IOException) {
        importResult = KeyImport.Failed(e.message)
      } catch (e: SecurityException) {
        importResult = KeyImport.Failed(e.message)
      } finally {
        busy = false
      }
    }
  }

  /** Imports the key the replace question was about, over the one with its ID. */
  fun replace() {
    val bytes = pending ?: return
    pending = null
    importResult = null
    busy = true
    viewModelScope.launch {
      try {
        add(bytes, replace = true)
      } finally {
        busy = false
      }
    }
  }

  fun dismissImport() {
    dropPending()
    importResult = null
  }

  /** Imports [bytes]; they are zeroed afterwards unless the replace question keeps them. */
  private suspend fun add(bytes: ByteArray, replace: Boolean) {
    val result = keyManager.addKey(PGPKey(bytes), replace)
    if (!replace && result.isErr && result.error is KeyAlreadyExistsException) {
      pending = bytes
      importResult = KeyImport.Exists
      return
    }
    // The ID is read before the bytes are zeroed: the returned key shares them.
    importResult =
      if (result.isOk) {
        val id = withContext(dispatcherProvider.default()) { KeyUtils.tryGetId(result.value) }
        KeyImport.Imported(id?.toString().orEmpty())
      } else KeyImport.Failed(result.error.message)
    bytes.fill(0)
    if (result.isOk) updateKeySet()
  }

  /**
   * Creates the local store at [root] for [row]: a git repository and a `.gpg-id` naming the key by
   * its uppercase long key ID ([gpgIdText]). Returns null, or why it failed.
   */
  suspend fun createStore(root: File, row: KeyRow): String? =
    withContext(dispatcherProvider.io()) {
      try {
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        PasswordRepository.createRepository(root)
        File(root, ".gpg-id").writeText(gpgIdText(listOf(row.keyId)))
        null
      } catch (e: IOException) {
        failed(root, e)
      } catch (e: GitAPIException) {
        failed(root, e)
      } catch (e: JGitInternalException) {
        failed(root, e)
      } catch (e: IllegalStateException) {
        failed(root, e)
      }
    }

  private fun failed(root: File, e: Exception): String {
    logcat(ERROR) { e.asLog() }
    // Only removes the folder while it is still empty, as the old onboarding did.
    root.delete()
    return e.message.orEmpty()
  }

  fun markDone() {
    done = true
  }

  override fun onCleared() {
    dropPending()
  }
}
