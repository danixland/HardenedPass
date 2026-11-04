# Compose UI overhaul, step 7: Autofill activities. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the UI of the autofill service's activities to Compose with the shared theme: the entry picker (`AutofillFilterView` with `PasswordViewHolder` and `SearchableRepositoryAdapter`), the publisher warning (`AutofillPublisherChangedActivity`) and the dialogs of the decryption (`AutofillDecryptActivity` with `PasswordDialog` and `BasePGPActivity.requireKeysExist`). `AutofillSaveActivity` already hosts the Compose editor since step 4 and does not change. Intents, extras, results, autofill parsing, crypto and preference keys stay as they are. Two security fixes come with it (maintainer decisions): autofill caches a passphrase only after a successful screen lock prompt, as the entry view does, and the publisher warning drops touches that reach it through another app's window.

**Architecture:** The four activities stay separate (the autofill service starts them through `PendingIntent`s) and keep their window themes (`DialogLikeThemeM3` for the picker and the warning, `NoBackgroundThemeM3` for the decryption). Each calls `setContent { APSTheme { ... } }`. Two stateless screens are new, in `app.passwordstore.ui.autofill`: `AutofillFilterScreen` (reusing the list's `EntryRow`, `GroupedSurface` and `ListRow`, Settings' `PreferenceSwitch`, and the existing `SearchableRepositoryViewModel`) and `PublisherChangedScreen`. Two pure functions are unit tested: `autofillRows` turns search results into picker rows, `cachesPassphrase` decides whether autofill may cache a passphrase. The decryption has no screen: its window shows only the "No keys imported" dialog and the passphrase dialog, which becomes the entry view's `PassphraseDialog` (made `internal`). No ViewModel is added: the picker reuses `SearchableRepositoryViewModel`, the warning has no state worth one, and the decryption keeps its flow in the activity, as before. Legacy layouts, `PasswordViewHolder`, `PasswordDialog`, `requireKeysExist` and the RecyclerView adapter are deleted by the task that replaces them, because lint (warnings as errors) reports unused resources at once.

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2024.09.03 (Material 3 1.3.0: `OutlinedTextField`, `AlertDialog`, `Button`, `OutlinedButton`, `Surface`; foundation 1.7.3: `LazyColumn`, `SelectionContainer`, `verticalScroll`), activity-compose (`setContent`), lifecycle-runtime-compose (`collectAsStateWithLifecycle`), kotlinx-collections-immutable, androidx biometric through `BiometricAuthenticator` (unchanged), kotlin-result 2.0.0. No new dependencies.

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`. Earlier plans: `2026-11-02-compose-foundation.md`, `2026-11-02-compose-list.md`, `2026-11-02-compose-entry.md`, `2026-11-02-compose-editor.md`, `2026-11-03-compose-setup.md`, `2026-11-03-compose-settings.md` (same folder).

## Decisions taken in this plan

- **Separate activities, shared theme.** As the spec says, the autofill service launches these activities directly, so they cannot be destinations of `MainActivity`'s graph. They keep their manifest entries, `exported="false"`, window themes, `PendingIntent` factories (request codes, `FLAG_MUTABLE` where the system must add extras) and extras unchanged. Only their content moves to Compose.
- **Setup intent.** The decryption still opens the PGP key import through `MainActivity.setupIntent(this, SetupStart.PgpImport)` for a result. The spec's "until step 7" assumed autofill would move into the graph; it cannot, so the setup intent stays for autofill for good. `MainActivity`'s `ponytail:` note and the spec's Setup bullet are reworded (Task 5). Nothing else changes there.
- **Picker rows.** `EntryRow` takes a plain name, so the old bold, underlined identifier inside "path/**identifier**" becomes the row's name: the origin identifier (the part that must match the site or app) is the headline, and the subtitle holds the account part and the folders above it ("john@doe.org · work"). The letter avatar follows the identifier. Without an identifier (directory based structure, entry at the root) the account part is the name, as before (`autofillRows`, tested).
- **Picker list.** One `GroupedSurface` wrapping a `LazyColumn` with one item per row, so a cleared search over a large store stays virtualized (the list's one-item-per-group shortcut would compose every entry). Search results arrive from `SearchableRepositoryViewModel.searchResult` with the old arguments (fuzzy or strict domain, recursive, files only); rows are built off the main thread. `null` rows mean "first search still running" and show nothing, so "No results." never flashes before the first result. The first search starts in `onCreate`, before anything is composed, so the ViewModel's default root listing (folders included) never reaches the picker. The rows flow is cold: each return to `STARTED` walks the store again (`ponytail:`, fine for a picker open a few seconds).
- **Picker state.** Query and the three switches (phishing-resistant search, match, clear existing matches) are `rememberSaveable` with the old defaults: query prefilled with the origin's trusted pretty identifier, strict search on and shown for websites only, match on, clear off. A recreation keeps them (the old views kept them through view state). The first tap on a row wins: a double tap no longer starts two decryptions. The window keeps its size (75 % wide, 90 % high), `setFinishOnTouchOutside(true)` and `adjustNothing`.
- **Publisher warning.** Same content and order of decisions: warning sign, the existing "Possible phishing attempt" title (the dataset's title, already in every locale) instead of the app logo and name, the header, the app name in quotes, the install date, the footer, then **Keep Autofill disabled** (now the filled, primary button; it was outlined like the others) and **Advanced information**. Re-enabling stays behind two taps: "Re-enable Autofill" and the package and certificate hash (monospace, selectable) appear only after "Advanced information". Re-enabling clears the app's matches and returns the prepared fill response, exactly as before; "Keep Autofill disabled", back and a tap outside return nothing. The expanded state is `rememberSaveable` (no secret; a recreation never skips the tap that expanded it). An unknown package still finishes at once. `stringResource` drops the footer's `<b>` in every locale but Italian (whose footer has none); the red title carries the emphasis instead (`ponytail:`). **Tapjacking guard** (maintainer decision): `window.decorView.filterTouchesWhenObscured = true`, so a touch that reaches the warning through another app's window (`MotionEvent.FLAG_WINDOW_IS_OBSCURED`) is dropped before Compose sees it; `ViewGroup.dispatchTouchEvent` checks the decor view's own flag first, which covers the `ComposeView` below it. Back still works under an overlay.
- **Decryption dialogs.** The window stays transparent with `FLAG_SECURE` (from `BasePGPActivity`). "No keys imported" becomes a Compose `AlertDialog` whose Import opens the key import; dismissing it now finishes the activity (the old View dialog could be dismissed and left an invisible, dimmed window over the app being filled). The passphrase dialog is the entry view's `PassphraseDialog` (`SecureFlagPolicy.SecureOn`, passphrase only in `remember`, never saved state), with the old "clear cached password on screen off" box when the cache is enabled. Cancel finishes with `RESULT_CANCELED`, as before. A wrong passphrase still cancels the fill without retry (maintainer decision). Dialog state is two fields, `noKeys` and `passphraseFor` (the recipients being asked for, cleared before decrypting); no closure is kept. Decryption and dataset building are unchanged.
- **Passphrase cache only after the screen lock** (maintainer decision). The old activity cached the passphrase whenever the cache feature was on, so a passphrase typed after a cancelled, failed or unavailable screen lock prompt was cached too; the entry view caches only after a successful prompt. Autofill now follows the entry view: `cachesPassphrase(cacheEnabled, auth)` is true only for `BiometricResult.Success` (unit tested), and the "clear on screen off" preference is written only together with a cached passphrase.
- **Decryption start.** The flow starts on the first window focus, not in `onStart`. `onStart` ran it again on every return to the activity (screen off and on while the passphrase dialog was up showed a second dialog; returning from the key import ran it twice, once from `onStart` and once from the import callback). Waiting for focus is also what `LaunchActivity` needed on Android 14, whose SystemUI evicts a screen lock prompt shown before its activity is on top (commit `3f5dec64`): the passphrase cache's prompt was likely affected the same way and fell back to the passphrase dialog. After a key import the flow restarts on the next window focus (the import's result arrives while its activity is still on top, where a prompt would be evicted). Rotation, resizing and keyboard changes no longer recreate the activity (`configChanges`); any other recreation (theme, locale) finishes it with `RESULT_CANCELED`, and the user taps the field again (`ponytail:`).
- **No FLAG_SECURE on the picker and the warning**: neither shows a secret (entry names are shown by the list too, without it). The decryption window keeps it; the save activity keeps its own.
- **No new strings.** Every text already exists in `values/` and `values-it/`.
- **Firefox release save support is out of scope** (maintainer decision): a change to `autofill-parser`'s browser table, made separately on `master` later (see "Out of scope").

## Feature parity

### `AutofillFilterView`

| Behavior today | Decision |
|---|---|
| Floating window, 75 % by 90 % of the screen, tap outside closes, `RESULT_CANCELED` unless a fill succeeds | Port |
| Finishes without client state or form origin | Port |
| Header: app logo, "Select entry to fill into" and the origin (untrusted pretty identifier) in bold | Port |
| Search field prefilled with the trusted pretty identifier, clear button | Port (`OutlinedTextField`, clear `IconButton` with "Clear search") |
| RecyclerView rows: "path/**identifier**" (bold, underlined), account part as subtitle | Changed: identifier as the row name, "account · folders" as the subtitle (`EntryRow` in a `GroupedSurface`) |
| Empty result: "No results." | Port; nothing shown before the first result |
| Phishing-resistant search: websites only, on by default | Port (`PreferenceSwitch`) |
| "Match with X" on, "Clear existing matches" off | Port (`PreferenceSwitch`) |
| Tap: clear matches if asked, record the match if asked, start the decryption with the forwarded extras, return its result | Port; a double tap starts one decryption only |

### `AutofillPublisherChangedActivity`

| Behavior today | Decision |
|---|---|
| Floating window, tap outside closes | Port |
| Logo and app name, warning sign, header, app name in quotes, install time, bold footer | Changed: warning sign and "Possible phishing attempt" title instead of the logo; footer without bold |
| "Keep Autofill disabled" (outlined), finishes without a result | Port as the primary button |
| "Advanced information" reveals package and hash and "Re-enable Autofill" | Port (`rememberSaveable`) |
| "Re-enable Autofill": clears the app's matches, returns the prepared fill response | Port, unchanged |
| Package info unavailable: logged, finishes | Port |
| Touches through another app's overlay reach the buttons | New: dropped (`filterTouchesWhenObscured`) |

### `AutofillDecryptActivity`

| Behavior today | Decision |
|---|---|
| Transparent window, `FLAG_SECURE` | Port |
| Flow from `onStart`, again on every restart | Changed: once, on the first window focus |
| No keys: View dialog, Import opens the key import, dismissable into an invisible window | Port as a Compose dialog; dismissing finishes |
| Passphrase cache with screen lock prompt, cached passphrase, cache cleared after screen off | Port |
| Passphrase cached whenever the cache feature is on, also after a cancelled or unavailable prompt | Fix: cached only after a successful prompt, as in the entry view (`cachesPassphrase`) |
| Key import result resumes the flow at once (and `onStart` ran it again) | Changed: restarts once, on the next window focus |
| `PasswordDialog` (View, `FLAG_SECURE`, clear-cache box) | Changed: the entry view's Compose `PassphraseDialog` |
| Wrong passphrase or failure: `RESULT_CANCELED` | Port (maintainer decision) |
| Rotation recreated the activity, the restarted flow showed a second prompt | Changed: no recreation on rotation, resizing or keyboard changes; other recreations cancel the fill |

### `AutofillSaveActivity`

| Behavior today | Decision |
|---|---|
| Compose editor since step 4, `FLAG_SECURE`, match and generated fill | Unchanged |

### Deleted in this step

`ui/autofill/PasswordViewHolder.kt`, `ui/crypto/PasswordDialog.kt`, `SearchableRepositoryAdapter` and `PasswordItemDiffCallback` (in `util/viewmodel/SearchableRepositoryViewModel.kt`), `BasePGPActivity.requireKeysExist` with its result launcher, layouts `activity_oreo_autofill_filter.xml`, `oreo_autofill_filter_row.xml`, `activity_oreo_autofill_publisher_changed.xml`, `dialog_password_entry.xml`, drawables `password_row_background.xml` and `ic_keyboard_arrow_right_24dp.xml`, `@color/list_multiselect_background` (`values/` and `values-night/colors_material3.xml`, used only by that drawable), `res/values/dimens.xml` (its two margins were used only by those layouts), the `SelectableText` entries of the deleted layouts in `app/lint-baseline.xml`, and whatever lint then reports unused.

**Kept:** `oreo_autofill_dataset.xml` (the fill suggestions are `RemoteViews` drawn by the system, which cannot be Compose: it stays for good), `ic_person_black_24dp` and `ic_warning_red_24dp` (used by `AutofillViewUtils`), the `DialogLikeThemeM3` and `NoBackgroundThemeM3` window themes. **Kept for step 8:** the `fastscroll`, `recyclerview` and `recyclerview-selection` dependencies (unused after Task 2, removed with the others), `FragmentExtensions` (its `Fragment.finish` loses its last user with `PasswordDialog`), `BasePGPActivity` itself (only `AutofillDecryptActivity` extends it now; folding it in is cleanup), `SearchableRepositoryViewModel`'s RecyclerView-era navigation stack.

## Lessons from steps 1 to 6, designed in

- **Hosting.** Activities pass values and lambdas to stateless screens; no composable members on activities (content is inline in `setContent`). `modifier: Modifier = Modifier` is the first optional parameter of every public composable; collections passed to composables are `ImmutableList`.
- **Recreation.** Drafts and switches are `rememberSaveable`; nothing secret is. The decryption cannot resume a half-done flow after a recreation, so it cancels (`ponytail:`), and rotation no longer recreates it.
- **Android 14 screen lock prompt.** Prompt on window focus, never from `onCreate`, `onStart` or an activity result callback (commit `3f5dec64`).
- **Slack compose-lint and lint.** Lint runs with warnings as errors: a layout, drawable or dimension left without a user fails `UnusedResources`, so each task deletes what it orphans. No `kotlin.runCatching` in new code (the moved code keeps kotlin-result's `runCatching`, as before).
- **Accessibility.** Icon-only controls carry content descriptions (clear search, warning sign); the app logo in the picker keeps its "HardenedPass" description (it tells the user which app is asking); switch rows are `PreferenceSwitch` (`toggleable`, `Role.Switch`).
- **Security.** No secret in a Bundle, an extra written by new code, `rememberSaveable` or a log. The passphrase dialog sets `FLAG_SECURE` on its own window; the decryption window keeps it. The publisher warning's re-enable path stays behind an explicit second tap and ignores touches through overlays. The picker records a match only for the row the user tapped. A passphrase is cached only after a successful screen lock prompt.
- **Lint baseline.** `app/lint-baseline.xml` lists issues by file: a task that deletes a layout also deletes that layout's baseline entries.
- **Lint per task.** Each task's check includes `:app:lintDebug`; Task 5 runs the full CI set from AGENTS.md.
- **Emulator.** Every `adb` command targets `ANDROID_SERIAL=emulator-5554`; a physical tablet may be connected and is never touched. Autofill itself cannot run on the emulator (AGENTS.md): Task 6 launches the activities directly as root with test extras, and the real fill is the maintainer's tablet check.

**Security-relevant tasks** (code review after implementation, before the next task): Task 2 (picker: which entry is filled into which origin, match recording), Task 3 (publisher warning: must not be bypassable, tapjacking guard), Task 4 (decryption: passphrase handling and caching, `FLAG_SECURE`, fill result).

**Conventions for every task**

- New files get this header:
  ```kotlin
  /*
   * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
   * SPDX-License-Identifier: GPL-3.0-only
   */
  ```
  Modified upstream files keep their header.
- Commits are GPG-signed (default config, never disable). End each message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No em dashes in docs, comments or commit messages.
- Before each commit run `./gradlew spotlessApply`, then the task's checks.
- Work on the `compose` branch.

---

### Task 1: Picker rows (TDD)

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/autofill/AutofillModel.kt`
- Test: `app/src/test/java/app/passwordstore/ui/autofill/AutofillModelTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/app/passwordstore/ui/autofill/AutofillModelTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import app.passwordstore.util.settings.DirectoryStructure
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AutofillModelTest {

  private val root = File("/store")

  private fun rows(structure: DirectoryStructure, vararg paths: String) =
    autofillRows(paths.map { File(root, it) }, root, structure).map {
      Triple(it.path, it.label, it.subtitle)
    }

  @Test
  fun fileBasedNamesTheIdentifier() {
    assertEquals(
      listOf(
        Triple("/store/work/example.org/john@doe.org.gpg", "example.org", "john@doe.org · work"),
        Triple("/store/example.org/john.gpg", "example.org", "john"),
        Triple("/store/example.org.gpg", "example.org", null),
      ),
      rows(
        DirectoryStructure.FileBased,
        "work/example.org/john@doe.org.gpg",
        "example.org/john.gpg",
        "example.org.gpg",
      ),
    )
  }

  @Test
  fun directoryBasedFallsBackToTheAccountPart() {
    assertEquals(
      listOf(
        Triple(
          "/store/example.org/john@doe.org/password.gpg",
          "example.org",
          "john@doe.org/password",
        ),
        Triple("/store/Temporary PIN.gpg", "Temporary PIN", null),
      ),
      rows(
        DirectoryStructure.DirectoryBased,
        "example.org/john@doe.org/password.gpg",
        "Temporary PIN.gpg",
      ),
    )
  }

  @Test
  fun encryptedUsernameShowsTheFolders() {
    assertEquals(
      listOf(Triple("/store/work/example.org.gpg", "example.org", "work")),
      rows(DirectoryStructure.EncryptedUsername, "work/example.org.gpg"),
    )
  }

  @Test
  fun rowsKeepTheRankingOrder() {
    assertEquals(
      listOf("b.org", "a.org"),
      rows(DirectoryStructure.FileBased, "b.org.gpg", "a.org.gpg").map { it.second },
    )
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.autofill.AutofillModelTest`
Expected: compilation FAIL, `Unresolved reference 'autofillRows'`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/app/passwordstore/ui/autofill/AutofillModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import app.passwordstore.ui.list.ListRow
import app.passwordstore.util.settings.DirectoryStructure
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The autofill picker's rows for [files] (entries under [root], already ranked), in their order.
 * The name is the origin identifier per [structure], the part that must match the site or app, so
 * it stands out as the old picker's bold part did; the subtitle holds the account part and the
 * folders above the identifier. Without an identifier the account part is the name.
 */
