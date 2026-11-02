/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.main

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.DecryptActivity
import app.passwordstore.ui.crypto.PasswordCreationActivity
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.ui.list.ListDestination
import app.passwordstore.ui.list.folderMoves
import app.passwordstore.ui.onboarding.activity.OnboardingActivity
import app.passwordstore.ui.settings.SettingsActivity
import app.passwordstore.util.extensions.launchActivity
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.PreferenceKeys
import app.passwordstore.util.shortcuts.ShortcutHandler
import com.github.michaelbull.result.onFailure
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/**
 * The password list of the folder at [path], relative to the store root ("" is the root). The back
 * stack holds one entry per folder level.
 */
@Serializable data class ListRoute(val path: String = "")

/** One entry. [filePath] was checked with [LaunchActivity.isFileInRepo] before routing here. */
@Serializable data class EntryRoute(val filePath: String)

/**
 * Single-activity host for the Compose UI. Not exported: it is only started by [LaunchActivity],
 * after its biometric gate and path check. Extends [BaseGitActivity] (an AppCompatActivity, so also
 * the FragmentActivity BiometricPrompt needs) because the git operations require one; it hosts no
 * fragments.
 */
@AndroidEntryPoint
class MainActivity : BaseGitActivity() {

  @Inject lateinit var shortcutHandler: ShortcutHandler

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val filePath = intent.getStringExtra(BasePGPActivity.EXTRA_FILE_PATH)
    val start: Any = if (filePath != null) EntryRoute(filePath) else ListRoute()
    setContent {
      APSTheme {
        val nav = rememberNavController()
        NavHost(nav, startDestination = start) {
          composable<ListRoute> { entry ->
            val path = entry.toRoute<ListRoute>().path
            ListDestination(
              activity = this@MainActivity,
              path = path,
              onOpenFolder = { target ->
                // Only the folder on top may move the stack: a double tap must not push twice.
                if (nav.currentBackStackEntry?.id == entry.id) {
                  val (pops, pushes) = folderMoves(path, target)
                  repeat(pops) { nav.popBackStack() }
                  pushes.forEach { nav.navigate(ListRoute(it)) }
                }
              },
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

  /** Re-reads the store; starts onboarding and returns false when there is none yet. */
  fun checkStore(): Boolean {
    PasswordRepository.initialize()
    if (sharedPrefs.getBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, false)) return true
    launchActivity(OnboardingActivity::class.java)
    return false
  }

  /** Git actions offered in the list's menu, chosen as the old list chose its menu. */
  fun gitOps(): ImmutableList<GitOp> =
    when {
      gitSettings.authMode == AuthMode.None -> persistentListOf(GitOp.PULL)
      PasswordRepository.isGitRepo() -> persistentListOf(GitOp.SYNC, GitOp.PULL, GitOp.PUSH)
      else -> persistentListOf()
    }

  /** Runs [op]; failures show the usual git error dialog. */
  suspend fun runGit(op: GitOp) {
    launchGitOperation(op).onFailure { promptOnErrorHandler(it) }
  }

  // ponytail: the list still opens the legacy decrypt screen directly; step 3 navigates to
  // EntryRoute instead (its trampoline would finish this activity and close the list).
  fun openEntry(file: File) {
    val item = entryItem(file)
    val authIntent = item.createAuthEnabledIntent(this)
    startActivity(Intent(authIntent).setComponent(ComponentName(this, DecryptActivity::class.java)))
    shortcutHandler.addDynamicShortcut(item, authIntent)
  }

  fun pinEntry(file: File) {
    val item = entryItem(file)
    shortcutHandler.addPinnedShortcut(item, item.createAuthEnabledIntent(this))
  }

  fun createEntry(dir: File) {
    startActivity(
      Intent(this, PasswordCreationActivity::class.java)
        .putExtra(BasePGPActivity.EXTRA_FILE_PATH, dir.absolutePath)
        .putExtra(
          BasePGPActivity.EXTRA_REPO_PATH,
          PasswordRepository.getRepositoryDirectory().absolutePath,
        )
    )
  }

  fun openSettings() {
    launchActivity(SettingsActivity::class.java)
  }

  private fun entryItem(file: File) =
    PasswordItem.newPassword(file.name, file, PasswordRepository.getRepositoryDirectory())
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
