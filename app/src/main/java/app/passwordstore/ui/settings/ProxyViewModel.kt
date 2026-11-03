/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.net.InetAddresses
import android.os.Build
import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.passwordstore.util.proxy.ProxyUtils
import app.passwordstore.util.settings.GitSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** A numeric address or a web host name, as the old proxy screen accepted. */
fun isProxyHost(text: String): Boolean {
  val numeric =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) InetAddresses.isNumericAddress(text)
    else @Suppress("DEPRECATION") Patterns.IP_ADDRESS.matcher(text).matches()
  return numeric || Patterns.WEB_URL.matcher(text).matches()
}

/**
 * The proxy form, prefilled from the stored proxy. The password lives here in memory only, never in
 * saved state; it is stored in `KeystorePreferences` through [GitSettings].
 */
@HiltViewModel
class ProxyViewModel
@Inject
constructor(private val gitSettings: GitSettings, private val proxyUtils: ProxyUtils) :
  ViewModel() {

  var host by mutableStateOf(gitSettings.proxyHost.orEmpty())
  var port by mutableStateOf(gitSettings.proxyPort.takeIf { it != -1 }?.toString().orEmpty())
  var username by mutableStateOf(gitSettings.proxyUsername.orEmpty())
  var password by mutableStateOf(gitSettings.proxyPassword.orEmpty())

  val hostError: Boolean
    get() = host.isNotBlank() && !isProxyHost(host.trim())

  val portError: Boolean
    get() = proxyPort(port) == null

  /** Stores the proxy and applies it at once; false, storing nothing, when a field is invalid. */
  fun save(): Boolean {
    if (hostError) return false
    val portValue = proxyPort(port) ?: return false
    gitSettings.proxyHost = host.trim().ifEmpty { null }
    gitSettings.proxyPort = portValue
    gitSettings.proxyUsername = username.trim().ifEmpty { null }
    gitSettings.proxyPassword = password.ifEmpty { null }
    proxyUtils.setDefaultProxy()
    return true
  }
}
