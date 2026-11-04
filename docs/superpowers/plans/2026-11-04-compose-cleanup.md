# Compose UI overhaul, step 8: Cleanup and merge. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete what the Compose overhaul left without users (Fragment and view binding helpers, the RecyclerView-era navigation stack, upstream leftovers, `BasePGPActivity`, the View-era dependencies, dead styles and attributes), name HardenedPass in every locale, record the finished overhaul in AGENTS.md, the spec and CONTRIBUTING.md, run the full CI set and an emulator regression pass, hand a signed release build of `compose` to the maintainer for the phone test, and only after the maintainer's explicit go merge `compose` into `master` and delete `compose`.

**Architecture:** No new code paths. Five small commits on `compose` remove or move code (each builds, tests and lints clean on its own), one docs commit closes the overhaul, then a maintainer gate: `master` stays at 0.1.5 until the maintainer has tested a release build of `compose` on the phone. After the go, one signed `--no-ff` merge commit lands it on `master` and `compose` is deleted. Material Components (Views) stays for the window themes, the git operations' View dialogs, `DynamicColors` and the snackbars. Two layouts stay: `oreo_autofill_dataset.xml` (`RemoteViews`) and `git_credential_layout.xml` (the git credential prompt, moved off ConstraintLayout). No release in this step.

**Tech Stack:** Kotlin 2.4.21, AGP 9.5.0, Gradle version catalog, Hilt (KSP), Compose BOM 2024.09.03, Material Components 1.12.0 (kept), JGit 5.13, the e2e Docker git server and the `aps-test` AVD.

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md` (sections "Removed at the end", "Branching", "Testing", "Migration order" step 8). Earlier plans: `2026-11-02-compose-foundation.md`, `2026-11-02-compose-list.md`, `2026-11-02-compose-entry.md`, `2026-11-02-compose-editor.md`, `2026-11-03-compose-setup.md`, `2026-11-03-compose-settings.md`, `2026-11-04-compose-autofill.md` (same folder).

## Decisions taken in this plan

- **Survey, not assumptions.** Every deletion below was checked with a grep for its users on `compose` at `9f4e4f12`. Each task repeats the grep before deleting, because a later fix commit could add a user.
- **Material Components (Views) stays** (maintainer decision 1). Things that still need it: `AppThemeM3` and its children derive from `Theme.Material3.DayNight` (the AppCompat activities need an AppCompat theme), the git operations' View dialogs (`BaseGitActivity.promptOnErrorHandler`, `GitOperation.onMissingSshKeyFile`, `CredentialFinder` with `TextInputLayout` and `MaterialCheckBox`, `BreakOutOfDetached.preExecute`, `StoreActions.moveFile` and `confirm`), `DynamicColors` in `Application`, and `FragmentActivity.snackbar` (28 call sites, used by Compose destinations and by `GitCommandExecutor`). Moving those to Compose is a later change on `master`.
- **`git_credential_layout.xml` stays, without ConstraintLayout** (maintainer decision 4). It is the only user of `androidx.constraintlayout`; a vertical `LinearLayout` gives the same layout, so our direct dependency goes. Material Components still pulls ConstraintLayout transitively; R8 drops its unused classes.
- **`fragment-ktx` stays as the version pin of `androidx.fragment`.** Nothing calls its Kotlin extensions after Task 1, but without it `androidx.fragment` resolves through `appcompat` 1.7.0 to 1.5.4 instead of 1.8.4, a silent downgrade of the library `FragmentActivity` and `BiometricPrompt` run on. Keeping the declaration (with a comment) is the smallest diff; Task 3 checks the resolved version.
- **`recyclerview` is not pinned.** After its direct declaration goes, it resolves transitively (through Material Components' `viewpager2`), possibly to a release candidate. Acceptable: no app code and no View still in use references `RecyclerView`, so R8 removes it from the release APK; pinning would keep a declaration for a library the app does not use. Task 3 records the resolved version.
- **`BasePGPActivity` is folded into `AutofillDecryptActivity`,** its only subclass. Its `FLAG_SECURE` line and three injected fields move into the activity. Its companion and top-level helpers move to `ui/crypto/CryptoExtensions.kt` (the same file, renamed with `git mv`, so blame follows): `getParentPath` and `getLongName` become top-level functions, `getRelativePath` becomes private. `EXTRA_FILE_PATH` (`"FILE_PATH"`) moves to `LaunchActivity`'s companion next to `ACTION_DECRYPT_PASS`; its value must not change, home screen shortcuts carry it.
- **`getPGPIdentifiers` reports an invalid `.gpg-id` with a Toast** on the application context. It showed a snackbar, which the autofill decryption never displays (it finishes at once on `null`). A Toast is drawn outside the activity's window, so it shows in the entry view, the editor and over the app being filled. The receiver becomes `Context`.
- **`SearchableRepositoryViewModel` loses its RecyclerView-era navigation stack** and the unused `ListMode.DirectoriesOnly`. Only `navigateTo(dir, pushPreviousLocation = false)`, `search`, `forceRefresh`, `rows` and `searchResult` have callers. `navigateTo` keeps one parameter; the stack, `canNavigateBack`, `navigateBack`, `reset`, `NavigationStackEntry`, `PasswordItem.stableId` and `SearchResult.isFiltered` go.
- **Upstream leftovers in Task 1:** `PasswordItem`'s four-argument `newCategory` and `newPassword` overloads (with `parent`), the `parent` property they alone filled, the `@JvmStatic` annotations (no Java code is left), the `"NAME"` extra that `createAuthEnabledIntent` writes and nothing reads, and the root `sentry.properties` (Sentry was removed with the `nonFree` flavor; no build file reads it).
- **Dependencies removed:** `androidx.preference:preference`, `androidx.recyclerview:recyclerview`, `androidx.recyclerview:recyclerview-selection`, `me.zhanghai.android.fastscroll`, `de.maxr1998:modernandroidpreferences`, `androidx.constraintlayout:constraintlayout`, with their catalog entries. `swiperefreshlayout` is already gone. `androidx.activity.viewModels` still comes through `activity-compose`.
- **View binding is switched off** in `build-logic` (`ApplicationPlugin`): nothing generates or reads binding classes. Lint's `UnusedIds` check, disabled "due to use of ViewBinding", is enabled again, and `FragmentFieldInjection` (fragments only) loses its disable line.
- **Dead resources:** the four `animator/slide_*` files (only `FragmentExtensions` used them), `map_preference_widget_switch.xml` (an override of modernandroidpreferences' widget), `values/attrs.xml` (`Multiselected`, unused since step 7), `values/styles_material3.xml` and the `toolbarStyle` and `bottomSheetDialogTheme` theme items (no View `Toolbar` or `BottomSheetDialog` is left), and the stale `app/lint.xml` ignores for deleted layouts.
- **Kept for good:** `oreo_autofill_dataset.xml`, `ic_person_black_24dp`, `ic_warning_red_24dp`, `DialogLikeThemeM3`, `NoBackgroundThemeM3`, `NoActionBarThemeM3`, `AppThemeM3`, `APSThemeM3`, `values/bools.xml`. Every drawable in `res/drawable` has a user. No manifest entry, ProGuard rule or `app/lint-baseline.xml` entry references a deleted class or file.
- **Locales** (maintainer decision 3). Eight locales still say "Password Store" in 22 strings: 20 name the app (autofill dialog, publisher warning, biometric summary) and become "HardenedPass"; the two German ones that mean the store itself become "Repository", the word that translation uses 21 times. 0.1.4 fixed English and Italian only.
- **Phone test before merge** (maintainer decision 6). `master` stays at 0.1.5 until the maintainer says go. The agent builds a release APK from `compose`, signed with the local release keystore, with `master`'s version (0.1.5, code 105) applied only for that build, so it installs over the phone's 0.1.5 in place; the agent never touches the phone.
- **Merge with a signed `--no-ff` merge commit** (maintainer decision 5). A fast-forward is impossible (`master` has the 0.1.5 release); rebasing would rewrite 74 pushed commits. A trial `git merge-tree` shows one conflict, in AGENTS.md: the `LaunchActivity` bullet next to the sshj bullet. The public suffix list date auto-merges to `master`'s `2026-11-03` as long as `compose` does not touch that line (Task 7 does not).
- **Order.** Code (Tasks 1 to 5), emulator regression (Task 6), docs and push of `compose` (Task 7), the maintainer's phone gate (Task 8), merge after the explicit go (Task 9), branch deletion (Task 10).

## Lessons from steps 1 to 7, designed in

- **Lint per task.** Lint runs with warnings as errors: a resource left without a user fails `UnusedResources`, so each task deletes what it orphans. Each code task's check includes `:app:lintDebug`; Tasks 6 and 9 run the full CI set from AGENTS.md.
- **`CI=1`** turns Kotlin warnings into errors: every build check below uses it.
- **Strings.** Deleting a string from `values/` means deleting it from every `values-*/strings.xml` (lint `ExtraTranslation`). This plan deletes no string; if lint reports one unused, delete it in all locales.
- **Device safety.** The agent's shell keeps no functions or variables between Bash calls, and helper names like `pass`, `open`, `top` or `sync` would run real commands (`/usr/bin/pass` would reach the maintainer's real store). Task 6 writes its environment and `e2e_`-prefixed helpers to a file in the executing session's scratchpad, sources it at the top of every step and stops unless `ANDROID_SERIAL` is `emulator-5554`. A physical tablet may be connected and is never touched; the PIN `1234` is the emulator's own test PIN.
- **Security.** No secret in a log, Bundle or saved state; `FLAG_SECURE` windows stay `FLAG_SECURE`; the `FILE_PATH` extra keeps its value and its `isFileInRepo` checks.

**Security-relevant tasks** (code review after implementation, before the next task): Task 2 (the autofill decryption window's `FLAG_SECURE`, `.gpg-id` recipient parsing, the exported `LaunchActivity`'s extra), Task 4 (the git credential prompt).

**Maintainer gates:** Task 8 (phone test of the `compose` release build; nothing merges before an explicit go) and Task 10 Step 1 (go to delete `compose`, implied by a successful Task 9 unless the maintainer says otherwise).

**Conventions for every task**

- New files get this header:
  ```kotlin
  /*
   * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
   * SPDX-License-Identifier: GPL-3.0-only
   */
  ```
  Modified or moved upstream files keep their header.
- Commits are GPG-signed (default config, never disable). End each message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No em dashes in docs, comments or commit messages. No personal details.
- Before each commit run `./gradlew spotlessApply`, then the task's checks.
- Tasks 1 to 8 work on `compose`; Task 9 on `master`.
- Never stage `HANDOFF.md`.

## File map

| File | Task | Change |
|---|---|---|
| `app/src/main/java/app/passwordstore/util/extensions/FragmentExtensions.kt` | 1 | delete |
| `app/src/main/java/app/passwordstore/util/extensions/FragmentViewBindingDelegate.kt` | 1 | delete |
| `app/src/main/res/animator/slide_{in,out}_{left,right}.xml` | 1 | delete |
| `sentry.properties` | 1 | delete |
| `app/src/main/java/app/passwordstore/util/extensions/AndroidExtensions.kt` | 1 | drop `resolveAttribute`, `isPermissionGranted`, `launchActivity`, `conditional` |
| `app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt` | 1 | drop the navigation stack, `stableId`, `isFiltered`, `DirectoriesOnly` |
| `app/src/main/java/app/passwordstore/data/password/PasswordItem.kt` | 1, 2 | drop `parent`, overloads, `@JvmStatic`, `"NAME"`; imports, extra constant |
| `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt` | 1 | `navigateTo(dir)`, two comments |
| `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt` | 2 | `git mv` to `CryptoExtensions.kt`, class removed |
| `app/src/test/java/app/passwordstore/ui/crypto/CryptoExtensionsTest.kt` | 2 | create |
| `app/src/main/java/app/passwordstore/ui/autofill/AutofillDecryptActivity.kt` | 2 | extend `AppCompatActivity`, own `FLAG_SECURE` and fields |
| `app/src/main/java/app/passwordstore/ui/main/LaunchActivity.kt`, `MainActivity.kt` | 2 | `EXTRA_FILE_PATH` |
| `app/src/main/java/app/passwordstore/ui/list/StoreActions.kt`, `ui/entry/EntryDestination.kt` | 2 | imports |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | 3, 4 | dependencies |
| `build-logic/src/main/kotlin/app/passwordstore/gradle/ApplicationPlugin.kt` | 3 | view binding off |
| `build-logic/src/main/kotlin/app/passwordstore/gradle/LintConfig.kt` | 3 | two disables removed |
| `app/lint.xml` | 3 | stale ignores removed |
| `app/src/main/res/layout/map_preference_widget_switch.xml`, `values/attrs.xml`, `values/styles_material3.xml` | 3 | delete |
| `app/src/main/res/values/themes_material3.xml` | 3 | two items and one style removed |
| `app/src/main/res/layout/git_credential_layout.xml` | 4 | `LinearLayout` |
| `app/src/main/res/values-{de,fr,gl,ko-rKR,pl-rPL,pt-rBR,ru,zh-rCN}/strings.xml` | 5 | 22 strings |
| `AGENTS.md`, the spec, `CONTRIBUTING.md` | 7 | docs |

---

### Task 1: Dead Fragment, view, navigation stack and upstream leftovers

**Files:**
- Delete: `app/src/main/java/app/passwordstore/util/extensions/FragmentExtensions.kt`, `app/src/main/java/app/passwordstore/util/extensions/FragmentViewBindingDelegate.kt`
- Delete: `app/src/main/res/animator/slide_in_left.xml`, `slide_in_right.xml`, `slide_out_left.xml`, `slide_out_right.xml`
- Delete: `sentry.properties`
- Modify: `app/src/main/java/app/passwordstore/util/extensions/AndroidExtensions.kt`
- Modify: `app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt`
- Modify: `app/src/main/java/app/passwordstore/data/password/PasswordItem.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`

- [ ] **Step 1: Confirm the candidates have no users**

```bash
git switch compose && git status --short
grep -rnw --include=*.kt -E 'performTransactionWithBackStack|viewBinding|FragmentViewBindingDelegate|resolveAttribute|launchActivity|conditional|isPermissionGranted|stableId|navigateBack|canNavigateBack|NavigationStackEntry|isFiltered|DirectoriesOnly' app/src
grep -rn --include=*.kt -E 'R\.animator\.|\.reset\(\)' app/src/main
grep -rn --include=*.kt 'navigateTo(' app/src
grep -rn --include=*.kt -E 'newCategory|newPassword|\.parent\b.*PasswordItem|PasswordItem\(|"NAME"|getStringExtra\("NAME' app/src
grep -rn --include=*.kts --include=*.properties --include=*.yml --include=*.pro -i sentry . | grep -v /build/
```
Expected:
- First grep: only lines in `FragmentExtensions.kt`, `FragmentViewBindingDelegate.kt`, `AndroidExtensions.kt` (the definitions) and `SearchableRepositoryViewModel.kt`.
- Second: only `FragmentExtensions.kt` (animators) and JGit `git.reset()` calls in `util/git/operation/` and `JGitBehaviourTest.kt` (not the ViewModel's `reset`).
- Third: `ListDestination.kt:92` (`viewModel.navigateTo(dir, pushPreviousLocation = false)`), the definition, and the ViewModel's own internal calls `navigateTo(oldDir, pushPreviousLocation = false)` (in `navigateBack`) and `navigateTo(pushPreviousLocation = false)` (in `reset`), both removed below.
- Fourth: only the three-argument calls in `SearchableRepositoryViewModel.kt` (`toPasswordItem`) and `PasswordRepository.kt`, the constructor calls inside `PasswordItem.kt`, and `PasswordItem.kt`'s `putExtra("NAME", ...)`; nothing reads `"NAME"`.
- Fifth: only `sentry.properties` itself (no build file, workflow or ProGuard rule reads it).

Anything else: stop and report.

- [ ] **Step 2: Delete the Fragment helpers, their animators and `sentry.properties`**

```bash
git rm app/src/main/java/app/passwordstore/util/extensions/FragmentExtensions.kt \
  app/src/main/java/app/passwordstore/util/extensions/FragmentViewBindingDelegate.kt \
  app/src/main/res/animator/slide_in_left.xml app/src/main/res/animator/slide_in_right.xml \
  app/src/main/res/animator/slide_out_left.xml app/src/main/res/animator/slide_out_right.xml \
  sentry.properties
