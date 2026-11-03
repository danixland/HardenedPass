/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.injection.prefs.PasswordGeneratorPreferences
import app.passwordstore.passgen.diceware.DicewarePassphraseGenerator
import app.passwordstore.passgen.random.PasswordOption
import app.passwordstore.ui.list.moveHistory
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.runCatching
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/** What a save did. */
sealed interface SaveResult {

  /**
   * Written to [file] from [form]. [staleOriginal] is the old file of a rename or move, left behind
   * because it could not be deleted.
   */
  data class Saved(val file: File, val form: EditorForm, val staleOriginal: File?) : SaveResult

  /** Nothing was written; [message] (a string resource, formatted with [arg]) says why. */
  data class Failed(@StringRes val message: Int, val arg: String = "") : SaveResult
}

/**
 * The editor's state and work: the [form] (plaintext, in memory only: never in saved state, reset
 * when this ViewModel is cleared), the generators and their stored options, QR decoding, and the
 * save (encrypt, write, replace the original). Holds no Context; calls take one and use the
 * application context.
 */
@HiltViewModel
class EditorViewModel
@Inject
constructor(
  private val repository: CryptoRepository,
  private val entryFactory: PasswordEntry.Factory,
  private val diceware: dagger.Lazy<DicewarePassphraseGenerator>,
  @PasswordGeneratorPreferences private val pwgenPrefs: SharedPreferences,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  /** The fields being edited. */
  var form by mutableStateOf(EditorForm())

  /**
   * Set while a save runs, and after one succeeded (the editor is leaving): saves and closes wait.
   */
  var saving by mutableStateOf(false)
    private set

  /** Whether [start] filled the form. */
  var started = false
    private set

  /** Fills the form the first time only and returns true then; a recreated activity keeps edits. */
  fun start(initial: EditorForm): Boolean {
    if (started) return false
    started = true
    form = initial
    return true
  }

  /**
   * True when [extra] already holds a TOTP secret (the OTP actions hide), as the old editor did.
   */
  fun hasOtp(extra: String): Boolean =
    entryFactory.create("PLACEHOLDER\n$extra".encodeToByteArray()).hasTotp()

  /** The random generator's options as the old dialog stored them. */
  fun randomSpec(context: Context): RandomSpec {
    val prefs = context.randomPrefs()
    return RandomSpec(
      numerals = !prefs.getBoolean(PasswordOption.NoDigits.key, false),
      symbols = prefs.getBoolean(PasswordOption.AtLeastOneSymbol.key, false),
      uppercase = !prefs.getBoolean(PasswordOption.NoUppercaseLetters.key, false),
      lowercase = !prefs.getBoolean(PasswordOption.NoLowercaseLetters.key, false),
      ambiguous = !prefs.getBoolean(PasswordOption.NoAmbiguousCharacters.key, false),
      pronounceable = !prefs.getBoolean(PasswordOption.FullyRandom.key, true),
      length = prefs.getInt(PreferenceKeys.LENGTH, 20),
    )
  }

  /**
   * Stores [spec], as the old dialog did on every generation, then generates off the main thread.
   */
  suspend fun generateRandom(spec: RandomSpec, context: Context): Result<String, Int> {
    val options = spec.options()
    context.randomPrefs().edit {
      PasswordOption.entries.forEach { putBoolean(it.key, it in options) }
      putInt(PreferenceKeys.LENGTH, spec.length)
    }
    return withContext(dispatcherProvider.default()) { randomPassword(spec) }
  }

  /** The diceware generator's options as the old dialog stored them. */
  fun dicewareSpec(): DicewareSpec =
    DicewareSpec(
      words = pwgenPrefs.getInt(PreferenceKeys.DICEWARE_LENGTH, 5),
      separator = pwgenPrefs.getString(PreferenceKeys.DICEWARE_SEPARATOR)?.firstOrNull() ?: '-',
    )

  /** Stores [spec] and generates a passphrase; the word list is read on first use, off main. */
  suspend fun generateDiceware(spec: DicewareSpec): String {
    pwgenPrefs.edit {
      putInt(PreferenceKeys.DICEWARE_LENGTH, spec.words)
      putString(PreferenceKeys.DICEWARE_SEPARATOR, spec.separator.toString())
    }
    return withContext(dispatcherProvider.io()) {
      diceware.get().generatePassphrase(spec.words, spec.separator)
    }
  }

  /** The text of the QR code in the image at [uri], or null when it has none. Off main. */
  suspend fun readQr(uri: Uri, context: Context): String? {
    val resolver = context.applicationContext.contentResolver
    return withContext(dispatcherProvider.io()) {
      runCatching {
          val bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri))
                .copy(Bitmap.Config.ARGB_8888, false)
            } else {
              @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(resolver, uri)
            }
          val pixels = IntArray(bitmap.width * bitmap.height)
          bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
          val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
          QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        }
        .get()
    }
  }

  /**
   * Encrypts the form to [ids] and writes it to [target], replacing [original] when the entry was
   * renamed or moved. Nothing is written (not even the folder) when [target] would replace another
   * entry or when encryption fails; a recipient whose key is missing fails it (see
   * CryptoRepository.encrypt). The caller checked [formError] and that [target] is inside the
   * store. Returns null when a save already ran or is running.
   */
  suspend fun save(
    ids: List<PGPIdentifier>,
    target: File,
    original: File?,
    context: Context,
  ): SaveResult? {
    if (saving) return null
    saving = true
    var result: SaveResult? = null
    try {
      result = write(ids, target, original, context.applicationContext)
    } finally {
      // Stays set after a success (the editor is leaving); cleared on failure or cancellation.
      if (result !is SaveResult.Saved) saving = false
    }
    return result
  }

  override fun onCleared() {
    form = EditorForm()
  }

  private suspend fun write(
    ids: List<PGPIdentifier>,
    target: File,
    original: File?,
    app: Context,
  ): SaveResult {
    val form = form
    if (withContext(dispatcherProvider.io()) { overwrites(target, original) })
      return SaveResult.Failed(R.string.password_creation_duplicate_error)
    val out = ByteArrayOutputStream()
    val encrypted = repository.encrypt(ids, passContent(form).byteInputStream(), out)
    if (!encrypted.isOk) {
      logcat(ERROR) { encrypted.error.asLog() }
      return SaveResult.Failed(
        R.string.password_creation_encrypt_fail_message,
        encrypted.error.message.orEmpty(),
      )
    }
    return withContext(dispatcherProvider.io()) {
      try {
        writeEntry(target, out.toByteArray())
      } catch (e: IOException) {
        logcat(ERROR) { e.asLog() }
        return@withContext SaveResult.Failed(R.string.password_creation_file_write_fail_message)
      }
      var stale: File? = null
      if (original != null && original.canonicalPath != target.canonicalPath) {
        if (original.delete() || !original.exists()) {
          AutofillMatcher.updateMatches(app, moveFromTo = mapOf(original to target))
          app.moveHistory(original, target)
        } else {
          stale = original
        }
      }
      SaveResult.Saved(target, form, stale)
    }
  }

  private fun Context.randomPrefs(): SharedPreferences =
    getSharedPreferences(RANDOM_PREFS, Context.MODE_PRIVATE)

  private companion object {
    /** The old random generator dialog's preferences file. */
    const val RANDOM_PREFS = "PasswordGenerator"
  }
}
