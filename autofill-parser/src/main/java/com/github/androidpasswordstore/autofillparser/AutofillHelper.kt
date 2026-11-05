/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: LGPL-3.0-only WITH LGPL-3.0-linking-exception
 */
package com.github.androidpasswordstore.autofillparser

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.assist.AssistStructure
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.service.autofill.SaveCallback
import android.util.Base64
import android.view.autofill.AutofillId
import android.widget.Toast
import java.security.MessageDigest
import logcat.LogPriority.ERROR
import logcat.logcat

private fun ByteArray.sha256(): ByteArray {
  return MessageDigest.getInstance("SHA-256").run {
    update(this@sha256)
    digest()
  }
}

private fun ByteArray.base64(): String {
  return Base64.encodeToString(this, Base64.NO_WRAP)
}

private fun stableHash(array: Collection<ByteArray>): String {
  val hashes = array.map { it.sha256().base64() }
  return hashes.sorted().joinToString(separator = ";")
}

/**
 * Computes a stable hash of all certificates associated to the installed app with package name
 * [appPackage].
 *
 * In most cases apps will only have a single certificate. If there are multiple, this functions
 * returns all of them in sorted order and separated with `;`.
 */
public fun computeCertificatesHash(context: Context, appPackage: String): String {
  // The warning does not apply since 1) we are specifically hashing **all** signatures and 2) it
  // no longer applies to Android 4.4+.
  // Even though there is a new way to get the certificates as of Android Pie, we need to keep
  // hashes comparable between versions and hence default to using the deprecated API.
  @SuppressLint("PackageManagerGetSignatures")
  @Suppress("DEPRECATION")
  val signaturesOld =
    context.packageManager
      .getPackageInfo(appPackage, PackageManager.GET_SIGNATURES)
      .signatures
      .orEmpty()
  val stableHashOld = stableHash(signaturesOld.map { it.toByteArray() })
  val info =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.getPackageInfo(
        appPackage,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
      )
    } else {
      context.packageManager.getPackageInfo(appPackage, PackageManager.GET_SIGNING_CERTIFICATES)
    }
  val signaturesNew =
    info.signingInfo?.signingCertificateHistory ?: info.signingInfo?.apkContentsSigners.orEmpty()
  val stableHashNew = stableHash(signaturesNew.map { it.toByteArray() })
  if (stableHashNew != stableHashOld)
    logcat("CertificatesHash", ERROR) {
      "Mismatch between old and new hash: $stableHashNew != $stableHashOld"
    }
  return stableHashOld
}

/**
 * Returns the "origin" (without port information) of the [AssistStructure.ViewNode] derived from
 * its `webDomain` and `webScheme`, if available.
 */
internal val AssistStructure.ViewNode.webOrigin: String?
  get() =
    webDomain?.let { domain ->
      val scheme = webScheme ?: "https"
      "$scheme://$domain"
    }

public class FixedSaveCallback(private val context: Context, private val callback: SaveCallback) {

  private val applicationContext = context.applicationContext

  public fun onFailure(message: CharSequence) {
    callback.onFailure(message)
    // When targeting SDK 29, the message is no longer shown as a toast.
    // See
    // https://developer.android.com/reference/android/service/autofill/SaveCallback#onFailure(java.lang.CharSequence)
    if (applicationContext.applicationInfo.targetSdkVersion >= 29) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
  }

  public fun onSuccess(intentSender: IntentSender) {
    when {
      // From Android 14 the framework sends this intent from the app being filled, which does not
      // opt in to background activity starts when it targets SDK 34 or later: the save window
      // opens hidden behind it. This service is bound by the system, so it may start it itself.
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
        val options =
          ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(
              ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            .toBundle()
        applicationContext.startIntentSender(intentSender, null, 0, 0, 0, options)
        callback.onSuccess()
      }
      else -> callback.onSuccess(intentSender)
    }
  }
}

private fun visitViewNodes(structure: AssistStructure, block: (AssistStructure.ViewNode) -> Unit) {
  for (i in 0 until structure.windowNodeCount) {
    visitViewNode(structure.getWindowNodeAt(i).rootViewNode, block)
  }
}

private fun visitViewNode(
  node: AssistStructure.ViewNode,
  block: (AssistStructure.ViewNode) -> Unit,
) {
  block(node)
  for (i in 0 until node.childCount) {
    visitViewNode(node.getChildAt(i), block)
  }
}

internal fun AssistStructure.findNodeByAutofillId(
  autofillId: AutofillId
): AssistStructure.ViewNode? {
  var node: AssistStructure.ViewNode? = null
  visitViewNodes(this) { if (it.autofillId == autofillId) node = it }
  return node
}