```

- [ ] **Step 3: `AndroidExtensions.kt`**

Delete these four functions:

```kotlin
/** Resolve [attr] from the [Context]'s theme */
fun Context.resolveAttribute(attr: Int): Int {
  val typedValue = TypedValue()
  this.theme.resolveAttribute(attr, typedValue, true)
  return typedValue.data
}
```

```kotlin
/** Check if [permission] has been granted to the app. */
fun FragmentActivity.isPermissionGranted(permission: String): Boolean {
  return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
```

```kotlin
/** Launch an activity denoted by [clazz]. */
fun <T : ComponentActivity> ComponentActivity.launchActivity(clazz: Class<T>) {
  startActivity(Intent(this, clazz).setAction(Intent.ACTION_VIEW))
}
```

```kotlin
/** Allows conditionally applying the given [modifier] if [isEnabled] is `true`. */
fun Modifier.conditional(isEnabled: Boolean, modifier: Modifier.() -> Modifier): Modifier {
  return if (isEnabled) {
    then(modifier())
  } else {
    this
  }
}
```

and the imports only they used:

```kotlin
import android.content.Intent
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
```

(`PackageManager` stays: `getPackageInfoCompat` and `getApplicationInfoCompat` use it. `FragmentActivity` stays: `commitChange` and `snackbar` use it.)

- [ ] **Step 4: `SearchableRepositoryViewModel.kt`**

Delete `import android.os.Parcelable` and

```kotlin
val PasswordItem.stableId: String
  get() = file.absolutePath

```

In `enum class ListMode`, delete the line `  DirectoriesOnly,`, and in the `prefilteredResultFlow` `when` delete

```kotlin
            ListMode.DirectoriesOnly ->
              listResultFlow.filter { it.isDirectory }.flowOn(dispatcherProvider.io())
```

Replace

```kotlin
  data class SearchResult(val passwordItems: List<PasswordItem>, val isFiltered: Boolean)
```

with

```kotlin
  data class SearchResult(val passwordItems: List<PasswordItem>)
```

and

```kotlin
        SearchResult(passwordList, isFiltered = searchAction.filterMode != FilterMode.NoFilter)
```

with

```kotlin
        SearchResult(passwordList)
```

Replace everything from `data class NavigationStackEntry(...)` through the end of `fun reset() { ... }`:

```kotlin
  data class NavigationStackEntry(val dir: File, val recyclerViewState: Parcelable?)

  private val navigationStack = ArrayDeque<NavigationStackEntry>()

  fun navigateTo(
    newDirectory: File = root,
    listMode: ListMode = ListMode.AllEntries,
    recyclerViewState: Parcelable? = null,
    pushPreviousLocation: Boolean = true,
  ) {
    if (!newDirectory.exists()) return
    require(newDirectory.isDirectory) { "Can only navigate to a directory" }
    if (pushPreviousLocation) {
      navigationStack.addFirst(NavigationStackEntry(_currentDir.value, recyclerViewState))
    }
    searchActionFlow.update {
      makeSearchAction(
        filter = "",
        baseDirectory = newDirectory,
        filterMode = FilterMode.NoFilter,
        searchMode = SearchMode.InCurrentDirectoryOnly,
        listMode = listMode,
      )
    }
    _currentDir.update { newDirectory }
  }

  val canNavigateBack
    get() = navigationStack.isNotEmpty()

  /**
   * Navigate back to the last location on the [navigationStack] and restore a cached scroll
   * position if possible.
   *
   * Returns the old RecyclerView's LinearLayoutManager state as a [Parcelable] if it was cached.
   */
  fun navigateBack(): Parcelable? {
    if (!canNavigateBack) return null
    val (oldDir, oldRecyclerViewState) = navigationStack.removeFirst()
    navigateTo(oldDir, pushPreviousLocation = false)
    return oldRecyclerViewState
  }

  fun reset() {
    navigationStack.clear()
    forceUpdateOnNextSearchAction()
    navigateTo(pushPreviousLocation = false)
  }
```

with

```kotlin
  /** Lists [newDirectory]; the Compose list keeps the back stack (one route per folder). */
  fun navigateTo(newDirectory: File) {
    if (!newDirectory.exists()) return
    require(newDirectory.isDirectory) { "Can only navigate to a directory" }
    searchActionFlow.update {
      makeSearchAction(
        filter = "",
        baseDirectory = newDirectory,
        filterMode = FilterMode.NoFilter,
        searchMode = SearchMode.InCurrentDirectoryOnly,
        listMode = ListMode.AllEntries,
      )
    }
    _currentDir.update { newDirectory }
  }
```

(`forceUpdateOnNextSearchAction` keeps its caller, `forceRefresh`.)

- [ ] **Step 5: `PasswordItem.kt`**

Delete the constructor parameter `  val parent: PasswordItem? = null,` and the line `    intent.putExtra("NAME", toString())`. Replace the companion's four factories:

```kotlin
    @JvmStatic
    fun newCategory(name: String, file: File, parent: PasswordItem, rootDir: File): PasswordItem {
      return PasswordItem(name, parent, TYPE_CATEGORY, file, rootDir)
    }

    @JvmStatic
    fun newCategory(name: String, file: File, rootDir: File): PasswordItem {
      return PasswordItem(name, null, TYPE_CATEGORY, file, rootDir)
    }

    @JvmStatic
    fun newPassword(name: String, file: File, parent: PasswordItem, rootDir: File): PasswordItem {
      return PasswordItem(name, parent, TYPE_PASSWORD, file, rootDir)
    }

    @JvmStatic
    fun newPassword(name: String, file: File, rootDir: File): PasswordItem {
      return PasswordItem(name, null, TYPE_PASSWORD, file, rootDir)
    }
```

with

```kotlin
    fun newCategory(name: String, file: File, rootDir: File): PasswordItem {
      return PasswordItem(name, TYPE_CATEGORY, file, rootDir)
    }

    fun newPassword(name: String, file: File, rootDir: File): PasswordItem {
      return PasswordItem(name, TYPE_PASSWORD, file, rootDir)
    }
```

(`equals`, `hashCode` and `compareTo` never read `parent`. Shortcuts already pinned on a home screen carry a `"NAME"` extra that nothing reads; they keep working.)

- [ ] **Step 6: `ListDestination.kt`**

Replace `    viewModel.navigateTo(dir, pushPreviousLocation = false)` with `    viewModel.navigateTo(dir)`.

In the KDoc above `fun ListDestination`, replace

```kotlin
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * folder picker, the legacy activities and the store operations. [onOpenFolder] takes a path
```

with

```kotlin
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * folder picker and the store operations. [onOpenFolder] takes a path
```

and replace

```kotlin
  // Every return to this folder (back from a child, or from a legacy activity) re-reads it.
```

with

```kotlin
  // Every return to this folder (back from a child, or from another activity) re-reads it.
```

- [ ] **Step 7: Check**

```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck :app:testDebugUnitTest -PslimTests :app:assembleDebug :app:lintDebug
grep -rn --include=*.kt -E 'androidx\.fragment\.app\.(Fragment$|FragmentManager|commit)|ViewBinding|JvmStatic' app/src
```
Expected: `BUILD SUCCESSFUL` (`StrictDomainRegexTest` still passes: `generateStrictDomainRegex` is untouched); the grep prints nothing.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/main/java/app/passwordstore app/src/main/res/animator sentry.properties
git status --short
git commit -m "refactor(app): drop dead Fragment, view and navigation stack helpers

The Compose screens use no Fragments or view binding, so
FragmentExtensions (with its four slide animators) and
FragmentViewBindingDelegate have no users, nor do resolveAttribute,
isPermissionGranted, launchActivity and the unused Modifier.conditional.
SearchableRepositoryViewModel keeps one navigateTo: the Compose list owns
the back stack, so the RecyclerView-era navigation stack, stableId,
isFiltered and the unused DirectoriesOnly mode go. PasswordItem loses
the parent overloads, @JvmStatic and the unread NAME extra, and the
leftover sentry.properties goes too.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: `git status --short` before the commit lists only the seven deletions and four modifications.

---

### Task 2: Fold `BasePGPActivity` into the autofill decryption (security-relevant)

**Files:**
- Move: `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt` to `app/src/main/java/app/passwordstore/ui/crypto/CryptoExtensions.kt`
- Create: `app/src/test/java/app/passwordstore/ui/crypto/CryptoExtensionsTest.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/autofill/AutofillDecryptActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/main/LaunchActivity.kt`, `MainActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/data/password/PasswordItem.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/StoreActions.kt`, `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt`

- [ ] **Step 1: Confirm the users**

```bash
grep -rn --include=*.kt --include=*.xml --include=*.pro -E 'BasePGPActivity|getParentPath|getLongName|getRelativePath|copyTextToClipboard|copyPasswordToClipboard|getPGPIdentifiers|"FILE_PATH"' app/src
```
Expected users outside `BasePGPActivity.kt`: `PasswordItem.kt` (`getLongName`, `"FILE_PATH"`), `SshKeyScreen.kt` (`copyTextToClipboard`), `EditorDestination.kt` (`copyPasswordToClipboard`, `getPGPIdentifiers`), `MainActivity.kt` and `LaunchActivity.kt` (`EXTRA_FILE_PATH`), `AutofillDecryptActivity.kt` (subclass, `getPGPIdentifiers`, `getParentPath`), `StoreActions.kt` (`getLongName`), `EntryDestination.kt` (`getParentPath`, both copies, `getPGPIdentifiers`). No manifest, ProGuard or test reference. Anything else: stop and report.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/app/passwordstore/ui/crypto/CryptoExtensionsTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the path helpers' behavior across their move out of BasePGPActivity. */
class CryptoExtensionsTest {

  @Test
  fun parentPathIsRelativeToTheStore() {
    assertEquals("/social/", getParentPath("/store/social/facebook.gpg", "/store"))
    assertEquals("/", getParentPath("/store/example.org.gpg", "/store"))
  }

  @Test
  fun longNameJoinsFolderAndName() {
    // StoreActions passes absolute folders, PasswordItem its path relative to the store.
    assertEquals("work/x", getLongName("/store/work", "/store", "x"))
    assertEquals("social/facebook", getLongName("/social/", "/store", "facebook"))
    assertEquals("x", getLongName("/store", "/store", "x"))
  }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew :app:testDebugUnitTest -PslimTests --tests app.passwordstore.ui.crypto.CryptoExtensionsTest`
Expected: compilation FAIL, `Unresolved reference 'getParentPath'` (they are companion members today).

- [ ] **Step 4: Move the file and drop the class**

```bash
git mv app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt \
  app/src/main/java/app/passwordstore/ui/crypto/CryptoExtensions.kt
```

In `CryptoExtensions.kt`, replace everything from the first import through the end of the class (the line `}` that closes `open class BasePGPActivity`) with:

```kotlin
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.extensions.clipboard
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.services.ClipboardService
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File

/** Gets the relative path to the repository */
private fun getRelativePath(fullPath: String, repositoryPath: String): String =
  fullPath.replace(repositoryPath, "").replace("/+".toRegex(), "/")

/** Gets the Parent path, relative to the repository */
fun getParentPath(fullPath: String, repositoryPath: String): String {
  val relativePath = getRelativePath(fullPath, repositoryPath)
  val index = relativePath.lastIndexOf("/")
  return "/${relativePath.substring(startIndex = 0, endIndex = index + 1)}/"
    .replace("/+".toRegex(), "/")
}

/** /path/to/store/social/facebook.gpg -> social/facebook */
fun getLongName(fullPath: String, repositoryPath: String, basename: String): String {
  var relativePath = getRelativePath(fullPath, repositoryPath)
  return if (relativePath.isNotEmpty() && relativePath != "/") {
    // remove preceding '/'
    relativePath = relativePath.substring(1)
    if (relativePath.endsWith('/')) {
      relativePath + basename
    } else {
      "$relativePath/$basename"
    }
  } else {
    basename
  }
}
```

(The package line and the upstream header above it stay. `copyTextToClipboard`, `copyPasswordToClipboard` and `findTillRoot` below stay unchanged.)

Replace the whole `getPGPIdentifiers` function, KDoc included:

```kotlin
/**
 * Get a list of available [PGPIdentifier]s for the current password repository. This method throws
 * when no identifiers were able to be parsed. If this method returns null, it means that an invalid
 * identifier was encountered and further execution must stop to let the user correct the problem.
 */
fun FragmentActivity.getPGPIdentifiers(subDir: String): List<PGPIdentifier>? {
```

through its closing `}` with:

```kotlin
/**
 * Get a list of available [PGPIdentifier]s for the current password repository. This method throws
 * when no identifiers were able to be parsed. If this method returns null, an invalid identifier
 * was encountered and reported with a toast: further execution must stop to let the user correct
 * the problem.
 */
fun Context.getPGPIdentifiers(subDir: String): List<PGPIdentifier>? {
  val repoRoot = PasswordRepository.getRepositoryDirectory()
  val gpgIdentifierFile =
    File(repoRoot, subDir).findTillRoot(".gpg-id", repoRoot)
      ?: File(repoRoot, ".gpg-id").apply { createNewFile() }
  val gpgIdentifiers =
    gpgIdentifierFile
      .readLines()
      .filter { it.isNotBlank() }
      .map { line ->
        PGPIdentifier.fromString(line)
          ?: run {
            // The line being empty means this is most likely an empty `.gpg-id`
            // file we created. Skip the validation so we can make the user add a
            // real ID.
            if (line.isEmpty()) return@run
            // Apparently `gpg-id` being the first line is also acceptable?
            if (line == "gpg-id") return@run
            val message =
              if (line.removePrefix("0x").matches("[a-fA-F0-9]{8}".toRegex())) R.string.short_gpg_id
              else R.string.invalid_gpg_id
            // A toast on the application context, not a snackbar: the autofill decryption
            // finishes its window right after, and the toast must outlive it.
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            return null
          }
      }
      .filterIsInstance<PGPIdentifier>()
  if (gpgIdentifiers.isEmpty()) {
    error("Failed to parse identifiers from .gpg-id: ${gpgIdentifierFile.readText()}")
  }
  return gpgIdentifiers
}
```

(Every caller runs on the main thread: `EntryDestination.unlock`, `EditorDestination.save` and the decryption's `decrypt`, called from the screen lock prompt's callback or its `else` branch.)

- [ ] **Step 5: `AutofillDecryptActivity.kt`**

Replace `import app.passwordstore.ui.crypto.BasePGPActivity` with `import app.passwordstore.ui.crypto.getParentPath`, and add:

```kotlin
import android.content.SharedPreferences
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.util.coroutines.DispatcherProvider
```

Replace

```kotlin
@AndroidEntryPoint
class AutofillDecryptActivity : BasePGPActivity() {

  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory
```

with

```kotlin
@AndroidEntryPoint
class AutofillDecryptActivity : AppCompatActivity() {

  @SettingsPreferences @Inject lateinit var settings: SharedPreferences
  @Inject lateinit var repository: CryptoRepository
  @Inject lateinit var dispatcherProvider: DispatcherProvider
  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory
```

and

```kotlin
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ponytail: a recreation
```

with

```kotlin
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Before anything is drawn: the passphrase dialog's parent stays out of screenshots and recents.
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    // ponytail: a recreation
```

(`getParentPath(filePath, ...)` in `decrypt` now resolves to the top-level import; the companion's private `EXTRA_FILE_PATH` no longer shadows anything.)

- [ ] **Step 6: `EXTRA_FILE_PATH` moves to `LaunchActivity`**

In `LaunchActivity.kt` delete `import app.passwordstore.ui.crypto.BasePGPActivity`, replace both `BasePGPActivity.EXTRA_FILE_PATH` with `EXTRA_FILE_PATH`, and replace

```kotlin
    const val ACTION_DECRYPT_PASS = "DECRYPT_PASS"
```

with

```kotlin
    const val ACTION_DECRYPT_PASS = "DECRYPT_PASS"

    /** The entry to open; home screen shortcuts carry this exact key, never change it. */
    const val EXTRA_FILE_PATH = "FILE_PATH"
```

In `MainActivity.kt` delete `import app.passwordstore.ui.crypto.BasePGPActivity` and replace `intent.getStringExtra(BasePGPActivity.EXTRA_FILE_PATH)` with `intent.getStringExtra(LaunchActivity.EXTRA_FILE_PATH)` (same package, no import).

In `PasswordItem.kt` replace `import app.passwordstore.ui.crypto.BasePGPActivity` with `import app.passwordstore.ui.crypto.getLongName`, `BasePGPActivity.getLongName(` with `getLongName(`, and `intent.putExtra("FILE_PATH", file.absolutePath)` with `intent.putExtra(LaunchActivity.EXTRA_FILE_PATH, file.absolutePath)` (`LaunchActivity` is already imported).

In `StoreActions.kt` replace `import app.passwordstore.ui.crypto.BasePGPActivity.Companion.getLongName` with `import app.passwordstore.ui.crypto.getLongName`.

In `EntryDestination.kt` replace `import app.passwordstore.ui.crypto.BasePGPActivity.Companion.getParentPath` with `import app.passwordstore.ui.crypto.getParentPath`.

- [ ] **Step 7: Run the test and the checks**

```bash
./gradlew spotlessApply
./gradlew :app:testDebugUnitTest -PslimTests --tests app.passwordstore.ui.crypto.CryptoExtensionsTest
CI=1 ./gradlew spotlessCheck :app:testDebugUnitTest -PslimTests :app:assembleDebug :app:lintDebug
grep -rn 'BasePGPActivity' app/src AGENTS.md
grep -n 'FLAG_SECURE' app/src/main/java/app/passwordstore/ui/autofill/AutofillDecryptActivity.kt
```
Expected: the new test passes, then `BUILD SUCCESSFUL`; the first grep prints nothing (AGENTS.md never named it); the second shows the `setFlags` line in `onCreate`.

- [ ] **Step 8: Security review**

Dispatch a reviewer (superpowers:code-reviewer) on the diff with this brief: the autofill decryption window sets `FLAG_SECURE` in `onCreate` right after `super.onCreate`, before `setContent` and before the early `finish()` paths, as `BasePGPActivity` did; the injected `settings`, `repository` and `dispatcherProvider` are the same bindings (`@SettingsPreferences` qualifier kept); `getPGPIdentifiers` parses `.gpg-id` exactly as before and still stops on an invalid or short ID (only the message channel changed, now a Toast on the application context); the `FILE_PATH` extra key is unchanged and `LaunchActivity` still forwards it only after `isFileInRepo`. Apply findings before committing.

- [ ] **Step 9: Commit**

```bash
git add -A app/src/main/java/app/passwordstore/ui app/src/main/java/app/passwordstore/data/password/PasswordItem.kt \
  app/src/test/java/app/passwordstore/ui/crypto/CryptoExtensionsTest.kt
git status --short
git commit -m "refactor(app): fold BasePGPActivity into the autofill decryption

AutofillDecryptActivity was its last subclass: it now sets FLAG_SECURE
and injects the settings, crypto repository and dispatchers itself. The
path and clipboard helpers move to CryptoExtensions.kt as top-level
functions (a test pins the path helpers), and the FILE_PATH extra key
moves, unchanged, to LaunchActivity. An invalid or short .gpg-id ID is
reported with a toast: the snackbar never showed in the autofill
decryption, which closes its window at once.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: `git status --short` shows the rename (`R  .../BasePGPActivity.kt -> .../CryptoExtensions.kt`), the new test and the six modified files.

---

### Task 3: View-era dependencies, view binding, dead styles and resources

**Files:**
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Modify: `build-logic/src/main/kotlin/app/passwordstore/gradle/ApplicationPlugin.kt`, `build-logic/src/main/kotlin/app/passwordstore/gradle/LintConfig.kt`
- Modify: `app/lint.xml`, `app/src/main/res/values/themes_material3.xml`
- Delete: `app/src/main/res/layout/map_preference_widget_switch.xml`, `app/src/main/res/values/attrs.xml`, `app/src/main/res/values/styles_material3.xml`

- [ ] **Step 1: Confirm nothing uses them**

```bash
grep -rn --include=*.kt --include=*.xml --include=*.kts --include=*.pro -E 'androidx\.preference|androidx\.recyclerview|RecyclerView|SelectionTracker|zhanghai|fastscroll|maxr1998|modernandroidpreferences|Multiselected|state_multiselected|APSThemeM3\.Toolbar|APSThemeM3\.BottomSheetDialog|BottomSheetDialog\b|widget\.Toolbar|map_preference_widget' app/src ui build-logic
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency androidx.fragment:fragment | grep -m1 'androidx.fragment:fragment:'
```
Expected: the grep shows only `themes_material3.xml` (the two style references and the BottomSheetDialog style), `styles_material3.xml` and `attrs.xml`; the insight shows `androidx.fragment:fragment:1.8.4` (the baseline for Step 6). Anything else: stop and report.

- [ ] **Step 2: `app/build.gradle.kts`**

Delete these five lines from `dependencies`:

```kotlin
  implementation(libs.androidx.preference)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.recyclerviewSelection)
```
```kotlin
  implementation(libs.thirdparty.fastscroll)
```
```kotlin
  implementation(libs.thirdparty.modernAndroidPrefs)
```

and replace `  implementation(libs.androidx.fragment.ktx)` with

```kotlin
  // Pins androidx.fragment (FragmentActivity, BiometricPrompt): appcompat alone resolves 1.5.4.
  implementation(libs.androidx.fragment.ktx)
```

(`libs.androidx.constraintlayout` goes in Task 4 with its last user.)

- [ ] **Step 3: `gradle/libs.versions.toml`**

Delete these lines under `[libraries]`:

```toml
androidx-preference = "androidx.preference:preference:1.2.1"
androidx-recyclerview = "androidx.recyclerview:recyclerview:1.4.0"
androidx-recyclerviewSelection = "androidx.recyclerview:recyclerview-selection:1.2.0"
thirdparty-fastscroll = "me.zhanghai.android.fastscroll:library:1.3.0"
thirdparty-modernAndroidPrefs = "de.maxr1998:modernandroidpreferences:2.4.0-beta2"
```

- [ ] **Step 4: View binding off, two lint disables gone**

In `ApplicationPlugin.kt` replace

```kotlin
      buildFeatures {
        viewBinding = true
        buildConfig = true
      }
```

with

```kotlin
      buildFeatures { buildConfig = true }
```

In `LintConfig.kt` delete

```kotlin
    // False-positives abound due to use of ViewBinding
    disable += "UnusedIds"
```

and

```kotlin
      // Hilt only does field injection for fragments
      disable += "FragmentFieldInjection"
```

- [ ] **Step 5: Resources**

```bash
git rm app/src/main/res/layout/map_preference_widget_switch.xml \
  app/src/main/res/values/attrs.xml app/src/main/res/values/styles_material3.xml
```

In `themes_material3.xml` delete from `AppThemeM3`:

```xml
    <item name="toolbarStyle">@style/APSThemeM3.Toolbar</item>
    <item name="bottomSheetDialogTheme">@style/APSThemeM3.BottomSheetDialog</item>
```

and the whole style:

```xml
  <style name="APSThemeM3.BottomSheetDialog" parent="ThemeOverlay.Material3.BottomSheetDialog">
    <item name="android:windowIsFloating">false</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
  </style>

```

In `app/lint.xml` delete `    <ignore path="src/main/res/layout/oreo_autofill_filter_row.xml" />` (deleted in step 7), `    <ignore path="src/main/res/layout/map_preference_widget_switch.xml" />` under `UnusedResources`, and the whole block:

```xml
  <issue id="PrivateResource">
    <ignore path="src/main/res/layout/map_preference_widget_switch.xml" />
  </issue>
```

- [ ] **Step 6: Check**

```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck :app:testDebugUnitTest -PslimTests :app:assembleDebug :app:assembleRelease :app:lintDebug
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | grep -cE 'modernandroidpreferences|fastscroll|recyclerview-selection|androidx.preference:preference'
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency androidx.fragment:fragment | grep -m1 'androidx.fragment:fragment:'
./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency androidx.recyclerview:recyclerview | grep -m1 'androidx.recyclerview:recyclerview:'
```
Expected: `BUILD SUCCESSFUL`; the count is `0`; `androidx.fragment:fragment:1.8.4` as in Step 1; the recyclerview line shows whatever Material Components' graph resolves (record it in the report; accepted per Decisions). If the build reports `Unresolved reference 'viewModels'` in `AutofillFilterView.kt`, stop and report (`activity-compose` should provide it through `activity-ktx`). If lint now reports `UnusedIds`, delete each reported id that no code reads (`grep -rn 'R.id.<name>' app/src`) and include it here; if code reads it, report.

- [ ] **Step 7: Commit**

```bash
git add -A app/build.gradle.kts gradle/libs.versions.toml build-logic app/lint.xml app/src/main/res
git status --short
git commit -m "build(app): drop the View-era dependencies and view binding

Nothing uses preference, recyclerview, recyclerview-selection,
fastscroll or modernandroidpreferences since the Compose screens
replaced the Views, nor view binding. fragment-ktx stays as the pin
that keeps androidx.fragment at 1.8.4. The preference widget override,
the multiselect attribute, the toolbar and bottom sheet styles and the
lint ignores for deleted layouts go. Lint checks unused ids again.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The git credential prompt without ConstraintLayout (security-relevant)

**Files:**
- Modify: `app/src/main/res/layout/git_credential_layout.xml`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`

- [ ] **Step 1: Confirm the only user**

```bash
grep -rn constraintlayout --include=*.xml --include=*.kt --include=*.kts --include=*.toml . | grep -v /build/
```
Expected: `app/build.gradle.kts`, `gradle/libs.versions.toml` and `git_credential_layout.xml` (open and close tags) only.

- [ ] **Step 2: Replace the layout**

Replace everything below the license comment of `git_credential_layout.xml` with:

```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
  xmlns:app="http://schemas.android.com/apk/res-auto"
  android:layout_width="match_parent"
  android:layout_height="match_parent"
  android:orientation="vertical"
  android:padding="16dp">

  <com.google.android.material.textfield.TextInputLayout
    android:id="@+id/git_auth_passphrase_layout"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:endIconMode="password_toggle"
    app:errorEnabled="true"
    app:hintEnabled="true">

    <com.google.android.material.textfield.TextInputEditText
      android:id="@+id/git_auth_credential"
      android:layout_width="match_parent"
      android:layout_height="wrap_content"
      android:hint="@string/ssh_keygen_passphrase"
      android:inputType="textPassword" />

    <requestFocus />
  </com.google.android.material.textfield.TextInputLayout>

  <com.google.android.material.checkbox.MaterialCheckBox
    android:id="@+id/git_auth_remember_credential"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="@string/git_operation_remember_passphrase"
    app:checkedState="checked" />
</LinearLayout>
```

Only the root and the `layout_constraint*` attributes change; ids, `inputType="textPassword"`, the password toggle, the error slot and the checkbox default stay. `CredentialFinder.kt` is untouched.

- [ ] **Step 3: Drop the dependency**

Delete `  implementation(libs.androidx.constraintlayout)` from `app/build.gradle.kts` and `androidx-constraintlayout = "androidx.constraintlayout:constraintlayout:2.2.2"` from `gradle/libs.versions.toml`.

- [ ] **Step 4: Check**

```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck :app:assembleDebug :app:assembleRelease :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. The dialog itself is checked on the emulator in Task 6 Step 9.

- [ ] **Step 5: Security review**

Reviewer brief: the credential field stays `textPassword` with the toggle, the same ids feed `CredentialFinder` (`findViewById` on three ids), the "remember" box keeps its default, and nothing else in the prompt changed. Apply findings.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/git_credential_layout.xml app/build.gradle.kts gradle/libs.versions.toml
git commit -m "refactor(app): lay out the git credential prompt without ConstraintLayout

The prompt was ConstraintLayout's last user: a vertical LinearLayout
holds the same field and checkbox, so the dependency goes. Ids, input
type and the remember box's default are unchanged.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Name HardenedPass in every locale

**Files:**
- Modify: `app/src/main/res/values-{de,fr,gl,ko-rKR,pl-rPL,pt-rBR,ru,zh-rCN}/strings.xml`

- [ ] **Step 1: The occurrences**

```bash
grep -n 'Password Store' app/src/main/res/values-*/strings.xml | grep '<string '
```
Expected: 22 lines: de 4, fr 3, gl 4, ko-rKR 1, pl-rPL 3, pt-rBR 4, ru 2, zh-rCN 1 (none in `values-it`, fixed in 0.1.4). Two German lines (`password_creation_file_write_fail_message`, `password_creation_file_delete_fail_message`) mean the store itself ("im Password Store", "aus dem Password Store"); English says "the store" there. The other 20 name the app.

- [ ] **Step 2: Replace**

```bash
sed -i 's/im Password Store/im Repository/; s/aus dem Password Store/aus dem Repository/' \
  app/src/main/res/values-de/strings.xml
sed -i '/<string /s/Password Store/HardenedPass/g' app/src/main/res/values-*/strings.xml
grep -n 'Password Store' app/src/main/res/values-*/strings.xml | grep '<string '
git diff --stat
git diff app/src/main/res/values-de/strings.xml
```
Expected: the third command prints nothing; `--stat` lists the eight locale files with 22 changed lines in all (20 "HardenedPass", 2 "Repository"); the German diff shows two "HardenedPass" lines (autofill description and instructions, the latter keeps its `\"...\"` quotes) and the two "Repository" lines. The copyright comments and the `<!-- Password Store -->` section comments are untouched (not `<string` lines).

- [ ] **Step 3: Check**

```bash
CI=1 ./gradlew :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values-*/strings.xml
git commit -m "fix(i18n): name HardenedPass in every locale

0.1.4 renamed the app in the English and Italian autofill and
biometric texts only; German, French, Galician, Korean, Polish,
Brazilian Portuguese, Russian and Chinese still said Password Store in
22 strings. 20 now name HardenedPass; the two German messages where it
meant the store itself say Repository, as the rest of that translation
does.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Full CI set and emulator regression pass

No commit. The build under test is `compose` after Tasks 1 to 5. No real store or keys: the e2e environment from AGENTS.md only.

**Device safety, for every step of this task.** The agent's shell keeps no functions or variables between Bash calls, so Step 2 writes the environment and every helper to `SCRATCHPAD/e2e.sh`, where `SCRATCHPAD` stands for the executing session's scratchpad directory: replace it with that absolute path everywhere in this task before running anything. Every later step starts with the two lines below, which also stop the step if the serial is not the emulator's. All helpers carry an `e2e_` prefix: unprefixed names such as `pass`, `open`, `top` or `sync` are real commands, and `pass` would reach the maintainer's real password store. A physical tablet may be connected: it is never touched. The PIN `1234` is the emulator's own test PIN, never the owner's.

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
```

Record each step's result (ok, MISSING, counts) for the report and for Task 7.

- [ ] **Step 1: Full local CI set**

```bash
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`. A failure here is fixed with a new commit on `compose` (message in the style of the task that caused it) before the emulator pass; describe it in the report.

- [ ] **Step 2: Environment file, server, emulator**

Write the environment and helpers:

```bash
mkdir -p SCRATCHPAD/e2e-out
cat > SCRATCHPAD/e2e.sh <<'EOF'
export ANDROID_SERIAL=emulator-5554
cd "$(git rev-parse --show-toplevel)" || exit 1
ANDROID_HOME=$(sed -n 's/^sdk.dir=//p' local.properties)
OUT=SCRATCHPAD/e2e-out
APP=xyz.danix.hardenedpass.debug
e2e_ui() { adb exec-out uiautomator dump /dev/tty; }
e2e_center() { local b; b=$(e2e_ui | grep -o "<node [^>]*$1[^>]*>" | sed -n "${2:-1}p" | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }; set -- $b; echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; }
e2e_tap() { local c; c=$(e2e_center "$1" "$2") && adb shell input tap $c; sleep 1; }
e2e_field() { e2e_tap 'class="android.widget.EditText"' "$1"; sleep 1; adb shell input keycombination 113 29; adb shell input keyevent 67; adb shell input text "$2"; }
e2e_value() { e2e_ui | grep -o '<node [^>]*class="android.widget.EditText"[^>]*>' | sed -n "${1}p" | grep -o ' text="[^"]*"' | cut -d'"' -f2; }
e2e_seen() { e2e_ui | grep -q "$1" && echo "ok: $1" || echo "MISSING: $1"; }
e2e_enabled() { e2e_ui | grep -o "<node [^>]*$1[^>]*>" | head -1 | grep -o 'enabled="[a-z]*"'; }
e2e_top() { adb shell dumpsys activity activities | grep topResumedActivity; }
e2e_open() { adb shell am start -n $APP/app.passwordstore.ui.main.LaunchActivity; sleep 3; }
e2e_pick() { sleep 2; e2e_ui | grep -q "text=\"$1\"" || { e2e_tap 'content-desc="Show roots"'; e2e_tap 'text="Downloads"'; sleep 1; }; e2e_tap "text=\"$1\""; sleep 2; }
e2e_until_seen() { for _ in $(seq 30); do e2e_ui | grep -q "$1" && { echo "ok: $1"; return; }; sleep 1; done; echo "MISSING: $1"; }
e2e_prefs() { adb shell run-as $APP cat shared_prefs/${APP}_preferences.xml | grep -o "name=\"$1\"[^/]*" || echo "absent: $1"; }
e2e_secure() { adb shell dumpsys window | grep -c 'fl=.*SECURE'; }
e2e_pass() { e2e_tap 'class="android.widget.EditText"'; adb shell input text "$1"; e2e_tap 'text="OK"'; sleep 2; }
e2e_srv() { docker exec -u git aps-e2e-git git -C /srv/git/store.git "$@"; }
e2e_reveal() { e2e_srv show "${2:-master}:$1.gpg" > "$OUT/x.gpg"; GNUPGHOME=.e2e/gnupg gpg -q --batch --pinentry-mode loopback --passphrase test -d "$OUT/x.gpg"; }
e2e_sync() { e2e_tap 'content-desc="More options"'; e2e_tap 'text="Synchronize repository"'; sleep 10; }
EOF
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
type e2e_pass | head -1
scripts/e2e-env.sh up
```
Expected: `e2e_pass is a function`; the e2e server is up. (The heredoc is quoted: `$1`, `$APP` and `$OUT` expand when the helpers run, not when the file is written.)

Start the emulator as a background command that outlives this call (the agent's background-run option; the variables come from the file):

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
"$ANDROID_HOME/emulator/emulator" -avd aps-test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu swiftshader_indirect
```

Then unlock and install:

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
adb shell locksettings set-pin 1234 2>/dev/null || true
adb shell input keyevent KEYCODE_WAKEUP; adb shell input keyevent 82
adb shell input text 1234; adb shell input keyevent 66
until [ "$(adb shell getprop sys.user.0.ce_available | tr -d '\r')" = true ] && \
  adb shell test -d /sdcard/Download; do sleep 2; done
for s in animator_duration_scale transition_animation_scale window_animation_scale; do
  adb shell settings put global $s 0; done
adb shell settings put secure autofill_service null
adb uninstall $APP || true
./gradlew :app:installDebug
scripts/e2e-env.sh push-keys
```
(`set-pin` fails harmlessly when the AVD already has the PIN. The wait matters: before the keyguard is dismissed and user storage is unlocked, `adb uninstall` fails with `DELETE_FAILED_INTERNAL_ERROR` and `/sdcard/Download` does not exist. `installDebug` and `push-keys` honor `ANDROID_SERIAL`.)

- [ ] **Step 3: Clone over SSH, PGP import**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_open; e2e_tap 'text="Clone Remote Repo"'
e2e_field 1 ssh://git@10.0.2.2:2222/srv/git/store.git
e2e_tap 'text="Manage"'; e2e_tap 'text="Import SSH key"'; e2e_pick aps-e2e-ssh-key
e2e_seen 'text="Imported private key"'; adb shell input keyevent 4; sleep 1
e2e_tap 'text="Clone"'; sleep 15
e2e_tap 'content-desc="Import PGP key"'; e2e_pick aps-e2e-pgp-key.asc; e2e_tap 'text="OK"'; sleep 2
e2e_seen 'text="example.org"'; e2e_seen 'text="work"'; e2e_seen 'text="otp"'; e2e_top
```
Expected: `MainActivity` on top with the cloned store (`example.org`, `work`, `otp`). (FABs expose their label as `content-desc`, not `text`, in the dump.)

- [ ] **Step 4: Decrypt**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'text="example.org"'; e2e_pass test
e2e_seen 'text="alice"'; e2e_seen 'text="hunter2"'; e2e_secure
adb shell input keyevent 4; sleep 1; e2e_seen 'text="otp"'; e2e_secure
```
Expected: the entry shows `alice` and `hunter2` (if `hunter2` is `MISSING`, the password is masked by setting: `e2e_tap 'content-desc="Show password"'` and check again), `e2e_secure` at `1` or more; back on the list, `0`.

- [ ] **Step 5: TOTP (by eye)**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'text="otp"'; e2e_tap 'text="example.com"'; e2e_pass test
adb emu screenrecord screenshot "$OUT"
python3 -c 'import hmac,hashlib,struct,time,base64;k=base64.b32decode("JBSWY3DPEHPK3PXP");h=hmac.new(k,struct.pack(">Q",int(time.time())//30),hashlib.sha1).digest();o=h[-1]&15;print("%06d"%((struct.unpack(">I",h[o:o+4])[0]&0x7fffffff)%1000000))'
e2e_secure
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1; e2e_seen 'text="otp"'
```
The OTP screen never goes idle (no `uiautomator dump` on it) and `screencap` is black (`FLAG_SECURE`); the emulator console screenshot grabs the host-side display. Expected: in the PNG in `$OUT`, `s3cret` and "OTP (expires in Ns)" with the code the Python line printed (same 30 s window) and a shrinking bar; `e2e_secure` at `1` or more. If the console screenshot is black or unsupported, record "TOTP by eye: not checked" for the maintainer's phone test.

- [ ] **Step 6: Create and push**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'content-desc="New entry"'; e2e_tap 'text="Create new password"'; e2e_secure
adb shell input text regress.example.net
e2e_field 2 r3gress-pw; e2e_field 3 dora
e2e_tap 'text="Save"'; sleep 3; e2e_seen 'text="regress.example.net"'
e2e_sync
e2e_seen 'text="An error occurred during a Git operation"'
e2e_srv log --format=%s -3
e2e_reveal regress.example.net
```
Expected: the editor is `FLAG_SECURE`; the list shows the new entry; the sync shows no error dialog (`MISSING`); the server log's newest commit names `regress.example.net` (above `Initial test store`); the server copy decrypts to `r3gress-pw` and `username: dora`.

- [ ] **Step 7: Conflict recovery, then hard reset**

A conflicting change on the server, made inside the container with its own git (no host git, hooks or signing involved):

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
printf 'server-side\nusername: alice\n' | GNUPGHOME=.e2e/gnupg gpg -q --batch --yes \
  --trust-model always -e -r "APS Test <aps.test@example.org>" -o "$OUT/example.org.gpg"
docker exec -u git aps-e2e-git git clone -q /srv/git/store.git /tmp/w
docker cp "$OUT/example.org.gpg" aps-e2e-git:/tmp/w/example.org.gpg
docker exec aps-e2e-git chown git:git /tmp/w/example.org.gpg
docker exec -u git aps-e2e-git sh -c 'cd /tmp/w && git -c user.name=e2e -c user.email=e2e@example.org commit -qam "Server-side change" && git push -q origin master'
e2e_srv log --format=%s -1
```
Expected: `Server-side change`.

The same file changed on the device, then a sync:

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'text="example.org"'; e2e_pass test; e2e_tap 'content-desc="Edit password"'
e2e_field 2 device-side; e2e_tap 'text="Save"'; sleep 3; e2e_seen 'text="example.org"'
e2e_sync
e2e_until_seen 'detached head'; e2e_seen 'text="An error occurred during a Git operation"'
e2e_tap 'text="OK"'
```
Expected: the rebase fails and the View error dialog says "Pull has failed, you're in a detached head..."; OK closes it.

Abort and push a new branch:

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'content-desc="More options"'; e2e_tap 'text="Settings"'; e2e_tap 'text="Repository"'
e2e_tap 'text="Local Git config'; sleep 2
e2e_seen 'HEAD detached at'; e2e_enabled 'text="Abort rebase and push new branch"'
e2e_tap 'text="Abort rebase and push new branch"'
e2e_until_seen 'pushed to another branch'; e2e_seen 'text="Abort and Push"'
e2e_tap 'text="OK"'; sleep 1
e2e_srv branch --list
B=$(e2e_srv branch --list 'conflicting-*' | tr -d ' *\r')
e2e_srv log --format=%s -1 "$B"; e2e_reveal example.org "$B"
```
Expected: the git config screen shows "HEAD detached at ...", the abort button `enabled="true"`; the "Abort and Push" dialog names the pushed branch; OK returns to the Repository settings. The server has `master` and one `conflicting-master-<millis>` branch whose newest commit is the device's edit of `example.org`, decrypting to `device-side` and `username: alice`.

Hard reset to the server's `master`:

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'text="Local Git config'; sleep 2; e2e_seen 'text="On branch master"'
e2e_tap 'text="Hard reset to remote branch"'; e2e_seen 'are discarded'; e2e_value 1
e2e_tap 'text="OK"'; e2e_until_seen 'remote branch: done'
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
e2e_tap 'text="example.org"'; e2e_pass test; e2e_seen 'text="server-side"'; e2e_seen 'text="alice"'
adb shell input keyevent 4; sleep 1
```
Expected: back on `master` after the abort; the reset dialog warns and prefills `master`; the snackbar "Hard reset to remote branch: done"; the list's `example.org` now decrypts to the server's `server-side`.

- [ ] **Step 8: Fingerprint unlock (passphrase cache and app lock)**

Fingerprint (once per AVD), after the two safety lines: `adb shell dumpsys fingerprint | grep -o '"count":[0-9]*'`. If it prints nothing or `"count":0`, enroll: `adb shell am start -a android.settings.FINGERPRINT_ENROLL`, enter the emulator PIN `1234`, follow the screens (dump them with `e2e_ui` to find the buttons) and run `adb emu finger touch 1` until enrollment completes; then `e2e_open`.

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'content-desc="More options"'; e2e_tap 'text="Settings"'; e2e_tap 'text="PGP settings"'
e2e_tap 'text="Enable passphrase caching"'; sleep 1; adb emu finger touch 1; sleep 2
e2e_prefs enable_gpg_passphrase_cache
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
e2e_tap 'text="example.org"'; e2e_seen 'text="Unlock passphrase cache"'
adb emu finger touch 1; sleep 2
e2e_seen 'text="Clear cached password on screen-off"'
e2e_tap 'text="Clear cached password on screen-off"'
e2e_pass test; e2e_seen 'text="server-side"'
adb shell input keyevent 4; sleep 1
e2e_tap 'text="example.org"'; adb emu finger touch 1; sleep 2
e2e_seen 'text="server-side"'; e2e_seen 'class="android.widget.EditText"'
adb shell input keyevent 4; sleep 1
```
Expected: the cache switch stores `true` after the touch; the first open shows the prompt, then the passphrase dialog with the cache box, and decrypts; the second open decrypts with the touch alone (`ok: server-side`, `MISSING: ...EditText`).

App lock:

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'content-desc="More options"'; e2e_tap 'text="Settings"'; e2e_tap 'text="General"'
e2e_tap 'text="Enable biometric authentication"'; sleep 1; adb emu finger touch 1; sleep 2
e2e_prefs biometric_auth_delete_soon_please
adb shell am force-stop $APP; e2e_open
adb emu finger touch 1; sleep 3; e2e_seen 'text="example.org"'; e2e_top
e2e_tap 'content-desc="More options"'; e2e_tap 'text="Settings"'; e2e_tap 'text="General"'
e2e_tap 'text="Enable biometric authentication"'; sleep 1; adb emu finger touch 1; sleep 2
e2e_prefs biometric_auth_delete_soon_please
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
```
Expected: `true` after the first touch; a cold start shows the prompt and the touch opens the list (`MainActivity` on top); `false` after turning it off.

- [ ] **Step 9: The git credential prompt (Task 4's layout)**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'content-desc="More options"'; e2e_tap 'text="Settings"'; e2e_tap 'text="Repository"'
e2e_tap 'text="Edit Git server settings"'; sleep 2
e2e_tap 'text="Password"'; e2e_tap 'text="Save"'; sleep 2
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
e2e_tap 'content-desc="More options"'; e2e_tap 'text="Pull from remote"'; sleep 3
e2e_seen 'Please provide the password'; e2e_seen 'text="Remember password"'
e2e_ui | grep -o '<node [^>]*text="Remember password"[^>]*>' | grep -o 'checked="[a-z]*"'
e2e_ui | grep -o '<node [^>]*class="android.widget.EditText"[^>]*>' | grep -o 'password="[a-z]*"'
adb exec-out screencap -p > "$OUT/credential.png"
e2e_tap 'text="Cancel"'; sleep 3
e2e_seen 'text="An error occurred during a Git operation"'
```
If an error dialog shows after Cancel, `e2e_tap 'text="OK"'` (after the two safety lines). Then restore SSH key mode:

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
e2e_tap 'content-desc="More options"'; e2e_tap 'text="Settings"'; e2e_tap 'text="Repository"'
e2e_tap 'text="Edit Git server settings"'; sleep 2
e2e_tap 'text="SSH key"'; e2e_tap 'text="Save"'; sleep 2
adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
e2e_sync; e2e_seen 'text="An error occurred during a Git operation"'
```
Expected: the View credential dialog shows the message, a password field (`password="true"`) and "Remember password" `checked="true"`, laid out as before (field above checkbox, see the PNG); Cancel closes it without a crash; after restoring SSH key mode the sync works again (no error dialog).

- [ ] **Step 10: Invalid `.gpg-id` (the Toast)**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
adb shell "run-as $APP sh -c 'mkdir -p files/store/badid && echo 0x1234ABCD > files/store/badid/.gpg-id && cp files/store/work/mail.example.org.gpg files/store/badid/x.gpg'"
e2e_open; e2e_tap 'text="badid"'; e2e_tap 'text="x"'; sleep 1
adb exec-out screencap -p > "$OUT/toast.png"
e2e_seen 'class="android.widget.EditText"'
adb logcat -d -b crash | grep -c 'FATAL EXCEPTION'
adb shell input keyevent 4; sleep 1
adb shell run-as $APP rm -r files/store/badid
```
Expected: no passphrase dialog (`MISSING: ...EditText`), no crash (`0`), and `toast.png` shows "Found .gpg-id, but it contains a short hex ID, which is not supported" at the bottom (the entry window behind it is black, FLAG_SECURE; the toast is SystemUI's window). If the screenshot shows no toast, record it for the maintainer's phone test. The folder is removed before anything syncs.

- [ ] **Step 11: Autofill activities as root (smoke)**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
if adb root | grep -q 'cannot run as root'; then echo "skip: no adb root"; else
  adb wait-for-device; sleep 2
  X=app.passwordstore.autofill.oreo.ui
  adb shell am start -n $APP/app.passwordstore.ui.autofill.AutofillFilterView \
    --es android.view.autofill.extra.CLIENT_STATE x --es $X.EXTRA_FORM_ORIGIN_WEB example.org; sleep 3
  e2e_top; e2e_seen 'Select entry to fill into'
  adb shell input keyevent 4; sleep 1
  adb logcat -c
  adb shell am start -n $APP/app.passwordstore.ui.autofill.AutofillDecryptActivity; sleep 2
  e2e_top; adb logcat -d | grep -c 'started without EXTRA_FILE_PATH'
  adb unroot; adb wait-for-device; sleep 2
fi
```
Expected with root: the picker opens (`AutofillFilterView` on top, header found); the decryption, started without extras, logs once (`1`) and finishes at once (no longer on top). Without root: `skip`. Record whether root worked: Task 7 adds the root note to AGENTS.md only if it did.

- [ ] **Step 12: Minified release build (smoke)**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
adb install -r "$(find app/build/outputs/apk/release -name '*.apk' | head -1)"
adb logcat -c
adb shell am start -n xyz.danix.hardenedpass/app.passwordstore.ui.main.LaunchActivity; sleep 3
e2e_tap 'text="Settings"'; e2e_tap 'text="General"'; e2e_seen 'text="Password sort order"'
adb shell input keyevent 4; sleep 1; e2e_tap 'text="Misc"'; e2e_seen 'text="Debug logging"'
adb logcat -d -b crash | grep -c 'FATAL EXCEPTION'
adb uninstall xyz.danix.hardenedpass
```
Expected: the R8 build from Step 1 opens the welcome screen and two Settings categories, crash count `0`; the release package is removed from the emulator afterwards.

- [ ] **Step 13: Tear down**

```bash
source SCRATCHPAD/e2e.sh
[ "$ANDROID_SERIAL" = emulator-5554 ] || exit 1
adb shell cmd uimode night no
adb uninstall $APP
adb shell rm -f /sdcard/Download/aps-e2e-ssh-key /sdcard/Download/aps-e2e-pgp-key.asc
adb emu kill
scripts/e2e-env.sh down
date +%F
```
Look at `SCRATCHPAD/e2e-out` (TOTP, credential and toast screenshots) before deleting it. Note the printed date: Task 7 records it as `RUN_DATE`.

---

### Task 7: AGENTS.md, the spec and CONTRIBUTING.md, push `compose`

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`
- Modify: `CONTRIBUTING.md`

Below, `RUN_DATE` is the date Task 6 Step 13 printed; write the actual date, never the placeholder. Do not touch AGENTS.md's public suffix list "Last run:" line: `master` changed it, and leaving it alone keeps the merge clean there.

- [ ] **Step 1: AGENTS.md, branches**

Replace

```
- `master` is the only long-lived branch. Upstream's `develop` was renamed; do not recreate it.
- Temporary exception: the Compose UI overhaul
  (`docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`) lives on `compose` until
  it is complete; nothing is released from it. `master` stays releasable (public suffix list,
  hotfixes). Remove this bullet when `compose` merges.
```

with (the exact line `master` has, so this hunk merges cleanly; it lands on `master` only with the merge, after which `compose` is deleted)

```
- `master` is the only branch. Upstream's `develop` was renamed; do not recreate it.
```

- [ ] **Step 2: AGENTS.md, what the UI is**

After the paragraph that ends ``Build conventions live in `build-logic/`.`` insert:

```

The UI is Jetpack Compose with Material 3. `MainActivity` hosts every screen in one `NavHost`
(list, entry, editor, setup, settings); `LaunchActivity` is the only exported activity. The
autofill service starts its four activities (`AutofillFilterView`, `AutofillDecryptActivity`,
`AutofillSaveActivity`, `AutofillPublisherChangedActivity`) directly, so they stay separate and
draw with the same theme.
```

- [ ] **Step 3: AGENTS.md, emulator notes**

Replace `    ./gradlew installDebug` with `    ./gradlew :app:installDebug`.

Replace

```
Drive it with `adb shell input`, `uiautomator dump` and `screencap`. Decrypt screens set
`FLAG_SECURE`, so screenshots of them are black; read them from the `uiautomator` dump.
```

with

```
If a physical device is connected too, `export ANDROID_SERIAL=emulator-5554` first, so every
`adb` and Gradle install command targets the emulator. Agent shells keep no functions or
variables between calls: keep that export and any helpers in a sourced file, and prefix helper
names (`e2e_pass`, not `pass`, which is the real `pass` and reaches a real store). Drive it with
`adb shell input`, `uiautomator dump` and `screencap`; Compose nodes carry `text` and
`content-desc` in the dump, no resource ids, so match on those. The entry view, the editor and
its generator sheet, the proxy screen, the passphrase dialogs and the autofill decryption and
save windows set `FLAG_SECURE`, so screenshots of them are black; read them from the
`uiautomator` dump.
```

Only if Task 6 Step 11 ran with root, replace

```
Autofill cannot be tested on the emulator image: Settings is denylisted in
`OreoAutofillService`, and the only browser (WebView Shell) is not a trusted browser. Use a
real device with a trusted browser (e.g. Firefox) instead:
```

with

```
Autofill cannot be tested on the emulator image: Settings is denylisted in
`OreoAutofillService`, and the only browser (WebView Shell) is not a trusted browser. Its
activities are not exported; after `adb root` (the AOSP image allows it), `am start -n` opens the
picker, the publisher warning and the save editor with test extras, but not a real decryption
(it needs a client state `Bundle`). Use a real device with a trusted browser (e.g. Firefox)
instead:
```

Replace

```
Last full device run (2026-11-02): clone over SSH, PGP import, decrypt, TOTP, create and push,
conflict recovery, hard reset, fingerprint unlock (emulator) and Firefox autofill (tablet) all
passed.
```

with (drop "TOTP" from the list if Task 6 Step 5 could not check it)

```
Last full emulator run (RUN_DATE, Compose UI): clone over SSH, PGP import, decrypt, TOTP,
create and push, conflict recovery (abort rebase, push a new branch), hard reset and
fingerprint unlock passed. Last Firefox autofill run on the tablet: 2026-11-02, before the
Compose autofill UI; repeat it before the next release.
```

(If the maintainer reports that step 7's tablet checklist passed, write that date instead and drop the "before the Compose autofill UI; repeat it" clause. Task 8 asks for it.)

- [ ] **Step 4: AGENTS.md, verified facts**

After the `LaunchActivity` bullet (already current on `compose`: it names `MainActivity` and `EntryRoute`), insert:

```
- Material Components (Views) stays after the Compose overhaul: the window themes derive from
  `Theme.Material3.DayNight` (the AppCompat activities need it), and the git operations' View
  dialogs (`BaseGitActivity`, `GitOperation`, `CredentialFinder` with
  `git_credential_layout.xml`, `BreakOutOfDetached`, `StoreActions`), `DynamicColors` and the
  `snackbar` helper use it. `oreo_autofill_dataset.xml` stays a layout: fill suggestions are
  `RemoteViews` drawn by the system. `fragment-ktx` is declared only to pin `androidx.fragment`
  (appcompat alone resolves an older one).
```

- [ ] **Step 5: The spec**

Replace the line

```
Date: 2026-11-02. Status: approved, not started.
```

with

```
Date: 2026-11-02. Status: complete (RUN_DATE); merges into `master` after the maintainer's phone test.
```

Replace the "Removed at the end" paragraph

```
`fragment-ktx`, `recyclerview`, `recyclerview-selection`, `swiperefreshlayout`,
`modernandroidpreferences`, the view-binding helpers (`FragmentViewBindingDelegate`,
`FragmentExtensions`), all layout XML files, and Material Components (Views) if nothing still
needs it.
```

with

```
`recyclerview`, `recyclerview-selection`, `swiperefreshlayout`, `modernandroidpreferences`,
`preference`, `constraintlayout`, fastscroll, view binding and its helpers
(`FragmentViewBindingDelegate`, `FragmentExtensions`), `BasePGPActivity` and every layout XML
file but two. Kept: `oreo_autofill_dataset.xml` (fill suggestions are `RemoteViews`, drawn by the
system) and `git_credential_layout.xml` (the git credential prompt), with Material Components
(Views): the window themes, the git operations' dialogs and the snackbars still need it. Moving
those to Compose is a separate change. `fragment-ktx` stays declared as the version pin of
`androidx.fragment`.
```

In "Branching", replace

```
to `origin` as a backup. CI is not extended to the branch: the local pre-commit check from
AGENTS.md runs before every commit, and CI runs at the merge.
```

with

```
to `origin` as a backup. CI is not extended to the branch: the local pre-commit check from
AGENTS.md runs before every commit, and CI runs at the merge. `master` stays at its last release
until the maintainer has tested a release build of `compose` on the phone and says go; then
`compose` merges with one signed `--no-ff` merge commit and is deleted.
```

In "Testing", replace

```
- Before merge: the full device checklist from AGENTS.md (clone over SSH, PGP import, decrypt,
  TOTP, create and push, conflict recovery, hard reset, fingerprint unlock, Firefox autofill),
  then the maintainer tests on the phone.
```

with

```
- Before merge: the full device checklist from AGENTS.md (clone over SSH, PGP import, decrypt,
  TOTP, create and push, conflict recovery, hard reset, fingerprint unlock, Firefox autofill),
  then the maintainer tests a release build of `compose` on the phone; the merge waits for the
  maintainer's go.
```

- [ ] **Step 6: CONTRIBUTING.md**

Replace

```
- For inflating a Fragment or Activity view, always use the [`viewBinding`](https://github.com/android-password-store/Android-Password-Store/blob/3a16d77db24b6d79d9d27ce3207d72a7a879f38a/app/src/main/java/dev/msfjarvis/aps/util/extensions/FragmentViewBindingDelegate.kt) extension.
```

with

```
- The UI is Jetpack Compose (Material 3): screens are stateless composables driven by a ViewModel. Do not add XML layouts, Fragments or view binding.
```

- [ ] **Step 7: Check, commit, push `compose`**

Docs only: Task 6 Step 1 already ran the full CI set on the code, so no Gradle run here.

```bash
grep -nw -e '`compose`' -e 'Temporary exception' -e 'DecryptActivity' -e 'installDebug' AGENTS.md
grep -c 'RUN_DATE' AGENTS.md docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git add AGENTS.md CONTRIBUTING.md docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "docs: record the finished Compose overhaul

AGENTS.md drops the compose branch exception, describes the Compose UI
and its activities, updates the emulator notes (serial pinning, prefixed
helpers, Compose nodes, which windows are FLAG_SECURE) and the last
device run, and records why Material Components (Views) and
fragment-ktx stay. The spec is marked complete with what was removed
and kept, and says the merge waits for the maintainer's phone test;
CONTRIBUTING.md no longer asks for view binding.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
git status -sb | head -1
```
Expected: the first grep shows only `:app:installDebug` (`-w` keeps `AutofillDecryptActivity` from matching `DecryptActivity`; no exception bullet left); the placeholder count is `0` for both files; the push reaches every `origin` push URL; `## compose...origin/compose` with nothing ahead. If the push fails with "agent refused operation", the smartcard needs the maintainer: ask them to run `! git push`.

---

### Task 8: Maintainer gate: phone test of a `compose` release build

The agent builds and verifies the APK and hands it over. The agent never connects to, installs on or reads the maintainer's phone or tablet.

- [ ] **Step 1: Signed release APK from `compose`, with `master`'s version**

`compose` still carries 0.1.4 (code 104); the phone runs 0.1.5 (code 105), and Android refuses a downgrade. `master`'s version file is applied only for this build and restored at once, so nothing is committed:

```bash
git switch compose && git status --short
git show master:app/version.properties > app/version.properties
./gradlew :app:assembleRelease
git checkout -- app/version.properties
git status --short
APK=$(find app/build/outputs/apk/release -name '*.apk' | head -1); echo "$APK"
sha256sum "$APK"
SDK=$(sed -n 's/^sdk.dir=//p' local.properties)
"$(ls -d "$SDK"/build-tools/* | tail -1)/apksigner" verify --print-certs "$APK" | grep -i 'SHA-256'
"$(ls -d "$SDK"/build-tools/* | tail -1)/../../cmdline-tools/latest/bin/apkanalyzer" manifest version-code "$APK" 2>/dev/null || true
```
Expected: `git status --short` clean before the build and again after the restore; the signer's SHA-256 digest is `725016669e482f9ac371dee8df19a2a1b34fa76d1af05b2a23784725b1b49c83` (the certificate in AGENTS.md "Releases"); version code `105` if `apkanalyzer` is installed (otherwise skip that line). Never print the keystore password.

- [ ] **Step 2: Hand over and wait**

Report the APK path and its SHA-256 to the maintainer with this checklist (the maintainer installs it over 0.1.5; same signing key, data kept):

1. App lock (if enabled), list, folders and breadcrumbs, search, pull to sync against the real server.
2. Open entries: password, username, OTP code and countdown, copies cleared after the timeout; screenshots of the entry view are blocked; the list screenshots normally after a passphrase dialog (an emulator oddity to confirm on the phone).
3. Edit and create an entry, sync, check it on the desktop with `pass`.
4. Settings: every category opens; the owed checks: clearing a saved HTTPS password or SSH passphrase really clears it, and the autofill instructions dialog's OK opens the system picker.
5. Firefox autofill (phone or tablet): the step 7 tablet checklist (`2026-11-04-compose-autofill.md`, Task 6 Step 10), still owed. Report its date for AGENTS.md.
6. Anything Task 6 recorded as "for the maintainer" (TOTP by eye, the `.gpg-id` toast).

Findings are fixed on `compose` (normal commits, the full CI set from AGENTS.md, push), and Step 1 is repeated for a new build. If the maintainer reports the Firefox autofill date, update AGENTS.md's "Last Firefox autofill run" line on `compose` in a `docs:` commit. **Stop here until the maintainer explicitly says go for the merge.**

---

### Task 9: Merge `compose` into `master` and push (only after the maintainer's go)

- [ ] **Step 1: Both branches current**

```bash
git status --short                     # clean (HANDOFF.md is ignored)
git fetch origin
git switch master
git merge --ff-only origin/master
git log --oneline -1
git log --oneline master..compose | wc -l
git log --oneline compose..master
```
Expected: clean tree; `master` at `0ad0b1ca` (`release: HardenedPass 0.1.5`) or later; about 82 commits on `compose` only; `compose..master` lists `0ad0b1ca`, `ad3c63f6` and `fd3909e1` (the last two are already on `compose` as cherry-picks), plus any newer `master` commit (a public suffix list run, say).

- [ ] **Step 2: Merge without committing**

```bash
git merge --no-ff --no-commit compose
git status --short | grep -v '^[AMDR] '
```
Expected: `UU AGENTS.md` only.

- [ ] **Step 3: Resolve AGENTS.md**

The conflict is around the bullet before the sshj bullet in "Verified facts and gotchas". Keep the `compose` side of the hunk in full: the `LaunchActivity` bullet

```
- `LaunchActivity` is exported and forwards `FILE_PATH` to `MainActivity` (not exported) only
  after `isFileInRepo` checks it canonically resolves inside the store; `MainActivity` opens it as
  `EntryRoute`, whose destination checks it again before decrypting.
```

and the Material Components bullet that Task 7 Step 4 inserted right after it (it sits inside the same conflict hunk, on the `compose` side, and must not be dropped). Delete `master`'s and the base's `DecryptActivity` versions with all markers. Rule for any other hunk: the `compose` text wins, except the public suffix list "Last run:" line, which keeps `master`'s date (`2026-11-03`, or a later one if `master` ran it since).

```bash
grep -c '^<<<<<<<\|^=======\|^>>>>>>>\|^|||||||' AGENTS.md
grep -n 'Last run:' AGENTS.md
grep -c 'KeyFactory.Ed25519' AGENTS.md
grep -c 'Material Components (Views) stays' AGENTS.md
git diff compose -- AGENTS.md
git add AGENTS.md
git diff --cached --stat compose
```
Expected: `0` markers; `Last run: 2026-11-03` (or later); the sshj bullet once (`1`); the Material Components bullet once (`1`); against `compose`, AGENTS.md differs only in the "Last run:" line; the staged tree differs from `compose` in `AGENTS.md`, `CHANGELOG.md` and `app/version.properties` only (`master`'s 0.1.5 release, plus whatever newer `master` commit Step 1 listed).

- [ ] **Step 4: Full CI set on the merge result, then commit**

```bash
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
git commit -m "Merge branch 'compose': Compose UI overhaul

Every screen moves from XML layouts, Views and Fragments to Jetpack
Compose with Material 3, in one MainActivity with a NavHost: the list
with breadcrumbs, search, pull to sync and multi-select; the entry view
with OTP countdown; the editor with generator sheet and folder picker;
setup (welcome, git server and clone, SSH key, PGP keys); settings, git
config, commit log and proxy; and the autofill picker, publisher
warning and decryption prompts. LaunchActivity stays the only exported
activity. Fixes found on the way reach master with it, among them
symlink-safe deletes, the proxy user name, screen lock gated switches,
the Android 14 app lock and screen lock prompts, and the autofill
passphrase cache rule. The View-era dependencies and view binding are
gone; Material Components (Views) stays for the git dialogs.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git log --format='%h %G? %s' -1
git log --oneline --graph -3
```
Expected: `BUILD SUCCESSFUL`; the merge commit shows `G` (signed); the graph shows two parents. If the hook rejects the message, treat it as correct (AGENTS.md "Personal-data git hooks"), fix the message and commit again.

- [ ] **Step 5: Push and watch CI**

```bash
git push origin master
git status -sb | head -1
git ls-remote github:danixland/HardenedPass.git refs/heads/master
git rev-parse HEAD
for i in $(seq 20); do
  ID=$(gh run list --repo danixland/HardenedPass --workflow CI --commit "$(git rev-parse HEAD)" \
    --json databaseId --jq '.[0].databaseId')
  [ -n "$ID" ] && break; sleep 15
done; echo "run: $ID"
gh run watch "$ID" --repo danixland/HardenedPass --exit-status
```
Expected: the push reaches both push URLs (own server and the GitHub mirror); `## master...origin/master` with nothing ahead; the GitHub `master` hash equals `HEAD`; the CI run for this exact commit appears within five minutes and `gh run watch` exits `0`. If no run appears, report it (the workflow may not have triggered). Never fix anything on GitHub itself; a CI failure is fixed locally on `master` and pushed. No tag, no release.

---

### Task 10: Delete `compose`

- [ ] **Step 1: Maintainer gate**

The maintainer's go in Task 8 covers the deletion once Task 9's CI run is green, unless the maintainer asked to keep the branch. Report the merge hash and CI result first.

- [ ] **Step 2: Delete the branch locally and on both remotes**

```bash
git switch master
git branch -d compose
git push origin --delete compose
git fetch --prune origin
git branch -a
git ls-remote --heads origin
git ls-remote --heads github:danixland/HardenedPass.git
```
Expected: `-d` succeeds (the branch is merged; never `-D`); the delete reaches both push URLs; afterwards only `master` exists locally, as `remotes/origin/master`, on the own server and on GitHub. AGENTS.md already says `master` is the only branch. Update the local `HANDOFF.md` (never staged or committed).

---

## Self-review notes

- **Spec coverage for step 8:** "Removed at the end": `recyclerview`, `recyclerview-selection`, `modernandroidpreferences` (Task 3), `swiperefreshlayout` (already gone), `FragmentViewBindingDelegate` and `FragmentExtensions` (Task 1), layouts (only `oreo_autofill_dataset.xml` and `git_credential_layout.xml` remain, Task 4 and Decisions), `fragment-ktx` kept as a version pin and Material Components kept, both recorded (Decisions, Task 7 Steps 4 and 5). "Branching": the AGENTS.md exception bullet goes (Task 7 Step 1), the spec says the merge waits for the phone test (Task 7 Step 5), CI runs at the merge (Task 9 Step 5). "Testing": full CI set (Tasks 6, 9), the emulator checklist pinned to `emulator-5554` (Task 6 Steps 3 to 9), Firefox autofill and the phone test before the merge (Task 8). "Migration order" step 8: delete, AGENTS.md, merge (Tasks 1 to 9).
- **Step 7 final review leftovers, each verified:** the three unused Gradle dependencies plus preference, modernandroidpreferences and constraintlayout (Tasks 3, 4); `FragmentExtensions.kt` and `FragmentViewBindingDelegate.kt`, view binding (Tasks 1, 3); `stableId`, `navigateBack`, `canNavigateBack`, `NavigationStackEntry.recyclerViewState`, `navigateTo`'s `Parcelable` (Task 1); `BasePGPActivity` with its helpers moved first and the `.gpg-id` snackbar changed to a Toast (Task 2); `MaterialAlertDialogBuilder` users kept, with Material Components (maintainer decision 1); kept-for-good resources unchanged.
- **Found in the survey beyond the list:** the four slide animators, `map_preference_widget_switch.xml`, `attrs.xml`, the toolbar and bottom sheet styles, stale `app/lint.xml` ignores, `UnusedIds` and `FragmentFieldInjection` lint disables, dead `AndroidExtensions` helpers, `SearchResult.isFiltered`, `ListMode.DirectoriesOnly`, `PasswordItem`'s `parent` overloads, `@JvmStatic` and `"NAME"` extra, `sentry.properties`, two "legacy activities" comments in `ListDestination.kt`, the view binding bullet in `CONTRIBUTING.md`, and AGENTS.md's "Decrypt screens" and `installDebug` lines. No manifest, ProGuard or `lint-baseline.xml` change is needed.
- **Review findings applied (2026-11-04):** `fragment-ktx` kept as the `androidx.fragment` 1.8.4 pin with a `dependencyInsight` check, `recyclerview` left to transitive resolution (Task 3); Task 6 helpers in a sourced scratchpad file, `e2e_` prefixed, with a serial guard at the top of every step; the CI run found by commit and retried (Task 9 Step 5); the Material Components bullet kept inside the conflict hunk (Task 9 Step 3); `-w` grep for `DecryptActivity` (Task 7 Step 7); the ViewModel's internal `navigateTo` calls in Task 1's expected grep; the Toast on the application context (Task 2); the dependency line count; the full FLAG_SECURE list (proxy screen, generator sheet) in AGENTS.md and no redundant full CI for the docs commit (Task 7); the upstream leftovers (Task 1).
- **Merge rehearsal:** `git merge-tree --write-tree master compose` at `9f4e4f12` conflicts only in AGENTS.md (the `LaunchActivity` bullet); the PSL date merges to `2026-11-03` by itself. Task 7 Step 1 uses `master`'s exact branch line, so that hunk merges cleanly too.
- **Security:** the autofill decryption keeps `FLAG_SECURE` from the first line after `super.onCreate` (Task 2, reviewed); the `FILE_PATH` key is unchanged and still forwarded only after `isFileInRepo`; `.gpg-id` parsing is unchanged apart from the message channel; the credential prompt keeps its password input (Task 4, reviewed, emulator Step 9). No secret enters a log, Bundle or saved state. The test APK for the phone is signed with the release key and verified against the AGENTS.md certificate (Task 8); the keystore password is never printed.
- **Type and name consistency:** `getParentPath(fullPath: String, repositoryPath: String): String`, `getLongName(fullPath: String, repositoryPath: String, basename: String): String` and `Context.getPGPIdentifiers(subDir: String): List<PGPIdentifier>?` (Task 2) are used with these signatures by `EntryDestination`, `AutofillDecryptActivity`, `StoreActions`, `PasswordItem` and `EditorDestination`. `LaunchActivity.EXTRA_FILE_PATH` is read by `MainActivity` and written by `PasswordItem` and `LaunchActivity`. `navigateTo(newDirectory: File)` (Task 1) matches `ListDestination`'s call. `PasswordItem(name, type, file, rootDir)` (Task 1) matches the two remaining factories.
- **Build order:** each of Tasks 1 to 5 builds and lints on its own (Task 1 deletes the animators with their last user; Task 3 removes dependencies after Tasks 1 and 2 removed their users; Task 4 removes ConstraintLayout with its last user).

## Out of scope

- **Moving the git operations' View dialogs and the snackbars to Compose,** and with it dropping Material Components (Views): a later change on `master` (maintainer decision 1).
- **`compose-ui-tooling` as `implementation`** in `app` and `ui/compose`: it belongs in `debugImplementation`, but that is a size fix unrelated to the overhaul.
- **The LeakCanary bools** in `values/bools.xml` (ignored by `app/lint.xml`), the `plumber-android-startup` dependency and the `sentry` package rule in `.github/renovate.json5`: untouched.
- **The stale "settings > git utils" wording** in `git_pull_rebase_fail_error` (the row is "Local Git config & utilities"): a string change in every locale, later.
- **CHANGELOG.md:** written at the next release (maintainer decision 7).
- **No release, no tag** in this step.

## Maintainer decisions (2026-11-04)

1. **Material Components (Views) stays** in step 8; the git dialogs and snackbars move to Compose later on `master`. AGENTS.md records why (Task 7 Step 4).
2. **Survey findings are in scope** as planned (Tasks 1 to 3), with the review's additions: the upstream leftovers in Task 1 and `fragment-ktx` kept as the `androidx.fragment` version pin.
3. **Locales in step 8:** 22 strings in eight locales, 20 renamed to "HardenedPass" and 2 German ones to "Repository" (Task 5).
4. **Credential layout:** `LinearLayout`, our `constraintlayout` dependency dropped (Task 4).
5. **Merge method:** one signed `--no-ff` merge commit (Task 9).
6. **Phone test before merge:** `master` stays at 0.1.5 until the maintainer, having tested a release build of `compose` on the phone (plus the owed Firefox autofill check), says go (Task 8). The agent never touches the phone. Then the merge (Task 9) and the branch deletion (Task 10). The spec's Branching and Testing text say so (Task 7 Step 5).
7. **No CHANGELOG entry** in step 8.
