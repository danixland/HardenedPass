# Compose UI overhaul, step 2: Password list. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy `PasswordStore` activity and `PasswordFragment` with a Compose list screen behind `MainActivity`'s `ListRoute`: pill search bar, breadcrumb chips, folders and entries in grouped surfaces, pull to sync, long-press multi-select (move, delete, rename, pin) and a create sheet. Screens not ported yet keep opening through their activity intents.

**Architecture:** One back stack entry per folder level: `ListRoute(path)`, where `path` is relative to the store root and `""` is the root. Each entry gets its own `SearchableRepositoryViewModel` through `hiltViewModel()`, so its rows, query and scroll position survive navigation, rotation and process death without extra code. System back pops one folder (the `NavHost` does it); at the root it leaves the app. Breadcrumb jumps and opening a folder from search results go through one pure function, `folderMoves`, that keeps the back stack equal to the folder path. `MainActivity` now extends `BaseGitActivity`, because the git operations (pull to sync, menu actions, commits after move and delete) need an `AppCompatActivity`; the store operations move from `PasswordStore` into top-level functions in `ui/list/StoreActions.kt`. The list refreshes every time its entry resumes, which also covers results coming back from legacy activities (editor, onboarding, folder picker, settings).

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2024.09.03 (Material 3 1.3.0: `SearchBar`, `Modifier.pullToRefresh`, `ModalBottomSheet`), navigation-compose 2.8.9, `androidx.hilt:hilt-navigation-compose:1.2.0` (new), `androidx.lifecycle:lifecycle-runtime-compose` 2.8.6 (made explicit), kotlinx-collections-immutable (already a dependency).

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`. Step 1 plan: `docs/superpowers/plans/2026-11-02-compose-foundation.md`.

## Decisions taken in this plan

- **Folder depth lives in the route, not in ViewModel state.** `ListRoute(val path: String = "")`. Reasons: system back pops one level for free; the navigation back stack (with its arguments) is saved across process death, while the ViewModel's private `navigationStack` is not; per-entry `rememberSaveable` keeps each folder's query and scroll position, which replaces the old RecyclerView state stack. Breadcrumb jumps pop entries; a folder opened from search results rebuilds the stack from the shared parent (`folderMoves`), so back always goes to the parent folder. `MainActivity` and `LaunchActivity` start the list exactly as before (`ListRoute()` is the root).
- **Entry subtitle:** none while browsing; in search results, the entry's folder relative to the current folder (what the old list showed above the name). Username and OTP are not shown: the default `DirectoryStructure` (`EncryptedUsername`) keeps the username inside the encrypted file, the path-based structures would only repeat the row label or the folder already shown in the breadcrumbs, and OTP is only knowable after decrypting. The list never decrypts. Task 5 corrects the spec line.
- **Tapping an entry still starts `DecryptActivity` directly** (as the old list did), not through `EntryRoute`: `EntryRoute`'s step 1 trampoline finishes `MainActivity`, which would close the list. Step 3 switches the tap to `navigate(EntryRoute(...))`.
- **`MainActivity` extends `BaseGitActivity`** (an `AppCompatActivity`, hence still a `FragmentActivity` for `BiometricPrompt`). Git operations cast their activity to `AppCompatActivity`. It gets a no-action-bar theme so AppCompat does not draw a bar over Compose. Task 5 corrects the spec line.
- **Results from legacy activities** are not read through result callbacks except for the folder picker (which returns data). The list refreshes on every resume of its back stack entry, which is when any legacy activity returns.
- **Dialogs:** create sheet, new folder, rename, delete confirmation, clone prompt and "create a store first" are Compose. The overwrite confirmation and the move error that can pop up in the middle of a move stay `MaterialAlertDialogBuilder`, like the git error prompts in `BaseGitActivity` (they are `ponytail:` marked).
- **Security:** `LaunchActivity`'s gate and `isFileInRepo` check are untouched. Entry paths given to `DecryptActivity` come from the app's own directory listing, as before. New folder names get the same "inside the repository" check renames already had (old folder creation had none). The autofill inline suggestion's attribution intent now opens `LaunchActivity` (biometric gate) instead of the list activity directly.

## Feature parity (old `PasswordStore` / `PasswordFragment` behavior)

| Behavior today | Decision |
|---|---|
| Onboarding redirect when `REPOSITORY_INITIALIZED` is false (checked on every resume, after `PasswordRepository.initialize()`) | Port (`MainActivity.checkStore`, called on resume) |
| `REPO_CHANGED` re-creates the fragment | Drop: nothing sets the key any more; the list re-reads the store on every resume anyway |
| `matchWith` extra (pick an entry and return it to the caller) | Drop: no caller sets it, dead code |
| Folder browsing with title = folder name, up arrow, back pops a folder | Port as breadcrumbs plus back stack entries |
| Search (fuzzy, recursive per `FILTER_RECURSIVELY`, sort per `SORT_ORDER`, `SHOW_HIDDEN_CONTENTS`) | Port: reuses the ViewModel unchanged |
| Search clears when a folder is opened; back collapses search | Port (`BackHandler` clears the query first) |
| `SEARCH_ON_START` opens search | Port (focuses the search field) |
| Type-to-search and Ctrl+F / search key on a hardware keyboard | Drop: the search field is always on screen, one tap away |
| Overflow: Sync / Pull / Push (by auth mode and git state), "create a store first" dialog when not initialized | Port |
| Overflow: Settings | Port (opens `SettingsActivity`) |
| Overflow: "Refresh list" | Drop: the list refreshes on every resume and after every action, pull to sync also refreshes |
| Pull to sync: refresh only without a `.git` dir, clone prompt for a non-git store, Pull for `AuthMode.None` else Sync, errors via `promptOnErrorHandler`, disabled by `DISABLE_SYNC_ACTION` and while selecting | Port |
| Tap entry: `DecryptActivity` plus dynamic shortcut | Port (legacy intent until step 3) |
| `RECENTLY_USED` sort records open time of tapped folders and entries | Port (`recordUse`) |
| Folder child count (subfolders and `.gpg` files) | Port (`FolderRow` count) |
| Multi-select: move (folder picker, overwrite prompt, autofill match update, commit) | Port (folder picker stays the legacy `SelectFolderActivity` until step 4) |
| Multi-select: delete (no prompt for empty folders, plural prompt otherwise, autofill update, commit) | Port |
| Multi-select: rename folders (validation, recently-used timestamp carried over, commit) | Port, one folder at a time (`ponytail:`, old code queued one dialog per selected folder) |
| Multi-select: pin entry shortcut on the home screen | Port |
| FAB opens the create sheet: new password (legacy editor), new folder (dialog, then opens it) | Port (editor stays `PasswordCreationActivity` until step 4) |
| Fast scroller, item animations, dividers | Drop: no Compose equivalent in our dependencies; search covers jumping |
| `onKeyDown` printable-char helper `isPrintable` | Drop with type-to-search |

Left for later steps because other legacy screens still use them: `PasswordItemRecyclerAdapter`, `password_recycler_view.xml`, `password_row_layout.xml`, `SearchableRepositoryAdapter` and fastscroll (`SelectFolderFragment`, `AutofillFilterView`), `BasicBottomSheet` (`GitServerConfigActivity`), `swiperefreshlayout` (the `password_recycler_view.xml` layout still contains it), the unused `PreferenceKeys.REPO_CHANGED` constant (step 8).

**Conventions for every task**

- New files get this header:
  ```kotlin
  /*
   * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
   * SPDX-License-Identifier: GPL-3.0-only
   */
  ```
- Commits are GPG-signed (default config, never disable). End each message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No em dashes in docs, comments or commit messages.
- Before each commit run `./gradlew spotlessApply`, then the step's checks.
- Work on the `compose` branch.

---

### Task 1: hilt-navigation-compose and lifecycle-runtime-compose

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Catalog entries**

In `[libraries]` of `gradle/libs.versions.toml`, after `androidx-fragment-ktx`, add:

```toml
androidx-hilt-navigation-compose = "androidx.hilt:hilt-navigation-compose:1.2.0"
```

After `androidx-lifecycle-livedataKtx`, add:

```toml
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
```

(`collectAsStateWithLifecycle` and `LifecycleResumeEffect` live there. It already arrives
transitively through Compose UI; declaring it pins it to the catalog's lifecycle version.)

- [ ] **Step 2: Use them in the app module**

In `app/build.gradle.kts` `dependencies { }`, after `implementation(libs.androidx.fragment.ktx)`:

```kotlin
  implementation(libs.androidx.hilt.navigation.compose)
