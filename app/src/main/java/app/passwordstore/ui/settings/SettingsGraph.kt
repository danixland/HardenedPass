/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.GitServerRoute
import app.passwordstore.ui.setup.PgpKeysRoute
import app.passwordstore.ui.setup.SshKeyGenRoute
import app.passwordstore.ui.setup.SshKeyRoute
import app.passwordstore.ui.setup.WelcomeRoute
import kotlinx.serialization.Serializable

/** The settings categories. */
@Serializable data object SettingsRoute

/** One settings category. */
@Serializable data class SettingsSectionRoute(val section: SettingsSection)

/** Author name and email, and the repository utilities. */
@Serializable data object GitConfigRoute

/** The store's commit log. */
@Serializable data object GitLogRoute

/** Version, links, certificate and licenses. */
@Serializable data object AboutRoute

/** HTTP(S) proxy settings. */
@Serializable data object ProxyRoute

/** The settings destinations, and where their rows lead. */
fun NavGraphBuilder.settingsGraph(activity: MainActivity, nav: NavHostController) {
  fun onTop(entry: NavBackStackEntry) = nav.currentBackStackEntry?.id == entry.id

  fun close(entry: NavBackStackEntry) {
    if (onTop(entry)) nav.popBackStack()
  }

  composable<SettingsRoute> { entry ->
    SettingsScreen(
      onSection = { if (onTop(entry)) nav.navigate(SettingsSectionRoute(it)) },
      onAbout = { if (onTop(entry)) nav.navigate(AboutRoute) },
      onBack = { close(entry) },
    )
  }
  composable<AboutRoute> { entry ->
    AboutDestination(activity = activity, onBack = { close(entry) })
  }
  composable<SettingsSectionRoute> { entry ->
    SettingsSectionDestination(
      activity = activity,
      section = entry.toRoute<SettingsSectionRoute>().section,
      onBack = { close(entry) },
      onOpen = { action ->
        if (onTop(entry))
          when (action) {
            SettingsAction.GitServer -> nav.navigate(GitServerRoute(clone = false))
            SettingsAction.Proxy -> nav.navigate(ProxyRoute)
            SettingsAction.GitConfig -> nav.navigate(GitConfigRoute)
            SettingsAction.SshKey -> nav.navigate(SshKeyRoute)
            SettingsAction.SshKeyGen -> nav.navigate(SshKeyGenRoute)
            SettingsAction.KeyManager -> nav.navigate(PgpKeysRoute())
            // Handled by the destination itself.
            SettingsAction.ClearSecret,
            SettingsAction.DeleteRepository,
            SettingsAction.Export -> Unit
          }
      },
      onDeleted = {
        // No store any more: setup starts over, as after a deletion from the list's side.
        if (onTop(entry)) nav.navigate(WelcomeRoute) { popUpTo(nav.graph.id) { inclusive = true } }
      },
    )
  }
  composable<GitConfigRoute> { entry ->
    GitConfigDestination(
      activity = activity,
      onClose = { close(entry) },
      onLog = { if (onTop(entry)) nav.navigate(GitLogRoute) },
    )
  }
  composable<GitLogRoute> { entry -> GitLogDestination(onClose = { close(entry) }) }
  composable<ProxyRoute> { entry ->
    ProxyDestination(activity = activity, onClose = { close(entry) })
  }
}
