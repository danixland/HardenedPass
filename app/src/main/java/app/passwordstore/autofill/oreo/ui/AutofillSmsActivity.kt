/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.autofill.oreo.ui

import android.content.Context
import android.content.IntentSender
import androidx.appcompat.app.AppCompatActivity

// ponytail: filling OTPs from SMS needed Play Services (removed with the nonFree flavor), so
// this stub keeps the feature off. Delete it together with the FillOtpFromSms autofill paths.
@Suppress("UNUSED_PARAMETER")
class AutofillSmsActivity : AppCompatActivity() {

  companion object {

    fun shouldOfferFillFromSms(context: Context): Boolean {
      return false
    }

    fun makeFillOtpFromSmsIntentSender(context: Context): IntentSender {
      throw NotImplementedError("Filling OTPs from SMS requires non-free dependencies")
    }
  }
}
