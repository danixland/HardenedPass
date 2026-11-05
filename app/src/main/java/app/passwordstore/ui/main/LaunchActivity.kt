/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File

@SuppressLint("CustomSplashScreen")
class LaunchActivity : FragmentActivity() {

  private var prompted = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!sharedPrefs.getBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, false)) startTargetActivity(true)
  }

  // The prompt waits for window focus: Android 14's SystemUI dismisses a prompt shown while its
  // activity is not yet on top (shown from onCreate), with ERROR_CANCELED, closing the app at once.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus || prompted || isFinishing) return
    val prefs = sharedPrefs
    if (prefs.getBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, false)) {
      prompted = true
      BiometricAuthenticator.authenticate(this) { result ->
        when (result) {
          is Result.Success -> {
            startTargetActivity(false)
          }
          is Result.HardwareUnavailableOrDisabled -> {
            prefs.edit { remove(PreferenceKeys.BIOMETRIC_AUTH_2) }
            startTargetActivity(false)
          }
          is Result.Failure,
          Result.CanceledBySystem,
          Result.CanceledByUser -> {
            finish()
          }
          is Result.Retry -> {}
        }
      }
    }
  }

  private fun startTargetActivity(noAuth: Boolean) {
    // This activity is exported, so the file path is untrusted input: only forward it when it
    // points at a file inside the password store. MainActivity is not exported and trusts it.
    val repoDir = PasswordRepository.getRepositoryDirectory()
    val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
    val intentToStart = Intent(this, MainActivity::class.java)
    if (intent.action == ACTION_DECRYPT_PASS && isFileInRepo(filePath, repoDir))
      intentToStart.putExtra(EXTRA_FILE_PATH, filePath)
    // A plain start replaces the task, so an instance already open (one whose store vanished, say)
    // is not left below the new one. An entry opens on top, back returns to where the user was.
    else intentToStart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    startActivity(intentToStart)

    Handler(Looper.getMainLooper()).postDelayed({ finish() }, if (noAuth) 0L else 500L)
  }

  companion object {

    const val ACTION_DECRYPT_PASS = "DECRYPT_PASS"

    /** The entry to open; home screen shortcuts carry this exact key, never change it. */
    const val EXTRA_FILE_PATH = "FILE_PATH"

    /** True when [path] resolves, after following `..` and symlinks, to a file under [repoDir]. */
    fun isFileInRepo(path: String?, repoDir: File): Boolean {
      if (path.isNullOrEmpty()) return false
      val file = File(path).canonicalFile
      return file.isFile && file.startsWith(repoDir.canonicalFile)
    }
  }
}
