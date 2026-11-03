# Compose UI overhaul, step 6: Settings, git config, git log, proxy. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the last legacy screens outside autofill with Compose destinations in `MainActivity`: `SettingsActivity` with its six `*Settings.kt` preference providers (ModernAndroidPreferences), `GitConfigActivity` (author name and email, abort rebase, hard reset, gc), `GitLogActivity` with `GitLogAdapter`, and `ProxySelectorActivity`. Settings rows that step 5 pointed at the setup intent now navigate within the graph, "Delete repository" no longer follows symlinks out of the store, and the "files first" and "type independent" sort orders are removed.

**Architecture:** Five routes in `app.passwordstore.ui.settings`, registered by `NavGraphBuilder.settingsGraph` in `MainActivity`'s `NavHost`: `SettingsRoute` (the categories), `SettingsSectionRoute(section)` with a `@Keep` enum `SettingsSection` (General, Autofill, Passwords, Repository, Misc, Pgp), `GitConfigRoute`, `GitLogRoute` and `ProxyRoute`. Rows are four generic composables in `PreferenceRows.kt` (switch, single choice, text, action) over the existing `SharedPreferences` keys; no settings library. One `SettingsViewModel` per section entry reads every displayed value into an immutable `SettingsState`, re-read on any preference change and on resume (so "View generated public SSH key" follows a key change made on the SSH key screens); the destination intercepts the rows that need the activity (screen lock prompts, the autofill service dialog, the export folder picker, confirmations). Pure logic (proxy port parsing, HEAD status, commit log, the sort order fallback and migration, the proxy user name fix) is unit tested. Git operations of the git config screen run in `activity.lifecycleScope` with a busy flag reset in `finally` and their success kept in the ViewModel, completed by a `LaunchedEffect`; the repository deletion runs in `viewModelScope`. The legacy activities, `TextInputDialog`, `GitLogModel`, their layouts and manifest entries are deleted.

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2024.09.03 (Material 3 1.3.0: `ListItem`, `Switch`, `RadioButton`, `AlertDialog`, `OutlinedTextField`; foundation 1.7.3: `Modifier.toggleable`, `Modifier.selectable`, `selectableGroup`, `LazyColumn`), navigation-compose 2.8.9 (type-safe routes, `data object` and enum arguments), `hilt-navigation-compose` 1.2.0, activity-compose (`rememberLauncherForActivityResult`), androidx biometric through `BiometricAuthenticator` (unchanged), JGit 5.13 (unchanged), kotlin-result 2.0.0. No new dependencies; `modernandroidpreferences` becomes unused and is removed with the other libraries in step 8, as the spec says.

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`. Earlier plans: `2026-11-02-compose-foundation.md`, `2026-11-02-compose-list.md`, `2026-11-02-compose-entry.md`, `2026-11-02-compose-editor.md`, `2026-11-03-compose-setup.md` (same folder).

## Decisions taken in this plan

- **Routes and layout.** Settings keeps its two levels: a categories screen (`SettingsRoute`, the old six sub-screen rows with their icons) and one screen per category (`SettingsSectionRoute(section)`). Every category fits one phone screen, as before; one long screen would be a new layout (maintainer decision 1: keep the categories). `SettingsSection` is a `@Keep @Serializable` enum route argument, like step 5's `KeysMode`, checked in a minified release build in Task 8. The list's overflow "Settings" and the welcome screen's "Settings" navigate to `SettingsRoute`; `MainActivity.openSettings` is deleted.
- **Rows.** `PreferenceSwitch`, `PreferenceChoice` (radio dialog), `PreferenceText` (text dialog, never used for secrets: its draft lives in `rememberSaveable`) and `PreferenceAction` in `ui/settings/PreferenceRows.kt`. Each section is one grouped surface of rows. A switch shows the stored value; for the switches behind the screen lock (biometric app lock, passphrase cache, its auto-clear) the value is written only after a successful prompt. The old library wrote first and reverted on failure, so a recreation or a process death during the prompt kept the unconfirmed value.
- **Preference compatibility.** Every key and stored value meaning stays: theme, sort order, directory structure and password generator are strings from the same arrays; the clipboard timeout stays a string (`GENERAL_SHOW_TIME`, read with `toIntOrNull()`); an emptied text value removes its key, which every reader already treats as the default. No migration except the sort order.
- **Sort orders.** `PasswordSortOrder.INDEPENDENT` and `FILE_FIRST` are deleted (the Compose list shows folders and entries in two separate groups, so neither did anything visible any more), with their array items and strings in every locale. `migrateRemovedSortOrders` in `Migrations.kt` turns a stored `FILE_FIRST` or `INDEPENDENT` into `FOLDER_FIRST`, the order that list shows by default; `getSortOrder` reads any unknown value (a backup from an older version, a downgrade) as `FOLDER_FIRST` instead of throwing from `valueOf`. Both tested.
- **Delete repository** (Repository) asks first, with the old title and a new message ("Delete the local store? Entries not pushed to the remote are lost."; the old screen reused the clone's "Target directory already exists" text), then runs in `viewModelScope` under `NonCancellable`, so it never stops half way: close the repository, empty the store folder with `deleteWithoutFollowingLinks()` (never `deleteRecursively()`, which follows a `store/link -> ..` symlink into the app's files and wipes the PGP and SSH keys), recreate the empty folder, drop the dynamic shortcuts, clear `REPOSITORY_INITIALIZED`. The result is kept in `SettingsViewModel.deleted`; a `LaunchedEffect` replaces the whole stack with `WelcomeRoute` (the old screen finished and let the list's resume find no store). A partial deletion shows a snackbar first. Back, rows and switches are ignored while it runs.
- **Clear saved HTTPS password / SSH key passphrase** never cleared anything: since the 2021 ModernAndroidPreferences rewrite its click only re-read the title. It now asks for confirmation (title of the row, new message) and removes the credential the title names (the HTTPS password if one is saved, otherwise the SSH key passphrase). Presence is checked with `contains`, so the secret is never decrypted to decide whether to show the row.
- **"Clear remembered OpenKeystore SSH Key ID" is dropped.** OpenKeychain SSH support is gone: nothing writes `SSH_OPENKEYSTORE_KEYID` any more (`BaseGitActivity` only removes it), so the row could only appear for a stale value nobody uses. The string goes from every locale, and a migration removes the stale value once (tested; maintainer decision 3).
- **Setup screens from Settings** navigate within the graph: "Edit Git server settings" to `GitServerRoute(clone = false)`, "Import SSH key" and "View generated public SSH key" to `SshKeyRoute`, "Generate SSH key pair" to `SshKeyGenRoute`, "Key manager" to `PgpKeysRoute()`. Their close already pops when there is an entry below. The section re-reads its state on resume, so a generated or imported key shows or hides "View generated public SSH key" at once (the step 5 result launcher is no longer needed). `SetupStart.GitServer` and `SetupStart.PgpKeys` lose their last callers and are removed; `setupIntent` stays for the git operations' "No SSH key" dialog, the entry view's key import and autofill (step 7).
- **Autofill rows.** "Enable Autofill" reflects `AutofillManager.hasEnabledAutofillServices()`, re-read on resume; turning it off disables the service, turning it on shows the old instructions dialog with the installed browsers' support levels (now a Compose dialog, `oreo_autofill_instructions.xml` is deleted) whose OK opens the system's autofill service picker. Default username and custom domains are enabled only while the service is on, as before. The autofill activities themselves stay for step 7.
- **Passphrase cache.** The switch is enabled while the device can authenticate, or while the cache is on (so it can always be turned off; without a screen lock it turns off without a prompt, as the old fallback did). Enabling or disabling asks the screen lock and empties the cache; disabling also resets auto-clear. Auto-clear is enabled only while the cache is on; turning it off asks the screen lock and empties the cache once, a failed prompt resets it to on. The cache is emptied in `viewModelScope` with `NonCancellable`, so leaving the screen at once cannot skip it.
- **Biometric app lock.** Same row; re-enabled on Android 14 (maintainer decision 4): upstream's block for issue 2802 and its "Feature unavailable due to a platform bug" summary are removed, and the string goes from `values/` and `values-it/`. The maintainer checks the app lock on his Android 14 tablet; if the platform bug appears, the block returns. The dynamic shortcuts are dropped only after a successful change (the old screen dropped them on every tap). Only one screen lock prompt runs at a time: a double tap on any gated switch cannot open two.
- **Git config screen** (`GitConfigRoute`) replaces `GitConfigActivity`: name, email (the old email check and dialog), Save (toast and close at once; the old screen showed a snackbar and closed after 500 ms, as step 5 changed for the server screen), "Utilities" with the HEAD status, "Show commit log", "Abort rebase and push new branch" (enabled while rebasing or merging, checked again before running), "Hard reset to remote branch" and "Run garbage collection job". Operations run through a new `MainActivity.gitOperation(op, branch)` in `activity.lifecycleScope`; back is ignored while one runs; a failure shows the git error dialog and the screen stays (the old screen finished after the dialog); a successful reset or gc stays on the screen with a snackbar ("Hard reset to remote branch: done", "Run garbage collection job: done"; maintainer decision 5), kept in the ViewModel until shown, so a recreation announces it once; a successful abort shows the old "Abort and Push" dialog, which closes the screen. `BreakOutOfDetached`'s "not rebasing" dialog no longer finishes the calling activity, which is now `MainActivity` (it closed the app).
- **Hard reset asks first.** The old flow was a bare "Remote branch name" field; cancelling it closed the screen. The dialog is now titled "Hard reset to remote branch", warns that local commits and changes not on the remote branch are discarded (new string), and prefills the current branch (maintainer decision 2). Cancel only closes the dialog.
- **Commit log** (`GitLogRoute`): newest first, message, short hash in monospace and the localized date, read off the main thread by `gitLog` (the old adapter read the whole log on the main thread on first bind). No bound on the number of commits, as before: the old code was tested with 1000 commits (`ponytail:`).
- **Proxy screen** (`ProxyRoute`): host, port, user name, password (masked, reveal toggle), Save. The old host check (numeric address or web host) is kept and now blocks the save; the port must be 1 to 65535 (the old screen crashed on a number that does not fit an `Int` and accepted ports a socket address refuses later); an emptied port is stored as -1, meaning no proxy port (the old screen could not clear it). Save applies the proxy at once (`ProxyUtils.setDefaultProxy`), shows a toast and closes. The password is prefilled from `KeystorePreferences` into ViewModel memory only (never `rememberSaveable`), and the screen holds `FLAG_SECURE` while shown, since the reveal toggle can show it.
- **Proxy user name fix.** `GitSettings.proxyUsername` wrote to the proxy preferences but read from the main settings, so `ProxyUtils` never saw a user name and an authenticating proxy never worked; the getter now reads the proxy preferences. Tested.
- **Error handling** is unchanged in kind: git failures through `promptOnErrorHandler`, a failed deletion as a snackbar, invalid input as field errors or the old dialogs.

## Feature parity

### `SettingsActivity`

| Behavior today | Decision |
|---|---|
| One activity, six collapsible sub-screens with icons (General, Autofill, Passwords, Repository, Misc, PGP settings) | Port: categories screen with the same icons, one route per category |
| Back leaves a sub-screen first, then the activity; title follows the sub-screen | Port: one back stack entry per screen, titled with the category |
| Adapter state saved across recreation | Port: navigation back stack; open dialogs in `rememberSaveable` |
| Opened as an activity from the list and the welcome screen | Changed: in-graph `SettingsRoute` |

### `GeneralSettings`

| Behavior today | Decision |
|---|---|
| App theme: light, dark, battery saver (system default from Android 10), default from `app_theme_def` | Port (same arrays and default); the change recreates the activity through `Application`'s listener, the screen stays |
| Password file organization (directory structure) | Port |
| Sort order: folders first, files first, type independent, recently used | Changed: files first and type independent removed, stored values migrated to folders first |
| Disable pull to sync, recursive filtering, search on start, show hidden | Port |
| Biometric lock: prompt, value written then reverted on failure, shortcuts dropped on every tap, disabled on Android 14 with its summary | Changed: written only after success, one prompt at a time, shortcuts dropped after success; enabled on Android 14 (maintainer decision 4) |

### `AutofillSettings`

| Behavior today | Decision |
|---|---|
| Enable switch reflects the service, re-read on resume; off disables the service; on shows the instructions with installed browsers, OK opens the system picker | Port (Compose dialog) |
| Default username, custom domains (hint, multi-line), enabled with the switch | Port |

### `PasswordSettings`

| Behavior today | Decision |
|---|---|
| Generator type (classic, diceware; default diceware) | Port |
| Copy timeout as text, number keyboard, summary with the value or 45 | Port (digits only) |
| Show the password, automatically copy | Port |

### `RepositorySettings`

| Behavior today | Decision |
|---|---|
| Rebase on pull with on/off summaries | Port |
| Git server, git config, import SSH key: only with a git repository | Port: in-graph |
| Proxy: only with an HTTPS remote | Port: in-graph `ProxyRoute` |
| Generate SSH key pair; view generated public key only for a generated key, refreshed through a result launcher | Port: in-graph, refreshed on resume |
| Clear saved HTTPS password / SSH passphrase: shown when one is saved, click did nothing | Fix: confirmation, then removed |
| Clear OpenKeystore SSH key ID | Dropped (dead); the stale value is removed by a migration (maintainer decision 3) |
| Delete repository: confirmation reusing the clone's "directory already exists" text, `deleteRecursively()` followed symlinks, shortcuts dropped, initialized flag cleared, activity finished | Port with the fix and its own message; never stops half way (`NonCancellable`); welcome screen replaces the stack; failure as a snackbar |

### `MiscSettings`

| Behavior today | Decision |
|---|---|
| Export passwords: folder picker with persistable grants, then `PasswordExportService` | Port (`rememberLauncherForActivityResult`) |
| Clipboard history workaround | Port |
| Debug logging, hidden in debug builds | Port |

### `PGPSettings`

| Behavior today | Decision |
|---|---|
| Key manager (setup intent since step 5) | Changed: in-graph `PgpKeysRoute()` |
| ASCII armor | Port |
| Passphrase cache: prompt to enable or disable, cache cleared, auto-clear reset on disable; written first, reverted on failure | Port; written only after success; switch stays usable to turn it off without a screen lock |
| Auto-clear: disabling asks the screen lock and clears once; failure resets to on | Port |

### `GitConfigActivity`

| Behavior today | Decision |
|---|---|
| Name (focused when empty), email, Save with the email check; snackbar, close after 500 ms | Port; toast and close at once; no initial focus |
| HEAD status: on branch, detached at hash, missing | Port (`headStatus`, tested) |
| Show commit log | Port: `GitLogRoute` |
| Abort rebase: enabled only while rebasing or merging; success dialog closes the screen; failure dialog closes the screen | Port; failure keeps the screen; the "not rebasing" path no longer finishes the activity |
| Hard reset: branch name dialog, no warning; cancel closes the screen; success closes it | Changed: warning, prefilled branch, cancel only closes the dialog (maintainer decision 2); success stays with a snackbar (maintainer decision 5) |
| Run gc; success closes, failure dialog closes | Changed: success stays with a snackbar (maintainer decision 5); failure keeps the screen |
| Rotation recreated the activity and cancelled a running operation | Changed: no recreation (`configChanges`); a theme change still cancels (`ponytail:`) |

### `GitLogActivity` and `GitLogAdapter`

| Behavior today | Decision |
|---|---|
| RecyclerView with dividers: message, 8-character hash, medium localized date | Port: `LazyColumn` of list items |
| Whole log read on the main thread (`GitLogModel`) | Changed: `gitLog` on the IO dispatcher, progress bar meanwhile |

### `ProxySelectorActivity`

| Behavior today | Decision |
|---|---|
| Host, user, port, password (toggle) prefilled | Port; the user name now actually reaches `ProxyUtils` |
| Invalid host shown as a field error, save still allowed | Changed: save blocked |
| Port: `toInt()` crash on overflow, could not be cleared | Fix: 1 to 65535, empty clears |
| Save, close after 500 ms | Changed: toast, close at once |
| No `FLAG_SECURE` | Changed: held while shown |

### Callers

| Behavior today | Decision |
|---|---|
| List overflow and welcome screen start `SettingsActivity` | Changed: navigate to `SettingsRoute` |
| `SetupStart.GitServer`, `SetupStart.PgpKeys` for Settings | Removed with their callers |

### Deleted in this step (Task 7)

`ui/settings/SettingsActivity.kt`, `SettingsProvider.kt`, `GeneralSettings.kt`, `AutofillSettings.kt`, `PasswordSettings.kt`, `RepositorySettings.kt`, `MiscSettings.kt`, `PGPSettings.kt`, `ui/git/config/GitConfigActivity.kt`, `ui/git/log/GitLogActivity.kt`, `GitLogAdapter.kt`, `util/git/GitLogModel.kt`, `ui/proxy/ProxySelectorActivity.kt`, `ui/dialogs/TextInputDialog.kt` (only `GitConfigActivity` used it), `@dimen/normal_margin` (only two deleted layouts used it), `pref_biometric_auth_summary_disabled_platform`, layouts `activity_preference_recyclerview.xml`, `activity_git_config.xml`, `activity_git_log.xml`, `git_log_row_layout.xml`, `activity_proxy_selector.xml`, `dialog_text_input.xml`, `oreo_autofill_instructions.xml`, four manifest entries, `pref_title_openkeystore_clear_keyid` in every locale, and whatever lint then reports unused.

**Kept for later steps:** the `modernandroidpreferences` dependency and its `map_preference_widget_switch.xml` override (step 8 removes the libraries); `CredentialFinder` with `git_credential_layout.xml`, `BaseGitActivity`'s View error dialog, `StoreActions`' View dialogs (step 8 or whenever the git prompts move); `setupIntent` for autofill and the git operations (step 7); the unused row-key constants in `PreferenceKeys` (`GIT_SERVER_INFO`, `PROXY_SETTINGS`, `GIT_CONFIG`, `SSH_KEY`, `SSH_KEYGEN`, `SSH_SEE_KEY`, `CLEAR_SAVED_PASS`, `GIT_DELETE_REPO`, `EXPORT_PASSWORDS`, `SSH_OPENKEYSTORE_CLEAR_KEY_ID`): they were keys of library rows, not stored values, and step 8's cleanup can drop them.

## Lessons from steps 1 to 5, designed in

- **Hosting.** Destinations take their route's values and a `MainActivity`; one ViewModel per back stack entry from `hiltViewModel()`, as a default parameter; only values and lambdas go down to screens. Only the destination on top may navigate (`nav.currentBackStackEntry?.id == entry.id`).
- **Recreation.** Git operations need the activity and run in `activity.lifecycleScope`; `GitConfigViewModel.busy` is reset in `finally`, the success is kept in `GitConfigViewModel.done` and completed by a `LaunchedEffect`, so a theme or locale change between success and its report still shows the snackbar (once: `done` goes back to null after it) or the abort dialog. The repository deletion needs no activity and runs in `viewModelScope` under `NonCancellable`; `SettingsViewModel.deleted` drives the navigation. Open dialogs and non-secret drafts are `rememberSaveable`. A screen lock prompt cancelled by a recreation writes nothing (values are written only on success).
- **Slack compose-lint and lint.** No composable members on activities (`settingsGraph` is a top-level extension); `modifier: Modifier = Modifier` is the first optional parameter of every public composable; collections passed to composables are `ImmutableList`; the ViewModel is a default parameter and never forwarded; no `kotlin.runCatching` (new code catches `IOException`, `GitAPIException`, `JGitInternalException`); ViewModels take `DispatcherProvider`; `SettingsViewModel` is an `AndroidViewModel` (no `Context` field: `StaticFieldLeak`); the enum route argument is `@Keep` (`MissingKeepAnnotation`). Lint runs with warnings as errors, so each task adds only the strings its code uses (`UnusedResources`).
- **Accessibility.** Back buttons and the proxy password toggle carry content descriptions on the `IconButton`'s icon; switch rows are one `toggleable` with `Role.Switch`, choice rows `selectable` with `Role.RadioButton` inside a `selectableGroup`; category icons are decorative (`contentDescription = null`) next to their text.
- **Security.** No secret in a route, a Bundle, `rememberSaveable` or a log: the proxy password lives in `ProxyViewModel` memory and `KeystorePreferences`; saved Git credentials are only tested with `contains`, never read; `PreferenceText` is never used for a secret. `FLAG_SECURE` while the proxy screen is shown. Destructive actions ask first and survive recreation (delete repository, clear a saved credential, hard reset). One screen lock prompt at a time. The store deletion never follows symlinks. The commit log and HEAD status are read off the main thread.
- **Lint per task.** Each task's check includes `:app:lintDebug`; Task 7 runs the full CI set from AGENTS.md.

**Conventions for every task**

- New files get this header:
  ```kotlin
  /*
   * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
   * SPDX-License-Identifier: GPL-3.0-only
   */
  ```
  Modified upstream files keep their header.
- Strings go in `app/src/main/res/values/strings.xml` and `values-it/strings.xml` ("voce" for entry). New ones are appended under a `<!-- Compose settings -->` comment before `</resources>`, after the step 5 block.
- Commits are GPG-signed (default config, never disable). End each message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No em dashes in docs, comments or commit messages.
- Before each commit run `./gradlew spotlessApply`, then the task's checks.
- Work on the `compose` branch.

---

### Task 1: Sort orders, stale key ID, proxy user name, settings model (TDD)

**Files:**
- Modify: `app/src/main/java/app/passwordstore/util/settings/PasswordSortOrder.kt`
- Modify: `app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt`
- Modify: `app/src/main/java/app/passwordstore/util/settings/Migrations.kt`
- Modify: `app/src/main/java/app/passwordstore/util/settings/GitSettings.kt`
- Modify: `app/src/main/res/values/arrays.xml`, `app/src/main/res/values*/strings.xml`
- Create: `app/src/main/java/app/passwordstore/ui/settings/SettingsModel.kt`
- Test: `app/src/test/java/app/passwordstore/util/settings/MigrationsTest.kt` (modify)
- Test: `app/src/test/java/app/passwordstore/util/settings/PasswordSortOrderTest.kt`
- Test: `app/src/test/java/app/passwordstore/util/settings/GitSettingsTest.kt`
- Test: `app/src/test/java/app/passwordstore/ui/settings/SettingsModelTest.kt`

- [ ] **Step 1: Failing tests for the sort order and the proxy user name**

Append to `MigrationsTest` (inside the class, after the last test):

```kotlin
  @Test
  fun removedSortOrdersFallBackToFoldersFirst() {
    val gitSettings =
      GitSettings(sharedPrefs, encryptedSharedPreferences, proxySharedPreferences, filesDir)
    for (removed in listOf("FILE_FIRST", "INDEPENDENT")) {
      sharedPrefs.edit { putString(PreferenceKeys.SORT_ORDER, removed) }
      runMigrations(filesDir, sharedPrefs, gitSettings)
      assertEquals("FOLDER_FIRST", sharedPrefs.getString(PreferenceKeys.SORT_ORDER))
    }
    sharedPrefs.edit { putString(PreferenceKeys.SORT_ORDER, "RECENTLY_USED") }
    runMigrations(filesDir, sharedPrefs, gitSettings)
    assertEquals("RECENTLY_USED", sharedPrefs.getString(PreferenceKeys.SORT_ORDER))
  }

  @Test
  fun staleOpenKeystoreKeyIdIsRemoved() {
    sharedPrefs.edit { putString(PreferenceKeys.SSH_OPENKEYSTORE_KEYID, "0123456789ABCDEF") }
    runMigrations(
      filesDir,
      sharedPrefs,
      GitSettings(sharedPrefs, encryptedSharedPreferences, proxySharedPreferences, filesDir),
    )
    assertFalse(sharedPrefs.contains(PreferenceKeys.SSH_OPENKEYSTORE_KEYID))
  }