```

and after `implementation(libs.androidx.lifecycle.viewmodel.compose)`:

```kotlin
  implementation(libs.androidx.lifecycle.runtime.compose)
```

- [ ] **Step 3: Build and lint**

Run: `CI=1 ./gradlew :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. If lint reports `app/lint-baseline.xml` entries that no longer match
(baselined jars are keyed on their cache path, see AGENTS.md), update the moved paths in
`app/lint-baseline.xml` and re-run.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/lint-baseline.xml
git commit -m "build(app): add hilt-navigation-compose and lifecycle-runtime-compose

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: List model (TDD)

Two pure functions: the rows for one folder (or one search inside it), and the back stack moves
between two folder paths.

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/list/ListModel.kt`
- Test: `app/src/test/java/app/passwordstore/ui/list/ListModelTest.kt`
- Modify: `app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/app/passwordstore/ui/list/ListModelTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ListModelTest {

  private val root = Files.createTempDirectory("aps").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun file(path: String) =
    File(root, path).apply {
      parentFile!!.mkdirs()
      writeText("x")
    }

  private fun folder(path: String) = File(root, path).apply { mkdirs() }

  @Test
  fun openingAChildFolderPushesIt() {
    assertEquals(0 to listOf("work"), folderMoves("", "work"))
  }

  @Test
  fun breadcrumbJumpsOnlyPop() {
    assertEquals(1 to emptyList<String>(), folderMoves("work/servers", "work"))
    assertEquals(2 to emptyList<String>(), folderMoves("work/servers", ""))
  }

  @Test
  fun searchResultsRebuildTheStackFromTheSharedParent() {
    assertEquals(1 to listOf("personal", "personal/mail"), folderMoves("work", "personal/mail"))
    assertEquals(
      0 to listOf("work/servers", "work/servers/eu"),
      folderMoves("work", "work/servers/eu"),
    )
  }

  @Test
  fun sameFolderMovesNothing() {
    assertEquals(0 to emptyList<String>(), folderMoves("work", "work"))
  }

  @Test
  fun browsingShowsNamesAndFolderCounts() {
    val work = folder("work")
    file("work/mail.gpg")
    file("work/notes.txt")
    folder("work/servers")
    val site = file("site.gpg")

    val rows = listRows(listOf(work, site), root)

    assertEquals(listOf(ListRow(work.absolutePath, "work", null, 2)), rows.folders)
    assertEquals(listOf(ListRow(site.absolutePath, "site", null, 0)), rows.entries)
  }

  @Test
  fun searchResultsShowWhereTheyAre() {
    val servers = folder("work/servers")
    val vpn = file("work/vpn.gpg")

    val rows = listRows(listOf(vpn, servers), root)

    assertEquals(listOf(ListRow(servers.absolutePath, "work/servers", null, 0)), rows.folders)
    assertEquals(listOf(ListRow(vpn.absolutePath, "vpn", "work", 0)), rows.entries)
  }

  @Test
  fun keepsTheGivenOrderInsideEachGroup() {
    val b = file("b.gpg")
    val a = file("a.gpg")
    val z = folder("z")
    val y = folder("y")

    val rows = listRows(listOf(b, z, a, y), root)

    assertEquals(listOf(z.absolutePath, y.absolutePath), rows.folders.map { it.path })
    assertEquals(listOf(b.absolutePath, a.absolutePath), rows.entries.map { it.path })
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.list.ListModelTest`
Expected: compilation FAILS with `Unresolved reference 'folderMoves'` / `'listRows'` / `'ListRow'`.

- [ ] **Step 3: Implement the model**

`app/src/main/java/app/passwordstore/ui/list/ListModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import app.passwordstore.ui.compose.crumbsFor
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * One list row. [path] is the absolute path and the row's key; [count] is a folder's number of
 * children, 0 for entries.
 */
data class ListRow(val path: String, val label: String, val subtitle: String?, val count: Int)

/** The rows of one folder, or of a search inside it: folders above entries. */
data class ListRows(
  val folders: ImmutableList<ListRow> = persistentListOf(),
  val entries: ImmutableList<ListRow> = persistentListOf(),
)

/**
 * Splits [files] (already sorted or ranked) into folder and entry rows for [dir], keeping their
 * order. Labels and subtitles are relative to [dir]: browsing shows plain names, search results
 * show where they are. Entries get no username or OTP subtitle: both live inside the encrypted
 * file, and the list never decrypts.
 */
fun listRows(files: List<File>, dir: File): ListRows {
  val (folders, entries) = files.partition { it.isDirectory }
  return ListRows(
    folders
      .map { ListRow(it.absolutePath, it.relativeTo(dir).path, null, childCount(it)) }
      .toImmutableList(),
    entries
      .map {
        val parent = it.parentFile?.relativeTo(dir)?.path.orEmpty()
        ListRow(it.absolutePath, it.name.removeSuffix(".gpg"), parent.ifEmpty { null }, 0)
      }
      .toImmutableList(),
  )
}

/** Children counted for a folder: subfolders and .gpg files, as the old list counted them. */
private fun childCount(folder: File): Int =
  folder.listFiles { f -> f.isDirectory || f.extension == "gpg" }?.size ?: 0

/**
 * How to get from the list at [from] to the list at [to] (both relative to the store root) while
 * the back stack holds one entry per folder level: how many entries to pop, then which paths to
 * push. Breadcrumb jumps only pop; opening a folder from search results may pop and push.
 */
fun folderMoves(from: String, to: String): Pair<Int, List<String>> {
  val current = crumbsFor(from)
  val target = crumbsFor(to)
  val shared = current.zip(target).takeWhile { (a, b) -> a == b }.size
  return (current.size - shared) to target.drop(shared).map { it.path }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.list.ListModelTest`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Expose rows from the ViewModel**

In `SearchableRepositoryViewModel.kt`, directly after the line
`val currentDir = _currentDir.asStateFlow()`, add:

```kotlin

  /** [searchResult] as folder and entry rows for the Compose list, built off the main thread. */
  val rows =
    searchResult
      .map { result -> listRows(result.passwordItems.map { it.file }, currentDir.value) }
      .flowOn(dispatcherProvider.io())
```

and add the import `import app.passwordstore.ui.list.listRows`. Nothing else in the ViewModel
changes; `AutofillFilterView` and `SelectFolderFragment` never collect `rows`.

- [ ] **Step 6: Build and test**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all unit tests pass (existing ones included).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/list/ListModel.kt \
  app/src/test/java/app/passwordstore/ui/list/ListModelTest.kt \
  app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt
git commit -m "feat(app): add the list rows and folder navigation model

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Store operations

Port of `PasswordStore`'s delete, move, rename and recently-used code into top-level functions,
so the Compose list can call them on `MainActivity`. Behavior is the same except for two fixes
noted inline: a move now waits for the overwrite answer before committing (the old code
committed first and moved later), and a new folder name is checked to stay inside the store.

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/list/StoreActions.kt`

- [ ] **Step 1: Write the functions**

`app/src/main/java/app/passwordstore/ui/list/StoreActions.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import android.content.Context
import androidx.core.content.edit
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.BasePGPActivity.Companion.getLongName
import app.passwordstore.ui.git.base.BaseGitActivity
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.extensions.base64
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.isInsideRepository
import app.passwordstore.util.extensions.listFilesRecursively
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PasswordSortOrder
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.logcat

/** Shared preferences file of the "recently used" sort order, keyed by base64 of the path. */
private const val HISTORY = "recent_password_history"

/** Password files in [files], counting a folder by everything below it. */
fun countFiles(files: List<File>): Int =
  files.sumOf { if (it.isFile) 1 else it.listFilesRecursively().size }

/** Why [name] cannot become the folder [target], as a string resource, or null when it can. */
fun folderNameError(name: String, target: File): Int? =
  when {
    name.isBlank() -> R.string.message_category_error_empty_field
    target.isFile -> R.string.folder_creation_err_file_exists
    target.isDirectory -> R.string.folder_creation_err_folder_exists
    !target.isInsideRepository() -> R.string.message_error_destination_outside_repo
    else -> null
  }

/** Records that [file] was opened, for the "recently used" sort order. */
fun Context.recordUse(file: File) {
  if (sharedPrefs.getString(PreferenceKeys.SORT_ORDER) != PasswordSortOrder.RECENTLY_USED.name)
    return
  getSharedPreferences(HISTORY, Context.MODE_PRIVATE).edit {
    putString(file.absolutePath.base64(), System.currentTimeMillis().toString())
  }
}

/** Deletes [files] (entries or whole folders), drops their autofill matches and commits. */
suspend fun BaseGitActivity.deleteItems(files: List<File>) {
  val root = PasswordRepository.getRepositoryDirectory()
  val deleted = files.flatMap { if (it.isDirectory) it.listFilesRecursively() else listOf(it) }
  files.forEach { it.deleteRecursively() }
  AutofillMatcher.updateMatches(applicationContext, delete = deleted)
  commitChange(
    getString(R.string.git_commit_remove_text, files.joinToString(", ") { it.toRelativeString(root) })
  )
}

/** Moves [files] into the folder [target], asking before overwriting, then commits. */
suspend fun BaseGitActivity.moveItems(files: List<File>, target: File) {
  val repo = PasswordRepository.getRepositoryDirectory().absolutePath
  for (source in files) {
    if (!source.exists()) {
      logcat(ERROR) { "Tried moving something that appears non-existent." }
      continue
    }
    val destination = File(target, source.name)
    val name = source.nameWithoutExtension
    if (
      destination.exists() &&
        !confirm(
          getString(R.string.password_exists_title),
          getString(
            R.string.password_exists_message,
            getLongName(target.absolutePath, repo, name),
            getLongName(source.parent.orEmpty(), repo, name),
          ),
        )
    )
      continue
    moveFile(source, destination)
  }
  val message =
    if (files.size == 1) {
      val name = files[0].nameWithoutExtension
      getString(
        R.string.git_commit_move_text,
        getLongName(files[0].parent.orEmpty(), repo, name),
        getLongName(target.absolutePath, repo, name),
      )
    } else {
      val relative = "${target.absolutePath}/".replace(repo, "").replace("/+".toRegex(), "/")
      getString(R.string.git_commit_move_multiple_text, relative)
    }
  commitChange(message)
}

/** Renames the folder [from] to [to], keeps its recently-used time, then commits. */
suspend fun BaseGitActivity.renameFolder(from: File, to: File) {
  moveFile(from, to)
  val history = getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
  val time = history.getString(from.absolutePath.base64())
  if (time != null)
    history.edit {
      remove(from.absolutePath.base64())
      putString(to.absolutePath.base64(), time)
    }
  commitChange(getString(R.string.git_commit_move_text, from.name, to.name))
}

private suspend fun BaseGitActivity.moveFile(source: File, destination: File) {
  val moved =
    withContext(dispatcherProvider.io()) {
      val map =
        if (source.isDirectory) {
          destination.mkdirs()
          source.listFilesRecursively().associateWith { destination.resolve(it.relativeTo(source)) }
        } else {
          mapOf(source to destination)
        }
      source.renameTo(destination).also { ok ->
        if (ok) AutofillMatcher.updateMatches(this@moveFile, map)
      }
    }
  if (!moved) {
    logcat(ERROR) { "Something went wrong while moving $source to $destination." }
    // ponytail: View dialog like BaseGitActivity's git prompts; both move to Compose together.
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.password_move_error_title)
      .setMessage(getString(R.string.password_move_error_message, source, destination))
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }
}

/** Shows [message] under [title] and suspends until answered; true when confirmed. */
// ponytail: View dialog, see moveFile.
private suspend fun Context.confirm(title: String, message: String): Boolean =
  suspendCancellableCoroutine { cont ->
    MaterialAlertDialogBuilder(this)
      .setTitle(title)
      .setMessage(message)
      .setPositiveButton(R.string.dialog_ok) { _, _ -> cont.resume(true) }
      .setNegativeButton(R.string.dialog_cancel) { _, _ -> cont.resume(false) }
      .setOnCancelListener { cont.resume(false) }
      .show()
  }
```

- [ ] **Step 2: Build**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (the functions are not called yet; unused top-level public functions
produce no Kotlin warning).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/list/StoreActions.kt
git commit -m "feat(app): port the list's store operations out of PasswordStore

A move now waits for the overwrite answer before it commits, and new
folder names must stay inside the store.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: List screen (stateless UI) and strings

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/list/ListScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`

- [ ] **Step 1: Strings**

In `app/src/main/res/values/strings.xml`, before the closing `</resources>`, add:

```xml
  <!-- Compose password list -->
  <string name="list_new_entry">New entry</string>
  <string name="list_store_root">Password store</string>
  <string name="list_more_options">More options</string>
  <string name="list_clear_search">Clear search</string>
```

In `app/src/main/res/values-it/strings.xml`, before the closing `</resources>`, add:

```xml
  <!-- Compose password list -->
  <string name="list_new_entry">Nuova voce</string>
  <string name="list_store_root">Archivio password</string>
  <string name="list_more_options">Altre opzioni</string>
  <string name="list_clear_search">Cancella ricerca</string>
```

Everything else reuses existing strings (`action_search`, `action_settings`, `git_sync`,
`git_pull`, `git_push`, `delete_title`, `move`, `delete`, `title_rename_folder`,
`place_shortcut_on_home_screen`, `dialog_cancel`, `bottom_sheet_create_new_password`,
`bottom_sheet_create_new_folder`) and existing drawables (`ic_search_24dp`,
`ic_move_folder_24dp`, `ic_delete_24dp`, `ic_edit_24dp`, `ic_push_pin_24dp`,
`ic_action_new_password`, `ic_action_new_folder`).

- [ ] **Step 2: The screen**

`app/src/main/java/app/passwordstore/ui/list/ListScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.BreadcrumbBar
import app.passwordstore.ui.compose.EntryRow
import app.passwordstore.ui.compose.FolderRow
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.crumbsFor
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet

/**
 * The password list: search bar, breadcrumbs, folders above entries, pull to sync, long-press
 * selection and the "New entry" button. Row callbacks take absolute paths; [onCrumb] takes a path
 * relative to the store root, like [path].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
  path: String,
  query: String,
  rows: ListRows,
  selected: ImmutableSet<String>,
  refreshing: Boolean,
  canSync: Boolean,
  gitOps: ImmutableList<GitOp>,
  onQueryChange: (String) -> Unit,
  onCrumb: (relativePath: String) -> Unit,
  onOpenFolder: (path: String) -> Unit,
  onOpenEntry: (path: String) -> Unit,
  onToggle: (path: String) -> Unit,
  onClearSelection: () -> Unit,
  onSync: () -> Unit,
  onGitOp: (GitOp) -> Unit,
  onSettings: () -> Unit,
  onNew: () -> Unit,
  onMove: () -> Unit,
  onDelete: () -> Unit,
  onRename: () -> Unit,
  onPin: () -> Unit,
  modifier: Modifier = Modifier,
  searchFocus: FocusRequester = remember { FocusRequester() },
) {
  val selecting = selected.isNotEmpty()
  val listState = rememberLazyListState()
  val pullState = rememberPullToRefreshState()
  // Filtered results always start at the top, where the best match is.
  LaunchedEffect(query) { if (query.isNotEmpty()) listState.scrollToItem(0) }
  Scaffold(
    modifier = modifier,
    topBar = {
      if (selecting) {
        SelectionBar(rows, selected, onClearSelection, onMove, onDelete, onRename, onPin)
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          SearchField(query, gitOps, onQueryChange, onGitOp, onSettings, searchFocus)
          BreadcrumbBar(
            crumbsFor(path),
            stringResource(R.string.list_store_root),
            onCrumb,
            Modifier.fillMaxWidth().padding(vertical = SpacingMedium),
          )
        }
      }
    },
    floatingActionButton = {
      if (!selecting)
        ExtendedFloatingActionButton(
          text = { Text(stringResource(R.string.list_new_entry)) },
          icon = { Icon(Icons.Filled.Add, contentDescription = null) },
          onClick = onNew,
        )
    },
  ) { padding ->
    Box(
      Modifier.padding(padding)
        .fillMaxSize()
        .pullToRefresh(refreshing, pullState, enabled = canSync && !selecting, onRefresh = onSync)
    ) {
      // ponytail: each group is one lazy item, fine for hundreds of rows; split rows into their
      // own items (with per-row corner shapes) if a store ever has thousands in one folder.
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
      ) {
        if (rows.folders.isNotEmpty())
          item(key = "folders") {
            GroupedSurface(Modifier.fillMaxWidth()) {
              rows.folders.forEach { row ->
                key(row.path) {
                  FolderRow(
                    name = row.label,
                    count = row.count,
                    onClick = { if (selecting) onToggle(row.path) else onOpenFolder(row.path) },
                    selected = row.path in selected,
                    onLongClick = { onToggle(row.path) },
                  )
                }
              }
            }
          }
        if (rows.entries.isNotEmpty())
          item(key = "entries") {
            GroupedSurface(Modifier.fillMaxWidth()) {
              rows.entries.forEach { row ->
                key(row.path) {
                  EntryRow(
                    name = row.label,
                    subtitle = row.subtitle,
                    onClick = { if (selecting) onToggle(row.path) else onOpenEntry(row.path) },
                    selected = row.path in selected,
                    onLongClick = { onToggle(row.path) },
                  )
                }
              }
            }
          }
      }
      PullToRefreshDefaults.Indicator(pullState, refreshing, Modifier.align(Alignment.TopCenter))
    }
  }
}

/** Bottom sheet behind the "New entry" button: a password entry or a folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSheet(
  onDismiss: () -> Unit,
  onNewEntry: () -> Unit,
  onNewFolder: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
    ListItem(
      headlineContent = { Text(stringResource(R.string.bottom_sheet_create_new_password)) },
      modifier = Modifier.clickable(onClick = onNewEntry),
      leadingContent = {
        Icon(painterResource(R.drawable.ic_action_new_password), contentDescription = null)
      },
    )
    ListItem(
      headlineContent = { Text(stringResource(R.string.bottom_sheet_create_new_folder)) },
      modifier = Modifier.clickable(onClick = onNewFolder),
      leadingContent = {
        Icon(painterResource(R.drawable.ic_action_new_folder), contentDescription = null)
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
  query: String,
  gitOps: ImmutableList<GitOp>,
  onQueryChange: (String) -> Unit,
  onGitOp: (GitOp) -> Unit,
  onSettings: () -> Unit,
  focus: FocusRequester,
) {
  val focusManager = LocalFocusManager.current
  var menu by remember { mutableStateOf(false) }
  SearchBar(
    inputField = {
      SearchBarDefaults.InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { focusManager.clearFocus() },
        expanded = false,
        onExpandedChange = {},
        modifier = Modifier.focusRequester(focus),
        placeholder = { Text(stringResource(R.string.action_search)) },
        leadingIcon = {
          Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null)
        },
        trailingIcon = {
          Row {
            if (query.isNotEmpty())
              IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, stringResource(R.string.list_clear_search))
              }
            Box {
              IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.list_more_options))
              }
              DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                gitOps.forEach { op ->
                  DropdownMenuItem(
                    text = { Text(stringResource(op.label)) },
                    onClick = {
                      menu = false
                      onGitOp(op)
                    },
                  )
                }
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.action_settings)) },
                  onClick = {
                    menu = false
                    onSettings()
                  },
                )
              }
            }
          }
        },
      )
    },
    expanded = false,
    onExpandedChange = {},
  ) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
  rows: ListRows,
  selected: ImmutableSet<String>,
  onClear: () -> Unit,
  onMove: () -> Unit,
  onDelete: () -> Unit,
  onRename: () -> Unit,
  onPin: () -> Unit,
) {
  val single = selected.singleOrNull()
  TopAppBar(
    title = { Text(pluralStringResource(R.plurals.delete_title, selected.size, selected.size)) },
    navigationIcon = {
      IconButton(onClick = onClear) {
        Icon(Icons.Filled.Close, stringResource(R.string.dialog_cancel))
      }
    },
    actions = {
      ActionIcon(R.drawable.ic_move_folder_24dp, R.string.move, onMove)
      ActionIcon(R.drawable.ic_delete_24dp, R.string.delete, onDelete)
      // ponytail: rename one folder at a time; the old list queued one dialog per folder.
      if (rows.folders.any { it.path == single })
        ActionIcon(R.drawable.ic_edit_24dp, R.string.title_rename_folder, onRename)
      if (rows.entries.any { it.path == single })
        ActionIcon(R.drawable.ic_push_pin_24dp, R.string.place_shortcut_on_home_screen, onPin)
    },
  )
}

@Composable
private fun ActionIcon(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
  IconButton(onClick = onClick) {
    Icon(painterResource(icon), contentDescription = stringResource(label))
  }
}

private val GitOp.label: Int
  get() =
    when (this) {
      GitOp.SYNC -> R.string.git_sync
      GitOp.PUSH -> R.string.git_push
      else -> R.string.git_pull
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If `SearchBar`'s `inputField` overload does not resolve, the
Compose BOM is older than 2024.09 (material3 1.3.0); check `gradle/libs.versions.toml` before
changing code.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/list/ListScreen.kt \
  app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat(app): add the Compose password list screen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Wire the list into MainActivity

After this task the launcher opens the Compose list. `PasswordStore` still compiles but nothing
starts it except the autofill attribution intent (fixed in Task 6).

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`
- Modify (replace whole file): `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/res/values/themes_material3.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`

- [ ] **Step 1: The list destination**

`app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.folderselect.SelectFolderActivity
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.ui.git.config.GitServerConfigActivity
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.PreferenceKeys
import app.passwordstore.util.viewmodel.SearchableRepositoryViewModel
import java.io.File
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.launch

private enum class ListDialog {
  None,
  Create,
  NewFolder,
  Rename,
  Delete,
  Clone,
  NotInitialized,
}

/**
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * legacy activities and the store operations. [onOpenFolder] takes a path relative to the root.
 */
@Composable
fun ListDestination(
  activity: MainActivity,
  path: String,
  onOpenFolder: (relativePath: String) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SearchableRepositoryViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  val dir = File(root, path)
  val prefs = activity.sharedPrefs
  var query by rememberSaveable { mutableStateOf("") }
  var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
  var dialog by rememberSaveable { mutableStateOf(ListDialog.None) }
  var deleteCount by rememberSaveable { mutableIntStateOf(0) }
  var refreshing by remember { mutableStateOf(false) }
  var resumes by remember { mutableIntStateOf(0) }
  val searchFocus = remember { FocusRequester() }

  // Declared before the collection below on purpose: effects start in order, so the ViewModel
  // already points at this folder when the first rows are built (no flash of the store root).
  LaunchedEffect(path, query) {
    viewModel.navigateTo(dir, pushPreviousLocation = false)
    if (query.isNotBlank()) viewModel.search(query.trim())
  }
  val rows by viewModel.rows.collectAsStateWithLifecycle(ListRows())
  val gitOps = remember(resumes) { activity.gitOps() }
  val canSync = remember(resumes) { !prefs.getBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, false) }

  LaunchedEffect(Unit) {
    if (prefs.getBoolean(PreferenceKeys.SEARCH_ON_START, false)) searchFocus.requestFocus()
  }
  // Every return to this folder (back from a child, or from a legacy activity) re-reads it.
  LifecycleResumeEffect(Unit) {
    if (activity.checkStore()) {
      if (path.isNotEmpty() && !dir.isDirectory) onOpenFolder("") else viewModel.forceRefresh()
    }
    resumes++
    onPauseOrDispose {}
  }
  BackHandler(enabled = selected.isNotEmpty()) { selected = emptyList() }
  BackHandler(enabled = selected.isEmpty() && query.isNotEmpty()) { query = "" }

  val moveLauncher =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      val data = result.data
      val target = data?.getStringExtra("SELECTED_FOLDER_PATH")?.let(::File)
      val files = data?.getStringArrayExtra("Files")?.map(::File)
      if (result.resultCode == Activity.RESULT_OK && target?.isDirectory == true && files != null) {
        selected = emptyList()
        activity.lifecycleScope.launch {
          activity.moveItems(files, target)
          viewModel.forceRefresh()
        }
      }
    }

  fun ifInitialized(action: () -> Unit) {
    if (PasswordRepository.isInitialized) action() else dialog = ListDialog.NotInitialized
  }

  fun runGit(op: GitOp) {
    refreshing = true
    activity.lifecycleScope.launch {
      activity.runGit(op)
      refreshing = false
      viewModel.forceRefresh()
    }
  }

  Box(modifier) {
    ListScreen(
      path = path,
      query = query,
      rows = rows,
      selected = selected.toImmutableSet(),
      refreshing = refreshing,
      canSync = canSync,
      gitOps = gitOps,
      onQueryChange = { query = it },
      onCrumb = onOpenFolder,
      onOpenFolder = { folder ->
        activity.recordUse(File(folder))
        query = ""
        onOpenFolder(File(folder).relativeTo(root).path)
      },
      onOpenEntry = { entry ->
        activity.recordUse(File(entry))
        activity.openEntry(File(entry))
      },
      onToggle = { p -> selected = if (p in selected) selected - p else selected + p },
      onClearSelection = { selected = emptyList() },
      onSync = {
        when {
          root.resolve(".git").listFiles().isNullOrEmpty() -> viewModel.forceRefresh()
          !PasswordRepository.isGitRepo() -> dialog = ListDialog.Clone
          activity.gitSettings.authMode == AuthMode.None -> runGit(GitOp.PULL)
          else -> runGit(GitOp.SYNC)
        }
      },
      onGitOp = { op -> ifInitialized { runGit(op) } },
      onSettings = activity::openSettings,
      onNew = { dialog = ListDialog.Create },
      onMove = {
        moveLauncher.launch(
          Intent(activity, SelectFolderActivity::class.java)
            .putExtra("Files", selected.toTypedArray())
        )
      },
      onDelete = {
        val files = selected.map(::File)
        val count = countFiles(files)
        if (count == 0) {
          files.forEach { it.deleteRecursively() }
          selected = emptyList()
          viewModel.forceRefresh()
        } else {
          deleteCount = count
          dialog = ListDialog.Delete
        }
      },
      onRename = { dialog = ListDialog.Rename },
      onPin = {
        activity.pinEntry(File(selected.single()))
        selected = emptyList()
      },
      searchFocus = searchFocus,
    )

    when (dialog) {
      ListDialog.None -> Unit
      ListDialog.Create ->
        CreateSheet(
          onDismiss = { dialog = ListDialog.None },
          onNewEntry = {
            dialog = ListDialog.None
            ifInitialized { activity.createEntry(dir) }
          },
          onNewFolder = {
            dialog = ListDialog.None
            ifInitialized { dialog = ListDialog.NewFolder }
          },
        )
      ListDialog.NewFolder ->
        FolderNameDialog(
          title = stringResource(R.string.title_create_folder),
          message = null,
          confirm = stringResource(R.string.button_create),
          validate = { folderNameError(it, File(dir, it)) },
          onConfirm = { name ->
            dialog = ListDialog.None
            val folder = File(dir, name).canonicalFile
            folder.mkdirs()
            onOpenFolder(folder.relativeTo(root.canonicalFile).path)
          },
          onDismiss = { dialog = ListDialog.None },
        )
      ListDialog.Rename -> {
        val folder = File(selected.single())
        FolderNameDialog(
          title = stringResource(R.string.title_rename_folder),
          message = stringResource(R.string.message_rename_folder, folder.name),
          confirm = stringResource(R.string.dialog_ok),
          validate = { folderNameError(it, File(folder.parentFile, it)) },
          onConfirm = { name ->
            dialog = ListDialog.None
            selected = emptyList()
            activity.lifecycleScope.launch {
              activity.renameFolder(folder, File(folder.parentFile, name))
              viewModel.forceRefresh()
            }
          },
          onDismiss = { dialog = ListDialog.None },
        )
      }
      ListDialog.Delete ->
        Prompt(
          text = pluralStringResource(R.plurals.delete_dialog_text, deleteCount, deleteCount),
          confirm = stringResource(R.string.dialog_yes),
          onConfirm = {
            val files = selected.map(::File)
            dialog = ListDialog.None
            selected = emptyList()
            activity.lifecycleScope.launch {
              activity.deleteItems(files)
              viewModel.forceRefresh()
            }
          },
          onDismiss = { dialog = ListDialog.None },
          dismiss = stringResource(R.string.dialog_no),
        )
      ListDialog.Clone ->
        Prompt(
          text = stringResource(R.string.clone_git_repo),
          confirm = stringResource(R.string.clone_button),
          onConfirm = {
            dialog = ListDialog.None
            activity.startActivity(GitServerConfigActivity.createCloneIntent(activity))
          },
          onDismiss = { dialog = ListDialog.None },
          dismiss = stringResource(R.string.dialog_cancel),
        )
      ListDialog.NotInitialized ->
        Prompt(
          text = stringResource(R.string.creation_dialog_text),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = { dialog = ListDialog.None },
          onDismiss = { dialog = ListDialog.None },
        )
    }
  }
}

/** Asks for a folder name; [validate] returns an error string resource or null. */
@Composable
private fun FolderNameDialog(
  title: String,
  message: String?,
  confirm: String,
  validate: (String) -> Int?,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by rememberSaveable { mutableStateOf("") }
  var error by remember { mutableStateOf<Int?>(null) }
  val focus = remember { FocusRequester() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      val err = error
      Column {
        if (message != null) Text(message, Modifier.padding(bottom = SpacingMedium))
        OutlinedTextField(
          value = name,
          onValueChange = {
            name = it
            error = null
          },
          modifier = Modifier.focusRequester(focus),
          label = { Text(stringResource(R.string.crypto_name_hint)) },
          supportingText = if (err != null) { { Text(stringResource(err)) } } else null,
          isError = err != null,
          singleLine = true,
        )
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val e = validate(name)
          error = e
          if (e == null) onConfirm(name)
        }
      ) {
        Text(confirm)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}

/** A one-message dialog; [dismiss] adds a second button that only closes it. */
@Composable
private fun Prompt(
  text: String,
  confirm: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  dismiss: String? = null,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton =
      if (dismiss != null) {
        { TextButton(onClick = onDismiss) { Text(dismiss) } }
      } else null,
  )
}
```

- [ ] **Step 2: MainActivity**

Replace `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt` with:

```kotlin
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
 * The password list of the folder at [path], relative to the store root ("" is the root). The
 * back stack holds one entry per folder level.
 */
@Serializable data class ListRoute(val path: String = "")

/** One entry. [filePath] was checked with [LaunchActivity.isFileInRepo] before routing here. */
@Serializable data class EntryRoute(val filePath: String)

/**
 * Single-activity host for the Compose UI. Not exported: it is only started by [LaunchActivity],
 * after its biometric gate and path check. Extends [BaseGitActivity] (an AppCompatActivity, so
 * also the FragmentActivity BiometricPrompt needs) because the git operations require one; it
 * hosts no fragments.
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
```

- [ ] **Step 3: No action bar for MainActivity**

In `app/src/main/res/values/themes_material3.xml`, after the closing `</style>` of
`AppThemeM3`, add:

```xml

  <!-- AppCompat activities whose whole UI is Compose -->
  <style name="NoActionBarThemeM3" parent="AppThemeM3">
    <item name="windowActionBar">false</item>
    <item name="windowNoTitle">true</item>
  </style>
```

- [ ] **Step 4: Manifest**

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <activity
      android:name=".ui.main.MainActivity"
      android:exported="false" />
```

with

```xml
    <activity
      android:name=".ui.main.MainActivity"
      android:exported="false"
      android:theme="@style/NoActionBarThemeM3"
      android:windowSoftInputMode="adjustResize" />
```

and in the `.ui.settings.SettingsActivity` element replace
`android:parentActivityName=".ui.passwords.PasswordStore"` with
`android:parentActivityName=".ui.main.MainActivity"` (Settings' up arrow returns to the list, as
before).

- [ ] **Step 5: Spec corrections**

In `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`, replace

```
- `MainActivity` (new, not exported, extends `FragmentActivity` for `BiometricPrompt`, uses no
  fragments) hosts a Compose `NavHost`.
```

with

```
- `MainActivity` (new, not exported, extends `BaseGitActivity`: an `AppCompatActivity`, as the
  git operations require, and so a `FragmentActivity` for `BiometricPrompt`; uses no fragments)
  hosts a Compose `NavHost`.
```

(the sentence continues unchanged with "It replaces ..."), and replace

```
- Folders in one grouped surface, entries in another below it. Folder rows show their entry
  count; entry rows show the username, plus "OTP" when the entry has one.
```

with

```
- Folders in one grouped surface, entries in another below it. Folder rows show their entry
  count; in search results, entry rows show their folder. Username and OTP are not shown: both
  live inside the encrypted file, and the list never decrypts.
- One back stack entry per folder level (`ListRoute(path)`): system back goes up one folder and
  leaves the app at the root.
```

- [ ] **Step 6: Build, test, lint**

Run:
```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. Likely lint findings and their fixes: a compose-lint rule on a new
composable (fix the code, do not suppress, unless it is `ComposeUnstableCollections` on a type
that is already immutable); nothing should report unused resources yet, because `PasswordStore`
still references the old ones.

- [ ] **Step 7: Quick smoke on the emulator**

With the emulator from Task 7 running (`export ANDROID_SERIAL=emulator-5554` first; a phone may
be connected over USB, never install on it):
```bash
./gradlew :app:installDebug
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity
sleep 3; adb shell dumpsys activity activities | grep -E "topResumedActivity"
```
Expected: `MainActivity` (or `OnboardingActivity` if the store is not set up). Full checks are in
Task 7.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/list/ListDestination.kt \
  app/src/main/java/app/passwordstore/ui/main/MainActivity.kt \
  app/src/main/res/values/themes_material3.xml app/src/main/AndroidManifest.xml \
  docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "feat(app): show the Compose password list in MainActivity

Folders are back stack entries (ListRoute(path)), so back goes up one
level. MainActivity extends BaseGitActivity for pull to sync and the
commits after move, rename and delete.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Remove PasswordStore and what only it used

**Files:**
- Delete: `app/src/main/java/app/passwordstore/ui/passwords/PasswordStore.kt`
- Delete: `app/src/main/java/app/passwordstore/ui/passwords/PasswordFragment.kt`
- Delete: `app/src/main/java/app/passwordstore/ui/dialogs/ItemCreationBottomSheet.kt`
- Delete: `app/src/main/java/app/passwordstore/ui/dialogs/FolderCreationDialogFragment.kt`
- Delete: `app/src/main/java/app/passwordstore/ui/util/OnOffItemAnimator.kt`
- Delete: `app/src/main/res/layout/activity_pwdstore.xml`, `item_create_sheet.xml`, `folder_dialog_fragment.xml`
- Delete: `app/src/main/res/menu/main_menu_git.xml`, `main_menu_no_auth.xml`, `main_menu_non_git.xml`, `context_pass.xml`
- Delete: `app/src/main/res/anim/scale_up.xml`, `scale_down.xml`
- Modify: `app/src/main/res/values/dimens.xml`, `app/src/main/res/values*/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/app/passwordstore/util/autofill/AutofillViewUtils.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/folderselect/SelectFolderActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/folderselect/SelectFolderFragment.kt`

Kept on purpose (still used by legacy screens): `PasswordItemRecyclerAdapter`,
`password_recycler_view.xml`, `password_row_layout.xml`, `SearchableRepositoryAdapter`,
`BasicBottomSheet`, the `swiperefreshlayout`, `recyclerview*` and fastscroll dependencies.

- [ ] **Step 1: Autofill attribution intent goes through the launcher**

In `AutofillViewUtils.kt`, replace the import `app.passwordstore.ui.passwords.PasswordStore` with
`app.passwordstore.ui.main.LaunchActivity`, and `Intent(context, PasswordStore::class.java)` with
`Intent(context, LaunchActivity::class.java)`. (Long-pressing an inline suggestion now passes
the biometric gate, instead of opening the list activity directly.)

- [ ] **Step 2: Folder picker no longer borrows PasswordStore's constants**

In `SelectFolderActivity.kt`, replace the body of `onCreate` with:

```kotlin
    super.onCreate(savedInstanceState)
    passwordList = SelectFolderFragment()
    supportActionBar?.show()
    supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    supportFragmentManager.commit { replace(R.id.pgp_handler_linearlayout, passwordList) }
```

and delete the imports `app.passwordstore.data.repo.PasswordRepository`,
`app.passwordstore.ui.passwords.PASSWORD_FRAGMENT_TAG` and
`app.passwordstore.ui.passwords.PasswordStore`. (The fragment tag was never looked up.)

In `SelectFolderFragment.kt`, replace

```kotlin
    val path =
      requireNotNull(requireArguments().getString(PasswordStore.REQUEST_ARG_PATH)) {
        "Cannot navigate if ${PasswordStore.REQUEST_ARG_PATH} is not provided"
      }
    model.navigateTo(File(path), listMode = ListMode.DirectoriesOnly, pushPreviousLocation = false)
```

with

```kotlin
    model.navigateTo(
      PasswordRepository.getRepositoryDirectory(),
      listMode = ListMode.DirectoriesOnly,
      pushPreviousLocation = false,
    )
```

and replace the import `app.passwordstore.ui.passwords.PasswordStore` with
`app.passwordstore.data.repo.PasswordRepository`. (The only path ever passed was the store root.)

- [ ] **Step 3: Manifest**

In `app/src/main/AndroidManifest.xml`, delete:

```xml
    <activity
      android:name=".ui.passwords.PasswordStore"
      android:configChanges="orientation|screenSize"
      android:exported="false" />

```

- [ ] **Step 4: Delete the files**

```bash
git rm app/src/main/java/app/passwordstore/ui/passwords/PasswordStore.kt \
  app/src/main/java/app/passwordstore/ui/passwords/PasswordFragment.kt \
  app/src/main/java/app/passwordstore/ui/dialogs/ItemCreationBottomSheet.kt \
  app/src/main/java/app/passwordstore/ui/dialogs/FolderCreationDialogFragment.kt \
  app/src/main/java/app/passwordstore/ui/util/OnOffItemAnimator.kt \
  app/src/main/res/layout/activity_pwdstore.xml \
  app/src/main/res/layout/item_create_sheet.xml \
  app/src/main/res/layout/folder_dialog_fragment.xml \
  app/src/main/res/menu/main_menu_git.xml \
  app/src/main/res/menu/main_menu_no_auth.xml \
  app/src/main/res/menu/main_menu_non_git.xml \
  app/src/main/res/menu/context_pass.xml \
  app/src/main/res/anim/scale_up.xml \
  app/src/main/res/anim/scale_down.xml
```

- [ ] **Step 5: Delete resources only those files used**

Lint runs with `UnusedResources` as an error, and a string left only in a translation is an
`ExtraTranslation` error, so remove them from every locale:

```bash
for s in refresh_list new_folder_set_gpg_key dialog_skip message_category_error_category_exists edit; do
  sed -i "/<string name=\"$s\">/d" app/src/main/res/values*/strings.xml
done
sed -i '/name="bottom_sheet_item_height"/d' app/src/main/res/values/dimens.xml
grep -rnE 'name="(refresh_list|new_folder_set_gpg_key|dialog_skip|message_category_error_category_exists|edit|bottom_sheet_item_height)"' app/src/main/res
```

Expected: the final `grep` prints nothing (each of these strings is a single line in every
locale).

- [ ] **Step 6: Nothing refers to the removed classes**

Run:
```bash
grep -rnwE 'PasswordStore|PasswordFragment|ItemCreationBottomSheet|FolderCreationDialogFragment|OnOffItemAnimator|PASSWORD_FRAGMENT_TAG' app/src
```
Expected: no output.

- [ ] **Step 7: Full local check**

Run:
```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`. If lint reports further unused resources, they were used only by
the deleted files: delete them (strings from every `values*/strings.xml`) and re-run. If it
reports an issue in a file from Tasks 2 to 5, fix it there and include it in this commit.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/main
git commit -m "refactor(app): remove the legacy PasswordStore list

The Compose list replaces it. The autofill inline suggestion's
attribution intent now opens LaunchActivity, behind the biometric gate.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Emulator check and push

No real store or keys: the e2e environment from AGENTS.md only. A physical phone may be
connected over USB: every `adb` and Gradle install command in this task targets the emulator
through `ANDROID_SERIAL`; never touch the phone.

- [ ] **Step 1: Server, emulator, clean install**

```bash
export ANDROID_SERIAL=emulator-5554
scripts/e2e-env.sh up
$ANDROID_HOME/emulator/emulator -avd aps-test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
for s in animator_duration_scale transition_animation_scale window_animation_scale; do
  adb shell settings put global $s 0; done
adb shell settings put secure autofill_service null
adb uninstall xyz.danix.hardenedpass.debug || true
./gradlew :app:installDebug
scripts/e2e-env.sh push-keys
```

(`ANDROID_HOME` is the `sdk.dir` from `local.properties`. `installDebug` and `adb` both honor
`ANDROID_SERIAL`.) If the emulator shows its lock screen, unlock it with its test PIN:
`adb shell input keyevent 82; adb shell input text 1234; adb shell input keyevent 66`.

Helpers for this shell (read the screen with `uiautomator`, tap by any node attribute):

```bash
ui() { adb exec-out uiautomator dump /dev/tty; }
center() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }; set -- $b; echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; }
tap() { local c; c=$(center "$1") && adb shell input tap $c; sleep 1; }
hold() { local c; c=$(center "$1") && set -- $c && adb shell input swipe $1 $2 $1 $2 900; sleep 1; }
seen() { ui | grep -q "$1" && echo "ok: $1" || echo "MISSING: $1"; }
top() { adb shell dumpsys activity activities | grep topResumedActivity; }
open() { adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity; sleep 3; }
```

- [ ] **Step 2: Onboarding redirect and clone**

`open`, then `seen "Let's Go"` (expected `ok`: the list sent a fresh install to onboarding).
Go through onboarding: "Let's Go", "Clone Remote Repo", Repository URL
`ssh://git@10.0.2.2:2222/srv/git/store.git`, authentication mode "SSH key", Save; import the
SSH key `aps-e2e-ssh-key` from Download when asked, accept the host key, then "Select key" and
import `aps-e2e-pgp-key.asc` from Download.
Expected afterwards: `top` shows `MainActivity`, and

