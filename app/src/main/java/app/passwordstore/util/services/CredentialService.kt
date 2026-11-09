/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.services

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.Action
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import app.passwordstore.R
import app.passwordstore.ui.autofill.AutofillDecryptActivity
import app.passwordstore.ui.autofill.AutofillFilterView
import app.passwordstore.ui.autofill.AutofillSaveActivity
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.makeFillMatchMetadata
import com.github.androidpasswordstore.autofillparser.FormOrigin
import com.github.michaelbull.result.getOrElse
import logcat.LogPriority.WARN
import logcat.logcat

/**
 * Credential Manager provider (Android 14+): apps that save or ask for a password through
 * `CredentialManager` (e.g. Signal's backup key) reach the store here. It reuses the autofill
 * matches, picker, decryption and save activities, keyed by the calling app's package.
 */
@RequiresApi(34)
class CredentialService : CredentialProviderService() {

  private var requestCode = 1

  /**
   * The calling app, or null for a browser asking for a website: its origin would need a
   * privileged-browser allowlist, and autofill already covers browsers.
   */
  private fun appOrigin(info: CallingAppInfo?): FormOrigin.App? =
    info?.takeUnless { it.isOriginPopulated() }?.let { FormOrigin.App(it.packageName) }

  private fun pendingIntent(intent: Intent): PendingIntent =
    PendingIntent.getActivity(
      this,
      requestCode++,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

  override fun onBeginCreateCredentialRequest(
    request: BeginCreateCredentialRequest,
    cancellationSignal: CancellationSignal,
    callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
  ) {
    val origin = appOrigin(request.callingAppInfo)
    if (request !is BeginCreatePasswordCredentialRequest || origin == null) {
      callback.onResult(BeginCreateCredentialResponse())
      return
    }
    // The username and password arrive in the activity's intent once the user picks this entry.
    val entry =
      CreateEntry.Builder(
          applicationInfo.loadLabel(packageManager),
          pendingIntent(Intent(this, AutofillSaveActivity::class.java)),
        )
        .build()
    callback.onResult(BeginCreateCredentialResponse(createEntries = listOf(entry)))
  }

  override fun onBeginGetCredentialRequest(
    request: BeginGetCredentialRequest,
    cancellationSignal: CancellationSignal,
    callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
  ) {
    val origin = appOrigin(request.callingAppInfo)
    val option = request.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>()
    if (origin == null || option.isEmpty()) {
      callback.onResult(BeginGetCredentialResponse())
      return
    }
    // ponytail: a changed publisher gets no entries at all; autofill shows a warning dataset
    // instead. Offer AutofillPublisherChangedActivity here if this ever bites.
    val files =
      AutofillMatcher.getMatchesFor(this, origin).getOrElse {
        logcat(WARN) { it.message.orEmpty() }
        callback.onResult(BeginGetCredentialResponse())
        return
      }
    val entries =
      option.flatMap { opt ->
        files.map { file ->
          val metadata = makeFillMatchMetadata(this, file)
          PasswordCredentialEntry.Builder(
              this,
              metadata.subtitle ?: metadata.title,
              pendingIntent(AutofillDecryptActivity.makeCredentialIntent(file, this)),
              opt,
            )
            .setDisplayName(metadata.title)
            .build()
        }
      }
    val search =
      Action(
        getString(R.string.oreo_autofill_search_in_store),
        pendingIntent(AutofillFilterView.makeCredentialIntent(this, origin)),
      )
    callback.onResult(
      BeginGetCredentialResponse(credentialEntries = entries, actions = listOf(search))
    )
  }

  override fun onClearCredentialStateRequest(
    request: ProviderClearCredentialStateRequest,
    cancellationSignal: CancellationSignal,
    callback: OutcomeReceiver<Void?, ClearCredentialException>,
  ) {
    // Nothing is signed in: there is no state to clear.
    callback.onResult(null)
  }
}