fun autofillRows(
  files: List<File>,
  root: File,
  structure: DirectoryStructure,
): ImmutableList<ListRow> =
  files
    .map { file ->
      val relative = file.relativeTo(root)
      val identifier = structure.getIdentifierFor(relative)
      val account = structure.getAccountPartFor(relative)
      val subtitle =
        if (identifier == null) null
        else
          listOfNotNull(account, structure.getPathToIdentifierFor(relative))
            .joinToString(" · ")
            .ifEmpty { null }
      ListRow(file.absolutePath, identifier ?: account.orEmpty(), subtitle, 0)
    }
    .toImmutableList()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.autofill.AutofillModelTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Checks and commit**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
git add app/src/main/java/app/passwordstore/ui/autofill/AutofillModel.kt \
  app/src/test/java/app/passwordstore/ui/autofill/AutofillModelTest.kt
git commit -m "feat(app): add the autofill picker's row model

autofillRows turns the picker's search results into list rows: the
origin identifier, the part that must match the site or app, becomes
the row's name, and the account part and the folders above it the
subtitle, as the old picker's bold title and subtitle showed them.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

`autofillRows` has no caller yet; Kotlin does not warn about an unused public function, and lint does not either.

---

### Task 2: The picker in Compose (security-relevant)

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/autofill/AutofillFilterScreen.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/autofill/AutofillFilterView.kt` (whole file)
- Modify: `app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt`
- Delete: `app/src/main/java/app/passwordstore/ui/autofill/PasswordViewHolder.kt`
- Delete: `app/src/main/res/layout/activity_oreo_autofill_filter.xml`, `app/src/main/res/layout/oreo_autofill_filter_row.xml`
- Delete: `app/src/main/res/drawable/password_row_background.xml`, `app/src/main/res/drawable/ic_keyboard_arrow_right_24dp.xml`
- Modify: `app/src/main/res/values/colors_material3.xml`, `app/src/main/res/values-night/colors_material3.xml` (delete `list_multiselect_background`)
- Modify: `app/lint-baseline.xml` (delete the deleted layouts' entries)

- [ ] **Step 1: The screen**

Create `app/src/main/java/app/passwordstore/ui/autofill/AutofillFilterScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.EntryRow
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.list.ListRow
import app.passwordstore.ui.settings.PreferenceSwitch
import kotlinx.collections.immutable.ImmutableList