```bash
seen 'text="otp"'; seen 'text="work"'; seen 'text="example.org"'; seen 'content-desc="Password store"'
```

all print `ok` (two folders in the first group, the entry in the second, the root chip).

- [ ] **Step 3: Folder navigation, breadcrumbs, back**

```bash
tap 'text="work"'; seen 'text="mail.example.org"'; seen 'text="work"'   # crumb "work" is shown
adb shell input keyevent 4; seen 'text="otp"'                          # back: up one level
tap 'text="work"'; tap 'content-desc="Password store"'; seen 'text="otp"'   # root chip jumps up
adb shell input keyevent 4; sleep 1; adb shell dumpsys window | grep mCurrentFocus
```
Expected: the last command shows a focus outside `xyz.danix.hardenedpass.debug` (back at the
root leaves the app). `open` again.

- [ ] **Step 4: Search**

```bash
tap 'text="Search"'; adb shell input text mail; sleep 1
seen 'text="mail.example.org"'; seen 'text="work"'   # result with its folder as subtitle
adb shell input keyevent 4; adb shell input keyevent 4; seen 'text="otp"'
```
Expected: all `ok`; the first back hides the keyboard, the second clears the query and the full
root list returns.

- [ ] **Step 5: Entry opens the legacy decrypt screen, list survives**

