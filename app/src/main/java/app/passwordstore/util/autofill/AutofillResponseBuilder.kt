/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.autofill

import android.content.Context
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.AutofillScenario
import com.github.androidpasswordstore.autofillparser.Credentials
import com.github.androidpasswordstore.autofillparser.FillableForm
import com.github.androidpasswordstore.autofillparser.fillWith
import logcat.LogPriority
import logcat.logcat

interface AutofillResponseBuilder {
  fun fillCredentials(context: Context, fillRequest: FillRequest, callback: FillCallback)

  interface Factory {
    fun create(form: FillableForm): AutofillResponseBuilder
  }

  companion object {
    fun makeFillInDataset(
      context: Context,
      credentials: Credentials,
      clientState: Bundle,
      action: AutofillAction,
    ): Dataset {
      val scenario = AutofillScenario.fromClientState(clientState)
      // FIXME: We should clone the original dataset here and add the credentials to be filled
      // in. Otherwise, the entry in the cached list of datasets will be overwritten by the
      // fill-in dataset without any visual representation. This causes it to be missing from
      // the Autofill suggestions shown after the user clears the filled out form fields.
      return Dataset.Builder().run {
        if (scenario != null) fillWith(scenario, action, credentials)
        else logcat(LogPriority.ERROR) { "Failed to recover scenario from client state" }
        build()
      }
    }
  }
}