/**
 * The autofill picker: which site or app is being filled ([origin], as the user can check it), a
 * search over the store's entries, the matching [rows] (null while the first search runs), and the
 * switches for phishing-resistant search ([showStrict]: websites only), recording the pick as a
 * match ([matchLabel]) and clearing earlier matches. [onPick] takes the entry's absolute path.
 */
@Composable
fun AutofillFilterScreen(
  origin: String,
  query: String,
  rows: ImmutableList<ListRow>?,
  showStrict: Boolean,
  strict: Boolean,
  matchLabel: String,
  match: Boolean,
  clear: Boolean,
  onQueryChange: (String) -> Unit,
  onStrictChange: (Boolean) -> Unit,
  onMatchChange: (Boolean) -> Unit,
  onClearChange: (Boolean) -> Unit,
  onPick: (path: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val fillInto = stringResource(R.string.oreo_autofill_select_and_fill_into)
  Surface(modifier.fillMaxSize()) {
    Column(Modifier.padding(vertical = SpacingLarge)) {
      Image(
        painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier.size(60.dp).align(Alignment.CenterHorizontally),
      )
      Text(
        buildAnnotatedString {
          append(fillInto)
          append("\n")
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(origin) }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = SpacingLarge),
        textAlign = TextAlign.Center,
      )
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(SpacingLarge),
        label = { Text(stringResource(R.string.action_search)) },
        leadingIcon = {
          Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null)
        },
        trailingIcon =
          if (query.isNotEmpty()) {
            {
              IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, stringResource(R.string.list_clear_search))
              }
            }
          } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      )
      Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
          rows == null -> Unit
          rows.isEmpty() ->
            Text(
              stringResource(R.string.oreo_autofill_filter_no_results),
              modifier = Modifier.padding(horizontal = SpacingLarge),
              style = MaterialTheme.typography.titleMedium,
            )
          else ->
            // One lazy item per row: a cleared search lists the whole store. The surface wraps
            // a short list and is capped by the box for a long one.
            GroupedSurface(Modifier.fillMaxWidth()) {
              LazyColumn {
                items(rows, key = { it.path }) { row ->
                  EntryRow(name = row.label, subtitle = row.subtitle, onClick = { onPick(row.path) })
                }
              }
            }
        }
      }
      if (showStrict)
        PreferenceSwitch(
          stringResource(R.string.oreo_autofill_strict_domain_search),
          strict,
          onStrictChange,
        )
      PreferenceSwitch(matchLabel, match, onMatchChange)
      PreferenceSwitch(
        stringResource(R.string.oreo_autofill_matches_clear_existing),
        clear,
        onClearChange,
      )
    }
  }
}
```

- [ ] **Step 2: The activity**

Replace the whole of `app/src/main/java/app/passwordstore/ui/autofill/AutofillFilterView.kt` with (the companion object is unchanged):

```kotlin
/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.list.ListRow
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.viewmodel.FilterMode
import app.passwordstore.util.viewmodel.ListMode
import app.passwordstore.util.viewmodel.SearchMode
import app.passwordstore.util.viewmodel.SearchableRepositoryViewModel
import com.github.androidpasswordstore.autofillparser.FormOrigin
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import logcat.LogPriority.ERROR
import logcat.logcat

@AndroidEntryPoint
class AutofillFilterView : AppCompatActivity() {

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  companion object {

    private const val HEIGHT_PERCENTAGE = 0.9
    private const val WIDTH_PERCENTAGE = 0.75

    private const val EXTRA_FORM_ORIGIN_WEB =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_WEB"
    private const val EXTRA_FORM_ORIGIN_APP =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_APP"
    private var matchAndDecryptFileRequestCode = 1

    fun makeMatchAndDecryptFileIntentSender(
      context: Context,
      formOrigin: FormOrigin,
    ): IntentSender {
      val intent =
        Intent(context, AutofillFilterView::class.java).apply {
          when (formOrigin) {
            is FormOrigin.Web -> putExtra(EXTRA_FORM_ORIGIN_WEB, formOrigin.identifier)
            is FormOrigin.App -> putExtra(EXTRA_FORM_ORIGIN_APP, formOrigin.identifier)
          }
        }
      return PendingIntent.getActivity(
          context,
          matchAndDecryptFileRequestCode++,
          intent,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
          } else {
            PendingIntent.FLAG_CANCEL_CURRENT
          },
        )
        .intentSender
    }
  }

  private lateinit var formOrigin: FormOrigin

  private val model: SearchableRepositoryViewModel by viewModels()

  /** Set by the first tap on a row: a double tap must not start a second decryption. */
  private var picked = false

  private val decryptAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
        setResult(RESULT_OK, result.data)
      }
      finish()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setFinishOnTouchOutside(true)

    val params = window.attributes
    params.height = (HEIGHT_PERCENTAGE * resources.displayMetrics.heightPixels).toInt()
    params.width = (WIDTH_PERCENTAGE * resources.displayMetrics.widthPixels).toInt()
    window.attributes = params

    if (intent?.hasExtra(AutofillManager.EXTRA_CLIENT_STATE) != true) {
      logcat(ERROR) { "AutofillFilterActivity started without EXTRA_CLIENT_STATE" }
      finish()
      return
    }
    formOrigin =
      when {
        intent?.hasExtra(EXTRA_FORM_ORIGIN_WEB) == true -> {
          FormOrigin.Web(intent!!.getStringExtra(EXTRA_FORM_ORIGIN_WEB)!!)
        }
        intent?.hasExtra(EXTRA_FORM_ORIGIN_APP) == true -> {
          FormOrigin.App(intent!!.getStringExtra(EXTRA_FORM_ORIGIN_APP)!!)
        }
        else -> {
          logcat(ERROR) {
            "AutofillFilterActivity started without EXTRA_FORM_ORIGIN_WEB or EXTRA_FORM_ORIGIN_APP"
          }
          finish()
          return
        }
      }
    supportActionBar?.hide()
    setResult(RESULT_CANCELED)

    val isWeb = formOrigin is FormOrigin.Web
    val initialQuery = formOrigin.getPrettyIdentifier(applicationContext, untrusted = false)
    // Before anything is composed, so the ViewModel's default root listing (folders included) is
    // never shown. The LaunchedEffect below repeats it with equal arguments, which emits nothing.
    search(initialQuery, isWeb)
    val root = PasswordRepository.getRepositoryDirectory()
    val directoryStructure = AutofillPreferences.directoryStructure(this)
    // ponytail: cold flow, so each return to STARTED walks the store again; fine for a picker open
    // a few seconds. Share it with stateIn in the ViewModel if a large store makes it slow.
    val rows =
      model.searchResult
        .map { result ->
          autofillRows(result.passwordItems.map { it.file }, root, directoryStructure)
        }
        .flowOn(dispatcherProvider.io())
    val origin = formOrigin.getPrettyIdentifier(applicationContext, untrusted = true)
    val matchLabel =
      getString(R.string.oreo_autofill_match_with, formOrigin.getPrettyIdentifier(applicationContext))

    setContent {
      APSTheme {
        var query by rememberSaveable { mutableStateOf(initialQuery) }
        var strict by rememberSaveable { mutableStateOf(isWeb) }
        var match by rememberSaveable { mutableStateOf(true) }
        var clear by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(query, strict) { search(query, strict) }
        val shown by rows.collectAsStateWithLifecycle<ImmutableList<ListRow>?>(initialValue = null)
        AutofillFilterScreen(
          origin = origin,
          query = query,
          rows = shown,
          showStrict = isWeb,
          strict = strict,
          matchLabel = matchLabel,
          match = match,
          clear = clear,
          onQueryChange = { query = it },
          onStrictChange = { strict = it },
          onMatchChange = { match = it },
          onClearChange = { clear = it },
          onPick = { path -> decryptAndFill(File(path), match, clear) },
        )
      }
    }
  }

  /** The old picker's search: fuzzy or strict domain, recursive, entries only. */
  private fun search(query: String, strict: Boolean) {
    model.search(
      query.trim(),
      filterMode = if (strict) FilterMode.StrictDomain else FilterMode.Fuzzy,
      searchMode = SearchMode.RecursivelyInSubdirectories,
      listMode = ListMode.FilesOnly,
    )
  }

  private fun decryptAndFill(file: File, match: Boolean, clear: Boolean) {
    if (picked) return
    picked = true
    if (clear) AutofillMatcher.clearMatchesFor(applicationContext, formOrigin)
    if (match) AutofillMatcher.addMatchFor(applicationContext, formOrigin, file)
    // intent?.extras? is checked to be non-null in onCreate
    decryptAction.launch(
      AutofillDecryptActivity.makeDecryptFileIntent(file, intent!!.extras!!, this)
    )
  }
}
```

The order of the match calls, the forwarded extras and the result handling are the old ones. `path` comes only from rows built from the store walk, never from outside input.

- [ ] **Step 3: Delete the RecyclerView pieces**

```bash
git rm app/src/main/java/app/passwordstore/ui/autofill/PasswordViewHolder.kt \
  app/src/main/res/layout/activity_oreo_autofill_filter.xml \
  app/src/main/res/layout/oreo_autofill_filter_row.xml \
  app/src/main/res/drawable/password_row_background.xml \
  app/src/main/res/drawable/ic_keyboard_arrow_right_24dp.xml