`tap 'text="example.org"'`, enter the PGP passphrase `test` if asked, then `top`: expected
`DecryptActivity`. `ui | grep -o 'text="hunter2"'` should match after revealing the password
(the screen is `FLAG_SECURE`, so screenshots are black; read the dump). Press back:
`top` shows `MainActivity` and `seen 'text="otp"'` prints `ok` (the list was not finished).

- [ ] **Step 6: Create entry through the legacy editor**

```bash
tap 'text="New entry"'; tap 'text="Create new password"'
tap 'resource-id="xyz.danix.hardenedpass.debug:id/filename"'; adb shell input text e2e-new
tap 'resource-id="xyz.danix.hardenedpass.debug:id/password"'; adb shell input text s3cret-e2e
tap 'content-desc="Save"' || tap 'text="Save"'
sleep 2; top; seen 'text="e2e-new"'
```
Expected: `MainActivity` on top and `ok: text="e2e-new"` (the list refreshed when the editor
returned).

- [ ] **Step 7: Create folder**

```bash
tap 'text="New entry"'; tap 'text="Create new folder"'; adb shell input text e2e-folder
tap 'text="Create"'; seen 'text="e2e-folder"'
adb shell input keyevent 4; seen 'text="e2e-folder"'
```
Expected: the list opens the new folder (crumb `e2e-folder`), back returns to the root, where the
folder is listed with count `0`.

