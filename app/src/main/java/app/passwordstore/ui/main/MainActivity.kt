/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.main

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.editor.EditorDestination
import app.passwordstore.ui.editor.EditorForm
import app.passwordstore.ui.editor.editorForm
import app.passwordstore.ui.entry.EntryDestination
import app.passwordstore.ui.entry.EntryViewModel
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

/**
 * One entry, by absolute path. Paths from [LaunchActivity] passed [LaunchActivity.isFileInRepo];
 * the entry destination checks again before decrypting.
 */
@Serializable data class EntryRoute(val filePath: String)

/**
 * The editor: a new entry in [dir] (relative to the store root, "" is the root), or the entry
 * [name] (file name without `.gpg`) there. An edit takes its plaintext from the entry view below it
 * on the back stack, never from the route.
 */
@Serializable data class EditRoute(val dir: String, val name: String? = null)

/**
 * Single-activity host for the Compose UI. Not exported: it is only started by [LaunchActivity],
 * after its biometric gate and path check. Extends [BaseGitActivity] (an AppCompatActivity, so also
 * the FragmentActivity BiometricPrompt needs) because the git operations require one; it hosts no
 * fragments.
 */
@AndroidEntryPoint
class MainActivity : BaseGitActivity() {

  @Inject lateinit var shortcutHandler: ShortcutHandler

  /** Composed destinations that show secrets; FLAG_SECURE is on while this is above zero. */
  private var secureHolds = 0

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
              onOpenEntry = { file ->
                if (nav.currentBackStackEntry?.id == entry.id) {
                  addShortcut(file)
                  nav.navigate(EntryRoute(file.absolutePath))
                }
              },
              onNewEntry = {
                if (nav.currentBackStackEntry?.id == entry.id) nav.navigate(EditRoute(path))
              },
            )
          }
          composable<EntryRoute> { entry ->
            val file = File(entry.toRoute<EntryRoute>().filePath)
            SecureWindow(this@MainActivity)
            EntryDestination(
              activity = this@MainActivity,
              file = file,
              onClose = {
                // Only the entry on top closes: the auto-close timer can race a back press.
                if (nav.currentBackStackEntry?.id == entry.id) {
                  if (nav.previousBackStackEntry == null) finish() else nav.popBackStack()
                }
              },
              onEdit = {
                if (nav.currentBackStackEntry?.id == entry.id) {
                  // Canonical folder, link's own name: a symlinked entry keeps its name.
                  val root = PasswordRepository.getRepositoryDirectory().canonicalFile
                  val dir = file.parentFile?.canonicalFile?.relativeTo(root)?.path.orEmpty()
                  nav.navigate(EditRoute(dir, file.nameWithoutExtension))
                }
              },
            )
          }
          composable<EditRoute> { entry ->
            val route = entry.toRoute<EditRoute>()
            val name = route.name
            // An edit reads the decrypted entry from the entry view below it. After process
            // death that ViewModel is new and empty, and the editor closes.
            val decrypted =
              if (name == null) null
              else
                remember(entry) {
                    // No entry view below (none on the stack): getBackStackEntry throws.
                    try {
                      nav.getBackStackEntry<EntryRoute>()
                    } catch (_: IllegalArgumentException) {
                      null
                    }
                  }
                  ?.let { hiltViewModel<EntryViewModel>(it).entry }
            SecureWindow(this@MainActivity)
            EditorDestination(
              activity = this@MainActivity,
              initial =
                if (name == null) EditorForm(dir = route.dir)
                else decrypted?.let { editorForm(it, route.dir, name) },
              original =
                name?.let {
                  File(File(PasswordRepository.getRepositoryDirectory(), route.dir), "$it.gpg")
                },
              onClose = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() },
              onSaved = {
                // Back to the list, which re-reads the store on resume. An entry opened from a
                // shortcut has no list below it: the list root replaces the stack.
                if (nav.currentBackStackEntry?.id == entry.id) {
                  if (!nav.popBackStack<ListRoute>(inclusive = false))
                    nav.navigate(ListRoute()) { popUpTo<EntryRoute> { inclusive = true } }
                }
              },
            )
          }
        }
      }
    }
  }

  /** Counts a destination that shows secrets; the first one turns FLAG_SECURE on. */
  fun holdSecure() {
    if (secureHolds++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  }

  /** Ends a [holdSecure]; FLAG_SECURE goes off when no secret-showing destination is left. */
  fun releaseSecure() {
    if (--secureHolds == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

  /** Adds [file] to the launcher's dynamic shortcuts, as opening it from the list always did. */
  fun addShortcut(file: File) {
    val item = entryItem(file)
    shortcutHandler.addDynamicShortcut(item, item.createAuthEnabledIntent(this))
  }

  fun pinEntry(file: File) {
    val item = entryItem(file)
    shortcutHandler.addPinnedShortcut(item, item.createAuthEnabledIntent(this))
  }

  fun openSettings() {
    launchActivity(SettingsActivity::class.java)
  }

  private fun entryItem(file: File) =
    PasswordItem.newPassword(file.name, file, PasswordRepository.getRepositoryDirectory())
}

/**
 * FLAG_SECURE while composed. Counted in [MainActivity], because in a transition (entry view to
 * editor and back) the entering destination is composed before the leaving one is disposed.
 */
@Composable
private fun SecureWindow(activity: MainActivity) {
  DisposableEffect(activity) {
    activity.holdSecure()
    onDispose { activity.releaseSecure() }
  }
}