sed -i '/name="list_multiselect_background"/d' \
  app/src/main/res/values/colors_material3.xml app/src/main/res/values-night/colors_material3.xml
python3 - <<'EOF'
p = "app/lint-baseline.xml"
gone = ("layout/activity_oreo_autofill_filter.xml", "layout/oreo_autofill_filter_row.xml")
blocks = open(p).read().split("\n\n")
open(p, "w").write("\n\n".join(b for b in blocks if not any(g + '"' in b for g in gone)))
EOF
grep -rn 'password_row_background\|ic_keyboard_arrow_right_24dp\|list_multiselect_background\|PasswordViewHolder\|SearchableRepositoryAdapter\|activity_oreo_autofill_filter\|oreo_autofill_filter_row' \
  app/src app/lint-baseline.xml --include=*.kt --include=*.xml
```
Expected from the grep: only the adapter class and its own references in `SearchableRepositoryViewModel.kt`. The baseline file keeps its `<issues>` wrapper and the blank lines between issues (the script splits on them); `git diff app/lint-baseline.xml` shows exactly three removed `<issue>` blocks.

In `app/src/main/java/app/passwordstore/util/viewmodel/SearchableRepositoryViewModel.kt`, delete everything from `private object PasswordItemDiffCallback` to the end of the file (the diff callback and the whole `SearchableRepositoryAdapter` class), so the file ends with the closing brace of `SearchableRepositoryViewModel`. Then delete the imports only they used:

```kotlin
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.Selection
import androidx.recyclerview.selection.SelectionPredicates
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.zhanghai.android.fastscroll.PopupTextProvider
```

(`android.os.Parcelable` stays: the navigation stack still uses it.) If the compiler reports one of these as still used, keep it; `spotlessApply` drops any import left unused.

- [ ] **Step 4: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. If lint reports a resource unused, it was used only by the deleted layouts: confirm with `grep -rn` and delete it (strings from every `values*/` file, an `ExtraTranslation` error otherwise). The two margins in `dimens.xml` are still used by the publisher layout and the password dialog until Tasks 3 and 4.

- [ ] **Step 5: Commit**

```bash
git add -A app/src app/lint-baseline.xml
git commit -m "feat(app): move the autofill picker to Compose

AutofillFilterView draws the picker with the shared theme: origin
header, search field, entry rows in a grouped surface and the three
switches, over the same SearchableRepositoryViewModel search. Rows
name the origin identifier and show the account and folders below it.
The first tap wins, so a double tap no longer starts two decryptions.
PasswordViewHolder, the RecyclerView adapter, the two layouts, their
drawables, the multiselect color and their lint baseline entries go.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

**Review after this task:** the match recording (only the tapped row, only with "Match with" on), the forwarded extras, the result path, the strict search default for websites, no secret in saved state.

---

### Task 3: The publisher warning in Compose (security-relevant)

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/autofill/PublisherChangedScreen.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/autofill/AutofillPublisherChangedActivity.kt` (whole file)
- Delete: `app/src/main/res/layout/activity_oreo_autofill_publisher_changed.xml`
- Modify: `app/lint-baseline.xml` (delete the layout's entries)

- [ ] **Step 1: The screen**

Create `app/src/main/java/app/passwordstore/ui/autofill/PublisherChangedScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingLargest

/**
 * Warns that the app being filled ([appName], installed [installed]) is signed by another publisher
 * than when an entry was first matched to it. [onKeepDisabled] fills nothing. [onReset] forgets the
 * old matches and fills; it is offered only after "Advanced information", which shows [details]
 * (package and certificate hash), so re-enabling always takes two deliberate taps.
 */
@Composable
fun PublisherChangedScreen(
  appName: String,
  installed: String,
  details: String,
  onKeepDisabled: () -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var advanced by rememberSaveable { mutableStateOf(false) }
  val center = Modifier.fillMaxWidth()
  Surface(modifier) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(SpacingLargest),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        painterResource(R.drawable.ic_warning_red_24dp),
        contentDescription =
          stringResource(R.string.oreo_autofill_warning_publisher_warning_sign_description),
        modifier = Modifier.size(40.dp),
        tint = MaterialTheme.colorScheme.error,
      )
      // ponytail: stringResource drops the footer's <b> in every locale but Italian (whose footer
      // has none); the red title carries the emphasis. Keep spans with getText if it is missed.
      Text(
        stringResource(R.string.oreo_autofill_warning_publisher_dataset_title),
        modifier = center,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        stringResource(R.string.oreo_autofill_warning_publisher_header),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        appName,
        modifier = center,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        installed,
        modifier = center,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        stringResource(R.string.oreo_autofill_warning_publisher_footer),
        style = MaterialTheme.typography.bodyMedium,
      )
      Button(onClick = onKeepDisabled, modifier = center) {
        Text(stringResource(R.string.oreo_autofill_warning_publisher_changed_disable_autofill_button))
      }
      if (!advanced) {
        OutlinedButton(onClick = { advanced = true }, modifier = center) {
          Text(stringResource(R.string.oreo_autofill_warning_publisher_advanced_info_button))
        }
      } else {
        OutlinedButton(onClick = onReset, modifier = center) {
          Text(stringResource(R.string.oreo_autofill_warning_publisher_reenable_button))
        }
        SelectionContainer {
          Text(
            details,
            modifier = center,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          )
        }
      }
    }
  }
}
```

- [ ] **Step 2: The activity**

Replace the whole of `app/src/main/java/app/passwordstore/ui/autofill/AutofillPublisherChangedActivity.kt` with (the companion object is unchanged):

```kotlin
/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.autofill.FillResponse
import android.text.format.DateUtils
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPublisherChangedException
import app.passwordstore.util.extensions.asLog
import app.passwordstore.util.extensions.getApplicationInfoCompat
import app.passwordstore.util.extensions.getPackageInfoCompat
import com.github.androidpasswordstore.autofillparser.FormOrigin
import com.github.androidpasswordstore.autofillparser.computeCertificatesHash
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.runCatching
import logcat.LogPriority.ERROR
import logcat.logcat

class AutofillPublisherChangedActivity : AppCompatActivity() {

  companion object {

    private const val EXTRA_APP_PACKAGE = "app.passwordstore.autofill.oreo.ui.EXTRA_APP_PACKAGE"
    private const val EXTRA_FILL_RESPONSE_AFTER_RESET =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FILL_RESPONSE_AFTER_RESET"
    private var publisherChangedRequestCode = 1

    fun makePublisherChangedIntentSender(
      context: Context,
      publisherChangedException: AutofillPublisherChangedException,
      fillResponseAfterReset: FillResponse?,
    ): IntentSender {
      val intent =
        Intent(context, AutofillPublisherChangedActivity::class.java).apply {
          putExtra(EXTRA_APP_PACKAGE, publisherChangedException.formOrigin.identifier)
          putExtra(EXTRA_FILL_RESPONSE_AFTER_RESET, fillResponseAfterReset)
        }
      return PendingIntent.getActivity(
          context,
          publisherChangedRequestCode++,
          intent,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
          } else {
            PendingIntent.FLAG_CANCEL_CURRENT
          },
        )
        .intentSender
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setFinishOnTouchOutside(true)
    // Tapjacking guard: a touch that reaches this window through another app's window (an overlay
    // drawn to trick the user into re-enabling) is dropped. ViewGroup.dispatchTouchEvent checks the
    // decor view's own flag before dispatching to any child, so the ComposeView is covered.
    window.decorView.filterTouchesWhenObscured = true

    val appPackage =
      intent.getStringExtra(EXTRA_APP_PACKAGE)
        ?: run {
          logcat(ERROR) { "AutofillPublisherChangedActivity started without EXTRA_PACKAGE_NAME" }
          finish()
          return
        }
    supportActionBar?.hide()
    val (packageInfo, appInfo, hash) =
      runCatching {
          Triple(
            packageManager.getPackageInfoCompat(appPackage, PackageManager.GET_META_DATA),
            packageManager.getApplicationInfoCompat(appPackage, PackageManager.GET_META_DATA),
            computeCertificatesHash(this, appPackage),
          )
        }
        .getOrElse { e ->
          logcat(ERROR) { e.asLog("Failed to retrieve package info for $appPackage") }
          finish()
          return
        }
    val appName =
      getString(
        R.string.oreo_autofill_warning_publisher_app_name,
        packageManager.getApplicationLabel(appInfo),
      )
    val installed =
      getString(
        R.string.oreo_autofill_warning_publisher_install_time,
        DateUtils.getRelativeTimeSpanString(packageInfo.firstInstallTime),
      )
    val details =
      getString(R.string.oreo_autofill_warning_publisher_advanced_info_template, appPackage, hash)
    setContent {
      APSTheme {
        PublisherChangedScreen(
          appName = appName,
          installed = installed,
          details = details,
          onKeepDisabled = { finish() },
          onReset = { reset(appPackage) },
        )
      }
    }
  }