```

`app/src/test/java/app/passwordstore/util/settings/PasswordSortOrderTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.settings

import androidx.core.content.edit
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordSortOrderTest {

  @Test
  fun unknownAndRemovedValuesReadAsFoldersFirst() {
    val prefs = SPMockBuilder().createSharedPreferences()
    assertEquals(PasswordSortOrder.FOLDER_FIRST, PasswordSortOrder.getSortOrder(prefs))
    for (value in listOf("FILE_FIRST", "INDEPENDENT", "bogus")) {
      prefs.edit { putString(PreferenceKeys.SORT_ORDER, value) }
      assertEquals(PasswordSortOrder.FOLDER_FIRST, PasswordSortOrder.getSortOrder(prefs))
    }
    prefs.edit { putString(PreferenceKeys.SORT_ORDER, "RECENTLY_USED") }
    assertEquals(PasswordSortOrder.RECENTLY_USED, PasswordSortOrder.getSortOrder(prefs))
  }
}
```

`app/src/test/java/app/passwordstore/util/settings/GitSettingsTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.settings

import app.passwordstore.util.extensions.getString
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitSettingsTest {

  @Test
  fun proxyUsernameIsReadWhereItIsWritten() {
    val settings = SPMockBuilder().createSharedPreferences()
    val proxy = SPMockBuilder().createSharedPreferences()
    val gitSettings =
      GitSettings(settings, SPMockBuilder().createSharedPreferences(), proxy, "/nonexistent")
    gitSettings.proxyUsername = "proxy-user"
    assertEquals("proxy-user", gitSettings.proxyUsername)
    assertEquals("proxy-user", proxy.getString(PreferenceKeys.PROXY_USERNAME))
    assertNull(settings.getString(PreferenceKeys.PROXY_USERNAME))
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests 'app.passwordstore.util.settings.*'`
Expected: `removedSortOrdersFallBackToFoldersFirst` FAILS (`expected FOLDER_FIRST, actual FILE_FIRST`),
`staleOpenKeystoreKeyIdIsRemoved` FAILS (the key is still there),
`unknownAndRemovedValuesReadAsFoldersFirst` FAILS (`FILE_FIRST`, then `IllegalArgumentException:
No enum constant ... bogus`), `proxyUsernameIsReadWhereItIsWritten` FAILS (`expected proxy-user,
actual null`). The other migration tests pass.

- [ ] **Step 2: Remove the two sort orders, read unknown values as folders first**

Replace everything below the license header of
`app/src/main/java/app/passwordstore/util/settings/PasswordSortOrder.kt` with:

```kotlin
package app.passwordstore.util.settings

import android.content.Context
import android.content.SharedPreferences
import app.passwordstore.Application
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.getString

enum class PasswordSortOrder(val comparator: java.util.Comparator<PasswordItem>) {
  FOLDER_FIRST(
    Comparator { p1: PasswordItem, p2: PasswordItem ->
      (p1.type + p1.name).compareTo(p2.type + p2.name, ignoreCase = true)
    }
  ),
  RECENTLY_USED(
    Comparator { p1: PasswordItem, p2: PasswordItem ->
      val recentHistory =
        Application.instance.getSharedPreferences("recent_password_history", Context.MODE_PRIVATE)
      val timeP1 = recentHistory.getString(p1.file.absolutePath.base64())
      val timeP2 = recentHistory.getString(p2.file.absolutePath.base64())
      when {
        timeP1 != null && timeP2 != null -> timeP2.compareTo(timeP1)
        timeP1 != null && timeP2 == null -> return@Comparator -1
        timeP1 == null && timeP2 != null -> return@Comparator 1
        else -> p1.name.compareTo(p2.name, ignoreCase = true)
      }
    }
  );

  companion object {

    /**
     * The stored sort order; anything else (the removed "files first" and "type independent", a
     * value from another version) reads as [FOLDER_FIRST].
     */
    @JvmStatic
    fun getSortOrder(settings: SharedPreferences): PasswordSortOrder {
      val stored = settings.getString(PreferenceKeys.SORT_ORDER)
      return entries.firstOrNull { it.name == stored } ?: FOLDER_FIRST
    }
  }
}
```

In `app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt`, replace

```kotlin
  return when (typeSortOrder) {
      PasswordSortOrder.FOLDER_FIRST -> compareBy { it.type }
      // In order to let INDEPENDENT not distinguish between items based on their type, we
      // simply
      // declare them all equal at this stage.
      PasswordSortOrder.INDEPENDENT -> Comparator { _, _ -> 0 }
      PasswordSortOrder.FILE_FIRST -> compareByDescending { it.type }
      PasswordSortOrder.RECENTLY_USED -> PasswordSortOrder.RECENTLY_USED.comparator
    }
```

with

```kotlin
  return when (typeSortOrder) {
      PasswordSortOrder.FOLDER_FIRST -> compareBy<PasswordItem> { it.type }
      PasswordSortOrder.RECENTLY_USED -> PasswordSortOrder.RECENTLY_USED.comparator
    }
```

In `app/src/main/res/values/arrays.xml`, replace the two sort order arrays with:

```xml
  <string-array name="sort_order_entries">
    <item>@string/pref_folder_first_sort_order</item>
    <item>@string/pref_recently_used_sort_order</item>
  </string-array>
  <string-array name="sort_order_values">
    <item>FOLDER_FIRST</item>
    <item>RECENTLY_USED</item>
  </string-array>
```

Delete the two strings from every locale (a string left in a translation only is lint's
`ExtraTranslation` error):

```bash
sed -i '/name="pref_file_first_sort_order"/d; /name="pref_type_independent_sort_order"/d' \
  app/src/main/res/values*/strings.xml
grep -rn 'pref_file_first_sort_order\|pref_type_independent_sort_order\|FILE_FIRST\|INDEPENDENT' \
  app/src/main
```
Expected: the `grep` prints nothing.

- [ ] **Step 3: The migrations**

In `app/src/main/java/app/passwordstore/util/settings/Migrations.kt`, add to `runMigrations`
after `removeCurrentBranchValue(sharedPrefs)`:

```kotlin
  migrateRemovedSortOrders(sharedPrefs)
  removeOpenKeystoreKeyId(sharedPrefs)
```

and append to the file:

```kotlin
/**
 * "Files first" and "type independent" were removed with the Compose list, which shows folders and
 * entries in separate groups: a stored one becomes folders first, the list's default.
 */
private fun migrateRemovedSortOrders(sharedPrefs: SharedPreferences) {
  val stored = sharedPrefs.getString(PreferenceKeys.SORT_ORDER) ?: return
  if (stored != "FILE_FIRST" && stored != "INDEPENDENT") return
  logcat(TAG, INFO) { "Replacing the removed sort order $stored" }
  sharedPrefs.edit { putString(PreferenceKeys.SORT_ORDER, PasswordSortOrder.FOLDER_FIRST.name) }
}

/** OpenKeychain SSH support is gone and nothing reads the remembered key ID any more. */
private fun removeOpenKeystoreKeyId(sharedPrefs: SharedPreferences) {
  if (!sharedPrefs.contains(PreferenceKeys.SSH_OPENKEYSTORE_KEYID)) return
  sharedPrefs.edit { remove(PreferenceKeys.SSH_OPENKEYSTORE_KEYID) }
}
```

- [ ] **Step 4: The proxy user name getter**

In `app/src/main/java/app/passwordstore/util/settings/GitSettings.kt`, replace

```kotlin
  var proxyUsername
    get() = settings.getString(PreferenceKeys.PROXY_USERNAME)
```

with

```kotlin
  var proxyUsername
    get() = proxySettings.getString(PreferenceKeys.PROXY_USERNAME)
```

Run: `./gradlew :app:testDebugUnitTest --tests 'app.passwordstore.util.settings.*'`
Expected: PASS.

- [ ] **Step 5: Failing test for the settings model**

`app/src/test/java/app/passwordstore/ui/settings/SettingsModelTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import app.passwordstore.util.git.GitCommit
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.util.FS

class SettingsModelTest {

  private val root = Files.createTempDirectory("aps-settings").toFile()
  private val identity = PersonIdent("test", "test@example.org")

  init {
    // Ignore the developer's ~/.gitconfig (default branch, commit signing).
    FS.DETECTED.setUserHome(root)
  }

  @AfterTest fun cleanUp() = root.deleteRecursively().let {}

  private fun repo(): Git =
    Git.init().setInitialBranch("master").setDirectory(File(root, "store")).call()

  private fun Git.commitFile(name: String, message: String): RevCommit {
    File(repository.workTree, name).writeText(message)
    add().addFilepattern(name).call()
    return commit()
      .setMessage(message)
      .setAuthor(identity)
      .setCommitter(identity)
      .setSign(false)
      .call()
  }

  @Test
  fun proxyPorts() {
    assertEquals(-1, proxyPort(""))
    assertEquals(-1, proxyPort("  "))
    assertEquals(8080, proxyPort("8080"))
    assertEquals(8080, proxyPort(" 8080 "))
    assertEquals(1, proxyPort("1"))
    assertEquals(65535, proxyPort("65535"))
    assertNull(proxyPort("0"))
    assertNull(proxyPort("65536"))
    assertNull(proxyPort("99999999999"))
    assertNull(proxyPort("-1"))
    assertNull(proxyPort("80a"))
  }

  @Test
  fun headStatusFollowsHead() {
    repo().use { git ->
      // A new repository: HEAD names a branch that has no commit yet.
      assertEquals(HeadStatus.Branch("master"), headStatus(git.repository))
      val first = git.commitFile("a.gpg", "Add a")
      git.commitFile("b.gpg", "Add b")
      assertEquals(HeadStatus.Branch("master"), headStatus(git.repository))
      assertFalse(needsAbort(git.repository))
      git.checkout().setName(first.name).call()
      assertEquals(HeadStatus.Detached(first.name.take(8)), headStatus(git.repository))
    }
  }

  @Test
  fun gitLogIsNewestFirst() {
    assertEquals(emptyList<GitCommit>(), gitLog(null))
    repo().use { git ->
      // No commit yet: JGit's NoHeadException is an empty log, not a crash.
      assertEquals(emptyList<GitCommit>(), gitLog(git.repository))
      val first = git.commitFile("a.gpg", "Add a")
      val second = git.commitFile("b.gpg", "Add b")
      val log = gitLog(git.repository)
      assertEquals(listOf("Add b", "Add a"), log.map(GitCommit::shortMessage))
      assertEquals(listOf(second.name, first.name), log.map(GitCommit::hash))
      assertEquals("test", log[0].authorName)
    }
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.settings.SettingsModelTest`
Expected: compilation FAILS with `Unresolved reference 'proxyPort'` (and `HeadStatus`,
`headStatus`, `needsAbort`, `gitLog`).

- [ ] **Step 6: The settings model**

`app/src/main/java/app/passwordstore/ui/settings/SettingsModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import app.passwordstore.util.extensions.hash
import app.passwordstore.util.extensions.time
import app.passwordstore.util.git.GitCommit
import java.io.IOException
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState

private const val TAG = "SettingsModel"

/**
 * The proxy port typed as [text]: -1 when empty (no port, as stored before any was set), null when
 * it is not a port number from 1 to 65535.
 */
fun proxyPort(text: String): Int? {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return -1
  return trimmed.toIntOrNull()?.takeIf { it in 1..65535 }
}

/** Where HEAD points, as the git config screen reports it. */
sealed interface HeadStatus {
  data class Branch(val name: String) : HeadStatus

  data class Detached(val hash: String) : HeadStatus

  data object Missing : HeadStatus
}

/** [repo]'s HEAD: a branch (also one without commits yet), a detached commit, or missing. */
fun headStatus(repo: Repository): HeadStatus =
  try {
    val head = repo.findRef(Constants.HEAD)
    when {
      head == null -> HeadStatus.Missing
      head.isSymbolic -> HeadStatus.Branch(Repository.shortenRefName(head.target.name))
      else -> head.objectId?.let { HeadStatus.Detached(it.abbreviate(8).name()) } ?: HeadStatus.Missing
    }
  } catch (e: IOException) {
    logcat(TAG, ERROR) { e.asLog() }
    HeadStatus.Missing
  }

/** Whether a rebase or a merge is in progress, so "Abort rebase" has something to abort. */
fun needsAbort(repo: Repository): Boolean =
  repo.repositoryState.let { it.isRebasing || it == RepositoryState.MERGING }

/**
 * The commits reachable from HEAD, newest first; empty without a repository or a commit. Blocking:
 * call it off the main thread.
 */
// ponytail: the whole log at once, as the old screen did (tested there with 1000 commits); page it
// if a store's history ever makes this slow.
fun gitLog(repo: Repository?): List<GitCommit> {
  if (repo == null) return emptyList()
  return try {
    Git(repo).log().call().map {
      GitCommit(it.hash, it.shortMessage, it.authorIdent.name, it.time)
    }
  } catch (e: GitAPIException) {
    logcat(TAG, ERROR) { e.asLog() }
    emptyList()
  } catch (e: JGitInternalException) {
    logcat(TAG, ERROR) { e.asLog() }
    emptyList()
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.settings.SettingsModelTest`
Expected: PASS.

- [ ] **Step 7: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. (`SettingsModel.kt` is not used by app code yet; lint does not
report unused Kotlin functions.)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/passwordstore/util/settings \
  app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt \
  app/src/main/java/app/passwordstore/ui/settings/SettingsModel.kt \
  app/src/main/res app/src/test/java/app/passwordstore/util/settings \
  app/src/test/java/app/passwordstore/ui/settings
git commit -m "feat(app): drop two sort orders, fix the proxy user name

The Compose list shows folders and entries in separate groups, so the
files first and type independent sort orders did nothing visible. They
are removed; a stored one migrates to folders first, and any unknown
value reads as folders first instead of throwing. The OpenKeychain SSH
key ID, which nothing reads any more, is removed once.

GitSettings wrote the proxy user name to the proxy preferences but read
it from the main settings, so ProxyUtils never authenticated.

Adds the pure parts of the settings port: proxy port parsing, HEAD
status and the commit log.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Preference rows and the settings ViewModel

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/settings/PreferenceRows.kt`
- Create: `app/src/main/java/app/passwordstore/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: The rows**

`app/src/main/java/app/passwordstore/ui/settings/PreferenceRows.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import kotlinx.collections.immutable.ImmutableList

private const val DISABLED_ALPHA = 0.38f

/** List item colors for rows inside a grouped surface: the surface shows through. */
@Composable
internal fun rowColors(): ListItemColors =
  ListItemDefaults.colors(containerColor = Color.Transparent)

private fun Modifier.dimmed(enabled: Boolean) = if (enabled) this else alpha(DISABLED_ALPHA)

/** A row that runs [onClick]: opens a screen or a dialog. */
@Composable
fun PreferenceAction(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true,
) {
  ListItem(
    headlineContent = { Text(title) },
    modifier = modifier.clickable(enabled = enabled, onClick = onClick).dimmed(enabled),
    supportingContent =
      if (summary != null) {
        { Text(summary) }
      } else null,
    colors = rowColors(),
  )
}

/**
 * A switch row showing [checked]. [onCheckedChange] gets the requested value; the caller decides
 * whether it is stored (a screen lock prompt may come first), and the row shows what was stored.
 */
@Composable
fun PreferenceSwitch(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true,
) {
  ListItem(
    headlineContent = { Text(title) },
    modifier =
      modifier
        .toggleable(
          value = checked,
          enabled = enabled,
          role = Role.Switch,
          onValueChange = onCheckedChange,
        )
        .dimmed(enabled),
    supportingContent =
      if (summary != null) {
        { Text(summary) }
      } else null,
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    colors = rowColors(),
  )
}

/**
 * A single choice among [values], shown by their [labels] (same order); the summary is the
 * selected label. Tapping opens a dialog of radio buttons; choosing one closes it.
 */
@Composable
fun PreferenceChoice(
  title: String,
  values: ImmutableList<String>,
  labels: ImmutableList<String>,
  selected: String,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  PreferenceAction(
    title = title,
    onClick = { open = true },
    modifier = modifier,
    summary = labels.getOrNull(values.indexOf(selected)),
  )
  if (open)
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text(title) },
      text = {
        Column(Modifier.selectableGroup()) {
          values.forEachIndexed { index, value ->
            Row(
              Modifier.fillMaxWidth()
                .selectable(
                  selected = value == selected,
                  role = Role.RadioButton,
                  onClick = {
                    open = false
                    onSelect(value)
                  },
                )
                .padding(vertical = SpacingMedium),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = value == selected, onClick = null)
              Text(labels[index], Modifier.padding(start = SpacingLarge))
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { open = false }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
}

/**
 * A text value; tapping opens a dialog to edit it. The draft is kept across recreation in saved
 * state: never use this row for a secret. [KeyboardType.Number] keeps digits only.
 */
@Composable
fun PreferenceText(
  title: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  hint: String? = null,
  singleLine: Boolean = true,
  keyboardType: KeyboardType = KeyboardType.Text,
  enabled: Boolean = true,
) {
  var draft by rememberSaveable { mutableStateOf<String?>(null) }
  PreferenceAction(
    title = title,
    onClick = { draft = value },
    modifier = modifier,
    summary = summary,
    enabled = enabled,
  )
  draft?.let { text ->
    AlertDialog(
      onDismissRequest = { draft = null },
      title = { Text(title) },
      text = {
        OutlinedTextField(
          value = text,
          onValueChange = { new ->
            draft = if (keyboardType == KeyboardType.Number) new.filter(Char::isDigit) else new
          },
          modifier = Modifier.fillMaxWidth(),
          placeholder =
            if (hint != null) {
              { Text(hint) }
            } else null,
          singleLine = singleLine,
          minLines = if (singleLine) 1 else 3,
          keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            draft = null
            onValueChange(text)
          }
        ) {
          Text(stringResource(R.string.dialog_ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { draft = null }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
  }
}
```

- [ ] **Step 2: The ViewModel**

`app/src/main/java/app/passwordstore/ui/settings/SettingsViewModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.app.Application
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.R
import app.passwordstore.data.crypto.PGPPassphraseCache
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.injection.prefs.GitPreferences
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.ui.list.deleteWithoutFollowingLinks
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.features.Feature
import app.passwordstore.util.git.sshj.SshKey
import app.passwordstore.util.settings.DirectoryStructure
import app.passwordstore.util.settings.GitSettings
import app.passwordstore.util.settings.PasswordSortOrder
import app.passwordstore.util.settings.PreferenceKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the settings sections show: stored values, and the state rows depend on. */
data class SettingsState(
  val theme: String,
  val directoryStructure: String,
  val sortOrder: String,
  val disableSync: Boolean,
  val filterRecursively: Boolean,
  val searchOnStart: Boolean,
  val showHidden: Boolean,
  val biometric: Boolean,
  val defaultUsername: String,
  val customSuffixes: String,
  val pwgenType: String,
  val clipboardTimeout: String,
  val showPassword: Boolean,
  val copyOnDecrypt: Boolean,
  val rebaseOnPull: Boolean,
  val isGitRepo: Boolean,
  val httpsRemote: Boolean,
  val canShowSshKey: Boolean,
  /** Title of the "clear saved credential" row, or null when none is saved. */
  @StringRes val savedSecret: Int?,
  val clearClipboardHistory: Boolean,
  val debugLogging: Boolean,
  val asciiArmor: Boolean,
  val passphraseCache: Boolean,
  val autoClearCache: Boolean,
)

/**
 * One settings section's state over the existing preference keys. [state] is re-read on every
 * change of the settings and on [refresh] (the destination calls it on resume, so changes made on
 * other screens, such as a new SSH key, show at once).
 */
@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  application: Application,
  @SettingsPreferences private val prefs: SharedPreferences,
  @GitPreferences private val gitPrefs: SharedPreferences,
  private val gitSettings: GitSettings,
  private val passphraseCache: PGPPassphraseCache,
  private val dispatcherProvider: DispatcherProvider,
) : AndroidViewModel(application) {

  var state by mutableStateOf(read())
    private set

  /** True while "Delete repository" runs. */
  var deleting by mutableStateOf(false)
    private set

  /** Null until "Delete repository" ran; then whether the whole store folder was deleted. */
  var deleted by mutableStateOf<Boolean?>(null)
    private set

  // A field: SharedPreferences holds its listeners weakly.
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

  init {
    prefs.registerOnSharedPreferenceChangeListener(listener)
  }

  override fun onCleared() {
    prefs.unregisterOnSharedPreferenceChangeListener(listener)
  }

  fun refresh() {
    state = read()
  }

  fun putBoolean(key: String, value: Boolean) {
    prefs.edit { putBoolean(key, value) }
  }

  /** Stores [value] under [key]; an empty value removes the key, so readers use their default. */
  fun putString(key: String, value: String) {
    prefs.edit { if (value.isEmpty()) remove(key) else putString(key, value) }
  }

  /** Turns the passphrase cache on or off (after the screen lock prompt); it starts empty. */
  fun setPassphraseCache(enabled: Boolean) {
    prefs.edit {
      putBoolean(Feature.EnablePGPPassphraseCache.configKey, enabled)
      if (!enabled) remove(PreferenceKeys.CLEAR_PASSPHRASE_CACHE)
    }
    clearCachedPassphrases()
  }

  /** Stops clearing the cache when the screen goes off; the cache is emptied once now. */
  fun disableAutoClear() {
    putBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, false)
    clearCachedPassphrases()
  }

  /** Back to the default: the cache is cleared when the screen goes off. */
  fun resetAutoClear() {
    prefs.edit { remove(PreferenceKeys.CLEAR_PASSPHRASE_CACHE) }
  }

  /** Forgets the credential the "clear" row names: the HTTPS password, else the SSH passphrase. */
  fun clearSavedSecret() {
    val key =
      if (gitPrefs.contains(PreferenceKeys.HTTPS_PASSWORD)) PreferenceKeys.HTTPS_PASSWORD
      else PreferenceKeys.SSH_KEY_LOCAL_PASSPHRASE
    gitPrefs.edit { remove(key) }
    refresh()
  }

  /**
   * Empties the store folder and marks setup as not done, then sets [deleted]. In [viewModelScope]
   * and [NonCancellable], so neither a recreation nor the ViewModel's end can stop it half way (a
   * half-deleted store still marked as set up). Never follows a symlink: `deleteRecursively()`
   * would, and a `store/link -> ..` link would take the PGP and SSH keys with it.
   */
  fun deleteRepository() {
    if (deleting) return
    deleting = true
    viewModelScope.launch {
      withContext(NonCancellable) {
        val root = PasswordRepository.getRepositoryDirectory()
        val gone =
          withContext(dispatcherProvider.io()) {
            PasswordRepository.closeRepository()
            val ok =
              Files.notExists(root.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                root.deleteWithoutFollowingLinks()
            root.mkdirs()
            ok
          }
        getApplication<Application>()
          .getSystemService<ShortcutManager>()
          ?.removeAllDynamicShortcuts()
        prefs.edit { putBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, false) }
        deleted = gone
      }
    }
  }

  // NonCancellable: leaving the screen right after the switch must not skip the clearing.
  private fun clearCachedPassphrases() {
    viewModelScope.launch(NonCancellable) {
      passphraseCache.clearAllCachedPassphrases(getApplication())
    }
  }

  private fun read(): SettingsState {
    val app = getApplication<Application>()
    return SettingsState(
      theme = prefs.getString(PreferenceKeys.APP_THEME) ?: app.getString(R.string.app_theme_def),
      directoryStructure =
        prefs.getString(PreferenceKeys.DIRECTORY_STRUCTURE) ?: DirectoryStructure.DEFAULT.value,
      sortOrder = PasswordSortOrder.getSortOrder(prefs).name,
      disableSync = prefs.getBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, false),
      filterRecursively = prefs.getBoolean(PreferenceKeys.FILTER_RECURSIVELY, true),
      searchOnStart = prefs.getBoolean(PreferenceKeys.SEARCH_ON_START, false),
      showHidden = prefs.getBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, false),
      biometric = prefs.getBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, false),
      defaultUsername = prefs.getString(PreferenceKeys.OREO_AUTOFILL_DEFAULT_USERNAME).orEmpty(),
      customSuffixes =
        prefs.getString(PreferenceKeys.OREO_AUTOFILL_CUSTOM_PUBLIC_SUFFIXES).orEmpty(),
      pwgenType = prefs.getString(PreferenceKeys.PREF_KEY_PWGEN_TYPE) ?: "diceware",
      clipboardTimeout = prefs.getString(PreferenceKeys.GENERAL_SHOW_TIME).orEmpty(),
      showPassword = prefs.getBoolean(PreferenceKeys.SHOW_PASSWORD, true),
      copyOnDecrypt = prefs.getBoolean(PreferenceKeys.COPY_ON_DECRYPT, false),
      rebaseOnPull = gitSettings.rebaseOnPull,
      isGitRepo = PasswordRepository.isGitRepo(),
      httpsRemote = gitSettings.url?.startsWith("https") == true,
      canShowSshKey = SshKey.canShowSshPublicKey,
      // contains, never getString: the secret is not decrypted to decide whether to show a row.
      savedSecret =
        when {
          gitPrefs.contains(PreferenceKeys.HTTPS_PASSWORD) -> R.string.clear_saved_passphrase_https
          gitPrefs.contains(PreferenceKeys.SSH_KEY_LOCAL_PASSPHRASE) ->
            R.string.clear_saved_passphrase_ssh
          else -> null
        },
      clearClipboardHistory = prefs.getBoolean(PreferenceKeys.CLEAR_CLIPBOARD_HISTORY, false),
      debugLogging = prefs.getBoolean(PreferenceKeys.ENABLE_DEBUG_LOGGING, false),
      asciiArmor = prefs.getBoolean(PreferenceKeys.ASCII_ARMOR, false),
      passphraseCache = prefs.getBoolean(Feature.EnablePGPPassphraseCache.configKey, false),
      autoClearCache = prefs.getBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, true),
    )
  }
}
```

(`deleteWithoutFollowingLinks` is `internal` in `ui/list/StoreActions.kt`, same module. Every
default here is the one the old rows and the readers use: `SHOW_PASSWORD` true,
`FILTER_RECURSIVELY` true, `REBASE_ON_PULL` true through `GitSettings`, `CLEAR_PASSPHRASE_CACHE`
true, the rest false; theme from `app_theme_def`, which is `follow_system` from Android 10.)

- [ ] **Step 3: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. This task adds no string. If lint reports `StaticFieldLeak` or
`MissingKeepAnnotation`, something stores a `Context` or an enum route argument lost `@Keep`:
fix it, never baseline it.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/settings/PreferenceRows.kt \
  app/src/main/java/app/passwordstore/ui/settings/SettingsViewModel.kt
git commit -m "feat(app): add Compose preference rows and the settings state

Switch, single choice, text and action rows over the existing
preference keys, and a ViewModel that reads every displayed value,
follows preference changes, deletes the store without following
symlinks, and clears saved credentials and the passphrase cache.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Settings screens

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`
- Create: `app/src/main/java/app/passwordstore/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/app/passwordstore/ui/settings/SettingsDestination.kt`

- [ ] **Step 1: Strings**

In `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml

  <!-- Compose settings -->
  <string name="store_delete_failed">Could not delete everything in the store folder.</string>
  <string name="clear_saved_secret_message">It will be asked again at the next Git operation.</string>
  <string name="store_delete_confirm">Delete the local store? Entries not pushed to the remote are lost.</string>
```

In `app/src/main/res/values-it/strings.xml`, before `</resources>`:

```xml

  <!-- Compose settings -->
  <string name="store_delete_failed">Impossibile eliminare tutto il contenuto della cartella dell\'archivio.</string>
  <string name="clear_saved_secret_message">Verrà chiesto di nuovo alla prossima operazione Git.</string>
  <string name="store_delete_confirm">Eliminare l\'archivio locale? Le voci di cui non è stato fatto il push sul remote vanno perse.</string>
```

Everything else the sections show reuses the old rows' strings, which exist in both languages.
The delete confirmation gets its own message: the old one (`dialog_delete_msg`) was written for
the clone into an occupied folder ("Target directory already exists...").

- [ ] **Step 2: The screens**

`app/src/main/java/app/passwordstore/ui/settings/SettingsScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.util.features.Feature
import app.passwordstore.util.settings.PreferenceKeys
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/** The settings categories, one screen each. A route argument: kept from minification. */
@Keep
@Serializable
enum class SettingsSection(@StringRes val title: Int, @DrawableRes val icon: Int) {
  General(R.string.pref_category_general_title, R.drawable.app_settings_alt_24px),
  Autofill(R.string.pref_category_autofill_title, R.drawable.ic_wysiwyg_24px),
  Passwords(R.string.pref_category_passwords_title, R.drawable.ic_password_24px),
  Repository(R.string.pref_category_repository_title, R.drawable.ic_call_merge_24px),
  Misc(R.string.pref_category_misc_title, R.drawable.ic_miscellaneous_services_24px),
  Pgp(R.string.pref_category_pgp_title, R.drawable.ic_lock_open_24px),
}

/** What a row asks for beyond storing a value: another screen, a dialog or a picker. */
enum class SettingsAction {
  GitServer,
  Proxy,
  GitConfig,
  SshKey,
  SshKeyGen,
  KeyManager,
  ClearSecret,
  DeleteRepository,
  Export,
}

/** Top bar with [title] and back, then [content] in one grouped surface, scrolling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      GroupedSurface(content = content)
    }
  }
}

/** The settings categories. */
@Composable
fun SettingsScreen(
  onSection: (SettingsSection) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsScaffold(stringResource(R.string.action_settings), onBack, modifier) {
    SettingsSection.entries.forEach { section ->
      ListItem(
        headlineContent = { Text(stringResource(section.title)) },
        modifier = Modifier.clickable { onSection(section) },
        leadingContent = { Icon(painterResource(section.icon), contentDescription = null) },
        colors = rowColors(),
      )
    }
  }
}

/**
 * One category's rows. Switches and values go out through [onBoolean] and [onString] with their
 * preference key (the destination decides which need a prompt first); everything else through
 * [onAction]. [canAuthenticate] and [autofillEnabled] come from the activity.
 */
@Composable
fun SettingsSectionScreen(
  section: SettingsSection,
  state: SettingsState,
  canAuthenticate: Boolean,
  autofillEnabled: Boolean,
  deleting: Boolean,
  onBoolean: (key: String, value: Boolean) -> Unit,
  onString: (key: String, value: String) -> Unit,
  onAction: (SettingsAction) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsScaffold(stringResource(section.title), onBack, modifier) {
    when (section) {
      SettingsSection.General -> {
        PreferenceChoice(
          title = stringResource(R.string.pref_app_theme_title),
          values = persistentListOf(*stringArrayResource(R.array.app_theme_values)),
          labels = persistentListOf(*stringArrayResource(R.array.app_theme_options)),
          selected = state.theme,
          onSelect = { onString(PreferenceKeys.APP_THEME, it) },
        )
        PreferenceChoice(
          title = stringResource(R.string.pref_directory_structure),
          values = persistentListOf(*stringArrayResource(R.array.directory_structure_values)),
          labels = persistentListOf(*stringArrayResource(R.array.directory_structure_entries)),
          selected = state.directoryStructure,
          onSelect = { onString(PreferenceKeys.DIRECTORY_STRUCTURE, it) },
        )
        PreferenceChoice(
          title = stringResource(R.string.pref_sort_order_title),
          values = persistentListOf(*stringArrayResource(R.array.sort_order_values)),
          labels = persistentListOf(*stringArrayResource(R.array.sort_order_entries)),
          selected = state.sortOrder,
          onSelect = { onString(PreferenceKeys.SORT_ORDER, it) },
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_disable_sync_on_pull_title),
          checked = state.disableSync,
          onCheckedChange = { onBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, it) },
          summary = stringResource(R.string.pref_disable_sync_on_pull_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_recursive_filter_title),
          checked = state.filterRecursively,
          onCheckedChange = { onBoolean(PreferenceKeys.FILTER_RECURSIVELY, it) },
          summary = stringResource(R.string.pref_recursive_filter_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_search_on_start_title),
          checked = state.searchOnStart,
          onCheckedChange = { onBoolean(PreferenceKeys.SEARCH_ON_START, it) },
          summary = stringResource(R.string.pref_search_on_start_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_show_hidden_title),
          checked = state.showHidden,
          onCheckedChange = { onBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, it) },
          summary = stringResource(R.string.pref_show_hidden_summary),
        )
        // Enabled on Android 14 too (maintainer decision 4): upstream blocked it there for issue
        // 2802; if that platform bug shows on the maintainer's Android 14 tablet, the block returns.
        PreferenceSwitch(
          title = stringResource(R.string.pref_biometric_auth_title),
          checked = state.biometric,
          onCheckedChange = { onBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, it) },
          summary =
            stringResource(
              if (canAuthenticate) R.string.pref_biometric_auth_summary
              else R.string.pref_biometric_auth_summary_error
            ),
        )
      }
      SettingsSection.Autofill -> {
        PreferenceSwitch(
          title = stringResource(R.string.pref_autofill_enable_title),
          checked = autofillEnabled,
          onCheckedChange = { onBoolean(PreferenceKeys.AUTOFILL_ENABLE, it) },
        )
        PreferenceText(
          title = stringResource(R.string.preference_default_username_title),
          value = state.defaultUsername,
          onValueChange = { onString(PreferenceKeys.OREO_AUTOFILL_DEFAULT_USERNAME, it) },
          summary = stringResource(R.string.preference_default_username_summary),
          enabled = autofillEnabled,
        )
        PreferenceText(
          title = stringResource(R.string.preference_custom_public_suffixes_title),
          value = state.customSuffixes,
          onValueChange = { onString(PreferenceKeys.OREO_AUTOFILL_CUSTOM_PUBLIC_SUFFIXES, it) },
          summary = stringResource(R.string.preference_custom_public_suffixes_summary),
          hint = stringResource(R.string.preference_custom_public_suffixes_hint),
          singleLine = false,
          enabled = autofillEnabled,
        )
      }
      SettingsSection.Passwords -> {
        PreferenceChoice(
          title = stringResource(R.string.pref_password_generator_type_title),
          values = persistentListOf(*stringArrayResource(R.array.pwgen_provider_values)),
          labels = persistentListOf(*stringArrayResource(R.array.pwgen_provider_labels)),
          selected = state.pwgenType,
          onSelect = { onString(PreferenceKeys.PREF_KEY_PWGEN_TYPE, it) },
        )
        PreferenceText(
          title = stringResource(R.string.pref_clipboard_timeout_title),
          value = state.clipboardTimeout,
          onValueChange = { onString(PreferenceKeys.GENERAL_SHOW_TIME, it) },
          summary =
            stringResource(
              R.string.pref_clipboard_timeout_summary,
              state.clipboardTimeout.ifEmpty { "45" },
            ),
          keyboardType = KeyboardType.Number,
        )
        PreferenceSwitch(
          title = stringResource(R.string.show_password_pref_title),
          checked = state.showPassword,
          onCheckedChange = { onBoolean(PreferenceKeys.SHOW_PASSWORD, it) },
          summary = stringResource(R.string.show_password_pref_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_copy_title),
          checked = state.copyOnDecrypt,
          onCheckedChange = { onBoolean(PreferenceKeys.COPY_ON_DECRYPT, it) },
          summary = stringResource(R.string.pref_copy_summary),
        )
      }
      SettingsSection.Repository -> {
        PreferenceSwitch(
          title = stringResource(R.string.pref_rebase_on_pull_title),
          checked = state.rebaseOnPull,
          onCheckedChange = { onBoolean(PreferenceKeys.REBASE_ON_PULL, it) },
          summary =
            stringResource(
              if (state.rebaseOnPull) R.string.pref_rebase_on_pull_summary_on
              else R.string.pref_rebase_on_pull_summary
            ),
        )
        if (state.isGitRepo) {
          PreferenceAction(
            title = stringResource(R.string.pref_edit_git_server_settings),
            onClick = { onAction(SettingsAction.GitServer) },
          )
          if (state.httpsRemote)
            PreferenceAction(
              title = stringResource(R.string.pref_edit_proxy_settings),
              onClick = { onAction(SettingsAction.Proxy) },
            )
          PreferenceAction(
            title = stringResource(R.string.pref_edit_git_config),
            onClick = { onAction(SettingsAction.GitConfig) },
          )
          PreferenceAction(
            title = stringResource(R.string.pref_import_ssh_key_title),
            onClick = { onAction(SettingsAction.SshKey) },
          )
        }
        PreferenceAction(
          title = stringResource(R.string.pref_ssh_keygen_title),
          onClick = { onAction(SettingsAction.SshKeyGen) },
        )
        if (state.isGitRepo && state.canShowSshKey)
          PreferenceAction(
            title = stringResource(R.string.pref_ssh_see_key_title),
            onClick = { onAction(SettingsAction.SshKey) },
          )
        state.savedSecret?.let { title ->
          PreferenceAction(
            title = stringResource(title),
            onClick = { onAction(SettingsAction.ClearSecret) },
          )
        }
        PreferenceAction(
          title = stringResource(R.string.pref_git_delete_repo_title),
          onClick = { onAction(SettingsAction.DeleteRepository) },
          summary = stringResource(R.string.pref_git_delete_repo_summary),
          enabled = !deleting,
        )
      }
      SettingsSection.Misc -> {
        PreferenceAction(
          title = stringResource(R.string.prefs_export_passwords_title),
          onClick = { onAction(SettingsAction.Export) },
          summary = stringResource(R.string.prefs_export_passwords_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_clear_clipboard_title),
          checked = state.clearClipboardHistory,
          onCheckedChange = { onBoolean(PreferenceKeys.CLEAR_CLIPBOARD_HISTORY, it) },
          summary = stringResource(R.string.pref_clear_clipboard_summary),
        )
        if (!BuildConfig.DEBUG)
          PreferenceSwitch(
            title = stringResource(R.string.pref_debug_logging_title),
            checked = state.debugLogging,
            onCheckedChange = { onBoolean(PreferenceKeys.ENABLE_DEBUG_LOGGING, it) },
            summary = stringResource(R.string.pref_debug_logging_summary),
          )
      }
      SettingsSection.Pgp -> {
        PreferenceAction(
          title = stringResource(R.string.pref_pgp_key_manager_title),
          onClick = { onAction(SettingsAction.KeyManager) },
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_pgp_ascii_armor_title),
          checked = state.asciiArmor,
          onCheckedChange = { onBoolean(PreferenceKeys.ASCII_ARMOR, it) },
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_passphrase_cache_title),
          checked = state.passphraseCache,
          onCheckedChange = { onBoolean(Feature.EnablePGPPassphraseCache.configKey, it) },
          summary = stringResource(R.string.pref_passphrase_cache_summary),
          // Without a screen lock it can still be turned off.
          enabled = canAuthenticate || state.passphraseCache,
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_passphrase_cache_auto_clear_title),
          checked = state.autoClearCache,
          onCheckedChange = { onBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, it) },
          summary = stringResource(R.string.pref_passphrase_cache_auto_clear_summary),
          enabled = state.passphraseCache,
        )
      }
    }
  }
}
```

- [ ] **Step 3: The section destination**

`app/src/main/java/app/passwordstore/ui/settings/SettingsDestination.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupDialog
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result
import app.passwordstore.util.extensions.autofillManager
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.features.Feature
import app.passwordstore.util.services.PasswordExportService
import app.passwordstore.util.settings.PreferenceKeys
import com.github.androidpasswordstore.autofillparser.BrowserAutofillSupportLevel
import com.github.androidpasswordstore.autofillparser.getInstalledBrowsersWithAutofillSupportLevel

private enum class SettingsDialog {
  Autofill,
  ClearSecret,
  DeleteRepository,
}

/** The export's folder picker, with the persistable write grant `PasswordExportService` needs. */
private class ExportFolder : ActivityResultContracts.OpenDocumentTree() {
  override fun createIntent(context: Context, input: Uri?): Intent =
    super.createIntent(context, input).apply {
      flags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
          Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
          Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
          Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}

private fun MainActivity.autofillEnabled() = autofillManager?.hasEnabledAutofillServices() == true

/**
 * One settings category: wires [SettingsSectionScreen] to the [viewModel] and to what needs the
 * activity: screen lock prompts, the autofill service, the export picker, confirmations. [onOpen]
 * gets the actions that open another screen; [onDeleted] runs once the store was deleted.
 */
@Composable
fun SettingsSectionDestination(
  activity: MainActivity,
  section: SettingsSection,
  onBack: () -> Unit,
  onOpen: (SettingsAction) -> Unit,
  onDeleted: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SettingsViewModel = hiltViewModel(),
) {
  var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
  var canAuthenticate by remember { mutableStateOf(BiometricAuthenticator.canAuthenticate(activity)) }
  var autofillEnabled by remember { mutableStateOf(activity.autofillEnabled()) }

  fun refresh() {
    viewModel.refresh()
    canAuthenticate = BiometricAuthenticator.canAuthenticate(activity)
    autofillEnabled = activity.autofillEnabled()
  }

  // Back from another screen (git server, SSH key, key generation, the system's autofill
  // settings): rows that depend on it follow, e.g. "View generated public SSH key".
  LifecycleResumeEffect(Unit) {
    refresh()
    onPauseOrDispose {}
  }
  // A finished deletion moves on, also when the screen was recreated in between.
  val deleted = viewModel.deleted
  LaunchedEffect(deleted) {
    if (deleted == null) return@LaunchedEffect
    if (!deleted) activity.snackbar(message = activity.getString(R.string.store_delete_failed))
    onDeleted()
  }
  BackHandler(enabled = viewModel.deleting) {}

  val export =
    rememberLauncherForActivityResult(ExportFolder()) { uri ->
      if (uri != null && DocumentFile.fromTreeUri(activity, uri) != null)
        activity.startForegroundService(
          Intent(activity, PasswordExportService::class.java)
            .setAction(PasswordExportService.ACTION_EXPORT_PASSWORD)
            .putExtra("uri", uri)
        )
    }

  // One screen lock prompt at a time: a double tap must not open two. A recreation resets it
  // (remember), and the old activity's prompt is gone with it.
  var prompting by remember { mutableStateOf(false) }

  fun authenticate(@StringRes title: Int, onResult: (Result) -> Unit) {
    if (prompting) return
    prompting = true
    BiometricAuthenticator.authenticate(activity, title) { result ->
      // Retry: a finger was not recognized, the prompt stays open.
      if (result !is Result.Retry) prompting = false
      onResult(result)
    }
  }

  // Values are written only once allowed: a failed or cancelled prompt leaves the stored value,
  // which the switch shows.
  fun setBoolean(key: String, value: Boolean) {
    when (key) {
      PreferenceKeys.AUTOFILL_ENABLE ->
        if (value) dialog = SettingsDialog.Autofill
        else {
          activity.autofillManager?.disableAutofillServices()
          refresh()
        }
      PreferenceKeys.BIOMETRIC_AUTH_2 ->
        authenticate(R.string.biometric_prompt_title) { result ->
          if (result is Result.Success) {
            viewModel.putBoolean(key, value)
            // Shortcuts were built for the old lock setting, as the old screen knew.
            activity.getSystemService<ShortcutManager>()?.removeAllDynamicShortcuts()
          }
        }
      Feature.EnablePGPPassphraseCache.configKey ->
        if (!BiometricAuthenticator.canAuthenticate(activity)) viewModel.setPassphraseCache(false)
        else
          authenticate(
            if (value) R.string.pref_passphrase_cache_authenticate_enable
            else R.string.pref_passphrase_cache_authenticate_disable
          ) { result ->
            if (result is Result.Success) viewModel.setPassphraseCache(value)
          }
      PreferenceKeys.CLEAR_PASSPHRASE_CACHE ->
        when {
          value -> viewModel.putBoolean(key, true)
          !BiometricAuthenticator.canAuthenticate(activity) -> viewModel.resetAutoClear()
          else ->
            authenticate(R.string.pref_passphrase_cache_auto_clear_authenticate_disable) { result ->
              when (result) {
                is Result.Success -> viewModel.disableAutoClear()
                is Result.Retry -> Unit
                else -> viewModel.resetAutoClear()
              }
            }
        }
      else -> viewModel.putBoolean(key, value)
    }
  }

  Box(modifier) {
    SettingsSectionScreen(
      section = section,
      state = viewModel.state,
      canAuthenticate = canAuthenticate,
      autofillEnabled = autofillEnabled,
      deleting = viewModel.deleting,
      // Nothing else starts while the store is being deleted.
      onBoolean = { key, value -> if (!viewModel.deleting) setBoolean(key, value) },
      onString = { key, value -> if (!viewModel.deleting) viewModel.putString(key, value) },
      onAction = { action ->
        if (!viewModel.deleting)
          when (action) {
            SettingsAction.ClearSecret -> dialog = SettingsDialog.ClearSecret
            SettingsAction.DeleteRepository -> dialog = SettingsDialog.DeleteRepository
            SettingsAction.Export -> export.launch(null)
            else -> onOpen(action)
          }
      },
      onBack = { if (!viewModel.deleting) onBack() },
    )
    when (dialog) {
      null -> Unit
      SettingsDialog.Autofill ->
        AutofillDialog(
          onConfirm = {
            dialog = null
            activity.startActivity(
              Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                .setData("package:${BuildConfig.APPLICATION_ID}".toUri())
            )
          },
          onDismiss = { dialog = null },
        )
      SettingsDialog.ClearSecret ->
        SetupDialog(
          title = viewModel.state.savedSecret?.let { stringResource(it) },
          text = stringResource(R.string.clear_saved_secret_message),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = {
            dialog = null
            viewModel.clearSavedSecret()
          },
          onDismiss = { dialog = null },
          dismiss = stringResource(R.string.dialog_cancel),
        )
      SettingsDialog.DeleteRepository ->
        SetupDialog(
          title = stringResource(R.string.pref_dialog_delete_title),
          text = stringResource(R.string.store_delete_confirm),
          confirm = stringResource(R.string.dialog_delete),
          onConfirm = {
            dialog = null
            viewModel.deleteRepository()
          },
          onDismiss = { dialog = null },
          dismiss = stringResource(R.string.dialog_do_not_delete),
        )
    }
  }
}

/** The old autofill instructions: what it does, how to enable it, and the installed browsers. */
@Composable
private fun AutofillDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val browsers = remember { getInstalledBrowsersWithAutofillSupportLevel(context) }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(R.string.pref_autofill_enable_title)) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SpacingLarge),
      ) {
        Text(stringResource(R.string.oreo_autofill_enable_dialog_description))
        Text(stringResource(R.string.oreo_autofill_enable_dialog_instructions))
        Column {
          Text(
            stringResource(R.string.oreo_autofill_enable_dialog_installed_browsers),
            fontWeight = FontWeight.Bold,
          )
          browsers.forEach { (label, level) -> Text("$label: ${stringResource(level.label())}") }
        }
      }
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.dialog_ok)) } },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}

@StringRes
private fun BrowserAutofillSupportLevel.label(): Int =
  when (this) {
    BrowserAutofillSupportLevel.None -> R.string.oreo_autofill_no_support
    BrowserAutofillSupportLevel.FlakyFill -> R.string.oreo_autofill_flaky_fill_support
    BrowserAutofillSupportLevel.PasswordFill -> R.string.oreo_autofill_password_fill_support
    BrowserAutofillSupportLevel.PasswordFillAndSaveIfNoAccessibility ->
      R.string.oreo_autofill_password_fill_and_conditional_save_support
    BrowserAutofillSupportLevel.GeneralFill -> R.string.oreo_autofill_general_fill_support
    BrowserAutofillSupportLevel.GeneralFillAndSave ->
      R.string.oreo_autofill_general_fill_and_save_support
  }
```

(`SetupDialog` is `internal` in `ui/setup/WelcomeScreen.kt`, same module: the settings
dialogs are the same plain dialog. `BiometricAuthenticator.authenticate` shows its own toast on
failures, as before; its default title is `biometric_prompt_title`, passed explicitly by the local
`authenticate`, which lets one prompt run at a time. A prompt cancelled by a recreation calls back into the old activity and
writes nothing; the switch keeps showing the stored value (`ponytail:` comment above
`setBoolean` if the reviewer asks for one). If `SettingsAction` grows, `else -> onOpen(action)`
forwards it: keep the three local actions explicit above it.)

- [ ] **Step 4: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. The screens are not reachable yet (Task 6 wires them); the two
new strings are used by compiled code, so `UnusedResources` stays quiet. If compose-lint
reports `ComposeModifierMissing` or `ComposeParameterOrder` on a new composable, fix the
signature (modifier first optional parameter), do not suppress.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml \
  app/src/main/java/app/passwordstore/ui/settings/SettingsScreen.kt \
  app/src/main/java/app/passwordstore/ui/settings/SettingsDestination.kt
git commit -m "feat(app): add the Compose settings screens

The categories and one screen per category, with the old rows over the
same keys. Screen lock gated switches store their value only after a
successful prompt. Clearing a saved HTTPS password or SSH passphrase,
which never removed anything, now asks and removes it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Proxy screen

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`
- Create: `app/src/main/java/app/passwordstore/ui/settings/ProxyViewModel.kt`
- Create: `app/src/main/java/app/passwordstore/ui/settings/ProxyScreen.kt`

- [ ] **Step 1: String**

`values/strings.xml`, after `clear_saved_secret_message`:

```xml
  <string name="invalid_proxy_port">Enter a port from 1 to 65535, or nothing</string>
```

`values-it/strings.xml`, after `clear_saved_secret_message`:

```xml
  <string name="invalid_proxy_port">Inserisci una porta da 1 a 65535, oppure niente</string>
```

- [ ] **Step 2: The ViewModel**

`app/src/main/java/app/passwordstore/ui/settings/ProxyViewModel.kt`:

```kotlin
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
 * The proxy form, prefilled from the stored proxy. The password lives here in memory only, never
 * in saved state; it is stored in `KeystorePreferences` through [GitSettings].
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
```

- [ ] **Step 3: The screen and destination**

`app/src/main/java/app/passwordstore/ui/settings/ProxyScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import app.passwordstore.R
import app.passwordstore.ui.compose.R as composeR
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.main.MainActivity

/** Proxy host, port, user name and password; [hostError] and [portError] block the save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(
  host: String,
  port: String,
  username: String,
  password: String,
  hostError: Boolean,
  portError: Boolean,
  onHostChange: (String) -> Unit,
  onPortChange: (String) -> Unit,
  onUsernameChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onSave: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var revealed by remember { mutableStateOf(false) }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.activity_proxy_label)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
    ) {
      val field = Modifier.fillMaxWidth()
      OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        modifier = field,
        label = { Text(stringResource(R.string.proxy_hostname)) },
        supportingText =
          if (hostError) {
            { Text(stringResource(R.string.invalid_proxy_url)) }
          } else null,
        isError = hostError,
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
          ),
      )
      OutlinedTextField(
        value = port,
        onValueChange = { onPortChange(it.filter(Char::isDigit)) },
        modifier = field,
        label = { Text(stringResource(R.string.port)) },
        supportingText =
          if (portError) {
            { Text(stringResource(R.string.invalid_proxy_port)) }
          } else null,
        isError = portError,
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
      )
      OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        modifier = field,
        label = { Text(stringResource(R.string.username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
      )
      OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = field,
        label = { Text(stringResource(R.string.password)) },
        trailingIcon = {
          IconButton(onClick = { revealed = !revealed }) {
            Icon(
              painterResource(
                if (revealed) composeR.drawable.baseline_visibility_off_24
                else composeR.drawable.baseline_visibility_24
              ),
              contentDescription =
                stringResource(
                  if (revealed) R.string.entry_hide_password else R.string.entry_show_password
                ),
            )
          }
        },
        visualTransformation =
          if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
          ),
      )
      Button(onClick = onSave, modifier = field, enabled = !hostError && !portError) {
        Text(stringResource(R.string.crypto_save))
      }
    }
  }
}

/**
 * The proxy screen. Holds FLAG_SECURE while shown: it shows the saved proxy password (masked, with
 * a reveal toggle).
 */
@Composable
fun ProxyDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: ProxyViewModel = hiltViewModel(),
) {
  DisposableEffect(activity) {
    activity.holdSecure()
    onDispose { activity.releaseSecure() }
  }
  ProxyScreen(
    host = viewModel.host,
    port = viewModel.port,
    username = viewModel.username,
    password = viewModel.password,
    hostError = viewModel.hostError,
    portError = viewModel.portError,
    onHostChange = { viewModel.host = it },
    onPortChange = { viewModel.port = it },
    onUsernameChange = { viewModel.username = it },
    onPasswordChange = { viewModel.password = it },
    onSave = {
      if (viewModel.save()) {
        Toast.makeText(activity, R.string.git_server_config_save_success, Toast.LENGTH_SHORT).show()
        onClose()
      }
    },
    onBack = onClose,
    modifier = modifier,
  )
}
```

- [ ] **Step 4: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml \
  app/src/main/java/app/passwordstore/ui/settings/ProxyViewModel.kt \
  app/src/main/java/app/passwordstore/ui/settings/ProxyScreen.kt
git commit -m "feat(app): add the Compose proxy screen

Host, port, user name and password. An invalid host or port blocks the
save, an emptied port is cleared, the proxy applies at once, and the
screen is FLAG_SECURE while the saved password is on it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Git config and commit log screens

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`
- Modify: `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/util/git/operation/BreakOutOfDetached.kt`
- Create: `app/src/main/java/app/passwordstore/ui/settings/GitConfigViewModel.kt`
- Create: `app/src/main/java/app/passwordstore/ui/settings/GitConfigScreen.kt`
- Create: `app/src/main/java/app/passwordstore/ui/settings/GitLogScreen.kt`

- [ ] **Step 1: String**

`values/strings.xml`, after `invalid_proxy_port`:

```xml
  <string name="git_reset_confirm_message">Local commits and changes that are not on this remote branch are discarded.</string>
  <string name="git_tool_done">%1$s: done</string>
```

`values-it/strings.xml`, after `invalid_proxy_port`:

```xml
  <string name="git_reset_confirm_message">I commit e le modifiche locali che non sono su questo remote branch vengono scartati.</string>
  <string name="git_tool_done">%1$s: completato</string>
```

- [ ] **Step 2: `MainActivity.gitOperation` and the abort dialog fix**

In `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`, add the import

```kotlin
import com.github.michaelbull.result.Result
```

and after `runGit`:

```kotlin
  /** Runs [op] (a hard reset against [branch]) and returns its outcome; the caller reports it. */
  suspend fun gitOperation(op: GitOp, branch: String = ""): Result<Unit, Throwable> {
    remoteBranch = branch
    return launchGitOperation(op)
  }
```

In `app/src/main/java/app/passwordstore/util/git/operation/BreakOutOfDetached.kt`, replace

```kotlin
        .setPositiveButton(callingActivity.resources.getString(R.string.dialog_ok)) { _, _ ->
          callingActivity.finish()
        }
```

with

```kotlin
        // Only closes the dialog: the calling activity is the app's main screen.
        .setPositiveButton(callingActivity.resources.getString(R.string.dialog_ok), null)
```

- [ ] **Step 3: The git config ViewModel**

`app/src/main/java/app/passwordstore/ui/settings/GitConfigViewModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.settings.GitSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The git config screen's state: author name and email being edited, HEAD, and the git operation
 * that runs or succeeded. The operation itself runs in the destination (it needs the activity);
 * this ViewModel keeps what must survive a recreation.
 */
@HiltViewModel
class GitConfigViewModel
@Inject
constructor(
  private val gitSettings: GitSettings,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var name by mutableStateOf(gitSettings.authorName)
  var email by mutableStateOf(gitSettings.authorEmail)

  var head by mutableStateOf<HeadStatus?>(null)
    private set

  var canAbort by mutableStateOf(false)
    private set

  /** A git operation runs; everything waits. */
  var busy by mutableStateOf(false)

  /**
   * The operation that succeeded and is not reported yet: a reset or gc gets a snackbar (then this
   * goes back to null), an abort its dialog.
   */
  var done by mutableStateOf<GitOp?>(null)

  /** The branch an abort pushed from, for its report. */
  var abortedBranch by mutableStateOf<String?>(null)

  init {
    refresh()
  }

  /** Re-reads HEAD and the rebase state, off the main thread. */
  fun refresh() {
    viewModelScope.launch {
      val repo = PasswordRepository.repository
      val (status, abort) =
        withContext(dispatcherProvider.io()) {
          if (repo == null) HeadStatus.Missing to false else headStatus(repo) to needsAbort(repo)
        }
      head = status
      canAbort = abort
    }
  }

  /** Saves name and email; false, saving nothing, when the email is not an address. */
  fun save(): Boolean {
    val address = email.trim()
    if (!Patterns.EMAIL_ADDRESS.matcher(address).matches()) return false
    gitSettings.authorEmail = address
    gitSettings.authorName = name.trim()
    return true
  }
}
```

- [ ] **Step 4: The git config screen and destination**

`app/src/main/java/app/passwordstore/ui/settings/GitConfigScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupDialog
import app.passwordstore.util.extensions.snackbar
import com.github.michaelbull.result.fold
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Author name and email with Save, then the repository utilities: HEAD ([head], already a
 * sentence), commit log, abort rebase ([canAbort]), hard reset, gc. [busy] disables everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitConfigScreen(
  name: String,
  email: String,
  head: String,
  canAbort: Boolean,
  busy: Boolean,
  onNameChange: (String) -> Unit,
  onEmailChange: (String) -> Unit,
  onSave: () -> Unit,
  onLog: () -> Unit,
  onAbort: () -> Unit,
  onReset: () -> Unit,
  onGc: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_activity_git_config)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
    ) {
      val field = Modifier.fillMaxWidth()
      OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = field,
        enabled = !busy,
        label = { Text(stringResource(R.string.git_user_name_hint)) },
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
          ),
      )
      OutlinedTextField(
        value = email,
        onValueChange = onEmailChange,
        modifier = field,
        enabled = !busy,
        label = { Text(stringResource(R.string.git_user_email)) },
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
          ),
      )
      Button(onClick = onSave, modifier = Modifier.align(Alignment.End), enabled = !busy) {
        Text(stringResource(R.string.crypto_save))
      }
      Text(stringResource(R.string.git_tools), style = MaterialTheme.typography.titleLarge)
      Text(head)
      if (busy) LinearProgressIndicator(field)
      OutlinedButton(onClick = onLog, modifier = field, enabled = !busy) {
        Text(stringResource(R.string.git_log))
      }
      OutlinedButton(onClick = onAbort, modifier = field, enabled = canAbort && !busy) {
        Text(stringResource(R.string.abort_rebase))
      }
      OutlinedButton(onClick = onReset, modifier = field, enabled = !busy) {
        Text(stringResource(R.string.reset_to_remote))
      }
      OutlinedButton(onClick = onGc, modifier = field, enabled = !busy) {
        Text(stringResource(R.string.git_run_gc_job))
      }
    }
  }
}

/** Asks before a hard reset, for the remote [branch] to reset to. */
@Composable
fun ResetDialog(
  branch: String,
  onBranchChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(R.string.reset_to_remote)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(SpacingLarge)) {
        Text(stringResource(R.string.git_reset_confirm_message))
        OutlinedTextField(
          value = branch,
          onValueChange = onBranchChange,
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.git_utils_reset_remote_branch_title)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm, enabled = branch.isNotBlank()) {
        Text(stringResource(R.string.dialog_ok))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}

private enum class GitConfigDialog {
  InvalidEmail,
  Reset,
}

/**
 * The git config screen: wires [GitConfigScreen] to the [viewModel] and runs the utilities as git
 * operations of [activity]. A successful reset or gc stays here with a short snackbar; an abort
 * shows its report, which closes the screen; a failure shows the git error dialog and the screen
 * stays.
 */
@Composable
fun GitConfigDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  onLog: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: GitConfigViewModel = hiltViewModel(),
) {
  var dialog by rememberSaveable { mutableStateOf<GitConfigDialog?>(null) }
  var branch by rememberSaveable { mutableStateOf("") }

  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  // A running operation holds the screen: leaving would cancel it half way.
  BackHandler(enabled = viewModel.busy) {}
  // A finished reset or gc is announced once, also when the screen was recreated in between:
  // the result stays in the ViewModel until the snackbar was shown.
  val done = viewModel.done
  LaunchedEffect(done) {
    val label =
      when (done) {
        GitOp.RESET -> R.string.reset_to_remote
        GitOp.GC -> R.string.git_run_gc_job
        else -> return@LaunchedEffect
      }
    activity.snackbar(
      message = activity.getString(R.string.git_tool_done, activity.getString(label)),
      length = Snackbar.LENGTH_LONG,
    )
    viewModel.done = null
  }

  // ponytail: the activity's scope, like every git operation of the app: a theme or locale change
  // cancels it, and the finally block frees the screen.
  fun run(op: GitOp, remote: String = "") {
    if (viewModel.busy) return
    viewModel.busy = true
    activity.lifecycleScope.launch {
      try {
        activity
          .gitOperation(op, remote)
          .fold(
            success = {
              if (op == GitOp.BREAK_OUT_OF_DETACHED)
                viewModel.abortedBranch = PasswordRepository.getCurrentBranch()
              viewModel.done = op
            },
            failure = { activity.promptOnErrorHandler(it) },
          )
      } finally {
        viewModel.busy = false
        viewModel.refresh()
      }
    }
  }

  val head =
    when (val status = viewModel.head) {
      is HeadStatus.Branch -> stringResource(R.string.git_head_on_branch, status.name)
      is HeadStatus.Detached -> stringResource(R.string.git_head_detached, status.hash)
      HeadStatus.Missing -> stringResource(R.string.git_head_missing)
      null -> ""
    }
  Box(modifier) {
    GitConfigScreen(
      name = viewModel.name,
      email = viewModel.email,
      head = head,
      canAbort = viewModel.canAbort,
      busy = viewModel.busy,
      onNameChange = { viewModel.name = it },
      onEmailChange = { viewModel.email = it },
      onSave = {
        if (viewModel.save()) {
          Toast.makeText(activity, R.string.git_server_config_save_success, Toast.LENGTH_SHORT)
            .show()
          onClose()
        } else dialog = GitConfigDialog.InvalidEmail
      },
      onLog = onLog,
      onAbort = {
        // Checked again: BreakOutOfDetached reports success without doing anything otherwise.
        if (PasswordRepository.repository?.let(::needsAbort) == true)
          run(GitOp.BREAK_OUT_OF_DETACHED)
        else viewModel.refresh()
      },
      onReset = {
        branch = PasswordRepository.getCurrentBranch().orEmpty()
        dialog = GitConfigDialog.Reset
      },
      onGc = { run(GitOp.GC) },
      onBack = { if (!viewModel.busy) onClose() },
    )
    when (dialog) {
      null -> Unit
      GitConfigDialog.InvalidEmail ->
        SetupDialog(
          text = stringResource(R.string.invalid_email_dialog_text),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = { dialog = null },
          onDismiss = { dialog = null },
        )
      GitConfigDialog.Reset ->
        ResetDialog(
          branch = branch,
          onBranchChange = { branch = it },
          onConfirm = {
            dialog = null
            run(GitOp.RESET, branch.trim())
          },
          onDismiss = { dialog = null },
        )
    }
    if (done == GitOp.BREAK_OUT_OF_DETACHED) {
      val aborted = viewModel.abortedBranch.orEmpty()
      SetupDialog(
        title = stringResource(R.string.git_abort_and_push_title),
        text =
          stringResource(R.string.git_break_out_of_detached_success, aborted, "conflicting-$aborted-..."),
        confirm = stringResource(R.string.dialog_ok),
        onConfirm = onClose,
        onDismiss = onClose,
      )
    }
  }
}
```

- [ ] **Step 5: The commit log**

`app/src/main/java/app/passwordstore/ui/settings/GitLogScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.git.GitCommit
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The store's commits, read once off the main thread. */
@HiltViewModel
class GitLogViewModel @Inject constructor(dispatcherProvider: DispatcherProvider) : ViewModel() {

  /** Null while loading. */
  var commits by mutableStateOf<ImmutableList<GitCommit>?>(null)
    private set

  init {
    viewModelScope.launch {
      commits =
        withContext(dispatcherProvider.io()) {
          gitLog(PasswordRepository.repository).toImmutableList()
        }
    }
  }
}

/** The commit log, newest first: message, short hash, date. A progress bar while [commits] is null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitLogScreen(
  commits: ImmutableList<GitCommit>?,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val formatter = remember {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
      .withLocale(Locale.getDefault())
      .withZone(ZoneId.systemDefault())
  }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_activity_git_log)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    if (commits == null) LinearProgressIndicator(Modifier.padding(padding).fillMaxWidth())
    else
      LazyColumn(Modifier.padding(padding).fillMaxSize()) {
        items(commits, key = GitCommit::hash) { commit ->
          ListItem(
            headlineContent = { Text(commit.shortMessage) },
            overlineContent = { Text(commit.hash.take(8), fontFamily = FontFamily.Monospace) },
            supportingContent = { Text(formatter.format(commit.time)) },
          )
          HorizontalDivider()
        }
      }
  }
}

@Composable
fun GitLogDestination(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: GitLogViewModel = hiltViewModel(),
) {
  GitLogScreen(commits = viewModel.commits, onBack = onClose, modifier = modifier)
}
```

- [ ] **Step 6: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml \
  app/src/main/java/app/passwordstore/ui/main/MainActivity.kt \
  app/src/main/java/app/passwordstore/util/git/operation/BreakOutOfDetached.kt \
  app/src/main/java/app/passwordstore/ui/settings/GitConfigViewModel.kt \
  app/src/main/java/app/passwordstore/ui/settings/GitConfigScreen.kt \
  app/src/main/java/app/passwordstore/ui/settings/GitLogScreen.kt
git commit -m "feat(app): add the Compose git config and commit log screens

Author name and email, HEAD status, commit log, abort rebase, hard
reset and gc. The hard reset now warns and prefills the current branch;
a finished reset or gc stays on the screen with a snackbar, and a
failed operation keeps the screen too. The commit log is read off the
main thread. BreakOutOfDetached's not-rebasing dialog no longer
finishes the calling activity.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Settings graph and its callers

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/settings/SettingsGraph.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt`

- [ ] **Step 1: The graph**

`app/src/main/java/app/passwordstore/ui/settings/SettingsGraph.kt`:

```kotlin
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
      onBack = { close(entry) },
    )
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
```

(`SettingsSection` as a route argument: navigation-common 2.8.9 maps `SerialKind.ENUM` to its
built-in enum `NavType`, as step 5 verified for `KeysMode`; `@Keep` keeps the enum through R8,
checked in Task 8.)

- [ ] **Step 2: `MainActivity`**

In `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`:

Remove the imports

```kotlin
import app.passwordstore.ui.settings.SettingsActivity
```
```kotlin
import app.passwordstore.util.extensions.launchActivity
```

and add

```kotlin
import app.passwordstore.ui.settings.SettingsRoute
import app.passwordstore.ui.settings.settingsGraph
```

In `composable<ListRoute>`, after the `onClone` argument, add

```kotlin
              onSettings = {
                if (nav.currentBackStackEntry?.id == entry.id) nav.navigate(SettingsRoute)
              },
```

After `setupGraph(this@MainActivity, nav)` add

```kotlin
          settingsGraph(this@MainActivity, nav)
```

Delete

```kotlin
  fun openSettings() {
    launchActivity(SettingsActivity::class.java)
  }

```

(`launchActivity` has no other use in `MainActivity`; check with
`grep -n launchActivity app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`, which must
print nothing.)

- [ ] **Step 3: The list and the welcome screen**

In `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`, in the KDoc replace

```kotlin
 * [onClone] the git server screen for a clone; [onNoStore] runs when the store is gone.
```

with

```kotlin
 * [onClone] the git server screen for a clone, [onSettings] the settings; [onNoStore] runs when the
 * store is gone.
```

add the parameter after `onClone: () -> Unit,`

```kotlin
  onSettings: () -> Unit,
```

and replace

```kotlin
      onSettings = activity::openSettings,
```

with

```kotlin
      onSettings = onSettings,
```

In `app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt`, add the import

```kotlin
import app.passwordstore.ui.settings.SettingsRoute
```

and replace

```kotlin
      onSettings = activity::openSettings,
```

with

```kotlin
      onSettings = { if (onTop(entry)) nav.navigate(SettingsRoute) },
```

- [ ] **Step 4: Nothing outside the legacy files opens the legacy screens**

```bash
grep -rnwE 'SettingsActivity|GitConfigActivity|GitLogActivity|ProxySelectorActivity|openSettings' \
  app/src/main/java | grep -vE 'ui/settings/(SettingsActivity|RepositorySettings)\.kt|ui/git/(config|log)/|ui/proxy/'
```
Expected: no output.

- [ ] **Step 5: Build**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Launch smoke check on the emulator**

The emulator only (`ANDROID_SERIAL`); a tablet may be connected over USB and is never touched.
Start the `aps-test` AVD as in Task 8, step 1, if it is not running (no e2e server needed).

```bash
export ANDROID_SERIAL=emulator-5554
adb uninstall xyz.danix.hardenedpass.debug || true
./gradlew :app:installDebug
adb logcat -c
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity; sleep 3
ui() { adb exec-out uiautomator dump /dev/tty; }
tap() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 2; }
tap 'text="Settings"'; ui | grep -o 'text="PGP settings"'
tap 'text="General"'; ui | grep -o 'text="Password sort order"'
adb shell input keyevent 4; sleep 1
tap 'text="Repository"'; ui | grep -o 'text="Generate SSH key pair"'
tap 'text="Generate SSH key pair"'; ui | grep -o 'text="ECDSA"'
adb shell input keyevent 4; sleep 1; ui | grep -o 'text="Delete repository"'
adb logcat -d -b crash | grep -c 'FATAL EXCEPTION'
adb uninstall xyz.danix.hardenedpass.debug
unset ANDROID_SERIAL
```
Expected: each `grep -o` prints its text (welcome screen's Settings opens the categories, the
General screen through its enum route argument, the Repository screen, the generation screen
in-graph and back to the Repository screen), and the crash count is `0`. A crash (for example a
missing `NavType`) is fixed before the commit.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/settings/SettingsGraph.kt \
  app/src/main/java/app/passwordstore/ui/main/MainActivity.kt \
  app/src/main/java/app/passwordstore/ui/list/ListDestination.kt \
  app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt
git commit -m "feat(app): run Settings in MainActivity

The list and the welcome screen open the Compose settings; their rows
open the git server, SSH key, key generation, PGP keys, proxy and git
config screens within the graph. Deleting the repository returns to the
welcome screen.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Remove the legacy settings screens

**Files:**
- Delete: the files listed in "Deleted in this step" above
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/res/values*/strings.xml`, `app/src/main/res/values/dimens.xml`
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`

- [ ] **Step 1: Delete the files**

```bash
git rm app/src/main/java/app/passwordstore/ui/settings/SettingsActivity.kt \
  app/src/main/java/app/passwordstore/ui/settings/SettingsProvider.kt \
  app/src/main/java/app/passwordstore/ui/settings/GeneralSettings.kt \
  app/src/main/java/app/passwordstore/ui/settings/AutofillSettings.kt \
  app/src/main/java/app/passwordstore/ui/settings/PasswordSettings.kt \
  app/src/main/java/app/passwordstore/ui/settings/RepositorySettings.kt \
  app/src/main/java/app/passwordstore/ui/settings/MiscSettings.kt \
  app/src/main/java/app/passwordstore/ui/settings/PGPSettings.kt \
  app/src/main/java/app/passwordstore/util/git/GitLogModel.kt \
  app/src/main/java/app/passwordstore/ui/dialogs/TextInputDialog.kt \
  app/src/main/res/layout/activity_preference_recyclerview.xml \
  app/src/main/res/layout/activity_git_config.xml \
  app/src/main/res/layout/activity_git_log.xml \
  app/src/main/res/layout/git_log_row_layout.xml \
  app/src/main/res/layout/activity_proxy_selector.xml \
  app/src/main/res/layout/dialog_text_input.xml \
  app/src/main/res/layout/oreo_autofill_instructions.xml
git rm -r app/src/main/java/app/passwordstore/ui/git/config \
  app/src/main/java/app/passwordstore/ui/git/log \
  app/src/main/java/app/passwordstore/ui/proxy
sed -i '/name="pref_title_openkeystore_clear_keyid"/d; /name="pref_biometric_auth_summary_disabled_platform"/d' \
  app/src/main/res/values*/strings.xml
sed -i '/name="normal_margin"/d' app/src/main/res/values/dimens.xml
```

(`pref_biometric_auth_summary_disabled_platform` lost its last user with `GeneralSettings.kt`:
the Compose row no longer blocks Android 14, maintainer decision 4; it exists only in `values/`
and `values-it/`. `@dimen/normal_margin` was used only by `git_log_row_layout.xml` and
`activity_proxy_selector.xml`, so it would be `UnusedResources`.)

(`ui/git/config` holds only `GitConfigActivity.kt` since step 5; `ui/git/log` only the activity
and its adapter. `GitCommit.kt` stays: `gitLog` returns it.)

- [ ] **Step 2: Manifest**

In `app/src/main/AndroidManifest.xml`, delete

```xml
    <activity
      android:name=".ui.proxy.ProxySelectorActivity"
      android:exported="false"
      android:label="@string/activity_proxy_label"
      android:windowSoftInputMode="adjustResize" />

```

and

```xml
    <activity
      android:name=".ui.git.config.GitConfigActivity"
      android:exported="false"
      android:label="@string/title_activity_git_config"
      android:windowSoftInputMode="adjustResize" />

    <activity
      android:name=".ui.git.log.GitLogActivity"
      android:exported="false"
      android:label="@string/title_activity_git_log" />

    <activity
      android:name=".ui.settings.SettingsActivity"
      android:exported="false"
      android:label="@string/action_settings"
      android:parentActivityName=".ui.main.MainActivity" />

```

- [ ] **Step 3: The setup starts Settings no longer uses**

In `app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt`, replace

```kotlin
enum class SetupStart(val route: Any) {
  GitServer(GitServerRoute(clone = false)),
  Ssh(SshKeyRoute),
  SshGenerate(SshKeyGenRoute),
  PgpKeys(PgpKeysRoute()),
  PgpImport(PgpKeysRoute(KeysMode.Need)),
}
```

with

```kotlin
enum class SetupStart(val route: Any) {
  Ssh(SshKeyRoute),
  SshGenerate(SshKeyGenRoute),
  PgpImport(PgpKeysRoute(KeysMode.Need)),
}
```

In `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`, replace the KDoc and
`ponytail:` comment of `setupIntent`

```kotlin
    /**
     * Opens one setup screen in an instance of its own, for callers outside the graph (legacy
     * Settings, autofill, git operations) and for those waiting for a result (the entry view's
     * missing key). Leaving that screen finishes the instance; the PGP keys screen sets RESULT_OK
     * once a key was imported.
     */
    // ponytail: a second instance instead of in-graph navigation, until steps 6 and 7 move
    // Settings and autofill into the graph.
```

with

```kotlin
    /**
     * Opens one setup screen in an instance of its own, for callers outside the graph (autofill,
     * the git operations' "No SSH key" dialog) and for those waiting for a result (the entry view's
     * missing key). Leaving that screen finishes the instance; the PGP keys screen sets RESULT_OK
     * once a key was imported.
     */
    // ponytail: a second instance instead of in-graph navigation, until step 7 moves autofill
    // into the graph and the git operations' dialogs become Compose dialogs.
```

- [ ] **Step 4: Nothing refers to the removed code**

```bash
grep -rnwE 'SettingsActivity|SettingsProvider|GeneralSettings|AutofillSettings|PasswordSettings|RepositorySettings|MiscSettings|PGPSettings|GitConfigActivity|GitLogActivity|GitLogAdapter|GitLogModel|ProxySelectorActivity|TextInputDialog|openSettings' \
  app/src
grep -rnE 'SetupStart\.(GitServer|PgpKeys)\b|de\.Maxr1998|R\.layout\.(activity_preference_recyclerview|activity_git_config|activity_git_log|git_log_row_layout|activity_proxy_selector|dialog_text_input|oreo_autofill_instructions)|Activity(PreferenceRecyclerview|GitConfig|GitLog|ProxySelector)Binding|GitLogRowLayoutBinding|DialogTextInputBinding' \
  app/src
grep -rn 'pref_title_openkeystore_clear_keyid\|pref_biometric_auth_summary_disabled_platform\|normal_margin' app/src
```
Expected: no output from any of them.

- [ ] **Step 5: The spec**

In `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`, replace

```
handling and the passphrase cache. Existing stores, keys and settings carry over without
migration.
```

with

```
handling and the passphrase cache. Existing stores, keys and settings carry over without
migration, with two exceptions from step 6: a stored "files first" or "type independent" sort
order (both removed) becomes folders first, and the unused OpenKeychain SSH key ID is dropped.
```

and replace

```
- Screens outside the graph (Settings, autofill, git operations) and the entry view's key
  import open these through a `MainActivity` setup intent until steps 6 and 7.
```

with

```
- Settings opens these within the graph. Autofill, the git operations' "No SSH key" dialog and
  the entry view's key import open them through a `MainActivity` setup intent until step 7.

### Settings

- Settings lists its categories (general, autofill, passwords, repository, misc, PGP); each opens
  a screen of rows (switches, single choices, text values, actions) over the existing preference
  keys. Switches behind the screen lock store their value only after a successful prompt.
- Rows that lead elsewhere navigate within `MainActivity`: git server, SSH key, key generation,
  PGP keys, proxy, and the git configuration screen (author name and email, commit log, abort
  rebase, hard reset, garbage collection).
- Destructive actions ask first: deleting the repository (which never follows symlinks out of the
  store and returns to the welcome screen), clearing a saved password or passphrase, the hard
  reset.
- The list's sort orders are folders first and recently used; "files first" and "type
  independent" are gone, and stored values fall back to folders first.
```

- [ ] **Step 6: Full local check**

Run:
```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`. If lint reports unused resources, they were used only by the
deleted files (`normal_margin` and the two strings are already handled in Step 1; candidates:
`title_activity_git_config` is still used by the git config screen,
`activity_proxy_label` by the proxy screen; strings or drawables only the deleted layouts used,
`pref_import_pgp_key_title` if nothing else uses it): confirm with `grep -rn` that nothing else
uses them, then delete them from every `values*/` file (a string left only in a translation is
an `ExtraTranslation` error) or from `res/drawable`, and re-run. `map_preference_widget_switch.xml`
stays (lint-ignored, removed with the library in step 8). If lint reports an issue in a file from
Tasks 1 to 6, fix it there and include it in this commit.

- [ ] **Step 7: Commit**

```bash
git add -A app/src docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "refactor(app): remove the legacy settings screens

The Compose settings, git config, commit log and proxy screens replace
SettingsActivity with its six preference providers, GitConfigActivity
with TextInputDialog, GitLogActivity with its adapter and GitLogModel,
and ProxySelectorActivity, with their layouts and manifest entries.
The dead OpenKeystore key ID row goes with its string, the Android 14
platform bug summary and normal_margin lose their last users, and the
setup intent loses the two starts only Settings used.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Emulator check and push

No real store or keys: the e2e environment from AGENTS.md only. A physical tablet may be
connected over USB: every `adb` and Gradle install command in this task targets the emulator
through `ANDROID_SERIAL`; never touch the tablet. The PIN `1234` below is the emulator's own test
PIN, never the owner's.

- [ ] **Step 1: Server, emulator, unlock, clean install, helpers**

```bash
export ANDROID_SERIAL=emulator-5554
scripts/e2e-env.sh up
$ANDROID_HOME/emulator/emulator -avd aps-test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
adb shell locksettings set-pin 1234 2>/dev/null || true
adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent 82
adb shell input text 1234; adb shell input keyevent 66
for s in animator_duration_scale transition_animation_scale window_animation_scale; do
  adb shell settings put global $s 0; done
adb shell settings put secure autofill_service null
adb uninstall xyz.danix.hardenedpass.debug || true
./gradlew :app:installDebug
scripts/e2e-env.sh push-keys
OUT=$(mktemp -d)
```

(`ANDROID_HOME` is the `sdk.dir` from `local.properties`. `set-pin` fails harmlessly when the
AVD already has the PIN.)

Helpers, the step 5 set plus `pin`, `until_seen` (polls, for snackbars), `prefs`, `secure` and
`enabled`:

```bash
APP=xyz.danix.hardenedpass.debug
ui() { adb exec-out uiautomator dump /dev/tty; }
center() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | sed -n "${2:-1}p" | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }; set -- $b; echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; }
tap() { local c; c=$(center "$1" "$2") && adb shell input tap $c; sleep 1; }
field() { tap 'class="android.widget.EditText"' "$1"; adb shell input keycombination 113 29; adb shell input keyevent 67; adb shell input text "$2"; }
value() { ui | grep -o '<node [^>]*class="android.widget.EditText"[^>]*>' | sed -n "${1}p" | grep -o ' text="[^"]*"' | cut -d'"' -f2; }
seen() { ui | grep -q "$1" && echo "ok: $1" || echo "MISSING: $1"; }
enabled() { ui | grep -o "<node [^>]*$1[^>]*>" | head -1 | grep -o 'enabled="[a-z]*"'; }
top() { adb shell dumpsys activity activities | grep topResumedActivity; }
open() { adb shell am start -n $APP/app.passwordstore.ui.main.LaunchActivity; sleep 3; }
pick() { sleep 2; ui | grep -q "text=\"$1\"" || { tap 'content-desc="Show roots"'; tap 'text="Downloads"'; sleep 1; }; tap "text=\"$1\""; sleep 2; }
pin() { sleep 2; adb shell input text 1234; adb shell input keyevent 66; sleep 2; }
until_seen() { for _ in $(seq 30); do ui | grep -q "$1" && { echo "ok: $1"; return; }; sleep 1; done; echo "MISSING: $1"; }
prefs() { adb shell run-as $APP cat shared_prefs/${APP}_preferences.xml | grep -o "name=\"$1\"[^/]*" || echo "absent: $1"; }
secure() { adb shell dumpsys window | grep -c 'fl=.*SECURE'; }
```

- [ ] **Step 2: A cloned store to work with**

```bash
open; tap 'text="Clone Remote Repo"'
field 1 ssh://git@10.0.2.2:2222/srv/git/store.git
tap 'text="Manage"'; tap 'text="Import SSH key"'; pick aps-e2e-ssh-key
seen 'text="Imported private key"'; adb shell input keyevent 4; sleep 1
tap 'text="Clone"'; sleep 15
tap 'text="Import PGP key"' 2; pick aps-e2e-pgp-key.asc; tap 'text="OK"'; sleep 2
seen 'text="example.org"'
```
Expected: the step 5 flow ends on the cloned store's list with `example.org`. (If the FAB is
the first "Import PGP key" match on this image, use index 1, as in step 5.)

- [ ] **Step 3: Categories, General, sort orders, theme recreation**

```bash
tap 'content-desc="More options"'; tap 'text="Settings"'
for t in General Autofill Passwords Repository Misc 'PGP settings'; do seen "text=\"$t\""; done
adb exec-out screencap -p > "$OUT/settings.png"
tap 'text="General"'
tap 'text="Password sort order"'
seen 'text="Folders first"'; seen 'text="Recently used"'; seen 'text="Files first"'; seen 'text="Type independent"'
tap 'text="Recently used"'; prefs sort_order
tap 'text="App theme"'; adb shell cmd uimode night yes; sleep 3
seen 'text="System default"'; tap 'text="Dark"'; sleep 3
seen 'text="Password sort order"'; prefs app_theme
tap 'text="App theme"'; tap 'text="System default"'; sleep 3
adb shell cmd uimode night no; sleep 3
tap 'text="Show all files and folders"'; prefs show_hidden_contents
tap 'text="Show all files and folders"'; prefs show_hidden_contents
```
Expected: the six categories; the sort dialog offers only "Folders first" and "Recently used"
(`MISSING` for the other two); `sort_order` is `RECENTLY_USED`; the theme dialog survives the
night mode recreation; choosing Dark recreates the activity and the General screen stays;
`app_theme` is `dark`, then back to system default; the switch writes `true`, then `false`.

- [ ] **Step 4: The sort order migration on upgrade**

```bash
adb shell am force-stop $APP
adb shell run-as $APP sed -i 's/RECENTLY_USED/FILE_FIRST/' shared_prefs/${APP}_preferences.xml
prefs sort_order
open; prefs sort_order
seen 'text="example.org"'
```
Expected: `FILE_FIRST` before the start, `FOLDER_FIRST` after (the migration ran in
`Application.onCreate`), and the list opens normally.

- [ ] **Step 5: Screen lock gated switches**

```bash
tap 'content-desc="More options"'; tap 'text="Settings"'; tap 'text="General"'
tap 'text="Enable biometric authentication"'; sleep 2; adb shell input keyevent 4; sleep 2
prefs biometric_auth_delete_soon_please
tap 'text="Enable biometric authentication"'; pin; prefs biometric_auth_delete_soon_please
tap 'text="Enable biometric authentication"'; pin; prefs biometric_auth_delete_soon_please
adb shell input keyevent 4; sleep 1; tap 'text="PGP settings"'
enabled 'text="Automatically clear passphrase cache"'
tap 'text="Enable passphrase caching"'; pin; prefs enable_gpg_passphrase_cache
enabled 'text="Automatically clear passphrase cache"'
tap 'text="Automatically clear passphrase cache"'; pin; prefs pgpainless_auto_clear_passphrase_cache_screen_off
tap 'text="Enable passphrase caching"'; pin
prefs enable_gpg_passphrase_cache; prefs pgpainless_auto_clear_passphrase_cache_screen_off
tap 'text="Key manager"'; sleep 2; seen 'text="f8993a789cdfb014"'; adb shell input keyevent 4; sleep 1
```
Expected: cancelling the prompt stores nothing (`absent: biometric_auth_delete_soon_please`);
the PIN stores `true`, the next one `false`. Auto-clear is `enabled="false"` until the cache is
on; enabling the cache stores `true`; turning auto-clear off after the PIN stores `false`;
disabling the cache stores `false` and removes the auto-clear key (`absent:`). The key manager
opens in-graph with the imported key. (The `enabled` helper reads the row node; if Compose
merges it differently on this image, compare the dumps of both states by eye.) The emulator runs
Android 15: the maintainer checks the app lock (enable, relaunch, unlock, disable) on his Android
14 tablet himself, since it is enabled there again (maintainer decision 4); if upstream's platform
bug (issue 2802) appears, the Android 14 block returns.

- [ ] **Step 6: Autofill and export**

```bash
adb shell input keyevent 4; sleep 1; tap 'text="Autofill"'
enabled 'text="Default username"'
tap 'text="Enable Autofill"'; seen 'Autofill support with installed browsers'
tap 'text="Cancel"'; seen 'text="Enable Autofill"'
adb shell input keyevent 4; sleep 1; tap 'text="Misc"'
tap 'text="Export passwords"'; sleep 2; top
adb shell input keyevent 4; sleep 2; top
```
Expected: default username disabled while the service is off; the instructions dialog lists the
browsers and Cancel closes it (the service setting is left alone: it was nulled in step 1 and
is not changed here); Export opens the system folder picker (`documentsui` on top), back returns
to `MainActivity`.

- [ ] **Step 7: Git config, commit log, hard reset, gc**

```bash
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
tap 'text="New entry"'; tap 'text="Create new password"'
adb shell input text local-only; field 2 x1y2; tap 'text="Save"'; sleep 3; seen 'text="local-only"'
tap 'content-desc="More options"'; tap 'text="Settings"'; tap 'text="Repository"'
tap 'text="Local Git config'; sleep 2
seen 'text="On branch master"'; enabled 'text="Abort rebase and push new branch"'
field 1 Test; field 2 not-an-address; tap 'text="Save"'
seen 'text="Please enter a valid email address"'; tap 'text="OK"'
field 2 test@example.org; tap 'text="Save"'; sleep 2; prefs git_config_user_email
tap 'text="Local Git config'; sleep 2; value 1; value 2
tap 'text="Show commit log"'; sleep 2; seen 'local-only'; seen 'text="Initial test store"'
adb exec-out screencap -p > "$OUT/log.png"
adb shell input keyevent 4; sleep 1
tap 'text="Hard reset to remote branch"'; seen 'are discarded'; value 1
tap 'text="Cancel"'; seen 'text="Utilities"'
tap 'text="Hard reset to remote branch"'; tap 'text="OK"'
until_seen 'remote branch: done'; seen 'text="Utilities"'
tap 'text="Show commit log"'; sleep 2; seen 'local-only'; adb shell input keyevent 4; sleep 1
tap 'text="Run garbage collection job"'; until_seen 'collection job: done'; seen 'text="Utilities"'
adb shell cmd uimode night yes; sleep 3; seen 'collection job: done'
adb shell cmd uimode night no; sleep 3
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
adb shell input keyevent 4; sleep 2
seen 'text="local-only"'; seen 'text="example.org"'
```
Expected: the new entry is committed locally (not pushed). The git config screen says "On branch
master", abort is `enabled="false"`; an invalid email shows the old dialog and saves nothing; a
valid one shows the toast and closes; reopened, the fields hold `Test` and `test@example.org`.
The log lists the local commit first and "Initial test store". The reset dialog warns and
prefills `master`; Cancel only closes the dialog; OK resets and the screen stays with the
snackbar "Hard reset to remote branch: done" (maintainer decision 5); the log no longer has the
local commit (`MISSING: local-only`); gc stays too, with its own snackbar and no error dialog,
and a recreation afterwards does not show it again (`MISSING` after the night mode switch, once
the first snackbar has gone; if it is still on screen, wait and repeat the check). Back on the
list `local-only` is `MISSING` (the reset discarded it) and `example.org` is there.

- [ ] **Step 8: Proxy (HTTPS remote) and FLAG_SECURE**

```bash
tap 'content-desc="More options"'; tap 'text="Settings"'; tap 'text="Repository"'
seen 'text="HTTP(S) proxy settings"'
tap 'text="Edit Git server settings"'; sleep 2
field 1 https://example.org/store.git; tap 'text="Save"'; sleep 2
seen 'text="HTTP(S) proxy settings"'; tap 'text="HTTP(S) proxy settings"'; sleep 2
secure
field 1 'bad%shost'; seen 'text="Invalid URL"'
field 1 10.0.2.2; field 2 70000; seen 'Enter a port from 1 to 65535'
field 2 3128; field 3 proxy-user; field 4 proxy-pass
tap 'text="Save"'; sleep 2; secure
tap 'text="HTTP(S) proxy settings"'; sleep 2; value 1; value 2; value 3
field 1 ''; field 2 ''; tap 'text="Save"'; sleep 2
tap 'text="HTTP(S) proxy settings"'; sleep 2; value 1; value 2; value 3
adb shell input keyevent 4; sleep 1
tap 'text="Edit Git server settings"'; sleep 2
field 1 ssh://git@10.0.2.2:2222/srv/git/store.git; tap 'text="Save"'; sleep 2
seen 'text="HTTP(S) proxy settings"'
```
Expected: no proxy row with the SSH remote (`MISSING`); after saving an HTTPS URL (mode falls
back to None) the row appears and opens the proxy screen with `secure` at `1` or more (FLAG_SECURE
on); a host with a space shows "Invalid URL", port 70000 the port error, and Save is disabled
for both; a valid proxy saves with the toast and closes, `secure` back at `0`; reopened, host
`10.0.2.2`, port `3128`, user `proxy-user` (the user name now reads back from where it is
stored); clearing host and port and saving leaves both empty on the next open. Restoring the SSH
URL hides the row again. (`field 1 ''` clears the field: the helper selects all and deletes
before typing nothing; the empty `input text` may print a usage line, which is harmless. If `secure` counts another window's SECURE flag, compare against the
count on the Repository screen.)

- [ ] **Step 9: Key generation refreshes "View generated public SSH key"**

```bash
seen 'text="View generated public SSH key"'
tap 'text="Generate SSH key pair"'; sleep 2
tap 'text="Protect with screen lock credential"'; tap 'text="Generate"'; sleep 5
seen 'text="Replace existing SSH key"'
```
If the replace confirmation shows, confirm it (`tap 'text="Replace"'` or the dialog's confirm
text on this image; dump with `ui` if unsure) and wait for the key:

```bash
seen 'text="Your public key"'; tap 'text="Later"'; sleep 2
seen 'text="View generated public SSH key"'
tap 'text="View generated public SSH key"'; sleep 2; seen 'ecdsa-sha2-nistp'
adb shell input keyevent 4; sleep 1
```
Expected: the row is `MISSING` with the imported e2e key; after generating an ECDSA key in-graph
and leaving the dialog, the Repository screen shows it at once (refreshed on resume), and it
opens the SSH key screen with the public key.

- [ ] **Step 10: Delete repository never follows symlinks**

```bash
adb shell run-as $APP ln -s .. files/store/up
adb shell run-as $APP ls files/pgp_keys
tap 'text="Delete repository"'; seen 'text="Clear repository"'
tap 'text="Cancel"'; seen 'text="Delete repository"'
tap 'text="Delete repository"'; tap 'text="Delete directory"'; sleep 3
seen 'text="Clone Remote Repo"'
adb shell run-as $APP ls -a files/store
adb shell run-as $APP ls files/pgp_keys
adb shell run-as $APP ls -a files | grep -c ssh_key
tap 'text="Settings"'; tap 'text="Repository"'
seen 'text="Generate SSH key pair"'; seen 'text="Delete repository"'
seen 'text="Edit Git server settings"'; seen 'text="Local Git config'
adb shell input keyevent 4; adb shell input keyevent 4; adb shell input keyevent 4; sleep 1
adb shell dumpsys window | grep mCurrentFocus
```
Expected: `store/up` points at the app's `files` folder; Cancel keeps everything; Delete
returns to the welcome screen (the stack was replaced); `files/store` is empty (`.` and `..`);
the PGP key file is still in `pgp_keys` and the SSH key files are still there (count `1` or
more): `deleteRecursively()` would have emptied `files` through the link. Settings from the
welcome screen shows only the rows that need no repository (`MISSING` for the git server and
git config). Back from the welcome screen leaves the app.

- [ ] **Step 11: Minified release build: the enum route argument**

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c
adb shell am start -n xyz.danix.hardenedpass/app.passwordstore.ui.main.LaunchActivity; sleep 3
tap 'text="Settings"'; tap 'text="General"'; seen 'text="Password sort order"'
adb shell input keyevent 4; sleep 1; tap 'text="Misc"'; seen 'text="Debug logging"'
adb logcat -d -b crash | grep -c 'FATAL EXCEPTION'
adb uninstall xyz.danix.hardenedpass
```
Expected: the R8-minified release build opens Settings and two category screens through the
`SettingsSection` route argument; the release build shows the "Debug logging" row (hidden in
debug builds); crash count `0`. The release package is removed from the emulator afterwards.

- [ ] **Step 12: Tear down and push**

```bash
adb shell cmd uimode night no
adb uninstall $APP
adb shell rm -f /sdcard/Download/aps-e2e-ssh-key /sdcard/Download/aps-e2e-pgp-key.asc
adb emu kill
scripts/e2e-env.sh down
unset ANDROID_SERIAL
rm -rf "$OUT"
git push
```

Expected: push succeeds to every `origin` push URL. Look at the screenshots before deleting
`$OUT` if a visual check is wanted (copy them elsewhere first).

---

## Self-review notes

- **Spec coverage for step 6:** Settings as hand-written Compose rows over the existing keys
  (Tasks 2, 3; checked in Task 8 steps 3 to 6), no settings library in use (the dependency goes in
  step 8 with the others); git log (Tasks 1, 5; step 7); proxy (Tasks 1, 4; step 8); the git
  config screen that step 5 left here (Tasks 1, 5; step 7). All four legacy activities, their
  layouts and manifest entries deleted (Task 7). `MainActivity` replaces `SettingsActivity`,
  `GitConfigActivity`, `GitLogActivity`, `ProxySelectorActivity` as the spec lists. Spec gains a
  Settings section and its Setup bullet is updated (Task 7).
- **Maintainer decisions applied:** 1 and 2 as planned (Tasks 3, 5); 3 plus a tested migration
  removing the stale key ID (Task 1); 4: the Android 14 block, its summary and string removed
  (Tasks 3, 7), the tablet check noted in Task 8 step 5; 5: reset and gc stay with a snackbar
  announced once from ViewModel state (Task 5, new string `git_tool_done`; Task 8 step 7).
- **Review findings applied:** `logcat(TAG, ERROR)` with a file `TAG` in `SettingsModel.kt` (the
  top-level `logcat(priority)` form does not exist in logcat 0.1); `deleteRepository` under
  `NonCancellable` and input ignored while it runs (Tasks 2, 3); `normal_margin` named and
  deleted with a grep check (Task 7); the spec's "carry over without migration" amended (Task 7);
  the stale `SSH_OPENKEYSTORE_KEYID` removed and tested (Task 1); a `prompting` guard for screen
  lock prompts (Task 3); `if (x != null) { { Text(x) } } else null` in `PreferenceRows.kt`
  (Task 2); a delete confirmation message of its own, `store_delete_confirm` (Task 3).
- **Known requirements:** "files first" and "independent" removed with a migration to folders
  first and a tolerant reader (Task 1, tested; Task 8 step 4); "Delete repository" uses
  `deleteWithoutFollowingLinks()` (Task 2; Task 8 step 10 with a `store/up -> ..` link); the
  rows step 5 rewired navigate in-graph and "View generated public SSH key" refreshes on resume
  (Tasks 3, 6; Task 8 steps 5, 9); `GitConfigActivity` ported (Task 5).
- **Lessons applied:** enum route argument `@Keep` and checked minified (Tasks 3, 8); work that
  needs the activity in `lifecycleScope` with state in the ViewModel and a `LaunchedEffect`
  (git config), the deletion in `viewModelScope`; compose-lint signatures, `ImmutableList`,
  ViewModels as default parameters and never forwarded, `DispatcherProvider`, no `runCatching`,
  no `Context` field; content descriptions on every icon-only control (back, password toggle);
  secrets only in ViewModel memory and `KeystorePreferences`, presence tested with `contains`;
  confirmations for delete, clear and reset, recreation-safe (`rememberSaveable` dialog state,
  ViewModel results); strings in `values/` and `values-it/`, each added by the task that uses it;
  `:app:lintDebug` in every task, full CI in Task 7; emulator pinned to `emulator-5554` with the
  e2e server (Task 8) and a smoke check in Task 6.
- **Fixes found while reading the legacy code:** the clear-saved-credential row never cleared
  anything (Task 2, 3); `GitSettings.proxyUsername` read the wrong preferences, so proxy
  authentication never worked (Task 1, tested; Task 8 step 8); `BreakOutOfDetached` finished the
  calling activity (Task 5); the proxy port crashed on overflow and could not be cleared (Tasks
  1, 4); the commit log was read on the main thread (Task 5); gated switches stored unconfirmed
  values (Task 3).
- **Not checked on the emulator:** clearing a saved credential (the e2e key has no passphrase
  and the e2e server no HTTPS); the abort rebase success path (needs a conflicting remote
  commit; the button's disabled state is checked, and the operation itself is unchanged and
  covered by the full device checklist before merge: "conflict recovery"); the autofill
  service picker's OK (it changes a device setting). The maintainer covers these on the phone if
  he uses them.
- **Deliberate shortcuts (`ponytail:`):** git operations of the git config screen cancelled by a
  theme or locale change (Task 5), as everywhere in the app; the whole commit log read at once
  (Task 1).
- **Type and name consistency:** `proxyPort(text: String): Int?`, `HeadStatus.Branch/Detached/
  Missing`, `headStatus(repo)`, `needsAbort(repo)`, `gitLog(repo: Repository?): List<GitCommit>`
  (Task 1) are used with these signatures in Tasks 4 and 5. `rowColors`, `PreferenceAction`,
  `PreferenceSwitch`, `PreferenceChoice(title, values, labels, selected, onSelect)`,
  `PreferenceText(title, value, onValueChange, modifier, summary, hint, singleLine,
  keyboardType, enabled)` (Task 2) match Task 3. `SettingsState` fields and
  `SettingsViewModel.state/deleting/deleted/refresh/putBoolean/putString/setPassphraseCache/
  disableAutoClear/resetAutoClear/clearSavedSecret/deleteRepository` (Task 2) match Task 3.
  New strings: `store_delete_failed`, `clear_saved_secret_message`, `store_delete_confirm`
  (Task 3), `invalid_proxy_port` (Task 4), `git_reset_confirm_message`, `git_tool_done` (Task 5),
  each in `values/` and `values-it/`.
  `SettingsSection`, `SettingsAction` (Task 3) match Task 6's routes and `onOpen`.
  `ProxyViewModel.host/port/username/password/hostError/portError/save` and `isProxyHost`
  (Task 4); `GitConfigViewModel.name/email/head/canAbort/busy/done/abortedBranch/refresh/save`,
  `MainActivity.gitOperation(op, branch)`, `ResetDialog`, `GitLogViewModel.commits`,
  `GitLogDestination(onClose)` (Task 5) match Task 6. `SetupDialog(text, confirm, onConfirm,
  onDismiss, modifier, title, dismiss, onDismissButton)` from step 5 is used with named
  arguments. `MainActivity.holdSecure/releaseSecure` (step 3) are public.
- **Build order:** Task 1 changes the sort enum, its arrays and strings together, so the legacy
  Settings keeps building; Tasks 2 to 5 add unreferenced but compiled code with the strings it
  uses; Task 6 wires it and leaves the legacy files compiling and unreferenced; Task 7 deletes
  them and the two `SetupStart` values their last callers used.

## Maintainer decisions (2026-11-03)

1. **Settings layout:** two levels, categories then one screen per category, as planned (Tasks 3,
   6).
2. **Hard reset:** the warning and the current branch prefilled, as planned (Task 5).
3. **"Clear remembered OpenKeystore SSH Key ID":** dropped, as planned; a migration also removes
   the stale `SSH_OPENKEYSTORE_KEYID` value (Task 1, tested).
4. **Biometric app lock on Android 14:** changed. Re-enabled: the Android 14 block and its
   "Feature unavailable due to a platform bug" path are removed, the string deleted from
   `values/` and `values-it/` (Tasks 3, 7). The maintainer checks the app lock on an Android 14
   tablet (Task 8 step 5); if the platform bug appears, the block returns.
5. **After a successful hard reset or gc:** changed. The git config screen stays and shows a short
   snackbar (`git_tool_done`, "%1$s: done"), kept in `GitConfigViewModel.done` until shown, so it is
   announced once across a recreation (Task 5; Task 8 step 7).
