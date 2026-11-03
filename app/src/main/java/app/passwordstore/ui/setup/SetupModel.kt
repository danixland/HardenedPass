/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.util.settings.AuthMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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

/** The recipients named by a `.gpg-id` file's [text]; blank and unparsable lines are skipped. */
fun storeRecipients(text: String): List<PGPIdentifier> =
  text.lines().map(String::trim).filter(String::isNotEmpty).mapNotNull(PGPIdentifier::fromString)

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
  val file = File(dir, ".gpg-id")
  val path = file.toPath()
  return try {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) null
    else if (Files.size(path) > MAX_GPG_ID_BYTES) null else file.readText()
  } catch (_: IOException) {
    null
  }
}
