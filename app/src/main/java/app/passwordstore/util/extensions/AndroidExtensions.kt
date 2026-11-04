/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.extensions

import android.app.KeyguardManager
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.content.pm.PackageManager.PackageInfoFlags
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.autofill.AutofillManager
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.crypto.keystorePreferences
import app.passwordstore.util.git.operation.GitOperation
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.google.android.material.snackbar.Snackbar
import logcat.logcat

/** Get an instance of [AutofillManager]. Only available on Android Oreo and above */
val Context.autofillManager: AutofillManager?
  get() = getSystemService()

/** Get an instance of [ClipboardManager] */
val Context.clipboard
  get() = getSystemService<ClipboardManager>()

/** The Keystore-encrypted preferences holding Git credentials. */
fun Context.getEncryptedGitPrefs() = keystorePreferences(GIT_SECRETS_PREFS)

const val GIT_SECRETS_PREFS = "git_secrets"

/** Get an instance of [KeyguardManager] */
val Context.keyguardManager: KeyguardManager
  get() = getSystemService()!!

/** Get the default [SharedPreferences] instance */
val Context.sharedPrefs: SharedPreferences
  get() = getSharedPreferences("${BuildConfig.APPLICATION_ID}_preferences", 0)

/**
 * Commit changes to the store from a [FragmentActivity] using a custom implementation of
 * [GitOperation]
 */
suspend fun FragmentActivity.commitChange(message: String): Result<Unit, Throwable> {
  if (!PasswordRepository.isInitialized) {
    return Ok(Unit)
  }
  return object : GitOperation(this@commitChange) {
      override val commands =
        arrayOf(
          // Stage all files
          git.add().addFilepattern("."),
          // Populate the changed files count
          git.status(),
          // Commit everything! If anything changed, that is.
          git.commit().setAll(true).setMessage(message),
        )

      override fun preExecute(): Boolean {
        logcat { "Committing with message: '$message'" }
        return true
      }
    }
    .execute()
}

/** Show a [Snackbar] in a [FragmentActivity]. */
fun FragmentActivity.snackbar(
  view: View = findViewById(android.R.id.content),
  message: String,
  length: Int = Snackbar.LENGTH_SHORT,
): Snackbar {
  val snackbar = Snackbar.make(view, message, length)
  snackbar.show()
  return snackbar
}

/** Simplifies the common `getString(key, null) ?: defaultValue` case slightly */
fun SharedPreferences.getString(key: String): String? = getString(key, null)

/** Convert this [String] to its [Base64] representation */
fun String.base64(): String {
  return Base64.encodeToString(encodeToByteArray(), Base64.NO_WRAP)
}

fun PackageManager.getPackageInfoCompat(packageName: String, flags: Int): PackageInfo {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getPackageInfo(packageName, PackageInfoFlags.of(flags.toLong()))
  } else {
    getPackageInfo(packageName, flags)
  }
}

fun PackageManager.getApplicationInfoCompat(packageName: String, flags: Int): ApplicationInfo {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getApplicationInfo(packageName, ApplicationInfoFlags.of(flags.toLong()))
  } else {
    getApplicationInfo(packageName, flags)
  }
}