- [ ] **Step 8: Multi-select move, then delete**

```bash
hold 'text="e2e-new"'; seen 'text="1 item selected"'
tap 'content-desc="Move"'          # legacy folder picker
tap 'text="work"'; tap 'content-desc="Select"' || tap 'text="Select"'
sleep 2; seen 'text="e2e-new"'     # expected MISSING at the root now
tap 'text="work"'; seen 'text="e2e-new"'
hold 'text="e2e-new"'; tap 'content-desc="Delete"'; tap 'text="Yes"'
sleep 2; seen 'text="e2e-new"'     # expected MISSING
adb shell input keyevent 4
```
Expected: as commented. Also check rotation keeps the folder: in `work`, run
`adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 2; seen 'text="mail.example.org"'; adb shell settings put system user_rotation 0`.

- [ ] **Step 9: Pull to sync pushes the commits**

```bash
adb shell input swipe 540 700 540 1800 400; sleep 8
seen 'text="otp"'
docker exec -u git aps-e2e-git git -C /srv/git/store.git log --oneline
```
Expected: no error dialog; the server log shows, above `Initial test store`, the commit that
added `e2e-new`, `Rename e2e-new to work/e2e-new.` and `Remove work/e2e-new.gpg from store.`

- [ ] **Step 10: Tear down and push**