  /** Forgets the app's matches and fills with the response the service prepared for this case. */
  private fun reset(appPackage: String) {
    AutofillMatcher.clearMatchesFor(this, FormOrigin.App(appPackage))
    val fillResponse =
      IntentCompat.getParcelableExtra(
        intent,
        EXTRA_FILL_RESPONSE_AFTER_RESET,
        FillResponse::class.java,
      )
    setResult(
      RESULT_OK,
      Intent().apply { putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillResponse) },
    )
    finish()
  }
}
```

`setResult` is called only in `reset`; every other way out (button, back, tap outside, missing package) returns `RESULT_CANCELED`, so the system fills nothing, as before. kotlin-result's `getOrElse` is inline, so the `return` inside it leaves `onCreate` (the old `decryptCredential` relies on the same).

- [ ] **Step 3: Delete the layout**

```bash
git rm app/src/main/res/layout/activity_oreo_autofill_publisher_changed.xml
python3 - <<'EOF'
p = "app/lint-baseline.xml"
blocks = open(p).read().split("\n\n")
gone = 'layout/activity_oreo_autofill_publisher_changed.xml"'
open(p, "w").write("\n\n".join(b for b in blocks if gone not in b))
EOF
grep -rn 'activity_oreo_autofill_publisher_changed\|ActivityOreoAutofillPublisherChangedBinding' app/src app/lint-baseline.xml
```
Expected: no output; `git diff app/lint-baseline.xml` shows two removed `<issue>` blocks.

- [ ] **Step 4: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. The margins in `dimens.xml` are still used by `dialog_password_entry.xml` (Task 4). If lint reports another resource only the deleted layout used, confirm with `grep -rn` and delete it.

- [ ] **Step 5: Commit**

```bash
git add -A app/src app/lint-baseline.xml
git commit -m "feat(app): move the publisher warning to Compose

AutofillPublisherChangedActivity draws the warning with the shared
theme. Keep Autofill disabled is now the primary button, and the
Possible phishing attempt title replaces the logo. Re-enabling still
takes two taps (Advanced information first) and still clears the
app's matches before returning the prepared fill response; every other
way out fills nothing. Touches that reach the warning through another
app's window are now dropped (filterTouchesWhenObscured on the decor
view), so an overlay cannot trick the user into re-enabling.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

**Review after this task:** no path to `RESULT_OK` without "Advanced information" then "Re-enable Autofill"; the reset clears matches before the result; the touch filter is set before any content; untrusted text (the app label) is only displayed.

---

### Task 4: The decryption's dialogs in Compose, passphrase cache only after the screen lock (security-relevant)

**Files:**
- Modify: `app/src/main/java/app/passwordstore/ui/autofill/AutofillModel.kt` (add `cachesPassphrase`)
- Test: `app/src/test/java/app/passwordstore/ui/autofill/AutofillModelTest.kt` (add a test)
- Modify: `app/src/main/java/app/passwordstore/ui/autofill/AutofillDecryptActivity.kt` (whole file)
- Modify: `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt` (`PassphraseDialog` visibility)
- Modify: `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt` (remove `requireKeysExist`)
- Modify: `app/src/main/AndroidManifest.xml` (decrypt activity `configChanges`)
- Delete: `app/src/main/java/app/passwordstore/ui/crypto/PasswordDialog.kt`, `app/src/main/res/layout/dialog_password_entry.xml`, `app/src/main/res/values/dimens.xml`

- [ ] **Step 1: Failing test for the cache rule**

In `app/src/test/java/app/passwordstore/ui/autofill/AutofillModelTest.kt` add these imports:

```kotlin
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import kotlin.test.assertFalse
import kotlin.test.assertTrue
```

and append inside the class, after the last test:

```kotlin
  @Test
  fun passphraseIsCachedOnlyAfterTheScreenLock() {
    assertTrue(cachesPassphrase(true, BiometricResult.Success(null)))
    val notConfirmed =
      listOf(
        BiometricResult.CanceledByUser,
        BiometricResult.CanceledBySystem,
        BiometricResult.HardwareUnavailableOrDisabled,
        BiometricResult.Failure(null, "failed"),
        BiometricResult.Retry,
      )
    for (auth in notConfirmed) assertFalse(cachesPassphrase(true, auth), auth.toString())
    assertFalse(cachesPassphrase(false, BiometricResult.Success(null)))
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.autofill.AutofillModelTest`
Expected: compilation FAIL, `Unresolved reference 'cachesPassphrase'`.

- [ ] **Step 3: The rule**

Append to `app/src/main/java/app/passwordstore/ui/autofill/AutofillModel.kt`, and add the import `app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult`:

```kotlin
/**
 * Whether autofill may cache the passphrase it just decrypted with: only with the cache feature on
 * ([cacheEnabled]) and after a successful screen lock prompt ([auth]), as the entry view does. A
 * passphrase typed after a cancelled, failed or unavailable prompt is never kept.
 */
fun cachesPassphrase(cacheEnabled: Boolean, auth: BiometricResult): Boolean =
  cacheEnabled && auth is BiometricResult.Success
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.autofill.AutofillModelTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Share the passphrase dialog**

In `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt` change

```kotlin
/** Asks for the PGP passphrase, as the old `PasswordDialog` did. */
@Composable
private fun PassphraseDialog(
```

to

```kotlin
/**
 * Asks for the PGP passphrase (entry view and autofill). The passphrase lives only in `remember`
 * and the dialog's window is FLAG_SECURE.
 */
@Composable
internal fun PassphraseDialog(
```

Nothing else in the file changes.

- [ ] **Step 6: The activity**

Replace the whole of `app/src/main/java/app/passwordstore/ui/autofill/AutofillDecryptActivity.kt` with (the companion object, `decryptWithPassphrase`'s body and `decryptCredential` are the old code, with the file, client state and action read from fields instead of parameters, and the cache condition replaced by `cachesPassphrase`):

```kotlin
/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import app.passwordstore.Application.Companion.screenWasOff
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.PGPPassphraseCache
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.getPGPIdentifiers
import app.passwordstore.ui.entry.PassphraseDialog
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupStart
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.autofill.AutofillResponseBuilder
import app.passwordstore.util.extensions.asLog
import app.passwordstore.util.features.Feature.EnablePGPPassphraseCache
import app.passwordstore.util.features.Features
import app.passwordstore.util.settings.DirectoryStructure
import app.passwordstore.util.settings.PreferenceKeys
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.Credentials
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/**
 * Decrypts one entry for the autofill service and returns the dataset to fill. Its transparent,
 * FLAG_SECURE window shows only the "no keys" and passphrase dialogs.
 */
@AndroidEntryPoint
class AutofillDecryptActivity : BasePGPActivity() {

  @Inject lateinit var passwordEntryFactory: PasswordEntry.Factory
  @Inject lateinit var features: Features
  @Inject lateinit var passphraseCache: PGPPassphraseCache

  private lateinit var directoryStructure: DirectoryStructure
  private lateinit var filePath: String
  private lateinit var clientState: Bundle
  private lateinit var action: AutofillAction
  private var clearCache = true

  /** The screen lock prompt's outcome: the passphrase is cached only after a success. */
  private var auth: BiometricResult = BiometricResult.CanceledByUser

  /** Set once the flow started; reset after a key import, so the next window focus restarts it. */
  private var started = false

  /** Shows the "No keys imported" dialog. */
  private var noKeys by mutableStateOf(false)

  /** The recipients the passphrase dialog asks for; null while it is not shown. */
  private var passphraseFor by mutableStateOf<List<PGPIdentifier>?>(null)

  // The import's activity is still on top when its result arrives: the flow restarts on the next
  // window focus, so the screen lock prompt is not shown (and evicted) behind it.
  private val keyImport =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) started = false else finish()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ponytail: a recreation (theme or locale change; rotation and resizing do not recreate, see
    // the manifest) cancels the fill instead of resuming a half-done flow; the user taps the field
    // again. Keep the flow's state in a ViewModel if this ever matters.
    if (savedInstanceState != null) {
      finish()
      return
    }
    filePath =
      intent?.getStringExtra(EXTRA_FILE_PATH)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_FILE_PATH" }
          finish()
          return
        }
    clientState =
      intent?.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
        ?: run {
          logcat(ERROR) { "AutofillDecryptActivity started without EXTRA_CLIENT_STATE" }
          finish()
          return
        }
    val isSearchAction = intent.getBooleanExtra(EXTRA_SEARCH_ACTION, true)
    action = if (isSearchAction) AutofillAction.Search else AutofillAction.Match
    directoryStructure = AutofillPreferences.directoryStructure(this)
    logcat { action.toString() }
    setContent {
      APSTheme {
        if (noKeys)
          AlertDialog(
            onDismissRequest = { finish() },
            title = { Text(stringResource(R.string.no_keys_imported_dialog_title)) },
            text = { Text(stringResource(R.string.no_keys_imported_dialog_message)) },
            confirmButton = {
              TextButton(
                onClick = {
                  noKeys = false
                  keyImport.launch(
                    MainActivity.setupIntent(this@AutofillDecryptActivity, SetupStart.PgpImport)
                  )
                }
              ) {
                Text(stringResource(R.string.button_label_import))
              }
            },
          )
        val identifiers = passphraseFor
        if (identifiers != null)
          PassphraseDialog(
            wrong = false,
            cacheEnabled = features.isEnabled(EnablePGPPassphraseCache),
            clearCache = clearCache,
            onSubmit = onSubmit@{ passphrase, clear ->
              // Cleared before decrypting: a second submit in the same frame does nothing.
              if (passphraseFor == null) return@onSubmit
              passphraseFor = null
              clearCache = clear
              lifecycleScope.launch(dispatcherProvider.main()) {
                decryptWithPassphrase(identifiers, passphrase)
              }
            },
            onCancel = { finish() },
          )
      }
    }
  }

  // The flow waits for window focus, as LaunchActivity's prompt does: Android 14's SystemUI
  // dismisses a screen lock prompt shown before its activity is on top. It runs once (again only
  // after a key import); the old onStart start ran it on every return to this activity.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus || started || isFinishing) return
    started = true
    start()
  }

  /** Asks for a key import first while no PGP key is imported. */
  private fun start() {
    if (isFinishing) return
    lifecycleScope.launch { if (repository.hasKeys()) unlock() else noKeys = true }
  }

  private fun unlock() {
    if (
      features.isEnabled(EnablePGPPassphraseCache) && BiometricAuthenticator.canAuthenticate(this)
    ) {
      BiometricAuthenticator.authenticate(
        this,
        R.string.biometric_prompt_title_gpg_passphrase_cache,
      ) { authResult ->
        decrypt(authResult)
      }
    } else {
      decrypt(BiometricResult.CanceledByUser)
    }
  }

  private fun decrypt(authResult: BiometricResult) {
    auth = authResult
    val gpgIdentifiers =
      getPGPIdentifiers(
        getParentPath(filePath, PasswordRepository.getRepositoryDirectory().toString())
      ) ?: return
    lifecycleScope.launch(dispatcherProvider.main()) {
      when (authResult) {
        // Internally handled by the prompt dialog
        is BiometricResult.Retry -> {}
        // If the dialog is dismissed for any reason, prompt for passphrase
        is BiometricResult.CanceledBySystem,
        is BiometricResult.CanceledByUser,
        is BiometricResult.Failure,
        is BiometricResult.HardwareUnavailableOrDisabled -> askPassphrase(gpgIdentifiers)
        is BiometricResult.Success -> {
          /* clear passphrase cache on first use after application startup or if screen was off;
          also make sure to purge a stale cache after caching has been disabled via PGP settings */
          clearCache = settings.getBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, true)
          if (screenWasOff && clearCache) {
            passphraseCache.clearAllCachedPassphrases(this@AutofillDecryptActivity)
            screenWasOff = false
          }
          val cachedPassphrase =
            passphraseCache.retrieveCachedPassphrase(
              this@AutofillDecryptActivity,
              gpgIdentifiers.first(),
            )
          if (cachedPassphrase != null) {
            decryptWithPassphrase(gpgIdentifiers, cachedPassphrase)
          } else {
            askPassphrase(gpgIdentifiers)
          }
        }
      }
    }
  }

  private suspend fun askPassphrase(identifiers: List<PGPIdentifier>) {
    if (!repository.isPasswordProtected(identifiers)) {
      decryptWithPassphrase(identifiers, password = "")
      return
    }
    passphraseFor = identifiers
  }

  private suspend fun decryptWithPassphrase(identifiers: List<PGPIdentifier>, password: String) {
    val credentials = decryptCredential(File(filePath), password, identifiers)
    if (credentials == null) {
      setResult(RESULT_CANCELED)
    } else {
      val fillInDataset =
        AutofillResponseBuilder.makeFillInDataset(
          this@AutofillDecryptActivity,
          credentials,
          clientState,
          action,
        )
      withContext(dispatcherProvider.main()) {
        setResult(
          RESULT_OK,
          Intent().apply { putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillInDataset) },
        )
      }
    }
    withContext(dispatcherProvider.main()) { finish() }
  }

  private suspend fun decryptCredential(
    file: File,
    password: String,
    identifiers: List<PGPIdentifier>,
  ): Credentials? {
    runCatching { file.readBytes().inputStream() }
      .onFailure { e ->
        logcat(ERROR) { e.asLog("File to decrypt not found") }
        return null
      }
      .onSuccess { encryptedInput ->
        val outputStream = ByteArrayOutputStream()
        repository
          .decrypt(password, identifiers, encryptedInput, outputStream)
          .onFailure { e ->
            logcat(ERROR) { e.asLog("Decryption failed") }
            return null
          }
          .onSuccess { result ->
            return runCatching {
                runCatching {
                    if (cachesPassphrase(features.isEnabled(EnablePGPPassphraseCache), auth)) {
                      passphraseCache.cachePassphrase(this, identifiers.first(), password)
                      settings.edit {
                        putBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, clearCache)
                      }
                    }
                  }
                  .onFailure { e -> logcat { e.asLog() } }
                val entry = passwordEntryFactory.create(result.toByteArray())
                AutofillPreferences.credentialsFromStoreEntry(this, file, entry, directoryStructure)
              }
              .getOrElse { e ->
                logcat(ERROR) { e.asLog("Failed to parse password entry") }
                return null
              }
          }
      }
    return null
  }

  companion object {

    private const val EXTRA_FILE_PATH = "app.passwordstore.autofill.oreo.EXTRA_FILE_PATH"
    private const val EXTRA_SEARCH_ACTION = "app.passwordstore.autofill.oreo.EXTRA_SEARCH_ACTION"

    private var decryptFileRequestCode = 1

    fun makeDecryptFileIntent(file: File, forwardedExtras: Bundle, context: Context): Intent {
      return Intent(context, AutofillDecryptActivity::class.java).apply {
        putExtras(forwardedExtras)
        putExtra(EXTRA_SEARCH_ACTION, true)
        putExtra(EXTRA_FILE_PATH, file.absolutePath)
      }
    }

    fun makeDecryptFileIntentSender(file: File, context: Context): IntentSender {
      val intent =
        Intent(context, AutofillDecryptActivity::class.java).apply {
          putExtra(EXTRA_SEARCH_ACTION, false)
          putExtra(EXTRA_FILE_PATH, file.absolutePath)
        }
      return PendingIntent.getActivity(
          context,
          decryptFileRequestCode++,
          intent,
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
          } else {
            PendingIntent.FLAG_CANCEL_CURRENT
          },
        )
        .intentSender
    }
  }
}
```

Notes for the implementer:
- The passphrase goes from the dialog's `remember` straight into `decryptWithPassphrase`; `passphraseFor` holds the recipients, never the passphrase. No new log line mentions it.
- `auth` is set at the start of every `decrypt`, so a cancelled prompt (`CanceledByUser`), an unavailable one (`HardwareUnavailableOrDisabled`, or no prompt at all because the device cannot authenticate) and a failed one never lead to caching. With a successful prompt, a passphrase typed because nothing was cached yet is cached, as in the entry view; a cached passphrase that decrypts is written back unchanged, as before.
- The "clear cached password on screen off" box still shows whenever the cache feature is on, as in the entry view; its value is stored only together with a cached passphrase.
- The old `intent?.getBooleanExtra(...)!!` becomes `intent.getBooleanExtra(...)` (an activity's `intent` is never null after the two checks above); if the compiler disagrees, keep the old form.

- [ ] **Step 7: No recreation on rotation or resizing**

In `app/src/main/AndroidManifest.xml` replace

```xml
    <activity
      android:name=".ui.autofill.AutofillDecryptActivity"
      android:exported="false"
      android:theme="@style/NoBackgroundThemeM3" />
