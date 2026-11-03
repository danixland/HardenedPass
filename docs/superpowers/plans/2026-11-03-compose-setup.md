# Compose UI overhaul, step 5: Setup. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy setup screens with Compose destinations in `MainActivity`: onboarding (`OnboardingActivity` and its three fragments), the git server screen and clone (`GitServerConfigActivity`), SSH key import, generation and display (`SshKeyImportActivity`, `SshKeyGenActivity`, `ShowSshKeyFragment`) and PGP key import and management (`PGPKeyImportActivity`, `PGPKeyListActivity`). The setup flow asks for the PGP key after a clone and lets a wrongly imported SSH key be replaced at any time, the two real-device findings of step 4.

**Architecture:** Five routes in a new `ui/setup` package, registered by `NavGraphBuilder.setupGraph` in `MainActivity`'s `NavHost`: `WelcomeRoute` (start destination when no store is set up), `GitServerRoute(clone)`, `SshKeyRoute`, `SshKeyGenRoute` and `PgpKeysRoute(mode)` with modes `Manage`, `Choose` (key of a new local store) and `Need` (a store or an entry needs a key). Each gets its own ViewModel per back stack entry (`GitServerViewModel`, `SshKeyViewModel`, `PgpKeysViewModel`). Pure logic (URL checks and fixes, auth modes per URL, clone target state, `.gpg-id` recipients, SSH private key file check) sits in `SetupModel.kt` and `SshKey.kt` with unit tests. Work that needs the activity (clone through `launchGitOperation`, the `.gpg-id` commit) runs in `activity.lifecycleScope` with busy flags reset in `finally`, and its success is kept in the ViewModel and completed by a `LaunchedEffect`; work that does not (key generation after the biometric prompt, SSH and PGP imports) runs in `viewModelScope`, so a recreation never loses it. Callers outside the graph or waiting for a result (legacy Settings rows, `GitOperation`'s "No SSH key" dialog, the entry view's and autofill's "No keys imported" prompts) start `MainActivity.setupIntent(context, SetupStart.X)`: an instance whose first screen is that destination and which finishes when it is left, returning `RESULT_OK` after a PGP import. The legacy activities, fragments, layouts, `BasicBottomSheet`, `PGPKeyList.kt`, `PGPKeyListViewModel` and ui:compose `APSAppBar` are deleted.

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2024.09.03 (Material 3 1.3.0: `SingleChoiceSegmentedButtonRow`, `SegmentedButton`, `ExtendedFloatingActionButton`, `LinearProgressIndicator`; foundation 1.7.3: `KeyboardOptions(autoCorrectEnabled = ...)`, `SelectionContainer`, `Modifier.toggleable`), navigation-compose 2.8.9 (type-safe routes, `data object` and enum arguments, `popUpTo(nav.graph.id)`), `hilt-navigation-compose` 1.2.0, sshj 0.41.2 (`KeyProviderUtil.detectKeyFileFormat`), PGPainless key manager (unchanged), JGit 5.13 (unchanged), kotlin-result 2.0.0. No new dependencies.

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`. Earlier plans: `2026-11-02-compose-foundation.md`, `2026-11-02-compose-list.md`, `2026-11-02-compose-entry.md`, `2026-11-02-compose-editor.md` (same folder).

## Decisions taken in this plan

- **Routes, package and the setup intent.** Everything new lives in `app.passwordstore.ui.setup`. The NavHost starts at `WelcomeRoute` when `checkStore()` finds no store, at `ListRoute()` otherwise (an `EntryRoute` from a shortcut and a setup start still win). `checkStore()` no longer starts an activity: the list calls a new `onNoStore` callback when the store disappears (deleted from Settings), which replaces the stack with `WelcomeRoute`. Setup is complete when the stack is replaced by `ListRoute()` (`popUpTo(nav.graph.id) { inclusive = true }`): after a clone that needs no key, after "Later" or an import on the `Need` screen, after a new local store was created. `MainActivity.setupIntent(context, start)` puts `SetupStart.name` in an extra; that instance's first screen closes with `finish()` instead of popping. It is the only way in for screens outside the graph until steps 6 and 7 (Settings, autofill) move into it; the entry view uses it too, because it waits for an activity result exactly as it did with `PGPKeyImportActivity`, so its code barely changes (`ponytail:` in `MainActivity`).
- **Welcome merges two screens** (maintainer decision 1). "Let's Go" and the repository type screen become one screen: icon, app name, the old explanation text, "Clone Remote Repo", "Create Local Repo" and "Settings" (still the legacy activity, step 6). It reuses the old strings.
- **One git server screen for clone and edit.** `GitServerRoute(clone = true)` from the welcome screen and the list's clone prompt, `clone = false` from Settings. URL field, authentication mode as segmented buttons with the old rules (SSH URLs: SSH key or password; HTTP(S) URLs: none or password; a choice no longer valid falls back to the first), and, while the mode is SSH key, a row with the key's state and **Manage** (opens `SshKeyRoute`). "Clear saved host key" while one is saved. The old URL warnings become dialogs with the same texts and fixes, checked by `urlProblem` before `GitSettings.updateConnectionSettingsIfValid` (unchanged); two old slips are fixed: `git://` is checked first (with a port it used to get the `ssh://` fix), and `http://` with a port is left alone (it used to get `ssh://http://...`). Save in edit mode shows a toast and closes at once (the old screen waited 500 ms).
- **Clone.** Same sequence as before: initialize the repository object if needed, delete confirmation when the store folder holds anything but a lone `.git` (deleted silently), "Deleting…" then "Cloning…" shown on the screen, `launchGitOperation(GitOp.CLONE)` in `activity.lifecycleScope`. Back is ignored while it runs. **A failed clone keeps the screen** with its URL and mode (the old activity finished after a failure that followed a deletion), so a wrong URL or SSH key is fixed and retried in place (step 4 finding). On success the key check runs in `viewModelScope` (it needs no activity, so a recreation in its IO cannot lose the answer) and stores whether the store still needs a PGP key in `GitServerViewModel.cloned`; a `LaunchedEffect` then opens `PgpKeysRoute(Need)` or the list. "Needs a key": no imported **secret** key matches any recipient of the store's root `.gpg-id` (or, without recipients, no secret key is imported at all); a public key alone cannot decrypt, so it does not count. The store folder is emptied with `deleteWithoutFollowingLinks()` (from `ui/list/StoreActions.kt`), never `deleteRecursively()`, which follows symlinks: a `store/link -> ../keys` link would wipe the PGP keys. Settings' "Delete repository" (`RepositorySettings`) still uses `deleteRecursively()` and has the same flaw; it is step 6's to fix with the Settings port. A theme or locale change in the middle of a clone cancels it, as for every git operation of the app (`ponytail:`); the clone can be started again and the delete confirmation covers the leftovers.
- **SSH key screen** (`SshKeyRoute`) replaces `SshKeyImportActivity` and `ShowSshKeyFragment`: the key's state ("No SSH key yet", "Imported private key", "Generated on this device"), the public key with Copy and Share when the device generated it, **Import SSH key** (the old replace confirmation, the document picker, the toast and the error dialog) and **Generate SSH key pair** (opens `SshKeyGenRoute`). Settings' "Import SSH key" and "View generated public SSH key" and the "No SSH key" dialog's Import all open this screen, one tap before the picker (maintainer decision 4). The import runs on the IO thread and checks, in addition to the old size and marker checks, that sshj recognizes the format (`KeyProviderUtil.detectKeyFileFormat` is not `Unknown`): an OpenPGP private key block (`-----BEGIN PGP PRIVATE KEY BLOCK-----`) matched the old marker regex and was accepted as an SSH key, which is how the e2e PGP key ended up as the device's SSH key in step 4. A refused file leaves the current key in place (the old key is deleted only after the checks, unchanged). The size query no longer crashes on an empty cursor: `cursor.moveToFirst()` was unchecked before `getInt(0)` (a `CursorIndexOutOfBoundsException` would escape `viewModelScope`); an empty cursor now ends in the existing "Unable to open the ssh private key" `IOException`.
- **Key generation screen** (`SshKeyGenRoute`) ports `SshKeyGenActivity`: RSA, ECDSA (default), Ed25519 as segmented buttons with the old explanations (plain text: `stringResource` drops their bold title, `ponytail:`), "Protect with screen lock credential" (enabled and on when the device is secure), the replace confirmation (Keep closes the screen, as before), the biometric prompt first when protected, the public key dialog (Share, Later; both close) and the error dialog (OK closes). Generation itself runs in `viewModelScope` (it needs no activity once the prompt succeeded), so the dialog that follows survives a recreation. `SshKey` is unchanged: Ed25519 keys still come from Bouncy Castle through `ed25519KeyPair` and `SshjConfig`'s pinned `KeyFactory.Ed25519`.
- **PGP keys screen** (`PgpKeysRoute(mode)`) replaces `PGPKeyListActivity` (with `KeyList`) and `PGPKeyImportActivity`. Rows show the user ID and the long key ID; an extended FAB "Import PGP key" opens the picker; the old success (key ID), replace and failure dialogs follow. `Manage` (Settings) deletes with the old confirmation, now by key ID (by user ID, two keys sharing one could delete the wrong key). `Choose` (welcome screen, "Create Local Repo") picks the key: only then is the repository created, `.gpg-id` written and committed with the old message. `.gpg-id` now holds the key's 16-hex long key ID in uppercase, one line per key (`F8993A789CDFB014\n`), the format of the maintainer's desktop `pass init` store, instead of the user ID the old selection wrote (maintainer decision 3); `PGPIdentifier.fromString` reads it back as a key ID. Backing out creates nothing (the old flow created the repository first, so backing out left an empty store that the next start treated as set up). `Need` explains that the store needs its private key, lists the root `.gpg-id` recipients, and offers "Later" (maintainer decision 2); an import, "Later" or back completes the flow. `.gpg-id` is read only when it is a regular file, not a symlink, of at most 64 KiB (`readGpgId`), and off the main thread. The key file is read and imported off the main thread (the old activity read it on the main thread and imported with `runBlocking`); the bytes kept for the replace question live in ViewModel memory only and are zeroed (`fill(0)`) once imported, refused, answered or when the ViewModel is cleared. Rows read the first user ID safely: `KeyUtils.tryGetEmail` called `userIDs.next()` unguarded and threw on a key without user ID; it now returns null there. `PGPKeyListViewModel` (user IDs only, delete by user ID) is replaced by `PgpKeysViewModel`.
- **Callers.** Settings rows (git server, import SSH key, generate, view public key, key manager) start setup intents. The list's clone prompt navigates to `GitServerRoute(clone = true)`. The entry view's "No keys imported" Import and `BasePGPActivity.requireKeysExist` (autofill decryption, step 7) start `PgpKeysRoute(Need)` for a result. `GitOperation`'s "No SSH key" dialog: Import opens the SSH key screen, Generate the generation screen, Cancel only closes the dialog (it used to finish the calling activity, which is now `MainActivity`, so the app closed). Its biometric branch for an unusable prompt also finished the calling activity; it now shows the same toast and cancels the operation.
- **No FLAG_SECURE on setup screens.** None shows a secret: passphrases (SSH key, HTTPS password) are still asked by `CredentialFinder` when a git operation needs them, PGP passphrases by the entry view. Key file bytes never reach Compose state.
- **`GitConfigActivity` stays for step 6** (maintainer decision 5): it is the Settings row "Edit git config" (author name and email) with the repository tools (git log, abort rebase, hard reset, gc), all Settings and git log material.

## Feature parity

### `OnboardingActivity` and `WelcomeFragment`

| Behavior today | Decision |
|---|---|
| Separate activity, started by `MainActivity.checkStore()` from the list's resume | Changed: `WelcomeRoute` is the start destination without a store; the list's resume check navigates there when the store disappears |
| Welcome: icon, app name, "Let's Go", Settings | Changed: merged with the repository type screen (maintainer decision 1); Settings kept (legacy activity) |
| Back on the first screen leaves the app (`finishAffinity`) | Port: the welcome screen is the stack's root |

### `CloneFragment`

| Behavior today | Decision |
|---|---|
| "Clone Remote Repo" opens `GitServerConfigActivity` for a clone; on success marks the store initialized and closes onboarding | Port: `GitServerRoute(clone = true)`; then the key check and the list, whose `checkStore()` marks the store initialized (the folder is no longer empty) |
| No PGP key step after a clone (step 4: the first save failed with "No keys were found") | New: `PgpKeysRoute(Need)` when no imported secret key matches the store's `.gpg-id` |
| "Create Local Repo" creates the repository, then asks for the key | Changed: key first, repository created with it |
| Creation failure: logged, empty folder deleted, onboarding closed | Changed: snackbar with the reason, empty folder deleted, the key screen stays |

### `KeySelectionFragment`

| Behavior today | Decision |
|---|---|
| "Select key" opens `PGPKeyListActivity` in selection mode | Port: `PgpKeysRoute(Choose)` |
| Writes the key's user ID to `.gpg-id` off the main thread, marks the store initialized, commits "Initialize GPG IDs in HardenedPass." | Changed: the uppercase 16-hex long key ID, one line per key, as the maintainer's desktop store (maintainer decision 3); same commit, a failed commit shows the git error dialog; then the list |
| Cancelled selection: snackbar "Selecting a GPG key is necessary to proceed" | Changed: back returns to the welcome screen, nothing was created |

### `GitServerConfigActivity`

| Behavior today | Decision |
|---|---|
| URL field prefilled from the settings | Port |
| Toggle group: SSH key and password for SSH URLs, password or nothing (none) for HTTPS; adjusted while typing | Port as segmented buttons, "None" a button of its own (new string) |
| Port and scheme warnings in bottom sheets with fixes; `git://` refused | Port as dialogs; `git://` checked first; `http://` with a port no longer offered `ssh://` |
| Save results: snackbars for an unparsable URL and a wrong mode, sheet for a missing user name | Port (dialog for the user name) |
| "Clear saved host key" when one is saved, snackbar | Port |
| No SSH key controls; Settings' SSH rows are hidden before a clone, so a wrong key could not be replaced during onboarding (step 4) | New: SSH key row with state and Manage |
| Edit mode: snackbar, finish after 500 ms | Changed: toast, closes at once |
| Clone: delete confirmation for an occupied folder, "Deleting…" snackbar, lone `.git` deleted silently; `deleteRecursively()` followed symlinks out of the store | Port: dialog, progress text on the screen; fix: deletion never follows symlinks |
| Clone failure: error dialog; after a deletion the activity finished as well | Changed: error dialog, the screen stays |
| Back during a clone | Changed: ignored while it runs |
| Rotation recreated the activity and cancelled a clone | Changed: no recreation (`configChanges`); a theme change still cancels (`ponytail:`) |

### `SshKeyImportActivity`

| Behavior today | Decision |
|---|---|
| Invisible activity: replace confirmation when a key exists, document picker (`*/*`), toast on success, error dialog | Port into the SSH key screen's "Import SSH key" |
| Checks: size 1 byte to 100 kB, private key markers on the first and last line (an OpenPGP private key passed); an empty size cursor crashed | Port and fix: the format must also be one sshj recognizes; a refused file keeps the current key; an empty cursor is an `IOException` |
| Runs on the main thread | Changed: IO thread |

### `SshKeyGenActivity` and `ShowSshKeyFragment`