```bash
adb uninstall xyz.danix.hardenedpass.debug
adb shell rm -f /sdcard/Download/aps-e2e-ssh-key /sdcard/Download/aps-e2e-pgp-key.asc
adb emu kill
scripts/e2e-env.sh down
unset ANDROID_SERIAL
git push
```

Expected: push succeeds to every `origin` push URL.

---

## Self-review notes

- **Spec coverage for step 2:** `ListRoute` is a real Compose screen (Tasks 4, 5); pill search
  bar (Material 3 `SearchBar`), breadcrumb chips with jumps, back pops one folder and leaves at
  the root (Tasks 2, 5); folders and entries in two `GroupedSurface`s, folder counts, letter
  avatars, subtitle decision recorded (Tasks 2, 4); pull to sync (Task 5); long-press
  multi-select with move and delete, plus rename and pin for parity (Tasks 3, 4, 5); extended
  FAB "New entry" opening the create sheet (Tasks 4, 5); `hilt-navigation-compose` added for
  `hiltViewModel()` (Task 1); `SearchableRepositoryViewModel` reused with one added flow
  (Task 2); results from legacy activities refresh the list through the resume refresh (Task 5);
  `PasswordStore` and its fragment, dialogs, layouts and menus deleted, shared leftovers listed
  for steps 4, 7 and 8 (Task 6); TDD for the pure logic (Task 2); emulator check covering
  onboarding clone, browse, search, folders and back, pull to sync, create entry and folder, move
  and delete (Task 7); full local CI (Task 6).