```

with

```xml
    <!-- ponytail: rotation, resizing and keyboard changes must not cancel a fill in progress;
         other recreations (theme, locale) cancel it. -->
    <activity
      android:name=".ui.autofill.AutofillDecryptActivity"
      android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden"
      android:exported="false"
      android:theme="@style/NoBackgroundThemeM3" />
```

- [ ] **Step 8: Remove `requireKeysExist` and `PasswordDialog`**

In `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt` delete the `onKeyImport` field with its KDoc, the `keyImportAction` launcher, and the whole `requireKeysExist` function with its KDoc. Then delete the imports only they used:

```kotlin
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.lifecycle.lifecycleScope
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupStart
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

The class keeps its injected `settings`, `repository` and `dispatcherProvider`, its `onCreate` (FLAG_SECURE) and its companion.

```bash
git rm app/src/main/java/app/passwordstore/ui/crypto/PasswordDialog.kt \
  app/src/main/res/layout/dialog_password_entry.xml \
  app/src/main/res/values/dimens.xml
grep -rn 'PasswordDialog\b\|DialogPasswordEntryBinding\|requireKeysExist\|activity_horizontal_margin\|activity_vertical_margin\|dialog_password_entry' \
  app/src app/lint-baseline.xml --include=*.kt --include=*.xml
```
Expected: no output (`dimens.xml` held only the two margins, whose last users were the three deleted layouts; `PassphraseDialog` does not match `PasswordDialog\b`; the lint baseline has no entry for `dialog_password_entry.xml`).

- [ ] **Step 9: Checks**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. If lint reports a resource only the deleted dialog layout used, confirm with `grep -rn` and delete it.

- [ ] **Step 10: Commit**

```bash
git add -A app/src app/lint-baseline.xml
git commit -m "feat(app): move the autofill decryption prompts to Compose

AutofillDecryptActivity shows the no keys dialog and the entry view's
passphrase dialog in Compose over its transparent FLAG_SECURE window.
The flow starts once, on the first window focus, instead of on every
onStart: Android 14 evicts a screen lock prompt shown before the
activity is on top, and a restart showed a second passphrase dialog.
After a key import it restarts on the next focus. Dismissing the no
keys dialog now finishes instead of leaving an invisible window.
Rotation and resizing no longer recreate the activity; other
recreations cancel the fill.

The passphrase is now cached only after a successful screen lock
prompt, as the entry view does: a passphrase typed after a cancelled
or unavailable prompt was cached whenever the cache feature was on.

PasswordDialog, its layout, dimens.xml and requireKeysExist lose their
last user and go.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

**Review after this task:** the passphrase never reaches saved state, an extra or a log; `FLAG_SECURE` on the window and the dialog; caching only after `BiometricResult.Success` (`auth` set on every path into `decrypt`); every exit without a dataset returns `RESULT_CANCELED`; the key import result path (restart on focus, never a second concurrent flow); the one-time start.

---

### Task 5: Setup intent wording, spec, full check

**Files:**
- Modify: `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt` (`setupIntent` comment)
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`

- [ ] **Step 1: `MainActivity.setupIntent`**

Replace

```kotlin
    // ponytail: a second instance instead of in-graph navigation, until step 7 moves autofill
    // into the graph and the git operations' dialogs become Compose dialogs.
```

with

```kotlin
    // ponytail: a second instance instead of in-graph navigation. Autofill's activities stay
    // outside the graph for good (the autofill service starts them); the git operations' "No SSH
    // key" dialog could navigate in-graph once it becomes a Compose dialog.
```

- [ ] **Step 2: The spec**

In `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md` replace

```
- Settings opens these within the graph. Autofill, the git operations' "No SSH key" dialog and
  the entry view's key import open them through a `MainActivity` setup intent until step 7.
```

with

```
- Settings opens these within the graph. Autofill, the git operations' "No SSH key" dialog and
  the entry view's key import open them through a `MainActivity` setup intent: the autofill
  activities live outside the graph, and the other two wait for a result.
```

and insert after the Settings section (before `## Architecture`):

