/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.app.Activity
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.passwordstore.ui.main.ListRoute
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.settings.SettingsRoute
import kotlinx.serialization.Serializable

/** The first screen without a store. */
@Serializable data object WelcomeRoute

/** Git server settings; with [clone], saving them clones the store. */
@Serializable data class GitServerRoute(val clone: Boolean)

/** The SSH key: state, public key, import. */
@Serializable data object SshKeyRoute

/** SSH key generation. */
@Serializable data object SshKeyGenRoute

/** The PGP keys, for [mode]. */
@Serializable data class PgpKeysRoute(val mode: KeysMode = KeysMode.Manage)

/** The setup screens [MainActivity.setupIntent] opens on their own. */
enum class SetupStart(val route: Any) {
  Ssh(SshKeyRoute),
  SshGenerate(SshKeyGenRoute),
  PgpImport(PgpKeysRoute(KeysMode.Need)),
}

/**
 * The setup destinations. In an instance started for one of them ([MainActivity.setupIntent]),
 * leaving its first screen finishes the activity.
 */
fun NavGraphBuilder.setupGraph(activity: MainActivity, nav: NavHostController) {
  fun onTop(entry: NavBackStackEntry) = nav.currentBackStackEntry?.id == entry.id

  fun close(entry: NavBackStackEntry) {
    if (!onTop(entry)) return
    if (nav.previousBackStackEntry == null) activity.finish() else nav.popBackStack()
  }

  // Setup is complete: the new store's list replaces the whole stack. An instance started for
  // this screen alone just finishes.
  fun complete(entry: NavBackStackEntry) {
    if (!onTop(entry)) return
    if (nav.previousBackStackEntry == null) activity.finish()
    else nav.navigate(ListRoute()) { popUpTo(nav.graph.id) { inclusive = true } }
  }

  composable<WelcomeRoute> { entry ->
    WelcomeScreen(
      onClone = { if (onTop(entry)) nav.navigate(GitServerRoute(clone = true)) },
      onCreate = { if (onTop(entry)) nav.navigate(PgpKeysRoute(KeysMode.Choose)) },
      onSettings = { if (onTop(entry)) nav.navigate(SettingsRoute) },
    )
  }
  composable<GitServerRoute> { entry ->
    GitServerDestination(
      activity = activity,
      clone = entry.toRoute<GitServerRoute>().clone,
      onClose = { close(entry) },
      onSshKey = { if (onTop(entry)) nav.navigate(SshKeyRoute) },
      onCloned = { keyNeeded ->
        if (!keyNeeded) complete(entry)
        else if (onTop(entry)) nav.navigate(PgpKeysRoute(KeysMode.Need))
      },
    )
  }
  composable<SshKeyRoute> { entry ->
    SshKeyDestination(
      activity = activity,
      onClose = { close(entry) },
      onGenerate = { if (onTop(entry)) nav.navigate(SshKeyGenRoute) },
    )
  }
  composable<SshKeyGenRoute> { entry ->
    SshKeyGenDestination(activity = activity, onClose = { close(entry) })
  }
  composable<PgpKeysRoute> { entry ->
    PgpKeysDestination(
      activity = activity,
      mode = entry.toRoute<PgpKeysRoute>().mode,
      onClose = { close(entry) },
      onDone = { complete(entry) },
      onImported = { activity.setResult(Activity.RESULT_OK) },
    )
  }
}
