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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File

@SuppressLint("CustomSplashScreen")
class LaunchActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = sharedPrefs
    if (prefs.getBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, false)) {
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
    } else {
      startTargetActivity(true)
    }
  }

  private fun startTargetActivity(noAuth: Boolean) {
    // This activity is exported, so the file path is untrusted input: only forward it when it
    // points at a file inside the password store. MainActivity is not exported and trusts it.
    val repoDir = PasswordRepository.getRepositoryDirectory()
    val filePath = intent.getStringExtra(BasePGPActivity.EXTRA_FILE_PATH)
    val intentToStart = Intent(this, MainActivity::class.java)
    if (intent.action == ACTION_DECRYPT_PASS && isFileInRepo(filePath, repoDir))
      intentToStart.putExtra(BasePGPActivity.EXTRA_FILE_PATH, filePath)
    startActivity(intentToStart)

    Handler(Looper.getMainLooper()).postDelayed({ finish() }, if (noAuth) 0L else 500L)
  }

  companion object {

    const val ACTION_DECRYPT_PASS = "DECRYPT_PASS"

    /** True when [path] resolves, after following `..` and symlinks, to a file under [repoDir]. */
    fun isFileInRepo(path: String?, repoDir: File): Boolean {
      if (path.isNullOrEmpty()) return false
      val file = File(path).canonicalFile
      return file.isFile && file.startsWith(repoDir.canonicalFile)
    }
  }
}