```
### Autofill

- The autofill service's activities keep their windows (floating picker and warning, transparent
  decryption) and draw them with the shared theme. The picker shows the site or app being filled,
  a search prefilled with it, entry rows named by their origin identifier, and the
  phishing-resistant search, match and clear-matches switches.
- The publisher warning offers "Keep Autofill disabled" first; re-enabling stays behind "Advanced
  information", and touches through another app's window are ignored. The decryption shows only
  the "no keys" and passphrase dialogs, starts once the window has focus, and caches the
  passphrase only after a successful screen lock prompt, as the entry view does. Saving and
  generating host the editor.
- Intents, extras, results and matching are unchanged.
```

- [ ] **Step 3: Full local check**

```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`. If lint reports an issue in a file from Tasks 1 to 4, fix it there and include it in this commit.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/main/MainActivity.kt \
  docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "docs: record the autofill activities' Compose UI in the spec

The autofill activities cannot join MainActivity's graph, so they keep
opening the PGP key import through the setup intent; the spec and the
ponytail note no longer promise otherwise. The spec gains an Autofill
section.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Emulator check, tablet checklist, push

No real store or keys: the e2e environment from AGENTS.md only. A physical tablet may be connected over USB: every `adb` and Gradle install command in this task targets the emulator through `ANDROID_SERIAL`; never touch the tablet. The PIN `1234` below is the emulator's own test PIN, never the owner's.

Autofill itself cannot run on the emulator (AGENTS.md: Settings is denylisted, WebView Shell is not a trusted browser). The activities are not exported, so they are launched directly from a root shell (`adb root`, available on the AOSP image without Google services) with test extras: the picker and the warning take only strings, so they can be driven fully. The decryption needs a `Bundle` client state, which `am start` cannot pass: here it is only checked to finish cleanly when started without one; its dialogs are covered by the entry view's shared `PassphraseDialog` (Step 3) and by the maintainer's tablet check.

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

(`ANDROID_HOME` is the `sdk.dir` from `local.properties`. `set-pin` fails harmlessly when the AVD already has the PIN.)

Helpers, the step 6 set plus `checked`, `logs` and the component names:

```bash
APP=xyz.danix.hardenedpass.debug
X=app.passwordstore.autofill.oreo.ui
CS=android.view.autofill.extra.CLIENT_STATE
FILTER=$APP/app.passwordstore.ui.autofill.AutofillFilterView
PUB=$APP/app.passwordstore.ui.autofill.AutofillPublisherChangedActivity
DEC=$APP/app.passwordstore.ui.autofill.AutofillDecryptActivity
SAVE=$APP/app.passwordstore.ui.autofill.AutofillSaveActivity
ui() { adb exec-out uiautomator dump /dev/tty; }
center() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | sed -n "${2:-1}p" | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }; set -- $b; echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; }
tap() { local c; c=$(center "$1" "$2") && adb shell input tap $c; sleep 1; }
field() { tap 'class="android.widget.EditText"' "$1"; adb shell input keycombination 113 29; adb shell input keyevent 67; adb shell input text "$2"; }
value() { ui | grep -o '<node [^>]*class="android.widget.EditText"[^>]*>' | sed -n "${1}p" | grep -o ' text="[^"]*"' | cut -d'"' -f2; }
seen() { ui | grep -q "$1" && echo "ok: $1" || echo "MISSING: $1"; }
checked() { ui | grep -o "<node [^>]*$1[^>]*>" | head -1 | grep -o 'checked="[a-z]*"'; }
top() { adb shell dumpsys activity activities | grep topResumedActivity; }
open() { adb shell am start -n $APP/app.passwordstore.ui.main.LaunchActivity; sleep 3; }
pick() { sleep 2; ui | grep -q "text=\"$1\"" || { tap 'content-desc="Show roots"'; tap 'text="Downloads"'; sleep 1; }; tap "text=\"$1\""; sleep 2; }
secure() { adb shell dumpsys window | grep -c 'fl=.*SECURE'; }
logs() { adb logcat -d | grep -c "$1"; }
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
Expected: the step 5 flow ends on the cloned store's list with `example.org`. (If the FAB is the first "Import PGP key" match on this image, use index 1, as in step 5.)

- [ ] **Step 3: The shared passphrase dialog still works in the entry view**

```bash
tap 'text="example.org"'; sleep 2
seen 'text="Password"'; secure
adb shell input text test; tap 'text="OK"'; sleep 3
seen 'text="alice"'
adb shell input keyevent 4; sleep 1
```
Expected: the passphrase dialog (title "Password") with `secure` at `1` or more; after the e2e passphrase the entry shows the username `alice`; back returns to the list. (The entry screen is FLAG_SECURE: read it from the dump, never a screenshot.)

- [ ] **Step 4: Root shell**

```bash
adb root; adb wait-for-device; sleep 2
adb shell whoami
```
Expected: `root`. If `adb root` is refused on this image, skip Steps 5 to 8 (they need it) and record that the picker and the warning were only built and linted; the tablet checklist then covers them.

- [ ] **Step 5: The picker (website)**

```bash
adb logcat -c
adb shell am start -n $FILTER --es $CS x --es $X.EXTRA_FORM_ORIGIN_WEB example.org; sleep 3
top
seen 'Select entry to fill into'; ui | grep -o 'text="example.org"' | wc -l
value 1
seen 'text="work"'; seen 'text="mail.example.org"'; seen 'text="otp"'
seen 'text="Phishing-resistant search"'; checked 'text="Phishing-resistant search"'
checked 'text="Match with example.org"'; checked 'text="Clear existing matches"'
adb exec-out screencap -p > "$OUT/picker.png"
tap 'text="Phishing-resistant search"'; field 1 example; sleep 2
seen 'text="otp"'; seen 'text="example.com"'
field 1 nothing.invalid; sleep 2; seen 'text="No results."'
adb shell cmd uimode night yes; sleep 3
value 1; checked 'text="Phishing-resistant search"'; seen 'text="No results."'
adb exec-out screencap -p > "$OUT/picker-dark.png"
adb shell cmd uimode night no; sleep 3
tap 'content-desc="Clear search"'; sleep 2; seen 'text="otp"'
field 1 example.org; tap 'text="Phishing-resistant search"'; sleep 2
tap 'text="example.org"' 2; sleep 3
top
adb shell run-as $APP cat shared_prefs/oreo_autofill_web_matches.xml
logs 'AutofillDecryptActivity started without EXTRA_CLIENT_STATE'
```
Expected: the picker is on top (`AutofillFilterView`) in a window narrower and shorter than the screen; the header names `example.org`; `text="example.org"` counts `2` (the field and the row; the header is one longer text, so the row is the second match, which the final tap uses); the field holds `example.org`; with strict search (`checked="true"`) the rows are `example.org` and `work` (subtitle `mail.example.org`) and `otp` is `MISSING`; match is `checked="true"`, clear `checked="false"`. With strict search off and `example`, `otp` (subtitle `example.com`) appears; `nothing.invalid` shows "No results."; the night mode recreation keeps `nothing.invalid`, strict off and the empty state; clearing the search lists every entry. Tapping `example.org` records the match (`matches;example.org` holding the path of `example.org.gpg`), starts the decryption, which finishes at once (count `1`: the string extra is not a `Bundle`), and the picker finishes with it: `MainActivity` is on top again.

- [ ] **Step 6: The picker (app) and tap outside**

```bash
adb shell am start -n $FILTER --es $CS x --es $X.EXTRA_FORM_ORIGIN_APP com.android.settings; sleep 3
seen 'text="Phishing-resistant search"'; seen 'Match with'; value 1
read W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
adb shell input tap $((W / 40)) $((H / 2)); sleep 2; top
adb shell am start -n $FILTER --es $X.EXTRA_FORM_ORIGIN_WEB example.org; sleep 3; top
```
Expected: for an app origin the strict search switch is `MISSING`, the match label names the app ("Settings" in quotes) and the field holds `Settings`; a tap in the side margin outside the window (the picker is 75 % wide and centered, the tap is at 2.5 % of the width, mid-height) closes it (`MainActivity` on top); started without client state the picker finishes at once.

- [ ] **Step 7: The publisher warning**

```bash
adb shell am start -n $PUB --es $X.EXTRA_APP_PACKAGE com.android.settings; sleep 3
seen 'text="Possible phishing attempt"'; seen 'Settings'; seen 'Installed:'
seen 'text="Keep Autofill disabled"'; seen 'text="Advanced information"'
seen 'text="Re-enable Autofill"'; seen 'Package:'
adb exec-out screencap -p > "$OUT/warning.png"
tap 'text="Keep Autofill disabled"'; sleep 2; top
adb shell am start -n $PUB --es $X.EXTRA_APP_PACKAGE com.android.settings; sleep 3
tap 'text="Advanced information"'
seen 'text="Re-enable Autofill"'; seen 'com.android.settings'; seen 'text="Advanced information"'
adb shell cmd uimode night yes; sleep 3; seen 'text="Re-enable Autofill"'
adb exec-out screencap -p > "$OUT/warning-dark.png"
adb shell cmd uimode night no; sleep 3
tap 'text="Re-enable Autofill"'; sleep 2; top
adb logcat -c
adb shell am start -n $PUB --es $X.EXTRA_APP_PACKAGE does.not.exist; sleep 3; top
logs 'Failed to retrieve package info'
```
Expected: the warning shows title, app name, install time and the two buttons; "Re-enable Autofill" and the package details are `MISSING` at first. "Keep Autofill disabled" closes it (`MainActivity` on top). After "Advanced information" the re-enable button and the details (package and hash) appear and "Advanced information" is `MISSING`; a recreation keeps them; "Re-enable Autofill" closes it. An unknown package closes at once with one log line (count `1`).

- [ ] **Step 8: Save activity regression (unchanged since step 4)**

```bash
adb shell am start -n $SAVE --es $X.EXTRA_NAME carol --es $X.EXTRA_FOLDER_NAME example.net \
  --es $X.EXTRA_PASSWORD e2e-only-pw --es $X.EXTRA_SHOULD_MATCH_WEB example.net \
  --ez $X.EXTRA_GENERATE_PASSWORD false; sleep 3