- **Parity table summary:** ported: onboarding redirect, folders and breadcrumbs, search with
  its settings, search-on-start, git menu (sync, pull, push, by auth mode), settings, pull to
  sync with all its branches, entry open plus dynamic shortcut, recently-used tracking, folder
  counts, move, delete, rename (one folder at a time), pin, create entry (legacy editor), create
  folder. Kept as legacy intents: decrypt, editor, folder picker, settings, onboarding, clone.
  Dropped: `REPO_CHANGED` and `matchWith` (dead), "Refresh list" (automatic now),
  type-to-search and Ctrl+F (search always visible), fast scroller and item animations.
- **Spec deviations, recorded in the spec by Task 5:** `MainActivity` extends `BaseGitActivity`;
  no username or OTP subtitle.
- **Open questions for the maintainer:**
  1. Sort order "files first" and "independent" lose their meaning now that folders always sit
     in their own group above entries (the spec layout). This plan ignores the difference;
     should step 6 drop those two choices from Settings, or should "files first" put the entries
     group above the folders group?
  2. Hardware-keyboard type-to-search and Ctrl+F are dropped. Do you use a keyboard with the
     phone or tablet? Restoring them is a small `onPreviewKeyEvent` on the screen.
  3. The "Refresh list" menu item is dropped (the list re-reads on every return and after every
     action). Keep it dropped?

## Maintainer decisions (2026-11-02)

- Sort order: the "files first" and "independent" options are removed when Settings is ported
  (spec step 6). Folders always sit above entries. Nothing to do in this plan.
- Hardware keyboard type-to-search and Ctrl+F: dropped.
- "Refresh list" menu item: dropped.
