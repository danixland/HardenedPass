/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.core.os.bundleOf
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.editor.EditorDestination
import app.passwordstore.ui.editor.EditorForm
import app.passwordstore.ui.editor.SaveResult
import app.passwordstore.ui.editor.passContent
import app.passwordstore.ui.prompts.PromptHost
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.autofill.AutofillResponseBuilder
import app.passwordstore.util.extensions.unsafeLazy
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.Credentials
import com.github.androidpasswordstore.autofillparser.FormOrigin
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import logcat.LogPriority.ERROR
import logcat.logcat

/**
 * Saves credentials the autofill service caught (or generates a password for a form) in the Compose
 * editor, prefilled from the form's origin, then records the autofill match and, for a generated
 * password, fills it in.
 */
@AndroidEntryPoint
class AutofillSaveActivity : FragmentActivity() {

  companion object {

    private const val EXTRA_FOLDER_NAME = "app.passwordstore.autofill.oreo.ui.EXTRA_FOLDER_NAME"
    private const val EXTRA_PASSWORD = "app.passwordstore.autofill.oreo.ui.EXTRA_PASSWORD"
    private const val EXTRA_NAME = "app.passwordstore.autofill.oreo.ui.EXTRA_NAME"
    private const val EXTRA_SHOULD_MATCH_APP =
      "app.passwordstore.autofill.oreo.ui.EXTRA_SHOULD_MATCH_APP"
    private const val EXTRA_SHOULD_MATCH_WEB =
      "app.passwordstore.autofill.oreo.ui.EXTRA_SHOULD_MATCH_WEB"
    private const val EXTRA_GENERATE_PASSWORD =
      "app.passwordstore.autofill.oreo.ui.EXTRA_GENERATE_PASSWORD"

    private var saveRequestCode = 1

    fun makeSaveIntentSender(
      context: Context,
      credentials: Credentials?,
      formOrigin: FormOrigin,
    ): IntentSender {
      val intent =
        Intent(context, AutofillSaveActivity::class.java)
          .putExtras(saveExtras(context, credentials, formOrigin))
      return PendingIntent.getActivity(
          context,
          saveRequestCode++,
          intent,
          PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        .intentSender
    }

    /** The editor's prefill: folder and name from [formOrigin] and the username. */
    private fun saveExtras(
      context: Context,
      credentials: Credentials?,
      formOrigin: FormOrigin,
    ): Bundle {
      val identifier = formOrigin.getPrettyIdentifier(context, untrusted = false)
      // Prevent directory traversals
      val sanitizedIdentifier =
        identifier.replace('\\', '_').replace('/', '_').trimStart('.').takeUnless { it.isBlank() }
          ?: formOrigin.identifier
      val directoryStructure = AutofillPreferences.directoryStructure(context)
      val folderName =
        directoryStructure.getSaveFolderName(
          sanitizedIdentifier = sanitizedIdentifier,
          username = credentials?.username,
        )
      val fileName =
        directoryStructure.getSaveFileName(
          username = credentials?.username,
          identifier = identifier,
        )
      return bundleOf(
        EXTRA_FOLDER_NAME to folderName,
        EXTRA_NAME to fileName,
        EXTRA_PASSWORD to credentials?.password,
        EXTRA_SHOULD_MATCH_APP to formOrigin.identifier.takeIf { formOrigin is FormOrigin.App },
        EXTRA_SHOULD_MATCH_WEB to formOrigin.identifier.takeIf { formOrigin is FormOrigin.Web },
        EXTRA_GENERATE_PASSWORD to (credentials == null),
      )
    }
  }

  private val formOrigin by unsafeLazy {
    val shouldMatchApp: String? = intent.getStringExtra(EXTRA_SHOULD_MATCH_APP)
    val shouldMatchWeb: String? = intent.getStringExtra(EXTRA_SHOULD_MATCH_WEB)
    if (shouldMatchApp != null && shouldMatchWeb == null) {
      FormOrigin.App(shouldMatchApp)
    } else if (shouldMatchApp == null && shouldMatchWeb != null) {
      FormOrigin.Web(shouldMatchWeb)
    } else {
      null
    }
  }

  private val generate by unsafeLazy { intent.getBooleanExtra(EXTRA_GENERATE_PASSWORD, false) }

  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory

  /** Set when Credential Manager asked to save a password, which then answers it. */
  private var fromCredentialManager = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= 34) {
      PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)?.let { request ->
        val create =
          request.callingRequest as? CreatePasswordRequest
            ?: run {
              finish()
              return
            }
        fromCredentialManager = true
        intent.putExtras(
          saveExtras(
            this,
            Credentials(create.id, create.password, null),
            FormOrigin.App(request.callingAppInfo.packageName),
          )
        )
      }
    }
    // The whole window is the editor: keep it out of screenshots and recents.
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val initial =
      EditorForm(
        name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
        dir = intent.getStringExtra(EXTRA_FOLDER_NAME).orEmpty().trim('/'),
        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
      )
    setContent {
      APSTheme {
        EditorDestination(
          activity = this,
          initial = initial,
          original = null,
          onClose = {
            setResult(RESULT_CANCELED)
            finish()
          },
          onSaved = ::finishSaved,
          generate = generate,
        )
        PromptHost(this@AutofillSaveActivity)
      }
    }
  }

  /** Records the autofill match and, for a generated password, returns it to fill the form. */
  private fun finishSaved(saved: SaveResult.Saved) {
    formOrigin?.let { AutofillMatcher.addMatchFor(this, it, saved.file) }
    val resultIntent =
      if (fromCredentialManager && Build.VERSION.SDK_INT >= 34) {
        Intent().also {
          PendingIntentHandler.setCreateCredentialResponse(it, CreatePasswordResponse())
        }
      } else if (generate) {
        val clientState =
          intent?.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
            ?: run {
              logcat(ERROR) { "AutofillSaveActivity started without EXTRA_CLIENT_STATE" }
              finish()
              return
            }
        // Read back from the saved content, as the old editor did, so a username in the extras
        // counts too.
        val entry = passwordEntryFactory.create(passContent(saved.form).encodeToByteArray())
        val username =
          entry.username ?: AutofillPreferences.directoryStructure(this).getUsernameFor(saved.file)
        val credentials = Credentials(username, entry.password, null)
        val fillInDataset =
          AutofillResponseBuilder.makeFillInDataset(
            this,
            credentials,
            clientState,
            AutofillAction.Generate,
          )
        Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillInDataset)
      } else {
        // The password came from the form: there is nothing to fill.
        Intent()
      }
    setResult(RESULT_OK, resultIntent)
    finish()
  }
}