secure; value 1
tap 'text="Save"'; sleep 4; top
adb shell run-as $APP cat shared_prefs/oreo_autofill_web_matches.xml
open; seen 'text="example.net"'
```
Expected: the editor prefilled with `carol` in folder `example.net`, `secure` at `1` or more; Save closes it, the web matches gain `matches;example.net`, and the list shows the `example.net` folder.

- [ ] **Step 9: Tear down and push**

```bash
adb unroot; adb wait-for-device
adb shell cmd uimode night no
adb uninstall $APP
adb shell rm -f /sdcard/Download/aps-e2e-ssh-key /sdcard/Download/aps-e2e-pgp-key.asc
adb emu kill
scripts/e2e-env.sh down
unset ANDROID_SERIAL
rm -rf "$OUT"
git push
```
Expected: push succeeds to every `origin` push URL. Look at the four screenshots before deleting `$OUT` if a visual check is wanted (copy them elsewhere first).

- [ ] **Step 10: Hand the tablet checklist to the maintainer**

Do not run this: it uses the maintainer's tablet. Report it with the results of Steps 1 to 8. Setup per AGENTS.md ("Autofill cannot be tested on the emulator image"): record `secure autofill_service` and `global stay_on_while_plugged_in`, `svc power stayon usb`, `adb reverse tcp:2222 tcp:2222` for the e2e server, a local login page through `python3 -m http.server` and `adb reverse`, the debug build set as autofill service, the e2e store cloned over `ssh://git@127.0.0.1:2222/srv/git/store.git`; restore everything afterwards.

1. **Fill from a match** (Firefox, the test page as `example.org` or its own matched entry): the suggestion fills username and password. With the passphrase cache off, the Compose passphrase dialog appears over Firefox; a screenshot of it is blocked (FLAG_SECURE); Cancel fills nothing and leaves no dimmed window; a wrong passphrase fills nothing and leaves no window.
2. **Passphrase cache on** (Settings, PGP): the screen lock prompt appears (Android 14: not dismissed at once), the first fill asks the passphrase, the next one uses the cache; screen off and on, then the cache is cleared if "clear on screen off" is checked.
3. **Cache only after the screen lock** (clear the cache first: screen off and on with "clear on screen off" checked): fill, cancel the screen lock prompt, type the passphrase: the fill works. Fill again and approve the prompt: the passphrase is asked again (nothing was cached after the cancelled prompt). Type it: the next fill with the prompt approved uses the cache.
4. **No keys imported** (delete the key in the key manager first): "No keys imported" appears over Firefox; Import opens the PGP keys screen; after importing the e2e key, with the passphrase cache on, the screen lock prompt appears over Firefox and stays up until answered (not dismissed at once), then the fill continues. Dismissing the "No keys imported" dialog leaves no window.
5. **Search entry** (the "Search entry" chip or dataset): the picker opens floating with the site prefilled, phishing-resistant search on, rows named by identifier; search, clear, toggle strict search; tap an entry: it fills, and the next visit offers it directly as a match. "Clear existing matches" on the next pick replaces the old match. A tap outside closes it. Light and dark theme, Italian locale.
6. **Create entry** (generate): the editor opens with a generated password, Save fills it and stores the entry (unchanged since step 4).
7. **Save** after submitting a login form, in a browser on the save list (Firefox Nightly or Fenix builds; Firefox release cannot, see "Out of scope"): the editor opens prefilled, Save stores it.
8. **Publisher changed**, if reproducible: match an app's login to an entry, then replace the app with the same package signed by another key (for example the F-Droid build after the upstream one); its login offers "Possible phishing attempt"; the warning shows the app and install time; "Keep Autofill disabled" fills nothing; "Advanced information" then "Re-enable Autofill" fills and the warning does not return for it. If a translucent overlay app (a screen dimmer, say) is at hand: with it drawn over the warning, taps on the buttons do nothing and back still closes the warning.
9. **Still owed from step 6:** the OK of Settings' autofill instructions dialog (it opens the system's autofill service picker).

---

## Self-review notes

- **Spec coverage for step 7:** the four activities stay separate (Architecture, Activities) and draw with `APSTheme` (Tasks 2, 3, 4; `AutofillSaveActivity` since step 4); the setup intent question is settled and the spec reworded (Task 5); intents, parsing, crypto and preference keys unchanged (feature parity tables). The one behavior change outside the UI, the passphrase cache rule, is a maintainer decision and brings autofill in line with the entry view. The spec gains an Autofill section (Task 5).
- **Security:** the picker records a match only for the tapped row and only with "Match with" on, and starts one decryption (Task 2); the publisher warning has no path to `RESULT_OK` but "Advanced information" then "Re-enable Autofill", the reset still clears matches first, and touches arriving through another app's window are dropped (Task 3; Task 6 Step 7); the decryption keeps `FLAG_SECURE`, the passphrase stays in the dialog's `remember` and goes straight to decryption, it is cached only after a successful screen lock prompt (unit tested), every exit without a dataset is `RESULT_CANCELED`, and the no-keys dialog can no longer leave an invisible window (Task 4). No new log lines carry secrets; the moved log lines are the old ones. No new strings, extras or exported components.
- **Fixes found while reading the legacy code:** the decryption flow ran on every `onStart` (two passphrase dialogs after screen off and on, a double run after the key import); the screen lock prompt from `onStart` likely hit the Android 14 eviction fixed for `LaunchActivity`; a dismissed "No keys imported" dialog left a transparent, dimmed window over the app being filled; a double tap in the picker started two decryptions; rotation during the decryption restarted the flow; autofill cached a passphrase typed after a cancelled or unavailable screen lock prompt.
- **Review findings applied:** after a key import the flow restarts on the next window focus (`started = false`), never directly from the result callback; `start()` returns when finishing; the decrypt activity's `configChanges` cover resizing and keyboards too; `noKeys` and `passphraseFor` replace a prompt enum and a stored closure; the picker's first search runs in `onCreate` (no directory filter needed) and its list is `GroupedSurface(Modifier.fillMaxWidth())`; the cold search flow's re-walk on `STARTED` is a `ponytail:` note; `@color/list_multiselect_background` and the stale `SelectableText` baseline entries go with their layouts; the warning's package details are local values; the emulator taps outside the picker in its side margin; the footer note names every locale but Italian; Task 2's separate smoke check is gone (Task 6 covers it).
- **Not changed on purpose:** a wrong passphrase cancels the fill without retry (maintainer decision); an invalid `.gpg-id` shows a snackbar in the transparent window and stays until back, as before; `addMatchFor` still throws when the publisher changed between listing and picking (unchanged, the service checks earlier); the `DirectoryBased` identifier is the parent's whole path, so a nested entry's row name can contain a slash (upstream's convention, `autofillRows` only presents it).
- **Not checked on the emulator:** the decryption's dialogs, the screen lock prompt, the passphrase cache rule in a real fill, the fill itself, the save request, the real publisher change and the tapjacking guard (it needs an overlay app); all are in the tablet checklist (Task 6 Step 10) or covered by review and the unit test. The shared passphrase dialog is checked through the entry view (Task 6 Step 3).
- **Deliberate shortcuts (`ponytail:`):** a recreation of the decryption cancels the fill (Task 4, activity and manifest); the warning footer loses its bold in every locale but Italian (Task 3); the picker's cold search flow walks the store again each time it returns to `STARTED` (Task 2).
- **Type and name consistency:** `autofillRows(files: List<File>, root: File, structure: DirectoryStructure): ImmutableList<ListRow>` (Task 1) is used with these arguments in Task 2. `cachesPassphrase(cacheEnabled: Boolean, auth: BiometricResult): Boolean` (Task 4 Step 3) is used in `decryptCredential`. `ListRow(path, label, subtitle, count)` is the list's existing class. `AutofillFilterScreen(origin, query, rows, showStrict, strict, matchLabel, match, clear, onQueryChange, onStrictChange, onMatchChange, onClearChange, onPick, modifier)` (Task 2 Step 1) matches its call in Step 2. `PublisherChangedScreen(appName, installed, details, onKeepDisabled, onReset, modifier)` (Task 3) matches its call. `PassphraseDialog(wrong, cacheEnabled, clearCache, onSubmit, onCancel)` is the entry view's existing signature, made `internal` (Task 4 Step 5). `PreferenceSwitch(title, checked, onCheckedChange)`, `EntryRow(name, subtitle, onClick)`, `GroupedSurface(modifier, content)` and `MainActivity.setupIntent(context, SetupStart.PgpImport)` are existing APIs.
- **Build order:** Task 1 adds an unused function; Tasks 2, 3 and 4 each replace one activity's UI and delete what it orphaned, so lint stays clean after every task (`dimens.xml` goes with its last user in Task 4); Task 5 changes only a comment and the spec and runs the full CI set.

## Out of scope

- **Firefox release in the autofill save list.** `org.mozilla.firefox` (and `firefox_beta`) are trusted for filling but missing from `BROWSER_SAVE_FLAG` in `autofill-parser/.../FeatureAndTrustDetection.kt`, so saving after a login does nothing there. Not in step 7 (maintainer decision): a separate fix on `master` later, after a tablet test that Firefox release sends save requests with the unmasked password.

## Maintainer decisions (2026-11-04)

1. **Firefox release save support:** not in step 7; a separate fix on `master` later (Out of scope).
2. **Wrong passphrase during autofill:** keep cancelling the fill (Task 4).
3. **Passphrase cache gap:** fixed in step 7. Autofill caches the passphrase only after a successful screen lock prompt, as the entry view does (`cachesPassphrase`, unit tested, Task 4; tablet checklist item 2).
4. **Tapjacking guard on the publisher warning:** added, `filterTouchesWhenObscured` on the decor view (Task 3). The planned look stays: "Keep Autofill disabled" is the primary button, the title is red.
5. **Layouts:** deleted in step 7, each by the task that replaces it (Tasks 2, 3, 4).
6. **Picker rows:** identifier as the name, "account · folders" as the subtitle (Tasks 1, 2).

No open questions remain.
