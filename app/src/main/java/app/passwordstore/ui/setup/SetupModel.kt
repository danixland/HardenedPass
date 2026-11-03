/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.util.settings.AuthMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.Locale

/** Why a repository URL is questioned before it is saved, as the old server screen checked it. */
enum class UrlProblem {
  /** An HTTPS URL with a custom port, which the old screen did not support. */
  HttpsPort,
  /** An scp-style SSH URL with a port: without `ssh://` the port is read as part of the path. */
  SshScheme,
  /** The unencrypted `git://` protocol. */
  GitScheme,
}

private val PORT = ":[0-9]{1,5}/".toRegex()

private fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

/** What is wrong with [url], or null. `git://` is checked first: a port does not make it SSH. */
fun urlProblem(url: String): UrlProblem? =
  when {
    url.startsWith("git://") -> UrlProblem.GitScheme
    !url.contains(PORT) -> null
    url.startsWith("https://") -> UrlProblem.HttpsPort
    url.startsWith("http://") || url.startsWith("ssh://") -> null
    else -> UrlProblem.SshScheme
  }

/** The old screen's fix for [problem] in [url], or null when there is none (`git://`). */
fun fixUrl(url: String, problem: UrlProblem): String? =
  when (problem) {
    UrlProblem.HttpsPort -> url.replace(PORT, "/")
    UrlProblem.SshScheme -> "ssh://$url"
    UrlProblem.GitScheme -> null
  }

/** The authentication modes offered for [url], the default first. */
fun authModes(url: String): List<AuthMode> =
  if (isHttp(url)) listOf(AuthMode.None, AuthMode.Password)
  else listOf(AuthMode.SshKey, AuthMode.Password)

/** [current] when [url] still allows it, the default for [url] otherwise. */
fun fitAuthMode(url: String, current: AuthMode): AuthMode =
  authModes(url).let { modes -> if (current in modes) current else modes.first() }

/** What a clone into the store folder finds there. */
enum class CloneTarget {
  /** Missing or empty. */
  Empty,
  /** Only a `.git` folder, from a store created and abandoned: deleted without asking. */
  OnlyGit,
  /** Anything else: deleted after a confirmation. */
  Occupied,
}

fun cloneTarget(dir: File): CloneTarget {
  val files = dir.listFiles().orEmpty()
  return when {
    files.isEmpty() -> CloneTarget.Empty
    files.size == 1 && files[0].name == ".git" -> CloneTarget.OnlyGit
    else -> CloneTarget.Occupied
  }
}

/**
 * The recipients named by a `.gpg-id` file's [text], read as pass reads it: a `#` starts a comment,
 * and a trailing `!` (GnuPG's "this exact subkey") is dropped. Blank and unparsable lines are
 * skipped.
 */
fun storeRecipients(text: String): List<PGPIdentifier> =
  text
    .lines()
    .map { it.substringBefore('#').trim().removeSuffix("!") }
    .filter(String::isNotEmpty)
    .mapNotNull(PGPIdentifier::fromString)

/**
 * A `.gpg-id` naming [keys]: each 16-hex long key ID in uppercase on a line of its own, the format
 * of a desktop store made with `pass init <key ID>`.
 */
fun gpgIdText(keys: List<PGPIdentifier.KeyId>): String =
  keys.joinToString("") { "${it.toString().uppercase(Locale.ROOT)}\n" }

private const val MAX_GPG_ID_BYTES = 64 * 1024

/**
 * The text of [dir]'s `.gpg-id`, or null when it is missing, not a regular file, a symlink, larger
 * than 64 KiB or unreadable. Blocking: call it off the main thread.
 */
fun readGpgId(dir: File): String? {
  val path = File(dir, ".gpg-id").toPath()
  if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
  // Opened without following a link too: one swapped in after the check is refused.
  val options = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
  return try {
    Channels.newInputStream(Files.newByteChannel(path, options))
      .use { readAtMost(it, MAX_GPG_ID_BYTES) }
      ?.decodeToString()
  } catch (_: IOException) {
    null
  }
}

/**
 * At most [limit] bytes of [input], or null when it holds more. Reads at most [limit] + 1 bytes,
 * whatever the stream's size, and zeroes its buffer: the bytes may be a private key.
 */
fun readAtMost(input: InputStream, limit: Int): ByteArray? {
  val buffer = ByteArray(limit + 1)
  try {
    var size = 0
    while (size < buffer.size) {
      val read = input.read(buffer, size, buffer.size - size)
      if (read < 0) break
      size += read
    }
    return if (size > limit) null else buffer.copyOf(size)
  } finally {
    buffer.fill(0)
  }
}
