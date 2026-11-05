/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.autofill.FillResponse
import android.text.format.DateUtils
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPublisherChangedException
import app.passwordstore.util.extensions.asLog
import app.passwordstore.util.extensions.getApplicationInfoCompat
import app.passwordstore.util.extensions.getPackageInfoCompat
import com.github.androidpasswordstore.autofillparser.FormOrigin
import com.github.androidpasswordstore.autofillparser.computeCertificatesHash
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.runCatching
import logcat.LogPriority.ERROR
import logcat.logcat

class AutofillPublisherChangedActivity : FragmentActivity() {

  companion object {

    private const val EXTRA_APP_PACKAGE = "app.passwordstore.autofill.oreo.ui.EXTRA_APP_PACKAGE"
    private const val EXTRA_FILL_RESPONSE_AFTER_RESET =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FILL_RESPONSE_AFTER_RESET"
    private var publisherChangedRequestCode = 1

    fun makePublisherChangedIntentSender(
      context: Context,
      publisherChangedException: AutofillPublisherChangedException,
      fillResponseAfterReset: FillResponse?,
    ): IntentSender {
      val intent =
        Intent(context, AutofillPublisherChangedActivity::class.java).apply {
          putExtra(EXTRA_APP_PACKAGE, publisherChangedException.formOrigin.identifier)
          putExtra(EXTRA_FILL_RESPONSE_AFTER_RESET, fillResponseAfterReset)
        }
      return PendingIntent.getActivity(
          context,
          publisherChangedRequestCode++,
          intent,
          PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        .intentSender
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setFinishOnTouchOutside(true)
    // Tapjacking guard: a touch that reaches this window through another app's window (an overlay
    // drawn to trick the user into re-enabling) is dropped. ViewGroup.dispatchTouchEvent checks the
    // decor view's own flag before dispatching to any child, so the ComposeView is covered.
    window.decorView.filterTouchesWhenObscured = true

    val appPackage =
      intent.getStringExtra(EXTRA_APP_PACKAGE)
        ?: run {
          logcat(ERROR) { "AutofillPublisherChangedActivity started without EXTRA_PACKAGE_NAME" }
          finish()
          return
        }
    val (packageInfo, appInfo, hash) =
      runCatching {
          Triple(
            packageManager.getPackageInfoCompat(appPackage, PackageManager.GET_META_DATA),
            packageManager.getApplicationInfoCompat(appPackage, PackageManager.GET_META_DATA),
            computeCertificatesHash(this, appPackage),
          )
        }
        .getOrElse { e ->
          logcat(ERROR) { e.asLog("Failed to retrieve package info for $appPackage") }
          finish()
          return
        }
    val appName =
      getString(
        R.string.oreo_autofill_warning_publisher_app_name,
        packageManager.getApplicationLabel(appInfo),
      )
    val installed =
      getString(
        R.string.oreo_autofill_warning_publisher_install_time,
        DateUtils.getRelativeTimeSpanString(packageInfo.firstInstallTime),
      )
    val details =
      getString(R.string.oreo_autofill_warning_publisher_advanced_info_template, appPackage, hash)
    setContent {
      APSTheme {
        PublisherChangedScreen(
          appName = appName,
          installed = installed,
          details = details,
          onKeepDisabled = { finish() },
          onReset = { reset(appPackage) },
        )
      }
    }
  }

  /** Forgets the app's matches and fills with the response the service prepared for this case. */
  private fun reset(appPackage: String) {
    AutofillMatcher.clearMatchesFor(this, FormOrigin.App(appPackage))
    val fillResponse =
      IntentCompat.getParcelableExtra(
        intent,
        EXTRA_FILL_RESPONSE_AFTER_RESET,
        FillResponse::class.java,
      )
    setResult(
      RESULT_OK,
      Intent().apply { putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillResponse) },
    )
    finish()
  }
}
