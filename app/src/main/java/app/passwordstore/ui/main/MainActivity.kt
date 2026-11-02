/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.DecryptActivity
import app.passwordstore.ui.passwords.PasswordStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable

/** The password list, at the store root. */
@Serializable data object ListRoute

/** One entry. [filePath] was checked with [LaunchActivity.isFileInRepo] before routing here. */
@Serializable data class EntryRoute(val filePath: String)

/**
 * Single-activity host for the Compose UI. Not exported: it is only started by [LaunchActivity],
 * after its biometric gate and path check. Extends [FragmentActivity] only because BiometricPrompt
 * needs one; it hosts no fragments.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val filePath = intent.getStringExtra(BasePGPActivity.EXTRA_FILE_PATH)
    val start: Any = if (filePath != null) EntryRoute(filePath) else ListRoute
    setContent {
      APSTheme {
        NavHost(rememberNavController(), startDestination = start) {
          composable<ListRoute> {
            LegacyScreen(
              this@MainActivity,
              Intent(this@MainActivity, PasswordStore::class.java).setAction(Intent.ACTION_VIEW),
            )
          }
          composable<EntryRoute> { entry ->
            val route = entry.toRoute<EntryRoute>()
            LegacyScreen(
              this@MainActivity,
              Intent(this@MainActivity, DecryptActivity::class.java)
                .putExtra(BasePGPActivity.EXTRA_FILE_PATH, route.filePath)
                .putExtra(
                  BasePGPActivity.EXTRA_REPO_PATH,
                  PasswordRepository.getRepositoryDirectory().absolutePath,
                ),
            )
          }
        }
      }
    }
  }
}

// ponytail: trampoline to a screen not yet ported; each port replaces one call with the
// real composable, and the last port deletes this function.
@Composable
private fun LegacyScreen(activity: Activity, intent: Intent) {
  LaunchedEffect(Unit) {
    activity.startActivity(intent)
    activity.finish()
  }
}
