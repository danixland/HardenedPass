/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git.operation

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.injection.prefs.GitPreferences
import app.passwordstore.ui.prompts.Prompts
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.git.sshj.InteractivePasswordFinder
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.PreferenceKeys
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class CredentialFinder(
  private val callingActivity: FragmentActivity,
  private val prompts: Prompts,
  private val authMode: AuthMode,
  dispatcherProvider: DispatcherProvider,
) : InteractivePasswordFinder(dispatcherProvider) {

  private val hiltEntryPoint =
    EntryPointAccessors.fromApplication(
      callingActivity.applicationContext,
      CredentialFinderEntryPoint::class.java,
    )

  override fun askForPassword(cont: Continuation<String?>, isRetry: Boolean) {
    val gitOperationPrefs = hiltEntryPoint.gitPrefs()
    val credentialPref: String
    @StringRes val messageRes: Int
    @StringRes val hintRes: Int
    @StringRes val rememberRes: Int
    @StringRes val errorRes: Int
    when (authMode) {
      AuthMode.SshKey -> {
        credentialPref = PreferenceKeys.SSH_KEY_LOCAL_PASSPHRASE
        messageRes = R.string.passphrase_dialog_text
        hintRes = R.string.ssh_keygen_passphrase
        rememberRes = R.string.git_operation_remember_passphrase
        errorRes = R.string.git_operation_wrong_passphrase
      }
      AuthMode.Password -> {
        // Could be either an SSH or an HTTPS password
        credentialPref = PreferenceKeys.HTTPS_PASSWORD
        messageRes = R.string.password_dialog_text
        hintRes = R.string.git_operation_hint_password
        rememberRes = R.string.git_operation_remember_password
        errorRes = R.string.git_operation_wrong_password
      }
      else ->
        throw IllegalStateException("Only SshKey and Password connection mode ask for passwords")
    }
    if (isRetry) gitOperationPrefs.edit { remove(credentialPref) }
    val storedCredential = gitOperationPrefs.getString(credentialPref, null)
    if (storedCredential == null) {
      val resources = callingActivity.resources
      prompts.askCredential(
        message = resources.getString(messageRes),
        hint = resources.getString(hintRes),
        remember = resources.getString(rememberRes),
        error = if (isRetry) resources.getString(errorRes) else null,
      ) { answer ->
        if (answer?.remember == true) {
          gitOperationPrefs.edit { putString(credentialPref, answer.text) }
        }
        cont.resume(answer?.text)
      }
    } else {
      cont.resume(storedCredential)
    }
  }

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface CredentialFinderEntryPoint {
    @GitPreferences fun gitPrefs(): SharedPreferences
  }
}