| Behavior today | Decision |
|---|---|
| RSA, ECDSA (default), Ed25519 with explanations | Port: segmented buttons; explanations without their bold title (`ponytail:`) |
| "Protect with screen lock credential": enabled and on when the device is secure | Port |
| Replace confirmation; Keep closes | Port |
| Biometric prompt first when protected; failure becomes the error dialog | Port |
| Button shows "Generating keys…" and is disabled | Port; back ignored while generating |
| Public key dialog: Share (chooser), Later; both close | Port |
| Error dialog "Message : ..."; OK closes | Port |
| Removes the local SSH passphrase | Kept in `SshKey.delete()`, which every generation calls (the activity's second removal was redundant) |
| `ShowSshKeyFragment`: public key dialog from Settings | Replaced: the SSH key screen shows it, with Copy and Share |
| Generation in the activity's scope | Changed: `viewModelScope` after the prompt |

### `PGPKeyImportActivity`

| Behavior today | Decision |
|---|---|
| Invisible activity, picker at once; read on the main thread, `runBlocking` import | Changed: "Import PGP key" on the keys screen; read and import off the main thread |
| Success dialog with the key ID | Port |
| Key ID already imported: replace question, bytes kept for it | Port: bytes in ViewModel memory only, zeroed when no longer needed |
| Failure dialog with the message | Port |
| Used for a result by the entry view's and autofill's "No keys imported" prompts | Port: they start `PgpKeysRoute(Need)` through the setup intent; `RESULT_OK` after an import |

### `PGPKeyListActivity`, `KeyList` and `PGPKeyListViewModel`

| Behavior today | Decision |
|---|---|
| Rows with the user ID, delete icon with "Delete key?" Yes/No, FAB import, empty guidance with the logo | Port in the app's look: grouped surface, extended FAB, guidance text |
| Rows show only the user ID; a key without user ID threw (`userIDs.next()`) | Changed: user ID and key ID; fix: a key without user ID shows its key ID |
| Delete by user ID | Fix: by key ID |
| Selection mode for onboarding | Port: `Choose` |
| `PGPKeyListViewModel` | Replaced by `PgpKeysViewModel` |

### Callers

| Behavior today | Decision |
|---|---|
| Settings: git server, import SSH key, generate, view public key, key manager start the legacy activities | Port: setup intents (`GitServer`, `Ssh`, `SshGenerate`, `Ssh`, `PgpKeys`) |
| List's clone prompt starts `GitServerConfigActivity` | Port: `GitServerRoute(clone = true)` |
| `GitOperation` "No SSH key": Import, Generate, Cancel (finishes the calling activity) | Port: setup intents; Cancel only closes the dialog |
| `GitOperation`: unusable biometric prompt shows a toast and finishes the calling activity | Fix: toast, operation cancelled |

### Deleted in this step (Task 7)

`ui/onboarding/activity/OnboardingActivity.kt`, `ui/onboarding/fragments/WelcomeFragment.kt`, `CloneFragment.kt`, `KeySelectionFragment.kt`, `ui/git/config/GitServerConfigActivity.kt`, `ui/sshkeygen/SshKeyImportActivity.kt`, `SshKeyGenActivity.kt`, `ShowSshKeyFragment.kt`, `ui/pgp/PGPKeyImportActivity.kt`, `PGPKeyListActivity.kt`, `PGPKeyList.kt`, `ui/dialogs/BasicBottomSheet.kt` (only `GitServerConfigActivity` used it), `util/viewmodel/PGPKeyListViewModel.kt`, ui:compose `APSAppBar.kt` (only `PGPKeyListActivity` used it), layouts `activity_onboarding.xml`, `fragment_welcome.xml`, `fragment_clone.xml`, `fragment_key_selection.xml`, `activity_git_clone.xml`, `activity_ssh_keygen.xml`, `basic_bottom_sheet.xml`, six manifest entries, and the resources lint then reports unused.

**Kept for later steps:** `GitConfigActivity`, `TextInputDialog`, `activity_git_config.xml`, `GitLogActivity` and the Settings rows themselves (step 6); `CredentialFinder` and `git_credential_layout.xml` (the passphrase and password prompts of git operations); `BasePGPActivity.requireKeysExist` (autofill decryption, step 7); `FragmentExtensions.kt` and the other view-binding helpers (step 8, as the spec says). For step 6: Settings' "Delete repository" (`RepositorySettings`) empties the store with `deleteRecursively()`, which follows symlinks out of it; port it with `deleteWithoutFollowingLinks()`.

## Lessons from steps 2 to 4, designed in

- **Hosting.** `MainActivity` (single activity, `configChanges="orientation|screenSize"`, extends `BaseGitActivity`) hosts the new destinations; each takes its route and gets its ViewModel from `hiltViewModel()` (one per back stack entry). Only the destination on top may navigate (`nav.currentBackStackEntry?.id == entry.id`), so double taps never push twice.
- **Recreation.** Work that needs the activity runs in `activity.lifecycleScope`: the clone (`launchGitOperation`, `promptOnErrorHandler`) and the `.gpg-id` commit (`commitChange`). Its busy flag lives in the ViewModel and is reset in `finally`, so a cancelled job never leaves a screen disabled; its success lives in the ViewModel (`GitServerViewModel.cloned`, set by a key check that itself runs in `viewModelScope`; `PgpKeysViewModel.done`) and a `LaunchedEffect` completes the navigation, so a recreation between success and navigation still moves on. Work that does not need the activity (key generation after the prompt, SSH and PGP imports) runs in `viewModelScope`, and the dialogs that report it are driven by ViewModel state.
- **Slack compose-lint and lint.** No composable members on activities (`setupGraph` is a top-level `NavGraphBuilder` extension, the dialogs are top-level functions); every public composable has `modifier: Modifier = Modifier` as its first optional parameter; collections passed to composables are `ImmutableList`; ViewModels are default parameters of the destinations and only values and lambdas go down to screens; no `kotlin.runCatching` (`DenyListedApi`): new code catches specific exceptions; ViewModels take `DispatcherProvider`, never `Dispatchers` (`RawDispatchersUse`).
- **Accessibility.** Every `IconButton` has a content description (back, delete); the FAB takes its description from its icon (Material 3 clears the label's semantics); the protect switch row is one `toggleable` with `Role.Switch`.
- **Security.** No secret goes into a route, a Bundle, `rememberSaveable` or a log. SSH keys are stored as before (`SshKey`, Keystore-backed for generated keys, `KeystorePreferences` for the passphrase); the import reads the URI inside `SshKey.import`. Ed25519 keys keep coming from Bouncy Castle (do not touch `SshjConfig`). Store paths come from `PasswordRepository.getRepositoryDirectory()` only; nothing takes a path from the user. Deleting the store folder never follows symlinks (`deleteWithoutFollowingLinks`), and `.gpg-id` is never read through a symlink or beyond 64 KiB (`readGpgId`). Possible private key bytes held for the replace question are zeroed as soon as they are not needed.
- **Real-device findings of step 4.** The clone flow now asks for the PGP key (`Need`); the SSH key can be replaced from the server screen at any time, and the import refuses OpenPGP key files.
- **Off the main thread:** key generation, both imports, the key list (parsing every key), the store creation, the folder deletion before a clone, the after-clone key check. Every `.gpg-id` read (after-clone check, `Need` screen) is off the main thread too, through `readGpgId` (regular file, no symlink, at most 64 KiB). Small reads stay where they were: the clone target listing before the dialog, the public key file.
- **Lint per task.** Each task's check command includes `:app:lintDebug`, so every commit passes lint on its own; Task 7 runs the full CI set.

**Conventions for every task**

- New files get this header:
  ```kotlin
  /*
   * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
   * SPDX-License-Identifier: GPL-3.0-only
   */
  ```
  Modified upstream files keep their header.
- Strings go in `app/src/main/res/values/strings.xml` and `values-it/strings.xml` ("voce" for entry).
- Commits are GPG-signed (default config, never disable). End each message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No em dashes in docs, comments or commit messages.
- Before each commit run `./gradlew spotlessApply`, then the step's checks.
- Work on the `compose` branch.

---

### Task 1: Setup model, key checks and the SSH key file check (TDD)

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/setup/SetupModel.kt`
- Test: `app/src/test/java/app/passwordstore/ui/setup/SetupModelTest.kt`
- Modify: `app/src/main/java/app/passwordstore/util/git/sshj/SshKey.kt`
- Test: `app/src/test/java/app/passwordstore/util/git/sshj/SshKeyFileTest.kt`
- Modify: `crypto/pgpainless/src/main/kotlin/app/passwordstore/crypto/KeyUtils.kt`
- Modify: `crypto/pgpainless/src/test/kotlin/app/passwordstore/crypto/KeyUtilsTest.kt`

- [ ] **Step 1: Failing tests for the setup model**

`app/src/test/java/app/passwordstore/ui/setup/SetupModelTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.util.settings.AuthMode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SetupModelTest {

  private val root = Files.createTempDirectory("aps").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun urlProblems() {
    assertEquals(UrlProblem.HttpsPort, urlProblem("https://example.org:8443/store.git"))
    assertEquals(UrlProblem.SshScheme, urlProblem("git@example.org:2222/srv/store.git"))
    assertEquals(UrlProblem.GitScheme, urlProblem("git://example.org/store.git"))
    assertEquals(UrlProblem.GitScheme, urlProblem("git://example.org:9418/store.git"))
    assertNull(urlProblem("ssh://git@example.org:2222/srv/store.git"))
    assertNull(urlProblem("git@example.org:srv/store.git"))
    assertNull(urlProblem("https://example.org/store.git"))
    assertNull(urlProblem("http://example.org:8080/store.git"))
  }

  @Test
  fun urlFixes() {
    assertEquals(
      "https://example.org/store.git",
      fixUrl("https://example.org:8443/store.git", UrlProblem.HttpsPort),
    )
    assertEquals(
      "ssh://git@example.org:2222/srv/store.git",
      fixUrl("git@example.org:2222/srv/store.git", UrlProblem.SshScheme),
    )
    assertNull(fixUrl("git://example.org/store.git", UrlProblem.GitScheme))
  }

  @Test
  fun authModesFollowTheUrl() {
    val ssh = listOf(AuthMode.SshKey, AuthMode.Password)
    val http = listOf(AuthMode.None, AuthMode.Password)
    assertEquals(ssh, authModes("ssh://git@example.org/store.git"))
    assertEquals(ssh, authModes("git@example.org:store.git"))
    assertEquals(ssh, authModes(""))
    assertEquals(http, authModes("https://example.org/store.git"))
    assertEquals(http, authModes("http://example.org/store.git"))
    assertEquals(AuthMode.None, fitAuthMode("https://example.org/x", AuthMode.SshKey))
    assertEquals(AuthMode.Password, fitAuthMode("https://example.org/x", AuthMode.Password))
    assertEquals(AuthMode.SshKey, fitAuthMode("git@example.org:x", AuthMode.None))
    assertEquals(AuthMode.Password, fitAuthMode("git@example.org:x", AuthMode.Password))
  }

  @Test
  fun cloneTargets() {
    val store = File(root, "store")
    assertEquals(CloneTarget.Empty, cloneTarget(store))
    store.mkdirs()
    assertEquals(CloneTarget.Empty, cloneTarget(store))
    File(store, ".git").mkdirs()
    assertEquals(CloneTarget.OnlyGit, cloneTarget(store))
    File(store, "a.gpg").writeText("x")
    assertEquals(CloneTarget.Occupied, cloneTarget(store))
    File(store, ".git").deleteRecursively()
    assertEquals(CloneTarget.Occupied, cloneTarget(store))
  }

  @Test
  fun storeRecipientsSkipBlankAndInvalidLines() {
    val ids =
      storeRecipients(
        "4882E8257A3700D43119B8D3F8993A789CDFB014\n\n  Alice <alice@example.org>  \nnot an id\n"
      )
    assertEquals(listOf("f8993a789cdfb014", "alice@example.org"), ids.map(PGPIdentifier::toString))
    assertEquals(emptyList(), storeRecipients(""))
  }

  @Test
  fun gpgIdTextIsUppercaseKeyIdsOnePerLine() {
    val ids =
      listOf("f8993a789cdfb014", "0x79E8208280490C77").map {
        assertIs<PGPIdentifier.KeyId>(PGPIdentifier.fromString(it))
      }
    val text = gpgIdText(ids)
    assertEquals("F8993A789CDFB014\n79E8208280490C77\n", text)
    // pass and this app read it back as the same key IDs.
    assertEquals(ids, storeRecipients(text))
  }

  @Test
  fun gpgIdIsReadOnlyFromASmallRegularFile() {
    assertNull(readGpgId(root))
    File(root, ".gpg-id").writeText("F8993A789CDFB014\n")
    assertEquals("F8993A789CDFB014\n", readGpgId(root))

    val linked = File(root, "linked").apply { mkdirs() }
    val outside = File(root, "outside").apply { writeText("F8993A789CDFB014\n") }
    Files.createSymbolicLink(File(linked, ".gpg-id").toPath(), outside.toPath())
    assertNull(readGpgId(linked))

    val big = File(root, "big").apply { mkdirs() }
    File(big, ".gpg-id").writeText("A".repeat(64 * 1024 + 1))
    assertNull(readGpgId(big))

    val folder = File(root, "folder").apply { File(this, ".gpg-id").mkdirs() }
    assertNull(readGpgId(folder))
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.setup.SetupModelTest`
Expected: compilation FAILS with `Unresolved reference 'UrlProblem'` (and the other new names).

- [ ] **Step 2: The setup model**

`app/src/main/java/app/passwordstore/ui/setup/SetupModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.util.settings.AuthMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/** Why a repository URL is questioned before it is saved, as the old server screen checked it. */
enum class UrlProblem {
  /** An HTTPS URL with a custom port, which the old screen did not support. */
  HttpsPort,
  /** An scp-style SSH URL with a port: without `ssh://` the port is read as part of the path. */
  SshScheme,
  /** The unencrypted `git://` protocol. */
  GitScheme,
}

private val PORT = ":[0-9]{1,5}/".toRegex()

private fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

/** What is wrong with [url], or null. `git://` is checked first: a port does not make it SSH. */
fun urlProblem(url: String): UrlProblem? =
  when {
    url.startsWith("git://") -> UrlProblem.GitScheme
    !url.contains(PORT) -> null
    url.startsWith("https://") -> UrlProblem.HttpsPort
    url.startsWith("http://") || url.startsWith("ssh://") -> null
    else -> UrlProblem.SshScheme
  }

/** The old screen's fix for [problem] in [url], or null when there is none (`git://`). */
fun fixUrl(url: String, problem: UrlProblem): String? =
  when (problem) {
    UrlProblem.HttpsPort -> url.replace(PORT, "/")
    UrlProblem.SshScheme -> "ssh://$url"
    UrlProblem.GitScheme -> null
  }

/** The authentication modes offered for [url], the default first. */
fun authModes(url: String): List<AuthMode> =
  if (isHttp(url)) listOf(AuthMode.None, AuthMode.Password)
  else listOf(AuthMode.SshKey, AuthMode.Password)

/** [current] when [url] still allows it, the default for [url] otherwise. */
fun fitAuthMode(url: String, current: AuthMode): AuthMode =
  authModes(url).let { modes -> if (current in modes) current else modes.first() }

/** What a clone into the store folder finds there. */
enum class CloneTarget {
  /** Missing or empty. */
  Empty,
  /** Only a `.git` folder, from a store created and abandoned: deleted without asking. */
  OnlyGit,
  /** Anything else: deleted after a confirmation. */
  Occupied,
}

fun cloneTarget(dir: File): CloneTarget {
  val files = dir.listFiles().orEmpty()
  return when {
    files.isEmpty() -> CloneTarget.Empty
    files.size == 1 && files[0].name == ".git" -> CloneTarget.OnlyGit
    else -> CloneTarget.Occupied
  }
}

/** The recipients named by a `.gpg-id` file's [text]; blank and unparsable lines are skipped. */
fun storeRecipients(text: String): List<PGPIdentifier> =
  text.lines().map(String::trim).filter(String::isNotEmpty).mapNotNull(PGPIdentifier::fromString)

/**
 * A `.gpg-id` naming [keys]: each 16-hex long key ID in uppercase on a line of its own, the format
 * of a desktop store made with `pass init <key ID>`.
 */
fun gpgIdText(keys: List<PGPIdentifier.KeyId>): String =
  keys.joinToString("") { "${it.toString().uppercase(Locale.ROOT)}\n" }

private const val MAX_GPG_ID_BYTES = 64 * 1024

/**
 * The text of [dir]'s `.gpg-id`, or null when it is missing, not a regular file, a symlink, larger
 * than 64 KiB or unreadable. Blocking: call it off the main thread.
 */
fun readGpgId(dir: File): String? {
  val file = File(dir, ".gpg-id")
  val path = file.toPath()
  return try {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) null
    else if (Files.size(path) > MAX_GPG_ID_BYTES) null
    else file.readText()
  } catch (_: IOException) {
    null
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.setup.SetupModelTest`
Expected: PASS (7 tests).

- [ ] **Step 3: Failing test for the SSH key file check**

`app/src/test/java/app/passwordstore/util/git/sshj/SshKeyFileTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git.sshj

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshKeyFileTest {

  private fun pem(type: String) =
    listOf("-----BEGIN $type-----", "b3BlbnNzaC1rZXktdjEAAAAABG5vbmU", "-----END $type-----")

  @Test
  fun acceptsSshPrivateKeys() {
    assertTrue(isSshPrivateKey(pem("OPENSSH PRIVATE KEY")))
    assertTrue(isSshPrivateKey(pem("RSA PRIVATE KEY")))
    assertTrue(isSshPrivateKey(pem("EC PRIVATE KEY")))
  }

  @Test
  fun refusesOtherFiles() {
    // The step 4 finding: an OpenPGP key block has the same markers and was accepted.
    assertFalse(isSshPrivateKey(pem("PGP PRIVATE KEY BLOCK")))
    assertFalse(isSshPrivateKey(pem("PGP PUBLIC KEY BLOCK")))
    assertFalse(isSshPrivateKey(listOf("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA test@example.org")))
    assertFalse(isSshPrivateKey(emptyList()))
    assertFalse(isSshPrivateKey(pem("OPENSSH PRIVATE KEY").dropLast(1)))
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.util.git.sshj.SshKeyFileTest`
Expected: compilation FAILS with `Unresolved reference 'isSshPrivateKey'`.

- [ ] **Step 4: The check, used by the import**

In `app/src/main/java/app/passwordstore/util/git/sshj/SshKey.kt`, add the imports

```kotlin
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
```

add, after `ed25519KeyPair`:

```kotlin
/**
 * True when [lines] look like an SSH private key sshj can load: private key markers on the first
 * and last line, in a format sshj recognizes. An OpenPGP private key block has the same markers
 * and is refused by the format check.
 */
internal fun isSshPrivateKey(lines: List<String>): Boolean {
  if (
    lines.size < 2 ||
      !Regex("BEGIN .* PRIVATE KEY").containsMatchIn(lines.first()) ||
      !Regex("END .* PRIVATE KEY").containsMatchIn(lines.last())
  )
    return false
  return try {
    KeyProviderUtil.detectKeyFileFormat(lines.joinToString("\n"), false) != KeyFormat.Unknown
  } catch (_: IOException) {
    false
  }
}
```

then, in `SshKey.import`, replace

```kotlin
        // Cursor returns only a single row.
        cursor.moveToFirst()
        cursor.getInt(0)
```

with

```kotlin
        // A single row; none when the provider cannot tell: the IOException below.
        if (cursor.moveToFirst()) cursor.getInt(0) else null
```

(an empty cursor threw `CursorIndexOutOfBoundsException`, which `SshKeyViewModel.import` does
not catch, so the app crashed; now it ends in the existing `?: throw IOException(...)`), and
replace

```kotlin
    // The file must have more than 2 lines, and the first and last line must have private key
    // markers.
    if (
      lines.size < 2 ||
        !Regex("BEGIN .* PRIVATE KEY").containsMatchIn(lines.first()) ||
        !Regex("END .* PRIVATE KEY").containsMatchIn(lines.last())
    )
      throw IllegalArgumentException(
```

with

```kotlin
    // Private key markers and a format sshj can read; the current key stays when this fails.
    if (!isSshPrivateKey(lines))
      throw IllegalArgumentException(
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.util.git.sshj.SshKeyFileTest --tests app.passwordstore.util.git.sshj.Ed25519KeyPairTest`
Expected: PASS (2 + 2 tests).

- [ ] **Step 5: Failing tests for the key checks**

In `crypto/pgpainless/src/test/kotlin/app/passwordstore/crypto/KeyUtilsTest.kt`, add the imports

```kotlin
import app.passwordstore.crypto.KeyUtils.isSecretKey
import app.passwordstore.crypto.KeyUtils.tryGetEmail
import kotlin.test.assertFalse
import kotlin.test.assertTrue
```

and, inside the class, after `isKeyUsable`:

```kotlin
  @Test
  fun secretKeysAreTold() {
    assertTrue(isSecretKey(PGPKey(AllKeys.ARMORED_SEC.keyMaterial)))
    assertTrue(isSecretKey(PGPKey(AllKeys.GNUPG_OCB_SEC.keyMaterial)))
    assertFalse(isSecretKey(PGPKey(AllKeys.ARMORED_PUB.keyMaterial)))
    assertFalse(isSecretKey(PGPKey("not a key".encodeToByteArray())))
  }

  @Test
  fun firstUserIdIsReturned() {
    val key = PGPKey(getArmoredSecretKeyWithMultipleIdentities())
    assertNotNull(tryGetEmail(key))
    assertEquals(null, tryGetEmail(PGPKey("not a key".encodeToByteArray())))
  }
```

Run: `./gradlew :crypto:pgpainless:test --tests app.passwordstore.crypto.KeyUtilsTest`
Expected: compilation FAILS with `Unresolved reference 'isSecretKey'`.

(No fixture has a key without user ID, so the `next()` fix below is covered by review, not by a
test: `firstOrNull()` cannot throw where `next()` did.)

- [ ] **Step 6: The key checks**

In `crypto/pgpainless/src/main/kotlin/app/passwordstore/crypto/KeyUtils.kt`, add the import

```kotlin
import org.bouncycastle.openpgp.PGPSecretKeyRing
```

replace

```kotlin
  public fun tryGetEmail(key: PGPKey): UserId? {
    val keyRing = tryParseKeyring(key) ?: return null
    return UserId(keyRing.publicKey.userIDs.next())
  }
```

with

```kotlin
  public fun tryGetEmail(key: PGPKey): UserId? {
    val keyRing = tryParseKeyring(key) ?: return null
    // A key may carry no user ID at all: next() threw NoSuchElementException there.
    return keyRing.publicKey.userIDs.asSequence().firstOrNull()?.let(::UserId)
  }

  /** True when [key] parses as a secret key ring, the only kind that can decrypt. */
  public fun isSecretKey(key: PGPKey): Boolean = tryParseKeyring(key) is PGPSecretKeyRing
```

(The app has no direct dependency on Bouncy Castle's OpenPGP classes, so the secret-key test
lives here, next to `tryParseKeyring`.)

Run: `./gradlew :crypto:pgpainless:test --tests app.passwordstore.crypto.KeyUtilsTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Checks and commit**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:testDebugUnitTest --tests 'app.passwordstore.ui.setup.*' \
  --tests 'app.passwordstore.util.git.sshj.*' :crypto:pgpainless:test :app:assembleDebug \
  :app:lintDebug
git add app/src/main/java/app/passwordstore/ui/setup/SetupModel.kt \
  app/src/test/java/app/passwordstore/ui/setup/SetupModelTest.kt \
  app/src/main/java/app/passwordstore/util/git/sshj/SshKey.kt \
  app/src/test/java/app/passwordstore/util/git/sshj/SshKeyFileTest.kt \
  crypto/pgpainless/src/main/kotlin/app/passwordstore/crypto/KeyUtils.kt \
  crypto/pgpainless/src/test/kotlin/app/passwordstore/crypto/KeyUtilsTest.kt
git commit -m "feat(app): add the setup model and stricter key checks

URL checks and fixes, authentication modes per URL, the clone target,
the store's recipients, a size- and symlink-checked .gpg-id read and
the .gpg-id text for a new store (uppercase long key IDs), as pure
functions for the Compose setup screens. The SSH key import now also
requires a format sshj recognizes (an OpenPGP private key block
matched the old marker check and was imported as the SSH key) and no
longer crashes on an empty size cursor. KeyUtils tells secret keys
apart and no longer throws on a key without user ID.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: `BUILD SUCCESSFUL`, one signed commit.

---

### Task 2: Setup ViewModels

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/setup/GitServerViewModel.kt`
- Create: `app/src/main/java/app/passwordstore/ui/setup/SshKeyViewModel.kt`
- Create: `app/src/main/java/app/passwordstore/ui/setup/PgpKeysViewModel.kt`

- [ ] **Step 1: SSH key ViewModel**

`app/src/main/java/app/passwordstore/ui/setup/SshKeyViewModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.R
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.git.sshj.SshKey
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The key types the generation screen offers, in the old screen's order. */
enum class SshKeyType(@StringRes val label: Int, @StringRes val explanation: Int) {
  Rsa(R.string.ssh_keygen_label_rsa, R.string.ssh_keygen_explanation_rsa),
  Ecdsa(R.string.ssh_keygen_label_ecdsa, R.string.ssh_keygen_explanation_ecdsa),
  Ed25519(R.string.ssh_keygen_label_ed25519, R.string.ssh_keygen_explanation_ed25519),
}

/** The current SSH key's state, as a string resource. */
@StringRes
fun sshKeySummary(): Int =
  when {
    !SshKey.exists -> R.string.ssh_key_state_none
    SshKey.canShowSshPublicKey -> R.string.ssh_key_state_generated
    else -> R.string.ssh_key_state_imported
  }

/**
 * The SSH key screens' work: import a key file, generate a key. Both run in [viewModelScope] (no
 * activity needed), so their outcome survives a recreation; the screens show it from [generated],
 * [imported] and [failure]. [version] changes with the key, so screens re-read [SshKey].
 */
@HiltViewModel
class SshKeyViewModel @Inject constructor(private val dispatcherProvider: DispatcherProvider) :
  ViewModel() {

  var type by mutableStateOf(SshKeyType.Ecdsa)

  /** The "protect with screen lock" switch; only effective when the device is secure. */
  var protect by mutableStateOf(true)

  var busy by mutableStateOf(false)
    private set

  var version by mutableIntStateOf(0)
    private set

  /** Set after a successful import, until the screen showed its toast. */
  var imported by mutableStateOf(false)
    private set

  /** The new public key after a generation. */
  var generated by mutableStateOf<String?>(null)
    private set

  /** Why the last import or generation failed. */
  var failure by mutableStateOf<String?>(null)
    private set

  /** Imports the private key at [uri]; [SshKey.import] reads it and keeps the old key on refusal. */
  fun import(uri: Uri) {
    if (busy) return
    busy = true
    viewModelScope.launch {
      try {
        withContext(dispatcherProvider.io()) { SshKey.import(uri) }
        imported = true
        version++
      } catch (e: IOException) {
        failure = e.message.orEmpty()
      } catch (e: IllegalArgumentException) {
        failure = e.message.orEmpty()
      } catch (e: SecurityException) {
        failure = e.message.orEmpty()
      } finally {
        busy = false
      }
    }
  }

  /** Generates a key of [type], replacing the current one; the prompt already ran if needed. */
  fun generate(requireAuthentication: Boolean) {
    if (busy) return
    busy = true
    val type = type
    viewModelScope.launch {
      try {
        generated =
          withContext(dispatcherProvider.io()) {
            when (type) {
              SshKeyType.Rsa ->
                SshKey.generateKeystoreNativeKey(SshKey.Algorithm.Rsa, requireAuthentication)
              SshKeyType.Ecdsa ->
                SshKey.generateKeystoreNativeKey(SshKey.Algorithm.Ecdsa, requireAuthentication)
              SshKeyType.Ed25519 -> SshKey.generateKeystoreWrappedEd25519Key(requireAuthentication)
            }
            SshKey.sshPublicKey.orEmpty()
          }
        version++
      } catch (e: GeneralSecurityException) {
        failure = e.message.orEmpty()
      } catch (e: ProviderException) {
        failure = e.message.orEmpty()
      } catch (e: IOException) {
        failure = e.message.orEmpty()
      } finally {
        busy = false
      }
    }
  }

  /** Reports a failure found before generating (the screen lock prompt). */
  fun fail(message: String) {
    failure = message
  }

  fun dismissFailure() {
    failure = null
  }

  fun announced() {
    imported = false
  }
}
```

- [ ] **Step 2: Git server ViewModel**

`app/src/main/java/app/passwordstore/ui/setup/GitServerViewModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.R
import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.ui.list.deleteWithoutFollowingLinks
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.settings.GitSettings
import com.github.michaelbull.result.get
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The git server screen's state: URL and mode being edited, the clone's [progress] and outcome
 * ([cloned]), the host key and SSH key state. The clone itself runs in the destination (it needs
 * the activity); this ViewModel keeps what must survive a recreation.
 */
@HiltViewModel
class GitServerViewModel
@Inject
constructor(
  private val gitSettings: GitSettings,
  private val keyManager: PGPKeyManager,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var url by mutableStateOf(gitSettings.url.orEmpty())
    private set

  var authMode by mutableStateOf(fitAuthMode(gitSettings.url.orEmpty(), gitSettings.authMode))

  /** What a running clone does, as a string resource; null when idle. */
  var progress by mutableStateOf<Int?>(null)

  /** Null until a clone succeeded; then whether the store still needs a PGP key imported. */
  var cloned by mutableStateOf<Boolean?>(null)
    private set

  var hasHostKey by mutableStateOf(false)
    private set

  var sshKey by mutableIntStateOf(R.string.ssh_key_state_none)
    private set

  init {
    refresh()
  }

  /** Re-reads what other screens change: the saved host key and the SSH key. */
  fun refresh() {
    hasHostKey = gitSettings.hasSavedHostKey()
    sshKey = sshKeySummary()
  }

  fun changeUrl(value: String) {
    url = value
    if (value.isNotEmpty()) authMode = fitAuthMode(value.trim(), authMode)
  }

  fun clearHostKey() {
    gitSettings.clearSavedHostKey()
    hasHostKey = false
  }

  /** Validates and stores URL and mode (unchanged rules in [GitSettings]). */
  fun save(): GitSettings.UpdateConnectionSettingsResult =
    gitSettings.updateConnectionSettingsIfValid(newAuthMode = authMode, newUrl = url.trim())

  /**
   * Empties the store folder at [root] for a clone, unless it is empty already. Never follows a
   * symlink: `deleteRecursively()` would, and a `store/link -> ../keys` link would take the PGP
   * keys with it.
   */
  suspend fun clearStore(root: File) {
    if (withContext(dispatcherProvider.io()) { cloneTarget(root) } == CloneTarget.Empty) return
    progress = R.string.delete_directory_progress_text
    withContext(dispatcherProvider.io()) { root.deleteWithoutFollowingLinks() }
    progress = R.string.git_clone_progress
  }

  /** Set while [finishClone]'s key check runs: the clone's [progress] stays up until it is done. */
  private var checking = false

  /**
   * After a successful clone into [root], checks in [viewModelScope] whether its key is missing and
   * records the answer in [cloned]: no recipient of the root `.gpg-id` is an imported secret key
   * (without recipients: no secret key is imported at all). A public key alone cannot decrypt.
   * In [viewModelScope], so a recreation during the check cannot lose the answer.
   */
  fun finishClone(root: File) {
    checking = true
    viewModelScope.launch {
      try {
        cloned =
          withContext(dispatcherProvider.io()) {
            val ids = readGpgId(root)?.let(::storeRecipients).orEmpty()
            if (ids.isEmpty()) keyManager.getAllKeys().get().orEmpty().none(KeyUtils::isSecretKey)
            else ids.none { id -> keyManager.getKeyById(id).get()?.let(KeyUtils::isSecretKey) == true }
          }
      } finally {
        checking = false
        progress = null
      }
    }
  }

  /** The clone's coroutine ended (done, failed or cancelled): free the screen, re-read state. */
  fun cloneEnded() {
    if (!checking) progress = null
    refresh()
  }
}
```

- [ ] **Step 3: PGP keys ViewModel**

`app/src/main/java/app/passwordstore/ui/setup/PgpKeysViewModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.crypto.KeyUtils
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.crypto.PGPKey
import app.passwordstore.crypto.PGPKeyManager
import app.passwordstore.crypto.errors.KeyAlreadyExistsException
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.util.coroutines.DispatcherProvider
import com.github.michaelbull.result.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.api.errors.JGitInternalException

/** One imported key: its first user ID, when it has one, and its long key ID. */
@Immutable data class KeyRow(val userId: String?, val keyId: PGPIdentifier.KeyId)

/** The outcome of a PGP key import, shown until dismissed. */
sealed interface KeyImport {
  data class Imported(val keyId: String) : KeyImport

  /** A key with the same ID is imported already: the screen asks whether to replace it. */
  data object Exists : KeyImport

  data class Failed(val message: String?) : KeyImport
}

/**
 * The PGP keys screen's state and work: the imported [keys], import with the replace question,
 * deletion by key ID, and the creation of a new local store for a chosen key. Holds no Context;
 * calls take one and use the application context.
 */
@HiltViewModel
class PgpKeysViewModel
@Inject
constructor(
  private val keyManager: PGPKeyManager,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var keys: ImmutableList<KeyRow> by mutableStateOf(persistentListOf())
    private set

  /** Set while an import or a store creation runs: further taps wait. */
  var busy by mutableStateOf(false)

  var importResult by mutableStateOf<KeyImport?>(null)
    private set

  /** Set once a new store is created and committed: the screen moves on. */
  var done by mutableStateOf(false)
    private set

  /** The root `.gpg-id` recipients, for the `Need` screen; see [loadRecipients]. */
  var recipients: ImmutableList<String> by mutableStateOf(persistentListOf())
    private set

  /**
   * The picked file while the replace question is open. Memory only, zeroed as soon as it is not
   * needed ([dropPending]): it may be a private key.
   */
  private var pending: ByteArray? = null

  init {
    updateKeySet()
  }

  /** Reads the recipients of the store at [root] off the main thread. */
  fun loadRecipients(root: File) {
    viewModelScope.launch {
      recipients =
        withContext(dispatcherProvider.io()) {
            readGpgId(root)?.let(::storeRecipients).orEmpty().map(PGPIdentifier::toString)
          }
          .toPersistentList()
    }
  }

  private fun dropPending() {
    pending?.fill(0)
    pending = null
  }

  fun updateKeySet() {
    viewModelScope.launch {
      keyManager.getAllKeys().onSuccess { all ->
        keys = withContext(dispatcherProvider.default()) { all.mapNotNull(::row) }.toPersistentList()
      }
    }
  }

  private fun row(key: PGPKey): KeyRow? {
    val id = KeyUtils.tryGetId(key) ?: return null
    return KeyRow(KeyUtils.tryGetEmail(key)?.email, id)
  }

  fun deleteKey(row: KeyRow) {
    viewModelScope.launch {
      keyManager.removeKey(row.keyId)
      updateKeySet()
    }
  }

  /** Reads the key file at [uri] and imports it. */
  fun import(uri: Uri, context: Context) {
    if (busy) return
    busy = true
    val resolver = context.applicationContext.contentResolver
    viewModelScope.launch {
      try {
        val bytes =
          withContext(dispatcherProvider.io()) {
            resolver.openInputStream(uri)?.use { it.readBytes() }
          }
        if (bytes == null) importResult = KeyImport.Failed(null) else add(bytes, replace = false)
      } catch (e: IOException) {
        importResult = KeyImport.Failed(e.message)
      } catch (e: SecurityException) {
        importResult = KeyImport.Failed(e.message)
      } finally {
        busy = false
      }
    }
  }

  /** Imports the key the replace question was about, over the one with its ID. */
  fun replace() {
    val bytes = pending ?: return
    pending = null
    importResult = null
    busy = true
    viewModelScope.launch {
      try {
        add(bytes, replace = true)
      } finally {
        busy = false
      }
    }
  }

  fun dismissImport() {
    dropPending()
    importResult = null
  }

  /** Imports [bytes]; they are zeroed afterwards unless the replace question keeps them. */
  private suspend fun add(bytes: ByteArray, replace: Boolean) {
    val result = keyManager.addKey(PGPKey(bytes), replace)
    if (!replace && result.isErr && result.error is KeyAlreadyExistsException) {
      pending = bytes
      importResult = KeyImport.Exists
      return
    }
    // The ID is read before the bytes are zeroed: the returned key shares them.
    importResult =
      if (result.isOk) {
        val id = withContext(dispatcherProvider.default()) { KeyUtils.tryGetId(result.value) }
        KeyImport.Imported(id?.toString().orEmpty())
      } else KeyImport.Failed(result.error.message)
    bytes.fill(0)
    if (result.isOk) updateKeySet()
  }

  /**
   * Creates the local store at [root] for [row]: a git repository and a `.gpg-id` naming the key by
   * its uppercase long key ID ([gpgIdText]). Returns null, or why it failed.
   */
  suspend fun createStore(root: File, row: KeyRow): String? =
    withContext(dispatcherProvider.io()) {
      try {
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        PasswordRepository.createRepository(root)
        File(root, ".gpg-id").writeText(gpgIdText(listOf(row.keyId)))
        null
      } catch (e: IOException) {
        failed(root, e)
      } catch (e: GitAPIException) {
        failed(root, e)
      } catch (e: JGitInternalException) {
        failed(root, e)
      } catch (e: IllegalStateException) {
        failed(root, e)
      }
    }

  private fun failed(root: File, e: Exception): String {
    logcat(ERROR) { e.asLog() }
    // Only removes the folder while it is still empty, as the old onboarding did.
    root.delete()
    return e.message.orEmpty()
  }

  fun markDone() {
    done = true
  }

  override fun onCleared() {
    dropPending()
  }
}
```

- [ ] **Step 4: Strings the ViewModels use**

`sshKeySummary` and `clearStore` refer to strings added in Task 3. Add these four now, so this
task builds on its own (Task 3 adds the rest next to them).

In `app/src/main/res/values/strings.xml`, before `</resources>`:

```xml

  <!-- Compose setup -->
  <string name="ssh_key_state_none">No SSH key yet</string>
  <string name="ssh_key_state_imported">Imported private key</string>
  <string name="ssh_key_state_generated">Generated on this device</string>
  <string name="git_clone_progress">Cloning…</string>
```

In `app/src/main/res/values-it/strings.xml`, before `</resources>`:

```xml

  <!-- Compose setup -->
  <string name="ssh_key_state_none">Ancora nessuna chiave SSH</string>
  <string name="ssh_key_state_imported">Chiave privata importata</string>
  <string name="ssh_key_state_generated">Generata su questo dispositivo</string>
  <string name="git_clone_progress">Clonazione…</string>
```

- [ ] **Step 5: Checks and commit**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug :app:lintDebug
git add app/src/main/java/app/passwordstore/ui/setup/GitServerViewModel.kt \
  app/src/main/java/app/passwordstore/ui/setup/SshKeyViewModel.kt \
  app/src/main/java/app/passwordstore/ui/setup/PgpKeysViewModel.kt \
  app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat(app): add the setup ViewModels

Git server (URL, mode, clone state, the after-clone key check), SSH
key (import and generation off the main thread, outcome kept across
recreation) and PGP keys (rows with key IDs, import with the replace
question, delete by key ID, creation of a local store).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Strings, welcome and git server screens

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`
- Create: `app/src/main/java/app/passwordstore/ui/setup/WelcomeScreen.kt`
- Create: `app/src/main/java/app/passwordstore/ui/setup/GitServerScreen.kt`

- [ ] **Step 1: Strings**

In `app/src/main/res/values/strings.xml`, after `<string name="git_clone_progress">Cloning…</string>`:

```xml
  <string name="connection_mode_none">None</string>
  <string name="ssh_key_manage">Manage</string>
```

In `app/src/main/res/values-it/strings.xml`, after `<string name="git_clone_progress">Clonazione…</string>`:

```xml
  <string name="connection_mode_none">Nessuna</string>
  <string name="ssh_key_manage">Gestisci</string>
```

Each task adds only the strings its own code uses, so its `:app:lintDebug` finds no unused
resource (`UnusedResources` is an error: lint runs with `warningsAsErrors`). Task 4 adds one
more, Task 5 three; everything else the setup screens show reuses existing strings in both
languages (titles, buttons, dialogs of the old screens).

- [ ] **Step 2: Welcome screen and the shared dialog**

`app/src/main/java/app/passwordstore/ui/setup/WelcomeScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingLargest

/** First start without a store: clone one, create a local one, or open the settings. */
@Composable
fun WelcomeScreen(
  onClone: () -> Unit,
  onCreate: () -> Unit,
  onSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(modifier = modifier) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(SpacingLargest),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Image(
        painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(144.dp),
      )
      Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
      Text(
        stringResource(R.string.select_repo_type_text),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
      )
      Button(onClick = onClone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.clone_remote_repo))
      }
      OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.create_local_repo))
      }
      TextButton(onClick = onSettings) { Text(stringResource(R.string.action_settings)) }
    }
  }
}

/**
 * A plain dialog for the setup screens: optional [title], [text], [confirm] and, when [dismiss] is
 * given, a second button running [onDismissButton] (by default the same as dismissing).
 */
@Composable
internal fun SetupDialog(
  text: String,
  confirm: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  title: String? = null,
  dismiss: String? = null,
  onDismissButton: () -> Unit = onDismiss,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = if (title != null) { { Text(title) } } else null,
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton =
      if (dismiss != null) {
        { TextButton(onClick = onDismissButton) { Text(dismiss) } }
      } else null,
  )
}
```

- [ ] **Step 3: Git server screen and destination**

`app/src/main/java/app/passwordstore/ui/setup/GitServerScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.git.base.BaseGitActivity.GitOp
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.settings.AuthMode
import app.passwordstore.util.settings.GitSettings.UpdateConnectionSettingsResult
import app.passwordstore.util.settings.Protocol
import com.github.michaelbull.result.fold
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Server URL and authentication mode; the SSH key's state ([sshKey], a string resource) with
 * Manage while the mode is SSH key; "Clear saved host key" when one is saved. [progress] (a
 * string resource) shows while a clone runs, and disables everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitServerScreen(
  clone: Boolean,
  url: String,
  authMode: AuthMode,
  @StringRes sshKey: Int,
  hasHostKey: Boolean,
  @StringRes progress: Int?,
  onUrlChange: (String) -> Unit,
  onAuthModeChange: (AuthMode) -> Unit,
  onSshKey: () -> Unit,
  onClearHostKey: () -> Unit,
  onSubmit: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val idle = progress == null
  val modes = remember(url) { authModes(url.trim()) }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_activity_git_clone)) },
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
      OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = idle,
        label = { Text(stringResource(R.string.server_url)) },
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
          ),
      )
      Text(stringResource(R.string.connection_mode), style = MaterialTheme.typography.labelLarge)
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
          SegmentedButton(
            selected = mode == authMode,
            onClick = { onAuthModeChange(mode) },
            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
            enabled = idle,
          ) {
            Text(stringResource(mode.label()))
          }
        }
      }
      if (authMode == AuthMode.SshKey)
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
              stringResource(R.string.connection_mode_ssh_key),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(sshKey))
          }
          TextButton(onClick = onSshKey, enabled = idle) {
            Text(stringResource(R.string.ssh_key_manage))
          }
        }
      if (hasHostKey)
        OutlinedButton(onClick = onClearHostKey, enabled = idle) {
          Text(stringResource(R.string.clear_saved_host_key))
        }
      if (progress != null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(progress))
      }
      Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth(), enabled = idle) {
        Text(stringResource(if (clone) R.string.clone_button else R.string.crypto_save))
      }
    }
  }
}

@StringRes
private fun AuthMode.label(): Int =
  when (this) {
    AuthMode.SshKey -> R.string.connection_mode_ssh_key
    AuthMode.Password -> R.string.connection_mode_basic_authentication
    AuthMode.None -> R.string.connection_mode_none
  }

private sealed interface ServerDialog {
  data class Url(val problem: UrlProblem) : ServerDialog

  data class Username(val protocol: Protocol) : ServerDialog

  data object DeleteStore : ServerDialog
}

/**
 * The git server screen: wires [GitServerScreen] to the [viewModel], the URL checks, the save and,
 * with [clone], the clone into the store folder. [onCloned] gets whether the store still needs a
 * PGP key. A failed clone stays here, so URL or SSH key can be fixed and the clone retried.
 */
@Composable
fun GitServerDestination(
  activity: MainActivity,
  clone: Boolean,
  onClose: () -> Unit,
  onSshKey: () -> Unit,
  onCloned: (keyNeeded: Boolean) -> Unit,
  modifier: Modifier = Modifier,
  viewModel: GitServerViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  val keyboard = LocalSoftwareKeyboardController.current
  var dialog by remember { mutableStateOf<ServerDialog?>(null) }

  // Back from the SSH key screen (or from an instance it opened) with another key.
  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }
  // A finished clone moves on from here, also when the screen was recreated in between.
  val cloned = viewModel.cloned
  LaunchedEffect(cloned) { if (cloned != null) onCloned(cloned) }
  // A running clone holds the screen: leaving would cancel it half way.
  BackHandler(enabled = viewModel.progress != null) {}

  fun say(message: String) {
    activity.snackbar(message = message, length = Snackbar.LENGTH_LONG)
  }

  // ponytail: a theme or locale change cancels the clone with the activity's scope, like every
  // git operation of the app; the finally block frees the screen, and a new clone first offers to
  // delete what was left. Once the clone succeeded, the key check runs in the ViewModel's scope
  // (finishClone), so a recreation from then on loses nothing.
  fun startClone() {
    viewModel.progress = R.string.git_clone_progress
    activity.lifecycleScope.launch {
      try {
        viewModel.clearStore(root)
        activity
          .launchGitOperation(GitOp.CLONE)
          .fold(
            success = { viewModel.finishClone(root) },
            failure = { activity.promptOnErrorHandler(it) },
          )
      } finally {
        viewModel.cloneEnded()
      }
    }
  }

  fun submit() {
    if (viewModel.progress != null) return
    keyboard?.hide()
    val problem = urlProblem(viewModel.url.trim())
    if (problem != null) {
      dialog = ServerDialog.Url(problem)
      return
    }
    when (val result = viewModel.save()) {
      UpdateConnectionSettingsResult.FailedToParseUrl ->
        say(activity.getString(R.string.git_server_config_save_error))
      is UpdateConnectionSettingsResult.MissingUsername ->
        dialog = ServerDialog.Username(result.newProtocol)
      is UpdateConnectionSettingsResult.AuthModeMismatch ->
        say(
          activity.getString(
            R.string.git_server_config_save_auth_mode_mismatch,
            result.newProtocol,
            result.validModes.joinToString(", "),
          )
        )
      UpdateConnectionSettingsResult.Valid ->
        if (!clone) {
          Toast.makeText(activity, R.string.git_server_config_save_success, Toast.LENGTH_SHORT)
            .show()
          onClose()
        } else {
          if (PasswordRepository.repository == null) PasswordRepository.initialize()
          if (cloneTarget(root) == CloneTarget.Occupied) dialog = ServerDialog.DeleteStore
          else startClone()
        }
    }
  }

  Box(modifier) {
    GitServerScreen(
      clone = clone,
      url = viewModel.url,
      authMode = viewModel.authMode,
      sshKey = viewModel.sshKey,
      hasHostKey = viewModel.hasHostKey,
      progress = viewModel.progress,
      onUrlChange = viewModel::changeUrl,
      onAuthModeChange = { viewModel.authMode = it },
      onSshKey = onSshKey,
      onClearHostKey = {
        viewModel.clearHostKey()
        say(activity.getString(R.string.clear_saved_host_key_success))
      },
      onSubmit = ::submit,
      onBack = { if (viewModel.progress == null) onClose() },
    )
    when (val current = dialog) {
      null -> Unit
      is ServerDialog.Url -> {
        val (title, text) =
          when (current.problem) {
            UrlProblem.HttpsPort ->
              R.string.https_scheme_with_port_title to R.string.https_scheme_with_port_message
            UrlProblem.SshScheme ->
              R.string.ssh_scheme_needed_title to R.string.ssh_scheme_needed_message
            UrlProblem.GitScheme ->
              R.string.git_scheme_disallowed_title to R.string.git_scheme_disallowed_message
          }
        val fixed = fixUrl(viewModel.url.trim(), current.problem)
        SetupDialog(
          title = stringResource(title),
          text = stringResource(text),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = {
            dialog = null
            fixed?.let(viewModel::changeUrl)
          },
          onDismiss = { dialog = null },
          dismiss = if (fixed != null) stringResource(R.string.dialog_cancel) else null,
        )
      }
      is ServerDialog.Username ->
        SetupDialog(
          title = stringResource(R.string.ssh_scheme_needed_title),
          text =
            stringResource(
              if (current.protocol == Protocol.Https)
                R.string.git_server_config_save_missing_username_https
              else R.string.git_server_config_save_missing_username_ssh
            ),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = { dialog = null },
          onDismiss = { dialog = null },
        )
      ServerDialog.DeleteStore ->
        SetupDialog(
          title = stringResource(R.string.dialog_delete_title),
          text = stringResource(R.string.dialog_delete_msg, root.toString()),
          confirm = stringResource(R.string.dialog_delete),
          onConfirm = {
            dialog = null
            startClone()
          },
          onDismiss = { dialog = null },
          dismiss = stringResource(R.string.dialog_do_not_delete),
        )
    }
  }
}
```

- [ ] **Step 4: Checks and commit**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug :app:lintDebug
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml \
  app/src/main/java/app/passwordstore/ui/setup/WelcomeScreen.kt \
  app/src/main/java/app/passwordstore/ui/setup/GitServerScreen.kt
git commit -m "feat(app): add the Compose welcome and git server screens

The welcome screen merges the old welcome and repository type screens.
The git server screen serves clone and edit: the old URL checks as
dialogs, authentication mode buttons, the SSH key's state with Manage,
the host key reset, and a clone that keeps the screen on failure.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: `BUILD SUCCESSFUL`. If `SingleChoiceSegmentedButtonRow` or `SegmentedButton` is
reported as not experimental and the opt-in as needless, keep the opt-in for `TopAppBar`; if the
compiler reports `SegmentedButton`'s trailing lambda as the wrong slot, name it `label = { ... }`.

---

### Task 4: SSH key and key generation screens

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`
- Create: `app/src/main/java/app/passwordstore/ui/setup/SshKeyScreen.kt`

- [ ] **Step 1: String**

In `app/src/main/res/values/strings.xml`, after `<string name="ssh_key_manage">Manage</string>`:

```xml
  <string name="ssh_key_public_hint">Provide this public key to your Git server.</string>
```

In `app/src/main/res/values-it/strings.xml`, after `<string name="ssh_key_manage">Gestisci</string>`:

```xml
  <string name="ssh_key_public_hint">Fornisci questa chiave pubblica al tuo server Git.</string>
```

- [ ] **Step 2: The screens and destinations**

`app/src/main/java/app/passwordstore/ui/setup/SshKeyScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.passwordstore.R
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.crypto.copyTextToClipboard
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.extensions.keyguardManager
import app.passwordstore.util.git.sshj.SshKey

/**
 * The SSH key: its state ([summary], a string resource), the public key with copy and share when
 * the device generated it, import and generation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyScreen(
  @StringRes summary: Int,
  publicKey: String?,
  busy: Boolean,
  onImport: () -> Unit,
  onGenerate: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.ssh_keygen_existing_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      GroupedSurface {
        Text(
          stringResource(summary),
          Modifier.padding(SpacingLarge),
          style = MaterialTheme.typography.bodyLarge,
        )
      }
      if (publicKey != null)
        GroupedSurface {
          Column(
            Modifier.padding(SpacingLarge),
            verticalArrangement = Arrangement.spacedBy(SpacingMedium),
          ) {
            Text(
              stringResource(R.string.your_public_key),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
              Text(
                publicKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
            }
            Text(
              stringResource(R.string.ssh_key_public_hint),
              style = MaterialTheme.typography.bodySmall,
            )
            Row {
              TextButton(onClick = onCopy) { Text(stringResource(R.string.copy_label)) }
              TextButton(onClick = onShare) { Text(stringResource(R.string.ssh_keygen_share)) }
            }
          }
        }
      Column(
        Modifier.padding(SpacingLarge),
        verticalArrangement = Arrangement.spacedBy(SpacingMedium),
      ) {
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.pref_import_ssh_key_title))
        }
        OutlinedButton(onClick = onGenerate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.pref_ssh_keygen_title))
        }
      }
    }
  }
}

/** A chooser sharing [publicKey] as text. */
private fun shareKey(publicKey: String): Intent =
  Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicKey),
    null,
  )

/**
 * The SSH key screen: wires [SshKeyScreen] to the [viewModel], the document picker and the replace
 * confirmation. [onGenerate] opens the generation screen.
 */
@Composable
fun SshKeyDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  onGenerate: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SshKeyViewModel = hiltViewModel(),
) {
  var resumes by remember { mutableIntStateOf(0) }
  // Back from the generation screen (or another instance) with a new key: read it again.
  LifecycleResumeEffect(Unit) {
    resumes++
    onPauseOrDispose {}
  }
  val version = viewModel.version
  val summary = remember(resumes, version) { sshKeySummary() }
  val publicKey =
    remember(resumes, version) { if (SshKey.canShowSshPublicKey) SshKey.sshPublicKey else null }
  var replace by remember { mutableStateOf(false) }
  val picker =
    rememberLauncherForActivityResult(OpenDocument()) { uri -> if (uri != null) viewModel.import(uri) }

  LaunchedEffect(viewModel.imported) {
    if (viewModel.imported) {
      Toast.makeText(activity, R.string.ssh_key_success_dialog_title, Toast.LENGTH_LONG).show()
      viewModel.announced()
    }
  }
  BackHandler(enabled = viewModel.busy) {}

  Box(modifier) {
    SshKeyScreen(
      summary = summary,
      publicKey = publicKey,
      busy = viewModel.busy,
      onImport = { if (SshKey.exists) replace = true else picker.launch(arrayOf("*/*")) },
      onGenerate = onGenerate,
      onCopy = { publicKey?.let { activity.copyTextToClipboard(it) } },
      onShare = { publicKey?.let { activity.startActivity(shareKey(it)) } },
      onBack = { if (!viewModel.busy) onClose() },
    )
    if (replace)
      SetupDialog(
        title = stringResource(R.string.ssh_keygen_existing_title),
        text = stringResource(R.string.ssh_keygen_existing_message),
        confirm = stringResource(R.string.ssh_keygen_existing_replace),
        onConfirm = {
          replace = false
          picker.launch(arrayOf("*/*"))
        },
        onDismiss = { replace = false },
        dismiss = stringResource(R.string.ssh_keygen_existing_keep),
      )
    viewModel.failure?.let { message ->
      SetupDialog(
        title = stringResource(R.string.ssh_key_error_dialog_title),
        text = message,
        confirm = stringResource(R.string.dialog_ok),
        onConfirm = viewModel::dismissFailure,
        onDismiss = viewModel::dismissFailure,
      )
    }
  }
}

/** Key type with its explanation, the screen lock switch and Generate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyGenScreen(
  type: SshKeyType,
  protect: Boolean,
  canProtect: Boolean,
  busy: Boolean,
  onTypeChange: (SshKeyType) -> Unit,
  onProtectChange: (Boolean) -> Unit,
  onGenerate: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val types = SshKeyType.entries
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.pref_ssh_keygen_title)) },
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
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        types.forEachIndexed { index, entry ->
          SegmentedButton(
            selected = entry == type,
            onClick = { onTypeChange(entry) },
            shape = SegmentedButtonDefaults.itemShape(index, types.size),
            enabled = !busy,
          ) {
            Text(stringResource(entry.label))
          }
        }
      }
      // ponytail: stringResource drops the explanations' bold title; plain text reads fine.
      Text(stringResource(type.explanation), style = MaterialTheme.typography.bodyMedium)
      Row(
        Modifier.fillMaxWidth()
          .toggleable(
            value = protect,
            enabled = canProtect && !busy,
            role = Role.Switch,
            onValueChange = onProtectChange,
          ),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(stringResource(R.string.ssh_keygen_require_authentication), Modifier.weight(1f))
        Switch(checked = protect, onCheckedChange = null, enabled = canProtect && !busy)
      }
      Button(onClick = onGenerate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(
          stringResource(
            if (busy) R.string.ssh_key_gen_generating_progress else R.string.ssh_keygen_generate
          )
        )
      }
    }
  }
}

/**
 * The key generation screen: replace confirmation, screen lock prompt when protected, then the
 * generation in the [viewModel] and the public key (or error) dialog; both dialogs close it.
 */
@Composable
fun SshKeyGenDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: SshKeyViewModel = hiltViewModel(),
) {
  val canProtect = remember { activity.keyguardManager.isDeviceSecure }
  var replace by remember { mutableStateOf(false) }
  // While the screen lock prompt is up. Plain remember: a recreation drops the prompt's callback,
  // and the screen must not stay disabled for it.
  var asking by remember { mutableStateOf(false) }

  fun generate() {
    if (!(canProtect && viewModel.protect)) return viewModel.generate(requireAuthentication = false)
    asking = true
    BiometricAuthenticator.authenticate(activity, R.string.biometric_prompt_title_ssh_keygen) {
      result ->
      // Failed attempts are handled by the prompt itself.
      if (result is BiometricAuthenticator.Result.Retry) return@authenticate
      asking = false
      if (result is BiometricAuthenticator.Result.Success)
        viewModel.generate(requireAuthentication = true)
      else viewModel.fail(activity.getString(R.string.biometric_auth_generic_failure))
    }
  }

  BackHandler(enabled = viewModel.busy) {}

  Box(modifier) {
    SshKeyGenScreen(
      type = viewModel.type,
      protect = canProtect && viewModel.protect,
      canProtect = canProtect,
      busy = viewModel.busy || asking,
      onTypeChange = { viewModel.type = it },
      onProtectChange = { viewModel.protect = it },
      onGenerate = { if (SshKey.exists) replace = true else generate() },
      onBack = { if (!viewModel.busy) onClose() },
    )
    if (replace)
      SetupDialog(
        title = stringResource(R.string.ssh_keygen_existing_title),
        text = stringResource(R.string.ssh_keygen_existing_message),
        confirm = stringResource(R.string.ssh_keygen_existing_replace),
        onConfirm = {
          replace = false
          generate()
        },
        onDismiss = { replace = false },
        dismiss = stringResource(R.string.ssh_keygen_existing_keep),
        onDismissButton = onClose,
      )
    viewModel.generated?.let { key ->
      SetupDialog(
        title = stringResource(R.string.your_public_key),
        text = stringResource(R.string.ssh_keygen_message, key),
        confirm = stringResource(R.string.ssh_keygen_share),
        onConfirm = {
          activity.startActivity(shareKey(key))
          onClose()
        },
        onDismiss = onClose,
        dismiss = stringResource(R.string.ssh_keygen_later),
      )
    }
    viewModel.failure?.let { message ->
      SetupDialog(
        title = stringResource(R.string.error_generate_ssh_key),
        text = stringResource(R.string.ssh_key_error_dialog_text) + message,
        confirm = stringResource(R.string.dialog_ok),
        onConfirm = onClose,
        onDismiss = onClose,
      )
    }
  }
}
```

- [ ] **Step 3: Checks and commit**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug :app:lintDebug
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml \
  app/src/main/java/app/passwordstore/ui/setup/SshKeyScreen.kt
git commit -m "feat(app): add the Compose SSH key and key generation screens

The SSH key screen shows the key's state and, for a generated key, the
public key with copy and share, and imports a key file after the old
replace confirmation. The generation screen ports the old one; the
key is generated in the ViewModel after the screen lock prompt.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: `BUILD SUCCESSFUL`.

---

### Task 5: PGP keys screen

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-it/strings.xml`
- Create: `app/src/main/java/app/passwordstore/ui/setup/PgpKeysScreen.kt`

`KeysMode`, the argument of `PgpKeysRoute` (Task 6), is declared in this file, so this task
builds on its own; Task 6 does not declare it again.

- [ ] **Step 1: Strings**

In `app/src/main/res/values/strings.xml`, after
`<string name="ssh_key_public_hint">Provide this public key to your Git server.</string>`:

```xml
  <string name="pgp_key_needed_message">Import the private key this store is encrypted for, so its entries can be read and saved.</string>
  <string name="pgp_key_needed_recipients">Store recipients</string>
  <string name="store_create_failed">Could not create the store: %1$s</string>
```

In `app/src/main/res/values-it/strings.xml`, after
`<string name="ssh_key_public_hint">Fornisci questa chiave pubblica al tuo server Git.</string>`:

```xml
  <string name="pgp_key_needed_message">Importa la chiave privata per cui è cifrato questo archivio, così le sue voci si possono leggere e salvare.</string>
  <string name="pgp_key_needed_recipients">Destinatari dell\'archivio</string>
  <string name="store_create_failed">Impossibile creare l\'archivio: %1$s</string>
```

- [ ] **Step 2: The screen and destination**

`app/src/main/java/app/passwordstore/ui/setup/PgpKeysScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.snackbar
import com.github.michaelbull.result.onFailure
import com.google.android.material.snackbar.Snackbar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What the PGP keys screen is for. */
@Serializable
enum class KeysMode {
  /** Import and delete keys (Settings). */
  Manage,
  /** Pick the key a new local store is encrypted for. */
  Choose,
  /** A key is needed (after a clone, or by an entry): import it, or go on without. */
  Need,
}

/**
 * The imported [keys] with the import FAB. [mode] decides what a row does (select in
 * [KeysMode.Choose], delete in [KeysMode.Manage]) and the guidance shown; [recipients] are the
 * store's `.gpg-id` entries, listed in [KeysMode.Need].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgpKeysScreen(
  mode: KeysMode,
  keys: ImmutableList<KeyRow>,
  recipients: ImmutableList<String>,
  busy: Boolean,
  onImport: () -> Unit,
  onSelect: (KeyRow) -> Unit,
  onDelete: (KeyRow) -> Unit,
  onLater: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var deleting by remember { mutableStateOf<KeyRow?>(null) }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(
              when (mode) {
                KeysMode.Manage -> R.string.activity_label_pgp_key_manager
                KeysMode.Choose -> R.string.gpg_key_select
                KeysMode.Need -> R.string.pref_import_pgp_key_title
              }
            )
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
        actions = {
          if (mode == KeysMode.Need)
            TextButton(onClick = onLater) { Text(stringResource(R.string.ssh_keygen_later)) }
        },
      )
    },
    floatingActionButton = {
      // M3 clears the label's semantics; the FAB takes its description from the icon.
      ExtendedFloatingActionButton(
        text = { Text(stringResource(R.string.pref_import_pgp_key_title)) },
        icon = { Icon(Icons.Filled.Add, stringResource(R.string.pref_import_pgp_key_title)) },
        onClick = { if (!busy) onImport() },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      val message =
        when (mode) {
          KeysMode.Manage -> null
          KeysMode.Choose -> R.string.select_gpg_key_message
          KeysMode.Need -> R.string.pgp_key_needed_message
        }
      if (message != null)
        Text(
          stringResource(message),
          Modifier.padding(SpacingLarge),
          style = MaterialTheme.typography.bodyLarge,
        )
      if (mode == KeysMode.Need && recipients.isNotEmpty())
        GroupedSurface {
          Column(Modifier.padding(SpacingLarge)) {
            Text(
              stringResource(R.string.pgp_key_needed_recipients),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recipients.forEach { Text(it, fontFamily = FontFamily.Monospace) }
          }
        }
      if (keys.isEmpty())
        Text(
          stringResource(R.string.pgp_key_manager_no_keys_guidance),
          Modifier.padding(SpacingLarge),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      else
        GroupedSurface {
          keys.forEach { row ->
            KeyItem(row = row, mode = mode, onSelect = onSelect, onDelete = { deleting = row })
          }
        }
      // Room for the FAB below the last row.
      Spacer(Modifier.height(88.dp))
    }
  }
  deleting?.let { row ->
    SetupDialog(
      title = stringResource(R.string.pgp_key_manager_delete_confirmation_dialog_title),
      text = row.userId ?: row.keyId.toString(),
      confirm = stringResource(R.string.dialog_yes),
      onConfirm = {
        deleting = null
        onDelete(row)
      },
      onDismiss = { deleting = null },
      dismiss = stringResource(R.string.dialog_no),
    )
  }
}

@Composable
private fun KeyItem(
  row: KeyRow,
  mode: KeysMode,
  onSelect: (KeyRow) -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier
      .fillMaxWidth()
      .then(if (mode == KeysMode.Choose) Modifier.clickable { onSelect(row) } else Modifier)
      .padding(horizontal = SpacingLarge, vertical = SpacingMedium),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        row.userId ?: row.keyId.toString(),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (row.userId != null)
        Text(
          row.keyId.toString(),
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (mode == KeysMode.Manage)
      IconButton(onClick = onDelete) {
        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
      }
  }
}

/**
 * The PGP keys screen: wires [PgpKeysScreen] to the [viewModel], the document picker and the
 * import dialogs. [onImported] runs after every successful import; [onDone] ends the flow: after
 * an import, "Later" or back in [KeysMode.Need], after the new store is created in
 * [KeysMode.Choose]. [onClose] leaves the other modes.
 */
@Composable
fun PgpKeysDestination(
  activity: MainActivity,
  mode: KeysMode,
  onClose: () -> Unit,
  onDone: () -> Unit,
  onImported: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: PgpKeysViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  // Read off the main thread, symlink- and size-checked (readGpgId).
  LaunchedEffect(mode) { if (mode == KeysMode.Need) viewModel.loadRecipients(root) }
  // A created store moves on from here, also when the screen was recreated in between.
  val done = viewModel.done
  LaunchedEffect(done) { if (done) onDone() }

  fun leave() {
    if (viewModel.busy) return
    if (mode == KeysMode.Need) onDone() else onClose()
  }
  BackHandler(onBack = ::leave)

  val picker =
    rememberLauncherForActivityResult(OpenDocument()) { uri ->
      if (uri != null) viewModel.import(uri, activity)
    }

  // ponytail: a theme or locale change in the middle cancels the commit; the next commit picks up
  // the new `.gpg-id`, and choosing the key again re-creates nothing that would conflict.
  fun choose(row: KeyRow) {
    if (viewModel.busy) return
    viewModel.busy = true
    activity.lifecycleScope.launch {
      try {
        val error = viewModel.createStore(root, row)
        if (error != null) {
          activity.snackbar(
            message = activity.getString(R.string.store_create_failed, error),
            length = Snackbar.LENGTH_LONG,
          )
        } else {
          // A failed commit is reported like any git error; the store exists either way, and
          // the next commit picks up the `.gpg-id`.
          activity
            .commitChange(
              activity.getString(R.string.git_commit_gpg_id, activity.getString(R.string.app_name))
            )
            .onFailure { activity.promptOnErrorHandler(it) }
          viewModel.markDone()
        }
      } finally {
        viewModel.busy = false
      }
    }
  }

  Box(modifier) {
    PgpKeysScreen(
      mode = mode,
      keys = viewModel.keys,
      recipients = viewModel.recipients,
      busy = viewModel.busy,
      onImport = { picker.launch(arrayOf("*/*")) },
      onSelect = ::choose,
      onDelete = viewModel::deleteKey,
      onLater = onDone,
      onBack = ::leave,
    )
    when (val result = viewModel.importResult) {
      null -> Unit
      is KeyImport.Imported -> {
        fun accept() {
          viewModel.dismissImport()
          onImported()
          if (mode == KeysMode.Need) onDone()
        }
        SetupDialog(
          title = stringResource(R.string.pgp_key_import_succeeded),
          text = stringResource(R.string.pgp_key_import_succeeded_message, result.keyId),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = ::accept,
          onDismiss = ::accept,
        )
      }
      KeyImport.Exists ->
        SetupDialog(
          title = stringResource(R.string.pgp_key_import_failed),
          text = stringResource(R.string.pgp_key_import_failed_replace_message),
          confirm = stringResource(R.string.dialog_yes),
          onConfirm = viewModel::replace,
          onDismiss = viewModel::dismissImport,
          dismiss = stringResource(R.string.dialog_no),
        )
      is KeyImport.Failed ->
        SetupDialog(
          title = stringResource(R.string.pgp_key_import_failed),
          text = result.message.orEmpty(),
          confirm = stringResource(R.string.dialog_ok),
          onConfirm = viewModel::dismissImport,
          onDismiss = viewModel::dismissImport,
        )
    }
  }
}
```

- [ ] **Step 3: Checks and commit**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug :app:lintDebug
git add app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml \
  app/src/main/java/app/passwordstore/ui/setup/PgpKeysScreen.kt
git commit -m "feat(app): add the Compose PGP keys screen

One screen for managing keys, choosing the key of a new local store
and importing the key a store needs. Rows show user ID and key ID;
the import keeps the old success, replace and failure dialogs.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Setup graph and its callers

After this task every caller uses the Compose setup screens; the legacy activities are still
compiled but unreachable (Task 7 deletes them).

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt`
- Modify (replace whole file): `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/util/git/operation/GitOperation.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/settings/RepositorySettings.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/settings/PGPSettings.kt`

- [ ] **Step 1: The setup graph**

`app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt`:

```kotlin
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
  GitServer(GitServerRoute(clone = false)),
  Ssh(SshKeyRoute),
  SshGenerate(SshKeyGenRoute),
  PgpKeys(PgpKeysRoute()),
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
      onSettings = activity::openSettings,
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
```

- [ ] **Step 2: MainActivity**

Replace `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt` with:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.main

import android.content.Context
import android.content.Intent
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
import app.passwordstore.ui.settings.SettingsActivity
import app.passwordstore.ui.setup.GitServerRoute
import app.passwordstore.ui.setup.SetupStart
import app.passwordstore.ui.setup.WelcomeRoute
import app.passwordstore.ui.setup.setupGraph
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
 * after its biometric gate and path check, and by the app itself through [setupIntent]. Extends
 * [BaseGitActivity] (an AppCompatActivity, so also the FragmentActivity BiometricPrompt needs)
 * because the git operations require one; it hosts no fragments.
 */
@AndroidEntryPoint
class MainActivity : BaseGitActivity() {

  @Inject lateinit var shortcutHandler: ShortcutHandler

  /** Composed destinations that show secrets; FLAG_SECURE is on while this is above zero. */
  private var secureHolds = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val filePath = intent.getStringExtra(BasePGPActivity.EXTRA_FILE_PATH)
    val setup =
      intent.getStringExtra(EXTRA_SETUP)?.let { name ->
        SetupStart.entries.firstOrNull { it.name == name }
      }
    // A restored back stack wins over this choice, so a recreation stays where it was.
    val start: Any =
      when {
        filePath != null -> EntryRoute(filePath)
        setup != null -> setup.route
        checkStore() -> ListRoute()
        else -> WelcomeRoute
      }
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
              onClone = {
                if (nav.currentBackStackEntry?.id == entry.id)
                  nav.navigate(GitServerRoute(clone = true))
              },
              onNoStore = {
                // The store is gone (deleted from Settings): setup starts over.
                if (nav.currentBackStackEntry?.id == entry.id)
                  nav.navigate(WelcomeRoute) { popUpTo(nav.graph.id) { inclusive = true } }
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
          setupGraph(this@MainActivity, nav)
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

  /** Re-reads the store; false when there is none yet and setup has to run. */
  fun checkStore(): Boolean {
    PasswordRepository.initialize()
    return sharedPrefs.getBoolean(PreferenceKeys.REPOSITORY_INITIALIZED, false)
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

  companion object {

    private const val EXTRA_SETUP = "SETUP"

    /**
     * Opens one setup screen in an instance of its own, for callers outside the graph (legacy
     * Settings, autofill, git operations) and for those waiting for a result (the entry view's
     * missing key). Leaving that screen finishes the instance; the PGP keys screen sets
     * RESULT_OK once a key was imported.
     */
    // ponytail: a second instance instead of in-graph navigation, until steps 6 and 7 move
    // Settings and autofill into the graph.
    fun setupIntent(context: Context, start: SetupStart): Intent =
      Intent(context, MainActivity::class.java).putExtra(EXTRA_SETUP, start.name)
  }
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
```

(Compared with the current file: the setup imports, the start route, `onClone` and `onNoStore`,
`setupGraph`, `checkStore()` without the onboarding launch, the companion; the
`OnboardingActivity` import is gone.)

- [ ] **Step 3: The list**

In `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`:

Delete the import

```kotlin
import app.passwordstore.ui.git.config.GitServerConfigActivity
```

Replace

```kotlin
/**
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * folder picker, the legacy activities and the store operations. [onOpenFolder] takes a path
 * relative to the root, [onOpenEntry] an entry file; [onNewEntry] opens the editor in this folder.
 */
@Composable
fun ListDestination(
  activity: MainActivity,
  path: String,
  onOpenFolder: (relativePath: String) -> Unit,
  onOpenEntry: (File) -> Unit,
  onNewEntry: () -> Unit,
  modifier: Modifier = Modifier,
```

with

```kotlin
/**
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * folder picker, the legacy activities and the store operations. [onOpenFolder] takes a path
 * relative to the root, [onOpenEntry] an entry file; [onNewEntry] opens the editor in this folder,
 * [onClone] the git server screen for a clone; [onNoStore] runs when the store is gone.
 */
@Composable
fun ListDestination(
  activity: MainActivity,
  path: String,
  onOpenFolder: (relativePath: String) -> Unit,
  onOpenEntry: (File) -> Unit,
  onNewEntry: () -> Unit,
  onClone: () -> Unit,
  onNoStore: () -> Unit,
  modifier: Modifier = Modifier,
```

Replace

```kotlin
    if (activity.checkStore()) refresh()
```

with

```kotlin
    if (activity.checkStore()) refresh() else onNoStore()
```

and replace

```kotlin
          onConfirm = {
            dialog = ListDialog.None
            activity.startActivity(GitServerConfigActivity.createCloneIntent(activity))
          },
```

with

```kotlin
          onConfirm = {
            dialog = ListDialog.None
            onClone()
          },
```

- [ ] **Step 4: The entry view and autofill decryption ask through the setup intent**

In `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt`, replace the import

```kotlin
import app.passwordstore.ui.pgp.PGPKeyImportActivity
```

with

```kotlin
import app.passwordstore.ui.setup.SetupStart
```

and

```kotlin
                keyImport.launch(Intent(activity, PGPKeyImportActivity::class.java))
```

with

```kotlin
                keyImport.launch(MainActivity.setupIntent(activity, SetupStart.PgpImport))
```

(The launcher keeps its contract: `RESULT_OK` unlocks, anything else closes the entry.)

In `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt`, replace the import

```kotlin
import app.passwordstore.ui.pgp.PGPKeyImportActivity
```

with

```kotlin
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupStart
```

and

```kotlin
              keyImportAction.launch(Intent(this@BasePGPActivity, PGPKeyImportActivity::class.java))
```

with

```kotlin
              keyImportAction.launch(
                MainActivity.setupIntent(this@BasePGPActivity, SetupStart.PgpImport)
              )
```

- [ ] **Step 5: GitOperation**

In `app/src/main/java/app/passwordstore/util/git/operation/GitOperation.kt`:

Replace the imports

```kotlin
import app.passwordstore.ui.sshkeygen.SshKeyGenActivity
import app.passwordstore.ui.sshkeygen.SshKeyImportActivity
```

with

```kotlin
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupStart
```

and delete the imports that only `getSshKey` used:

```kotlin
import app.passwordstore.util.extensions.launchActivity
```
```kotlin
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.runCatching
```
```kotlin
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat
```

(Check with `grep -n 'runCatching\|onFailure\|logcat\|asLog\|launchActivity' GitOperation.kt`
after the edits below: only the import lines may have matched.)

Replace

```kotlin
  private fun getSshKey(generateKey: Boolean) {
    runCatching {
        callingActivity.launchActivity(
          if (generateKey) SshKeyGenActivity::class.java else SshKeyImportActivity::class.java
        )
      }
      .onFailure { e -> logcat(ERROR) { e.asLog() } }
  }
```

with

```kotlin
  private fun getSshKey(generateKey: Boolean) {
    callingActivity.startActivity(
      MainActivity.setupIntent(
        callingActivity,
        if (generateKey) SetupStart.SshGenerate else SetupStart.Ssh,
      )
    )
  }
```

Replace

```kotlin
      .setNeutralButton(callingActivity.resources.getString(R.string.dialog_cancel)) { _, _ ->
        // Finish the blank GitActivity so user doesn't have to press back
        callingActivity.finish()
      }
```

with

```kotlin
      // Cancel only closes the dialog: the calling activity is the app's own screen now.
      .setNeutralButton(callingActivity.resources.getString(R.string.dialog_cancel), null)
```

and, in `executeAfterAuthentication`, replace

```kotlin
                Toast.makeText(
                    callingActivity,
                    R.string.biometric_auth_generic_failure,
                    Toast.LENGTH_LONG,
                  )
                  .show()
                callingActivity.finish()
```

with

```kotlin
                Toast.makeText(
                    callingActivity,
                    R.string.biometric_auth_generic_failure,
                    Toast.LENGTH_LONG,
                  )
                  .show()
                return Err(SSHException(DisconnectReason.AUTH_CANCELLED_BY_USER))
```

- [ ] **Step 6: Settings rows**

In `app/src/main/java/app/passwordstore/ui/settings/RepositorySettings.kt`, delete the imports

```kotlin
import android.content.Intent
```
```kotlin
import app.passwordstore.ui.git.config.GitServerConfigActivity
```
```kotlin
import app.passwordstore.ui.sshkeygen.ShowSshKeyFragment
import app.passwordstore.ui.sshkeygen.SshKeyGenActivity
import app.passwordstore.ui.sshkeygen.SshKeyImportActivity
```

add

```kotlin
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupStart
```

and replace, in order:

```kotlin
          activity.launchActivity(GitServerConfigActivity::class.java)
```
with
```kotlin
          activity.startActivity(MainActivity.setupIntent(activity, SetupStart.GitServer))
```

```kotlin
          activity.launchActivity(SshKeyImportActivity::class.java)
```
with
```kotlin
          activity.startActivity(MainActivity.setupIntent(activity, SetupStart.Ssh))
```

```kotlin
          generateSshKey.launch(Intent(activity, SshKeyGenActivity::class.java))
```
with
```kotlin
          generateSshKey.launch(MainActivity.setupIntent(activity, SetupStart.SshGenerate))
```

```kotlin
            ShowSshKeyFragment().show(activity.supportFragmentManager, "public_key")
```
with
```kotlin
            activity.startActivity(MainActivity.setupIntent(activity, SetupStart.Ssh))
```

In `app/src/main/java/app/passwordstore/ui/settings/PGPSettings.kt`, replace the imports

```kotlin
import app.passwordstore.ui.pgp.PGPKeyListActivity
```
```kotlin
import app.passwordstore.util.extensions.launchActivity
```

with

```kotlin
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.setup.SetupStart
```

and

```kotlin
          activity.launchActivity(PGPKeyListActivity::class.java)
```

with

```kotlin
          activity.startActivity(MainActivity.setupIntent(activity, SetupStart.PgpKeys))
```

- [ ] **Step 7: Nothing outside the legacy files uses the legacy screens**

```bash
grep -rnwE 'OnboardingActivity|GitServerConfigActivity|SshKeyImportActivity|SshKeyGenActivity|ShowSshKeyFragment|PGPKeyImportActivity|PGPKeyListActivity' \
  app/src/main/java | grep -vE 'ui/(onboarding|sshkeygen|pgp)/|ui/git/config/GitServerConfigActivity.kt'
```
Expected: no output.

- [ ] **Step 8: Build and smoke check**

```bash
./gradlew spotlessApply
CI=1 ./gradlew :app:assembleDebug test -PslimTests :app:lintDebug
```
Expected: `BUILD SUCCESSFUL`. `PgpKeysRoute.mode` stays an enum: navigation-common 2.8.9's
type-safe route converter maps `SerialKind.ENUM` arguments to its built-in enum `NavType`
(`InternalNavType$EnumNullableType` and `NavType.EnumType`, checked in the 2.8.9 jar), so no
custom `typeMap` is needed. The next step confirms it on a device.

- [ ] **Step 9: Launch smoke check on the emulator**

The emulator only (`ANDROID_SERIAL`); a phone may be connected. Start the `aps-test` AVD as in
Task 8, step 1, if it is not running (no e2e server needed).

```bash
export ANDROID_SERIAL=emulator-5554
adb uninstall xyz.danix.hardenedpass.debug || true
./gradlew :app:installDebug
adb logcat -c
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity; sleep 3
ui() { adb exec-out uiautomator dump /dev/tty; }
tap() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); sleep 2; }
ui | grep -o 'text="Clone Remote Repo"'
tap 'text="Create Local Repo"'; ui | grep -o 'text="Select key"'
adb shell input keyevent 4; sleep 1
tap 'text="Clone Remote Repo"'; ui | grep -o 'text="Repository information"'
tap 'text="Manage"'; ui | grep -o 'text="No SSH key yet"'
tap 'text="Generate SSH key pair"'; ui | grep -o 'text="ECDSA"'
adb logcat -d -b crash | grep -c 'FATAL EXCEPTION'
adb uninstall xyz.danix.hardenedpass.debug
unset ANDROID_SERIAL
```
Expected: each `grep -o` prints its text (welcome, the `Choose` screen titled "Select key" with
its enum route argument, the server, SSH key and generation screens), and the crash count is
`0`. A crash here (for example a missing `NavType`) is fixed before the commit.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/setup/SetupGraph.kt \
  app/src/main/java/app/passwordstore/ui/main/MainActivity.kt \
  app/src/main/java/app/passwordstore/ui/list/ListDestination.kt \
  app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt \
  app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt \
  app/src/main/java/app/passwordstore/util/git/operation/GitOperation.kt \
  app/src/main/java/app/passwordstore/ui/settings/RepositorySettings.kt \
  app/src/main/java/app/passwordstore/ui/settings/PGPSettings.kt
git commit -m "feat(app): run setup in MainActivity

The welcome screen starts the app without a store, the list sends a
vanished store back to it, and its clone prompt opens the git server
screen. A clone asks for the store's PGP key when none matches it.
Settings, the entry view, autofill decryption and git operations open
the setup screens through MainActivity.setupIntent. The No SSH key
dialog's Cancel and the unusable screen lock prompt no longer finish
the calling activity, which is now the app's main screen.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Remove the legacy setup screens

**Files:**
- Delete: the files listed in "Deleted in this step" above
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/lint.xml`
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`
- Modify (if lint says so): `app/src/main/res/values*/strings.xml`, `values/dimens.xml`, `res/drawable`

- [ ] **Step 1: Delete the files**

```bash
git rm -r app/src/main/java/app/passwordstore/ui/onboarding \
  app/src/main/java/app/passwordstore/ui/sshkeygen \
  app/src/main/java/app/passwordstore/ui/pgp \
  app/src/main/java/app/passwordstore/ui/git/config/GitServerConfigActivity.kt \
  app/src/main/java/app/passwordstore/ui/dialogs/BasicBottomSheet.kt \
  app/src/main/java/app/passwordstore/util/viewmodel/PGPKeyListViewModel.kt \
  ui/compose/src/main/kotlin/app/passwordstore/ui/APSAppBar.kt \
  app/src/main/res/layout/activity_onboarding.xml \
  app/src/main/res/layout/fragment_welcome.xml \
  app/src/main/res/layout/fragment_clone.xml \
  app/src/main/res/layout/fragment_key_selection.xml \
  app/src/main/res/layout/activity_git_clone.xml \
  app/src/main/res/layout/activity_ssh_keygen.xml \
  app/src/main/res/layout/basic_bottom_sheet.xml
```

- [ ] **Step 2: Manifest and lint config**

In `app/src/main/AndroidManifest.xml`, delete

```xml
    <activity
      android:name=".ui.onboarding.activity.OnboardingActivity"
      android:configChanges="orientation|screenSize"
      android:exported="false" />

```

```xml
    <activity
      android:name=".ui.git.config.GitServerConfigActivity"
      android:exported="false"
      android:label="@string/title_activity_git_clone"
      android:windowSoftInputMode="adjustResize" />

```

```xml
    <activity
      android:name=".ui.sshkeygen.SshKeyImportActivity"
      android:exported="false"
      android:theme="@style/NoBackgroundThemeM3"
      android:windowSoftInputMode="adjustResize" />
    <activity
      android:name=".ui.sshkeygen.SshKeyGenActivity"
      android:exported="false"
      android:label="@string/pref_ssh_keygen_title"
      android:windowSoftInputMode="adjustResize" />
```

and

```xml
    <activity
      android:name=".ui.pgp.PGPKeyImportActivity"
      android:theme="@style/NoBackgroundThemeM3" />
    <activity
      android:name=".ui.pgp.PGPKeyListActivity"
      android:exported="false"
      android:label="@string/activity_label_pgp_key_manager"
      android:parentActivityName=".ui.settings.SettingsActivity" />
```

In `app/lint.xml`, the `Overdraw` issue ignores four of the deleted layouts. Replace

```xml
  <issue id="Overdraw">
    <ignore path="src/main/res/layout/activity_git_clone.xml" />
    <ignore path="src/main/res/layout/fragment_clone.xml" />
    <ignore path="src/main/res/layout/fragment_key_selection.xml" />
    <ignore path="src/main/res/layout/fragment_welcome.xml" />
  </issue>
```

with nothing (delete the block; no other path is listed under it).

- [ ] **Step 3: Nothing refers to the removed code**

```bash
grep -rnwE 'OnboardingActivity|WelcomeFragment|CloneFragment|KeySelectionFragment|GitServerConfigActivity|SshKeyImportActivity|SshKeyGenActivity|ShowSshKeyFragment|PGPKeyImportActivity|PGPKeyListActivity|KeyList|PGPKeyListViewModel|BasicBottomSheet|APSAppBar' \
  app/src ui/compose/src
grep -rnE 'ActivityOnboardingBinding|FragmentWelcomeBinding|FragmentCloneBinding|FragmentKeySelectionBinding|ActivityGitCloneBinding|ActivitySshKeygenBinding|R\.layout\.(activity_onboarding|fragment_welcome|fragment_clone|fragment_key_selection|activity_git_clone|activity_ssh_keygen|basic_bottom_sheet)' \
  app/src
```
Expected: no output from either.

- [ ] **Step 4: The spec**

In `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`, insert before
`## Architecture`:

```
### Setup

- Without a store the app opens on a welcome screen: clone a remote repository or create a
  local store; Settings is reachable from it.
- The git server screen serves the clone and later edits: URL, authentication mode (SSH key or
  password; none or password for HTTP(S)), the SSH key's state with "Manage", "Clear saved host
  key". A failed clone stays on the screen, so URL or key can be fixed and the clone retried.
- The SSH key screen shows the key's state and, for a key generated on the device, the public
  key (copy, share); it imports a key file (refusing files sshj cannot read, such as OpenPGP
  keys) and opens key generation (RSA, ECDSA, Ed25519, optionally behind the screen lock).
- One PGP keys screen manages keys (import, delete), picks the key of a new local store (the
  repository is created only then, its `.gpg-id` naming the key by its uppercase long key ID, as
  `pass init <key ID>` does), and imports the key a store needs: after a clone that no
  imported key can decrypt, and when an entry is opened with no key imported.
- Screens outside the graph (Settings, autofill, git operations) and the entry view's key
  import open these through a `MainActivity` setup intent until steps 6 and 7.

```

- [ ] **Step 5: Full local check**

Run:
```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`. If lint reports unused resources, they were used only by the
deleted files (likely: `let_s_go`, `select_n_repository_type`, `select_gpg_key_title`,
`gpg_key_select_mandatory`, `app_icon_hint`, `welcome_text`, the `onboarding_*` dimens in
`values/dimens.xml`, `ic_add_48dp`, `ic_arrow_back_black_24dp`, `ic_delete_24dp`, and strings or
styles only `basic_bottom_sheet.xml` used): confirm with `grep -rn` that nothing else uses
them, then delete them from every `values*/` file (a string left only in a translation is an
`ExtraTranslation` error) or from `res/drawable`, and re-run. (Only `autofill-parser` has a
metalava API file; ui:compose has none, so removing `APSAppBar` needs no API update.) If lint
reports an issue in a file from Tasks 1 to 6, fix it there and include it in this commit.

- [ ] **Step 6: Commit**

```bash
git add -A app/src app/lint.xml ui/compose \
  docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "refactor(app): remove the legacy setup screens

The Compose welcome, git server, SSH key, key generation and PGP keys
screens replace OnboardingActivity with its fragments,
GitServerConfigActivity with BasicBottomSheet, SshKeyImportActivity,
SshKeyGenActivity, ShowSshKeyFragment, PGPKeyImportActivity and
PGPKeyListActivity with KeyList and PGPKeyListViewModel, their layouts
and manifest entries, and ui:compose's APSAppBar.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Emulator check and push

No real store or keys: the e2e environment from AGENTS.md only. A physical phone may be
connected over USB: every `adb` and Gradle install command in this task targets the emulator
through `ANDROID_SERIAL`; never touch the phone. The PIN `1234` below is the emulator's own test
PIN, never the owner's.

- [ ] **Step 1: Server, emulator, unlock, clean install**

```bash
export ANDROID_SERIAL=emulator-5554
scripts/e2e-env.sh up
$ANDROID_HOME/emulator/emulator -avd aps-test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu swiftshader_indirect &
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
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

(`ANDROID_HOME` is the `sdk.dir` from `local.properties`. `push-keys` only after the unlock.
The uninstall also avoids a signature mismatch between checkouts, see AGENTS.md.)

Helpers for this shell. `center`, `tap` and `field` take an optional match index (1 = first).
`pick` chooses a file in the system document picker, opening its Downloads root when the file is
not listed at once.

```bash
APP=xyz.danix.hardenedpass.debug
ui() { adb exec-out uiautomator dump /dev/tty; }
center() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | sed -n "${2:-1}p" | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }; set -- $b; echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; }
tap() { local c; c=$(center "$1" "$2") && adb shell input tap $c; sleep 1; }
field() { tap 'class="android.widget.EditText"' "$1"; adb shell input keycombination 113 29; adb shell input keyevent 67; adb shell input text "$2"; }
value() { ui | grep -o '<node [^>]*class="android.widget.EditText"[^>]*>' | sed -n "${1}p" | grep -o ' text="[^"]*"' | cut -d'"' -f2; }
seen() { ui | grep -q "$1" && echo "ok: $1" || echo "MISSING: $1"; }
top() { adb shell dumpsys activity activities | grep topResumedActivity; }
mains() { adb shell dumpsys activity activities | grep -c 'Hist.*ui.main.MainActivity'; }
open() { adb shell am start -n $APP/app.passwordstore.ui.main.LaunchActivity; sleep 3; }
pass() { tap 'class="android.widget.EditText"'; adb shell input text "$1"; tap 'text="OK"'; sleep 2; }
pick() { sleep 2; ui | grep -q "text=\"$1\"" || { tap 'content-desc="Show roots"'; tap 'text="Downloads"'; sleep 1; }; tap "text=\"$1\""; sleep 2; }
store() { adb shell run-as $APP ls -a "files/store/$1"; }
```

(If `pick` finds neither the file nor "Downloads", the picker's root list differs on this
image: dump it with `ui` and adapt the two taps; the files are in `/sdcard/Download`.)

- [ ] **Step 2: Welcome screen, URL check, rotation**

```bash
open; top; mains
seen 'text="Clone Remote Repo"'; seen 'text="Create Local Repo"'; seen 'text="Settings"'
adb exec-out screencap -p > "$OUT/welcome.png"
tap 'text="Clone Remote Repo"'
seen 'text="Repository information"'; seen 'text="SSH key"'; seen 'text="No SSH key yet"'
field 1 git@10.0.2.2:2222/srv/git/store.git
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 2
value 1
adb shell settings put system user_rotation 0; sleep 2
tap 'text="Clone"'; seen 'text="Potentially incorrect URL"'
tap 'text="OK"'; value 1
adb exec-out screencap -p > "$OUT/server.png"
```
Expected: `MainActivity` on top, `1` instance; the welcome screen with both choices and
Settings; the server screen with the SSH key row; the URL survives the rotation; Clone shows the
scheme dialog and OK turns the URL into `ssh://git@10.0.2.2:2222/srv/git/store.git`. Both
screenshots show the screens (setup screens are not `FLAG_SECURE`).

- [ ] **Step 3: No SSH key: generate in a second instance; a failed clone stays**

```bash
tap 'text="Clone"'; sleep 3
seen 'text="No SSH key"'; tap 'text="Generate"'; sleep 2
top; mains
seen 'text="Generate SSH key pair"'; tap 'text="Ed25519"'; seen 'only supported by rather modern servers'
tap 'text="Protect with screen lock credential"'
ui | grep -o 'checkable="true" checked="[a-z]*"'
tap 'text="Generate"'; sleep 5
seen 'text="Your public key"'; seen 'ssh-ed25519 '
tap 'text="Later"'; sleep 2; mains
seen 'text="Generated on this device"'
tap 'text="Clone"'; sleep 12
seen 'text="An error occurred during a Git operation"'; tap 'text="OK"'
seen 'text="Clone"'; seen 'text="Clear saved host key"'; value 1
```
Expected: the "No SSH key" dialog from the git operation; Generate opens a second
`MainActivity` (`mains` prints `2`) on the generation screen; Ed25519 shows its explanation; the
switch reads `checked="false"` after the tap; the public key dialog shows an `ssh-ed25519` key;
Later closes the second instance (`mains` prints `1`) and the server screen now says "Generated
on this device". The clone fails (the server does not know this key) with the git error dialog,
and the screen stays usable with its URL; the host key was saved on the way, so "Clear saved
host key" is offered.

- [ ] **Step 4: SSH key screen: a wrong file is refused, the right one replaces the key**

```bash
tap 'text="Manage"'; seen 'text="Generated on this device"'; seen 'ssh-ed25519 '
tap 'text="Import SSH key"'; seen 'Replace existing SSH key'; tap 'text="Replace"'
pick aps-e2e-pgp-key.asc
seen 'text="Key import error"'; seen 'does not appear to be an SSH private key'; tap 'text="OK"'
seen 'text="Generated on this device"'; seen 'ssh-ed25519 '
tap 'text="Import SSH key"'; tap 'text="Replace"'; pick aps-e2e-ssh-key
seen 'text="Imported private key"'; seen 'ssh-ed25519 '
adb shell input keyevent 4; seen 'text="Imported private key"'
```
Expected: the PGP key file is refused and the generated key stays (state and public key
unchanged); the SSH key file is imported: "Imported private key" and `MISSING: ssh-ed25519`
(an imported key has no public key to show); back on the server screen the row agrees.

- [ ] **Step 5: Clone, the store's key, recreation, decrypt**

```bash
tap 'text="Clone"'; sleep 15
seen 'text="Import PGP key"'; seen 'text="f8993a789cdfb014"'; seen 'text="Later"'
seen 'Import a key using the add button below'
adb exec-out screencap -p > "$OUT/keys.png"
adb shell cmd uimode night yes; sleep 3; seen 'text="f8993a789cdfb014"'
adb shell cmd uimode night no; sleep 3
tap 'text="Import PGP key"' 2; pick aps-e2e-pgp-key.asc
seen 'text="Successfully imported PGP key"'; seen 'f8993a789cdfb014'
tap 'text="OK"'; sleep 2
top; seen 'text="example.org"'; seen 'text="otp"'
tap 'text="example.org"'; pass test; seen 'text="hunter2"'
adb shell input keyevent 4
```
Expected: after the clone the `Need` screen lists the store's recipient `f8993a789cdfb014` (the
long key ID of the fingerprint in `.e2e/seed/.gpg-id`) and no imported key; a dark mode switch
recreates the activity and the screen stays (the restored back stack wins over the start route);
the FAB (second "Import PGP key" node; the first is the title) imports the key, the dialog shows
its key ID, OK opens the list of the cloned store, and `example.org` decrypts. (If the FAB is the
first match on this image, use index 1.)

- [ ] **Step 6: Settings entry points**

```bash
tap 'content-desc="More options"'; tap 'text="Settings"'; top
tap 'text="Repository"'; tap 'text="Edit Git server settings"'; sleep 2
top; mains; value 1; seen 'text="Save"'; seen 'text="Imported private key"'
tap 'text="Save"'; sleep 2; top
tap 'text="Import SSH key"'; sleep 2; top; seen 'text="Imported private key"'
adb shell input keyevent 4; sleep 1; top
tap 'text="Generate SSH key pair"'; sleep 2; seen 'text="ECDSA"'
adb shell input keyevent 4; sleep 1; seen 'View generated public SSH key'
adb shell input keyevent 4; tap 'text="PGP settings"'; tap 'text="Key manager"'; sleep 2
top; seen 'text="PGP Key Manager"'; seen 'text="f8993a789cdfb014"'
tap 'content-desc="Delete"'; seen 'text="Delete key?"'; tap 'text="Yes"'; sleep 2
seen 'Import a key using the add button below'
adb shell input keyevent 4; adb shell input keyevent 4; adb shell input keyevent 4; sleep 1; top
```
Expected: each Settings row opens `MainActivity` (`mains` prints `2` while it is up) on the right
screen, and leaving it returns to `SettingsActivity`: the server screen in edit mode (button
"Save", the cloned URL, the key's state; Save closes it), the SSH key screen, the generation
screen (left without generating). "View generated public SSH key" is hidden
(`MISSING`: the key is imported). The key manager lists the key and deletes it after the
confirmation. The last backs return to the list (`MainActivity` on top).

- [ ] **Step 7: Entry without keys: import through the setup intent, then decrypt**

```bash
tap 'text="example.org"'; sleep 2; seen 'text="No keys imported"'
tap 'text="Import"'; sleep 2; mains; seen 'text="Later"'
tap 'text="Import PGP key"' 2; pick aps-e2e-pgp-key.asc; tap 'text="OK"'; sleep 2
mains; pass test; seen 'text="hunter2"'
adb shell input keyevent 4
```
Expected: the entry view's prompt opens a second `MainActivity` on the `Need` screen (`2`);
after the import it finishes with `RESULT_OK` (`1`) and the entry view goes on to the passphrase
prompt and decrypts. The same intent serves `AutofillDecryptActivity`; the maintainer checks that
path on the tablet with Firefox (delete the key in the key manager, autofill a login, import when
asked).

- [ ] **Step 8: A vanished store sends the list back to the welcome screen**

```bash
adb shell input keyevent 3; sleep 1
adb shell run-as $APP sh -c 'rm -rf files/store; mkdir files/store'
adb shell monkey -p $APP -c android.intent.category.LAUNCHER 1; sleep 3
top; mains; seen 'text="Clone Remote Repo"'
adb shell input keyevent 4; sleep 1; adb shell dumpsys window | grep mCurrentFocus
```
Expected: the existing task comes back (`mains` prints `1`), the list's resume finds no store and
the welcome screen replaces the stack; back leaves the app (focus outside `$APP`).

- [ ] **Step 9: Local store: backing out creates nothing; import, choose, use**

```bash
adb shell pm clear $APP
open; tap 'text="Create Local Repo"'
seen 'Select a GPG key to initialize your store with'; seen 'Import a key using the add button below'
adb shell input keyevent 4; seen 'text="Clone Remote Repo"'
adb shell run-as $APP ls -a files; store
tap 'text="Create Local Repo"'; tap 'text="Import PGP key"'; pick aps-e2e-pgp-key.asc
tap 'text="OK"'; sleep 1; seen 'text="f8993a789cdfb014"'
tap 'text="f8993a789cdfb014"'; sleep 3
top; seen 'text="New entry"'
adb shell run-as $APP cat files/store/.gpg-id
adb shell run-as $APP cat files/store/.git/logs/HEAD | grep -o 'Initialize GPG IDs in [^.]*\.'
tap 'text="New entry"'; tap 'text="Create new password"'
adb shell input text local-test; field 2 x1y2
tap 'text="Save"'; sleep 3; seen 'text="local-test"'
tap 'text="local-test"'; pass test; seen 'text="x1y2"'
adb shell input keyevent 4
```
Expected: after `pm clear` the app opens on the welcome screen; the `Choose` screen has no keys
yet; backing out returns to the welcome screen and `files` holds no store (`store` lists nothing
or only `.` and `..`). After importing and tapping the key, the empty list of the new store
appears; `.gpg-id` holds exactly `F8993A789CDFB014` on one line (the uppercase long key ID,
maintainer decision 3); the git log has `Initialize GPG IDs in
HardenedPass Debug.`; a new entry saves, is encrypted to that key and decrypts. (The `Choose`
title is "Select key", the FAB is the only "Import PGP key" node there.)

- [ ] **Step 10: Tear down and push**

```bash
adb shell settings put system user_rotation 0; adb shell settings put system accelerometer_rotation 1
adb shell cmd uimode night no
adb uninstall $APP
adb shell rm -f /sdcard/Download/aps-e2e-ssh-key /sdcard/Download/aps-e2e-pgp-key.asc
adb emu kill
scripts/e2e-env.sh down
unset ANDROID_SERIAL
rm -rf "$OUT"
git push
```

Expected: push succeeds to every `origin` push URL. Look at the three screenshots before
deleting `$OUT` if a visual check is wanted (copy them elsewhere first).

---

## Self-review notes

- **Spec coverage for step 5:** onboarding as Compose destinations in `MainActivity` (welcome,
  Tasks 3 and 6; checked in Task 6 step 9 and Task 8 steps 2, 8, 9); git server config and
  clone (Tasks 2, 3, 6; Task 8 steps 2, 3, 5, 6); SSH keygen and import (Tasks 1, 2, 4, 6;
  steps 3, 4, 6); PGP import and key list (Tasks 1, 2, 5, 6; steps 5, 6, 7, 9). Legacy
  activities, fragments, layouts and their lint ignores deleted (Task 7). Strings in `values/`
  and `values-it/`, each added by the task that uses it ("voci" for entries). TDD for the pure
  logic (Task 1). `:app:lintDebug` in every task, full local CI in Task 7. Emulator check pinned
  to `emulator-5554` with the e2e server (Task 8), plus a launch smoke check in Task 6. Spec
  gains a Setup section (Task 7).
- **Step 4 findings:** the PGP key after a clone (`Need`, Task 8 step 5), now only satisfied by
  a secret key; a wrong SSH key replaceable from the server screen and an OpenPGP file refused
  as SSH key (Task 1, Task 8 step 4).
- **Maintainer decisions applied:** 1 and 2 as planned (Tasks 3, 5); 3: `gpgIdText` writes
  uppercase long key IDs, one per line (Task 1, tested; used by `PgpKeysViewModel.createStore`
  in Task 2; checked in Task 8 step 9); 4 and 5 as planned (Tasks 4, 6).
- **Review findings applied:** symlink-safe store deletion before a clone (Task 2); `:app:lintDebug`
  per task (Tasks 1 to 6), which also moved the screen strings into the tasks that use them;
  the empty size cursor in `SshKey.import` (Task 1); `tryGetEmail` safe on a key without user
  ID (Task 1); the after-clone check requires a secret key (`KeyUtils.isSecretKey`, Tasks 1 and
  2); `.gpg-id` read through `readGpgId` (regular file, no symlink, at most 64 KiB), off the
  main thread (Tasks 1, 2, 5); the key check in `viewModelScope` (Tasks 2, 3); pending key bytes
  zeroed (Task 2); a failed `.gpg-id` commit reported (Task 5); `KeysMode` stays an enum route
  argument, verified against the navigation-common 2.8.9 jar and smoke-checked (Task 6).
- **Parity summary:** ported: every old dialog and message (URL warnings, missing user name,
  wrong mode, host key reset, delete before clone, SSH replace confirmation, import toast and
  error, key generation with types, explanations, screen lock prompt, public key dialog, PGP
  success, replace and failure dialogs, delete confirmation, the commit message). Changed:
  welcome merged with the repository type screen, toast instead of a delayed close on save,
  segmented buttons, the public key on the SSH key screen instead of a dialog, key ID shown in
  key rows, `.gpg-id` with the long key ID instead of the user ID, the local repository created
  only once a key is chosen, the clone screen staying after a failure, setup screens opened from
  outside through a second instance. Fixed: OpenPGP files imported as SSH keys, the empty
  cursor crash, `git://` and `http://` URL checks, deletion following symlinks, delete by user
  ID, keys without user ID, the PGP import on the main thread with `runBlocking`, "No SSH key"
  Cancel and the unusable prompt finishing `MainActivity`. New: SSH key row with Manage, `Need`
  screen after a clone.
- **Deliberate shortcuts (`ponytail:`):** the second `MainActivity` instance for setup intents
  (Task 6); a clone or `.gpg-id` commit cancelled by a theme or locale change (Tasks 3, 5); key
  type explanations without their bold title (Task 4).
- **Type and name consistency:** `UrlProblem`, `urlProblem`, `fixUrl`, `authModes`,
  `fitAuthMode`, `CloneTarget`, `cloneTarget`, `storeRecipients`, `gpgIdText(keys:
  List<PGPIdentifier.KeyId>)`, `readGpgId(dir: File): String?` (Task 1) are used with these
  signatures in Tasks 2, 3 and 5; `isSshPrivateKey` (Task 1) only in `SshKey.import`;
  `KeyUtils.isSecretKey` and the fixed `KeyUtils.tryGetEmail` (Task 1) in Task 2;
  `sshKeySummary`, `SshKeyType`, `SshKeyViewModel.import/generate/fail/dismissFailure/announced`,
  `type`, `protect`, `busy`, `version`, `imported`, `generated`, `failure` (Task 2) match Task 4;
  `GitServerViewModel.url/changeUrl/authMode/progress/cloned/hasHostKey/sshKey/refresh/
  clearHostKey/save/clearStore` (suspend) `/finishClone` (not suspend, launches in
  `viewModelScope`) `/cloneEnded` (Task 2) match Task 3; `KeyRow(userId, keyId)` (no `gpgId`
  member any more), `KeyImport`, `PgpKeysViewModel.keys/recipients/loadRecipients/busy/
  importResult/done/import/replace/dismissImport/deleteKey/createStore/markDone` (Task 2) match
  Task 5; `KeysMode` is declared once, in Task 5; `SetupStart.GitServer/Ssh/SshGenerate/PgpKeys/
  PgpImport` and `MainActivity.setupIntent` (Task 6) match the callers in Task 6;
  `ListDestination(..., onClone, onNoStore)` matches `MainActivity`; `SetupDialog(text, confirm,
  onConfirm, onDismiss, modifier, title, dismiss, onDismissButton)` (Task 3) matches its uses in
  Tasks 3 to 5.
- **Build order:** Task 1 changes `crypto:pgpainless` before the app uses `isSecretKey`; Task 2
  adds the four strings its ViewModels need, Tasks 3 to 5 the strings of their screens; Task 5
  declares `KeysMode` so it builds before Task 6; Task 6 leaves the legacy files compiling and
  unreferenced; Task 7 deletes them.

## Maintainer decisions (2026-11-03)

1. **Welcome screen:** the old "Let's Go" screen and the repository type screen are merged into
   one (icon, name, explanation, "Clone Remote Repo", "Create Local Repo", "Settings"), as
   planned (Task 3).
2. **"Later" on the `Need` key screen:** offered, as planned; back does the same (Task 5).
3. **`.gpg-id` of a new local store:** changed. It holds the key's 16-hex long key ID in
   uppercase hex, one line per key (`F8993A789CDFB014`), the format of the maintainer's desktop
   `pass init` store, instead of the user ID (`gpgIdText`, Task 1; `createStore`, Task 2;
   checked in Task 8 step 9).
4. **Import SSH key from Settings and from the "No SSH key" dialog:** opens the SSH key screen,
   as planned (Tasks 4, 6).
5. **`GitConfigActivity`:** stays a legacy activity until step 6, as planned.
