/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.passwordstore.R
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.passgen.random.MaxIterationsExceededException
import app.passwordstore.passgen.random.NoCharactersIncludedException
import app.passwordstore.passgen.random.PasswordGenerator
import app.passwordstore.passgen.random.PasswordLengthTooShortException
import app.passwordstore.passgen.random.PasswordOption
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.runCatching
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the editor edits. [dir] is the folder relative to the store root ("" is the root), [name]
 * the file name without `.gpg`. Plaintext: held in memory only, never in saved state or a route.
 */
@Immutable
data class EditorForm(
  val name: String = "",
  val dir: String = "",
  val password: String = "",
  val username: String = "",
  val extra: String = "",
)

/** The form for [entry], stored as [name] in [dir], split as the old editor split it. */
fun editorForm(entry: PasswordEntry, dir: String, name: String): EditorForm =
  EditorForm(
    name = name,
    dir = dir,
    password = entry.password.orEmpty(),
    username = entry.username.orEmpty(),
    extra = entry.extraContentWithoutUsername,
  )

/** The pass file, as the old editor wrote it: password, `username: ...` when set, the extras. */
fun passContent(form: EditorForm): String {
  val username = if (form.username.isEmpty()) "" else "\nusername: ${form.username}"
  return "${form.password}$username\n${form.extra}"
}

/** Why [form] cannot be saved, as a string resource, or null when it can. */
@StringRes
fun formError(form: EditorForm): Int? =
  when {
    form.name.isBlank() -> R.string.file_toast_text
    '/' in form.name -> R.string.invalid_filename_text
    form.password.isEmpty() && form.extra.isEmpty() -> R.string.empty_toast_text
    else -> null
  }

/** Where [form] is saved in the store at [root]. */
fun entryFile(root: File, form: EditorForm): File =
  File(File(root, form.dir), "${form.name.trim()}.gpg").toPath().normalize().toFile()

/** True when saving to [target] would replace a file other than the [original] being edited. */
fun overwrites(target: File, original: File?): Boolean =
  target.exists() && target.canonicalPath != original?.canonicalPath

/**
 * Writes [bytes] to [target], creating its folder, through a temporary file moved over it, so a
 * failed write never truncates an existing entry.
 */
fun writeEntry(target: File, bytes: ByteArray) {
  val dir = target.parentFile ?: throw IOException("No folder for $target")
  dir.mkdirs()
  val temp = File(dir, ".${target.name}.tmp")
  try {
    FileOutputStream(temp).use {
      it.write(bytes)
      it.fd.sync()
    }
    Files.move(
      temp.toPath(),
      target.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
  } finally {
    temp.delete()
  }
}

/** [extra] with [line] added on a line of its own. */
fun withLine(extra: String, line: String): String =
  if (extra.isEmpty() || extra.endsWith('\n')) "$extra$line" else "$extra\n$line"

/**
 * An `otpauth://totp` URI, as the old manual OTP dialog built it, for a typed [secret] (spaces
 * dropped: authenticators show it in groups) and an optional [issuer]; null for a blank secret.
 */
fun totpUri(secret: String, issuer: String): String? {
  val key = secret.filterNot(Char::isWhitespace)
  if (key.isEmpty()) return null
  fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
  val tail = if (issuer.isBlank()) "" else "&issuer=${encode(issuer.trim())}"
  return "otpauth://totp?secret=${encode(key)}$tail"
}

/** The random generator's options, as its checkboxes show them; defaults are the old dialog's. */
@Immutable
data class RandomSpec(
  val numerals: Boolean = true,
  val symbols: Boolean = false,
  val uppercase: Boolean = true,
  val lowercase: Boolean = true,
  val ambiguous: Boolean = true,
  val pronounceable: Boolean = false,
  val length: Int = 20,
)

/** The generator's options for this spec, negated where the generator asks what to leave out. */
fun RandomSpec.options(): List<PasswordOption> =
  listOfNotNull(
    PasswordOption.NoDigits.takeUnless { numerals },
    PasswordOption.AtLeastOneSymbol.takeIf { symbols },
    PasswordOption.NoUppercaseLetters.takeUnless { uppercase },
    PasswordOption.NoLowercaseLetters.takeUnless { lowercase },
    PasswordOption.NoAmbiguousCharacters.takeUnless { ambiguous },
    PasswordOption.FullyRandom.takeUnless { pronounceable },
  )

/** A random password for [spec], or the string resource saying why there is none. */
fun randomPassword(spec: RandomSpec): Result<String, Int> =
  runCatching { PasswordGenerator.generate(spec.options(), spec.length) }
    .mapError { e ->
      when (e) {
        is MaxIterationsExceededException -> R.string.pwgen_max_iterations_exceeded
        is NoCharactersIncludedException -> R.string.pwgen_no_chars_error
        is PasswordLengthTooShortException -> R.string.pwgen_length_too_short_error
        else -> R.string.pwgen_some_error_occurred
      }
    }

/** The diceware generator's options; defaults are the old dialog's. */
@Immutable data class DicewareSpec(val words: Int = 5, val separator: Char = '-')
