# Compose UI overhaul, step 4: Entry editor. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy `PasswordCreationActivity`, its generator dialogs, the manual OTP dialog and `SelectFolderActivity` with Compose: an editor destination (`EditRoute`) in `MainActivity`, opened from the list's create sheet and from the entry view's edit action; a generator bottom sheet with random and diceware tabs; OTP from QR (camera or image) and manual; a folder picker dialog shared by the editor and the list's move action. The autofill save flow hosts the same editor. Every save-path check of 0.1.2 stays.

**Architecture:** `EditRoute(dir, name?)` gets an `EditorViewModel` per back stack entry. The form (`EditorForm`: name, folder, password, username, extra) lives in Compose state in that ViewModel only. A new entry starts empty in the list's folder. An edit copies its plaintext once from the `EntryViewModel` of the `EntryRoute` below it on the back stack (`nav.getBackStackEntry<EntryRoute>()`), so plaintext never travels in a route or saved state and no second passphrase prompt is needed. Pure logic (form to pass file, validation, target file, overwrite check, atomic write, OTP URI, generator options) sits in `EditorModel.kt` with unit tests. Saving runs in `activity.lifecycleScope`, like the list's store operations: the ViewModel encrypts (CryptoRepository, unchanged) and writes off the main thread, the destination commits (`commitChange` needs the activity). `FLAG_SECURE` moves out of the destinations into a counted hold in `MainActivity` (`SecureWindow`), so entry and editor can overlap during a transition without clearing it. `AutofillSaveActivity` sets `FLAG_SECURE` for its window and shows `EditorDestination` directly. The legacy editor, its dialogs and layouts, the folder picker activity and fragment, and the now unused `flowbinding` and `swiperefreshlayout` dependencies are deleted.

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2024.09.03 (foundation 1.7.3: `KeyboardOptions(autoCorrectEnabled = ...)`; Material 3 1.3.0: `ModalBottomSheet(properties = ModalBottomSheetProperties(securePolicy = ...))`, `PrimaryTabRow`, `Tab`), navigation-compose 2.8.9 (`getBackStackEntry<T>()`, `popBackStack<T>(inclusive)`), `hilt-navigation-compose` 1.2.0 (`hiltViewModel(viewModelStoreOwner)`), zxing-android-embedded (unchanged), passgen modules (unchanged), kotlin-result 2.0.0. No new dependencies.

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`. Earlier plans: `2026-11-02-compose-foundation.md`, `2026-11-02-compose-list.md`, `2026-11-02-compose-entry.md` (same folder).

## Decisions taken in this plan

- **How plaintext reaches the editor: the entry view's ViewModel.** The `EditRoute` composable looks up the `EntryRoute` back stack entry below it and reads `hiltViewModel<EntryViewModel>(that entry).entry`, then the editor copies it into its own ViewModel once (`EditorViewModel.start`, a no-op afterwards). Rejected: nav arguments or saved state (plaintext in a Bundle, forbidden); re-decrypting in the editor (a second passphrase prompt, or a silent dependency on the cache); an activity-scoped holder (outlives the entry). Consequences, all safe: after process death the restored `EditRoute` finds no plaintext and closes, so the entry view asks for the passphrase again (`ponytail:` marked). The entry's auto-close timer may drop its copy while the editor is open; the editor already has its own, and cancelling returns to an entry that then closes itself, as the old decrypt activity did.
- **Routes and returns.** `EditRoute(dir, name = null)`: `dir` relative to the store root, `name` the entry's file name without `.gpg` (null for a new entry). From the list: `EditRoute(path)`. From the entry view: `EditRoute(folder, name)`. After save: pop to the topmost `ListRoute` (it re-reads the store on resume); with none below (entry opened from a shortcut) the list root replaces the stack, as step 3 decided. After cancel (close icon or system back): pop, back to the list or the entry view. Both go through one guarded path: only the editor on top acts, nothing happens while a save runs, the keyboard is hidden and focus cleared first.
- **Folder picker: a dialog, not a destination.** `FolderPicker` (breadcrumbs plus subfolder rows with counts, New folder, Select, Cancel) returns its pick through a callback into the caller's own state, so there is no result channel across back stack entries. It shows no secrets; its window inherits `FLAG_SECURE` from the editor below. It replaces `SelectFolderActivity` for the editor and the list's move action. "New folder" creates a folder inside the folder shown and opens it, through the list's own `FolderNameDialog` and `folderNameError` (blank, already a file or folder, outside the store by path components), shared rather than copied (maintainer decision 2).
- **Autofill save hosts the Compose editor.** `AutofillSaveActivity` stays an activity (the autofill service starts it) but now calls `setContent { EditorDestination(...) }` with the form prefilled from its intent, instead of starting `PasswordCreationActivity` for a result. It sets `FLAG_SECURE`, uses `NoActionBarThemeM3` and `configChanges="orientation|screenSize"`. After saving it records the autofill match and, for "generate password", returns the fill dataset, exactly as before. This is what lets the legacy editor and its dialogs go in this step (open question 1).
- **FLAG_SECURE is counted.** `MainActivity.holdSecure()` / `releaseSecure()` and a private top-level `SecureWindow(activity)` composable (a `DisposableEffect`) wrap the entry and editor destinations in the `NavHost`. The flag goes on with the first hold and off with the last release, so entry to editor and back never clears it while either is composed. The `EntryDestination`'s own add/clear pair and its `ponytail:` note are removed. The generator sheet and the manual OTP dialog set `SecureFlagPolicy.SecureOn` explicitly; the picker inherits.
- **Save path, every 0.1.2 check kept, three old gaps closed.** In order: `formError` (empty name, `/` in the name, empty password and extra: same messages); `isInsideRepository` on the target (component-wise since 0.1.2), now checked before anything is created (the old editor created the folder first, so a crafted autofill folder name such as `site/../../x` could create directories outside the store); recipients from the nearest `.gpg-id` (`getPGPIdentifiers`, same snackbars for short or invalid IDs); refusal to overwrite another entry, now also when an edit only moves to another folder (the old check missed that case and overwrote silently); encryption to every recipient, refused when a key is missing (`CryptoRepository.encrypt`, untouched): nothing is written and nothing committed, the folder is not even created; the write goes through a temporary file moved over the target, so a failed write cannot truncate the existing entry; on rename or move the old file is deleted, now also for a move without rename (the old editor left a copy), and its autofill matches and recently-used time follow it; then `git add` and commit with the old messages.
- **Generator sheet.** One `ModalBottomSheet`, tabs Random and Diceware, the first tab chosen by the existing "Password generator" setting (`PREF_KEY_PWGEN_TYPE`, default diceware). Options, preference files and keys are the old ones (`PasswordGenerator` file for random, `pwgen` for diceware), so stored choices carry over. Every change regenerates (as the old dialogs did), Generate rolls again, Use fills the password. Errors show in the sheet instead of a toast. Generation runs off the main thread (the diceware word list is read on first use). Lengths are capped by the fields (3 digits, 2 for diceware words), `ponytail:` marked.
- **OTP.** "OTP from QR" opens a small menu: "Scan QR code" (zxing `CaptureActivity`, unchanged, only when the device has a camera) and "Choose an image" (decoded off the main thread). "Manual OTP" opens a dialog with secret and account; spaces in the secret are dropped. The resulting URI line is appended to the extra content on its own line. Both actions hide once the extra content holds a TOTP secret, as before.
- **Username always shown; "Encrypt username" switch dropped.** The spec puts a username field on the editor; the switch only moved text between the name and username fields for the file-based layout (open question 3).
- **"Save and copy" kept, in the overflow.** It now copies only after a successful save, through `copyPasswordToClipboard` (sensitive flag and clear timer). The spec's top bar has Save only (open question 4).
- **"No keys imported" prompt not ported to the editor.** The entry view still has it, and the editor can only edit an entry the entry view decrypted. A new entry on a store without keys fails at encryption with the 0.1.2 refusal ("Could not encrypt the password, nothing was saved: ...").
- **Keyboard types.** Password and extra content use `KeyboardType.Password` (multi-line for the extras), keeping the old `textVisiblePassword` intent that keyboards do not learn them; Compose has no visible-password type, and the text stays visible unless masked by the reveal toggle. Name and username turn autocorrect off.
- **Password visibility** starts from the "Show the password" setting, as in the entry view.
- **Commit failures are not surfaced**, exactly like the list's move and delete (`commitChange` result ignored); a theme or locale change in the middle of a save cancels before the commit, and the next commit picks the file up. `ponytail:` marked.

## Feature parity

### `PasswordCreationActivity`

| Behavior today | Decision |
|---|---|
| `FLAG_SECURE` on the whole activity (`BasePGPActivity`) | Port: counted hold while the editor is composed; `AutofillSaveActivity` sets it for its window |
| Title "New password" / "Edit password", up arrow cancels | Port: "New entry" / "Edit entry" (the app's word), close icon and system back through one guarded close path |
| Name field, focused on a new entry | Port (focus only on first show, not after recreation) |
| Directory field: hidden for new entries from the list, editable text for edits and autofill, created with `createDirectories` before any check | Changed: breadcrumb chips plus "Change" (picker) everywhere; the folder is created when the file is written, after the inside-repository check |
| Username field shown only for the encrypted-username layout or when prefilled | Changed: always shown (spec) |
| "Encrypt username" switch (file-based layout, new entries) | Drop: both fields are always visible (open question 3) |
| Password field, visible while typing, masked when prefilled or generated, toggle | Port: reveal toggle, initial state from "Show the password", monospace |
| "Generate" button opens the classic or diceware dialog per setting | Port: generator icon in the password field opens the sheet; the setting picks the first tab |
| Extra content, multi-line, `textVisiblePassword` | Port: multi-line, `KeyboardType.Password` |
| "Add OTP" hidden once the extras hold a TOTP secret | Port (both OTP actions) |
| OTP: scan QR (camera only), choose an image, enter manually; manual only without a camera | Port: "OTP from QR" menu (scan with a camera, image always), "Manual OTP" dialog |
| OTP import snackbars (success, failure, no image selected) | Port |
| Save | Port: top bar |
| Save and Copy (copied before encrypting, even when the save then failed) | Port in the overflow; copies only after a successful save (open question 4) |
| `requireKeysExist` "No keys imported" dialog with import | Drop: see decisions; a save without the recipient key is refused and writes nothing |
| Validation: empty name, `/` in the name, empty password and extra content | Port (`formError`, same strings) |
| Recipients from the nearest `.gpg-id`, snackbars for short or invalid IDs | Port (`getPGPIdentifiers`) |
| Encrypt to every recipient, refuse when a key is missing (0.1.2) | Port unchanged (`CryptoRepository.encrypt`) |
| Encrypt in memory before writing; nothing written or committed on failure (0.1.2) | Port; the target folder is now also left uncreated |
| Duplicate check for new entries and renames | Port and fix: also for a move to another folder |
| `isInsideRepository` on the target (after creating the folder) | Port, checked first |
| File written in place | Changed: temporary file moved over the target |
| Rename deletes the old file when the name changed | Port and fix: also when only the folder changed |
| Old file not deletable: dialog, no commit, editor closes | Changed: long snackbar, the new file is committed and the editor closes |
| Recently-used time carried over on rename | Port (`moveHistory`, shared with the list's folder rename) |
| Autofill matches of a renamed entry | New: updated like the list's move does (the old editor lost them) |
| Write error: dialog, editor closes | Changed: dialog, the editor stays open with its content |
| Commit "Add generated password for ..." / "Edit password for ..." | Port (named after the new location) |
| Result extras for the caller (created file, name, long name; password and username when generated) | Port as `SaveResult.Saved` for autofill; `MainActivity` returns to the list |
| Rotation recreates the activity; the fields' text went into the saved instance state | Changed: no recreation (`configChanges`), form in ViewModel memory only, never in saved state |

### Generator dialogs

| Behavior today | Decision |
|---|---|
| Random: numerals, symbols, uppercase, lowercase, pronounceable, ambiguous, length; `PasswordGenerator` preferences | Port, same keys and defaults (length 20; 16 when the field is empty) |
| Diceware: word count, separator; `pwgen` preferences | Port, same keys and defaults (5 words, `-`) |
| Regenerate on every change, "Generate", OK, Cancel | Port: regenerate on change, "Generate", "Use", dismiss cancels |
| Generator errors as a toast | Changed: shown in the sheet |
| Monospace preview | Port |
| Generation on the main thread | Changed: off the main thread |
| Dialog window inherits the secure flag | Changed: `SecureOn` explicitly |

### OTP import

| Behavior today | Decision |
|---|---|
| Manual dialog: secret, account; `otpauth://totp?secret=...&issuer=...`; a blank secret appended an empty string | Port: OK disabled while the secret is blank; spaces dropped; issuer percent-encoded |
| zxing `CaptureActivity` launched for a result | Keep as is |
| QR image decoded on the main thread | Port, off the main thread |

### Folder picker (`SelectFolderActivity`, `SelectFolderFragment`)

| Behavior today | Decision |
|---|---|
| Separate activity with a RecyclerView of folders (hidden ones per "Show hidden contents", never `.git`), fast scroller, Select menu item, up arrow | Replace: `FolderPicker` dialog with breadcrumbs (jump up), folder rows with counts, Select, Cancel; same hidden filter; no fast scroller |
| No way to create a folder (the old edit screen took a typed path instead) | New: "New folder" in the picker, the list's dialog and checks |
| Used by the list's move action | Port: the list's move uses `FolderPicker` |

### Deleted in this step (Task 7)

`PasswordCreationActivity.kt`, `EditPasswordScreen.kt` (upstream WIP), `PasswordGeneratorDialogFragment.kt`, `DicewarePasswordGeneratorDialogFragment.kt`, `OtpImportDialogFragment.kt`, `SelectFolderActivity.kt`, `SelectFolderFragment.kt`, `PasswordItemRecyclerAdapter.kt`, layouts `password_creation_activity.xml`, `fragment_pwgen.xml`, `fragment_pwgen_diceware.xml`, `fragment_manual_otp_entry.xml`, `select_folder_layout.xml`, `password_recycler_view.xml`, `password_row_layout.xml`, menus `pgp_handler_new_password.xml`, `pgp_handler_select_folder.xml`, drawables `ic_save_24dp.xml`, `ic_save_copy_24dp.xml`, ui:compose `PasswordField.kt` (`PasswordField`, `CopyButton`: only `EditPasswordScreen` used them), the `flowbinding` and `swiperefreshlayout` dependencies (only the deleted dialogs and `password_recycler_view.xml` used them), the two manifest entries and the two baseline issues in the deleted dialogs.

**What remains of `BasePGPActivity`** (for `AutofillDecryptActivity` until step 7): the class with `requireKeysExist`, the injected `settings`, `repository`, `dispatcherProvider` and `FLAG_SECURE` in `onCreate`; the companion's `EXTRA_FILE_PATH` (`LaunchActivity`, `MainActivity`), `getRelativePath`, `getParentPath`, `getLongName` (`PasswordItem`, `StoreActions`, entry and autofill); the `copyTextToClipboard`, `copyPasswordToClipboard` and `getPGPIdentifiers` extensions. Removed: `repoPath`, `fullPath`, `EXTRA_REPO_PATH` (only the editor used them).

**Kept for later steps:** `PasswordDialog` and `dialog_password_entry.xml` (`AutofillDecryptActivity`, step 7); ui:compose `PasswordItem.kt` and `APSAppBar.kt` (unrelated to the editor; `PGPKeyListActivity` uses `APSAppBar`, steps 5 and 8); `recyclerview`, `recyclerview-selection`, `fastscroll`, `SearchableRepositoryAdapter`, `password_row_background.xml` (autofill filter and git log, steps 6 to 8).

## Lessons from steps 2 and 3, designed in

- `MainActivity` keeps `configChanges="orientation|screenSize"`; `AutofillSaveActivity` gets the same for the same reason (a save running in `lifecycleScope` must survive rotation).
- Slack compose-lint: no composable members on activities (`SecureWindow` is a private top-level function taking the activity); every public composable has `modifier: Modifier = Modifier` as its first optional parameter; no collection parameters (the picker reads its own rows, kept as `ImmutableList`); `EditorViewModel` is a default parameter of `EditorDestination` and only method references and values are passed down.
- Accessibility: every `IconButton` has a content description (close, more options, show or hide password, generate); the editor has no FAB.
- Compose 1.7 focus and keyboard: the editor requests focus only on its first showing per ViewModel (name for a new entry, username for an autofill prefill, nothing for an edit); the guarded close path hides the keyboard and clears focus before leaving, so neither the entry view nor the list comes back with a stray keyboard (the list's own return handling from step 2 still applies).
- Dialogs and sheets are composed only while shown, so each opening starts fresh; the passphrase-style `remember`, never `rememberSaveable`, holds the manual OTP secret and the generated preview.
- Double submit: `EditorViewModel.save` sets `saving` before its first suspension (`lifecycleScope` runs on `Main.immediate`), Save is disabled while it is set, and it stays set after a success; the list's picker ignores a second Select.
- Off the main thread: encryption (CryptoRepository), the overwrite check, the write and delete, subfolder listing, QR decoding, password generation. The `.gpg-id` read stays on the main thread inside `getPGPIdentifiers` (it shows snackbars), as in the entry view.
- One ViewModel per back stack entry (`hiltViewModel()` in the destination).

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

### Task 1: Editor model and folder listing (TDD)

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/editor/EditorModel.kt`
- Test: `app/src/test/java/app/passwordstore/ui/editor/EditorModelTest.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/ListModel.kt`
- Modify: `app/src/test/java/app/passwordstore/ui/list/ListModelTest.kt`

- [ ] **Step 1: Failing tests for the editor model**

`app/src/test/java/app/passwordstore/ui/editor/EditorModelTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import app.passwordstore.R
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.passgen.random.PasswordOption
import app.passwordstore.util.time.UserClock
import app.passwordstore.util.totp.UriTotpFinder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorModelTest {

  private val root = Files.createTempDirectory("aps").toFile()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  private fun entry(content: String) =
    PasswordEntry(UserClock(), UriTotpFinder(), content.encodeToByteArray())

  @Test
  fun editingWithoutChangesKeepsThePassFile() {
    val files =
      listOf(
        "hunter2\nusername: alice\nurl: https://example.org\n",
        "s3cret\notpauth://totp/example.com:carol?secret=JBSWY3DPEHPK3PXP&issuer=example.com\n",
      )
    for (content in files) assertEquals(content, passContent(editorForm(entry(content), "", "x")))
  }

  @Test
  fun formFromAnEntrySplitsItsFields() {
    val form =
      editorForm(entry("hunter2\nusername: alice\nurl: https://example.org\n"), "work", "mail")
    assertEquals(EditorForm("mail", "work", "hunter2", "alice", "url: https://example.org\n"), form)
  }

  @Test
  fun newEntryContent() {
    assertEquals(
      "pw\nusername: bob\nnote: x",
      passContent(EditorForm(name = "n", password = "pw", username = "bob", extra = "note: x")),
    )
    assertEquals("pw\n", passContent(EditorForm(name = "n", password = "pw")))
  }

  @Test
  fun formErrors() {
    assertEquals(R.string.file_toast_text, formError(EditorForm(name = " ", password = "pw")))
    assertEquals(
      R.string.invalid_filename_text,
      formError(EditorForm(name = "a/b", password = "pw")),
    )
    assertEquals(R.string.empty_toast_text, formError(EditorForm(name = "a", username = "bob")))
    assertNull(formError(EditorForm(name = "a", extra = "note")))
    assertNull(formError(EditorForm(name = "a", password = "pw")))
  }

  @Test
  fun entryFileTrimsTheNameAndUsesTheFolder() {
    assertEquals(File(root, "work/mail.gpg"), entryFile(root, EditorForm(name = " mail ", dir = "work")))
    assertEquals(File(root, "mail.gpg"), entryFile(root, EditorForm(name = "mail")))
  }

  @Test
  fun overwritesOnlyAnotherFile() {
    val taken = File(root, "a.gpg").apply { writeText("x") }
    assertFalse(overwrites(File(root, "work/b.gpg"), null))
    assertTrue(overwrites(taken, null))
    assertFalse(overwrites(taken, taken))
    assertTrue(overwrites(taken, File(root, "work/b.gpg")))
  }

  @Test
  fun writeEntryCreatesTheFolderAndReplacesTheFile() {
    val target = File(root, "new/dir/a.gpg")
    writeEntry(target, byteArrayOf(1))
    writeEntry(target, byteArrayOf(2, 3))
    assertContentEquals(byteArrayOf(2, 3), target.readBytes())
    assertEquals(listOf("a.gpg"), target.parentFile!!.list()!!.toList())
  }

  @Test
  fun linesGoOnTheirOwnLine() {
    assertEquals("otpauth://x", withLine("", "otpauth://x"))
    assertEquals("a\notpauth://x", withLine("a", "otpauth://x"))
    assertEquals("a\notpauth://x", withLine("a\n", "otpauth://x"))
  }

  @Test
  fun manualTotpUriIsReadBackByTheParser() {
    val uri = totpUri(" JBSW Y3DP EHPK 3PXP ", "Example Co")!!
    assertEquals("JBSWY3DPEHPK3PXP", UriTotpFinder().findSecret(uri))
    assertEquals("Example Co", UriTotpFinder().findIssuer(uri))
    assertTrue(entry("PLACEHOLDER\n$uri").hasTotp())
    assertEquals("otpauth://totp?secret=JBSWY3DPEHPK3PXP", totpUri("JBSWY3DPEHPK3PXP", " "))
    assertNull(totpUri("  ", "Example Co"))
  }

  @Test
  fun randomOptionsFollowTheCheckboxes() {
    assertEquals(listOf(PasswordOption.FullyRandom), RandomSpec().options())
    assertEquals(
      listOf(
        PasswordOption.NoDigits,
        PasswordOption.AtLeastOneSymbol,
        PasswordOption.NoUppercaseLetters,
        PasswordOption.NoLowercaseLetters,
        PasswordOption.NoAmbiguousCharacters,
      ),
      RandomSpec(
          numerals = false,
          symbols = true,
          uppercase = false,
          lowercase = false,
          ambiguous = false,
          pronounceable = true,
        )
        .options(),
    )
  }

  @Test
  fun randomPasswordOrWhyNot() {
    assertEquals(20, randomPassword(RandomSpec()).value.length)
    assertEquals(
      R.string.pwgen_no_chars_error,
      randomPassword(RandomSpec(numerals = false, uppercase = false, lowercase = false)).error,
    )
    assertEquals(
      R.string.pwgen_length_too_short_error,
      randomPassword(RandomSpec(length = 2)).error,
    )
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.editor.EditorModelTest`
Expected: compilation FAILS with `Unresolved reference 'EditorForm'` (and the other new names).

- [ ] **Step 2: The editor model**

`app/src/main/java/app/passwordstore/ui/editor/EditorModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import app.passwordstore.R
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.passgen.random.MaxIterationsExceededException
import app.passwordstore.passgen.random.NoCharactersIncludedException
import app.passwordstore.passgen.random.PasswordGenerator
import app.passwordstore.passgen.random.PasswordLengthTooShortException
import app.passwordstore.passgen.random.PasswordOption
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.runCatching
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What the editor edits. [dir] is the folder relative to the store root ("" is the root), [name]
 * the file name without `.gpg`. Plaintext: held in memory only, never in saved state or a route.
 */
@Immutable
data class EditorForm(
  val name: String = "",
  val dir: String = "",
  val password: String = "",
  val username: String = "",
  val extra: String = "",
)

/** The form for [entry], stored as [name] in [dir], split as the old editor split it. */
fun editorForm(entry: PasswordEntry, dir: String, name: String): EditorForm =
  EditorForm(
    name = name,
    dir = dir,
    password = entry.password.orEmpty(),
    username = entry.username.orEmpty(),
    extra = entry.extraContentWithoutUsername,
  )

/** The pass file, as the old editor wrote it: password, `username: ...` when set, the extras. */
fun passContent(form: EditorForm): String {
  val username = if (form.username.isEmpty()) "" else "\nusername: ${form.username}"
  return "${form.password}$username\n${form.extra}"
}

/** Why [form] cannot be saved, as a string resource, or null when it can. */
@StringRes
fun formError(form: EditorForm): Int? =
  when {
    form.name.isBlank() -> R.string.file_toast_text
    '/' in form.name -> R.string.invalid_filename_text
    form.password.isEmpty() && form.extra.isEmpty() -> R.string.empty_toast_text
    else -> null
  }

/** Where [form] is saved in the store at [root]. */
fun entryFile(root: File, form: EditorForm): File =
  File(File(root, form.dir), "${form.name.trim()}.gpg")

/** True when saving to [target] would replace a file other than the [original] being edited. */
fun overwrites(target: File, original: File?): Boolean =
  target.exists() && target.canonicalPath != original?.canonicalPath

/**
 * Writes [bytes] to [target], creating its folder, through a temporary file moved over it, so a
 * failed write never truncates an existing entry.
 */
fun writeEntry(target: File, bytes: ByteArray) {
  val dir = target.parentFile ?: throw IOException("No folder for $target")
  dir.mkdirs()
  val temp = File(dir, ".${target.name}.tmp")
  try {
    temp.writeBytes(bytes)
    Files.move(
      temp.toPath(),
      target.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE,
    )
  } finally {
    temp.delete()
  }
}

/** [extra] with [line] added on a line of its own. */
fun withLine(extra: String, line: String): String =
  if (extra.isEmpty() || extra.endsWith('\n')) "$extra$line" else "$extra\n$line"

/**
 * An `otpauth://totp` URI, as the old manual OTP dialog built it, for a typed [secret] (spaces
 * dropped: authenticators show it in groups) and an optional [issuer]; null for a blank secret.
 */
fun totpUri(secret: String, issuer: String): String? {
  val key = secret.filterNot(Char::isWhitespace)
  if (key.isEmpty()) return null
  fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
  val tail = if (issuer.isBlank()) "" else "&issuer=${encode(issuer.trim())}"
  return "otpauth://totp?secret=${encode(key)}$tail"
}

/** The random generator's options, as its checkboxes show them; defaults are the old dialog's. */
@Immutable
data class RandomSpec(
  val numerals: Boolean = true,
  val symbols: Boolean = false,
  val uppercase: Boolean = true,
  val lowercase: Boolean = true,
  val ambiguous: Boolean = true,
  val pronounceable: Boolean = false,
  val length: Int = 20,
)

/** The generator's options for this spec, negated where the generator asks what to leave out. */
fun RandomSpec.options(): List<PasswordOption> =
  listOfNotNull(
    PasswordOption.NoDigits.takeUnless { numerals },
    PasswordOption.AtLeastOneSymbol.takeIf { symbols },
    PasswordOption.NoUppercaseLetters.takeUnless { uppercase },
    PasswordOption.NoLowercaseLetters.takeUnless { lowercase },
    PasswordOption.NoAmbiguousCharacters.takeUnless { ambiguous },
    PasswordOption.FullyRandom.takeUnless { pronounceable },
  )

/** A random password for [spec], or the string resource saying why there is none. */
fun randomPassword(spec: RandomSpec): Result<String, Int> =
  runCatching { PasswordGenerator.generate(spec.options(), spec.length) }
    .mapError { e ->
      when (e) {
        is MaxIterationsExceededException -> R.string.pwgen_max_iterations_exceeded
        is NoCharactersIncludedException -> R.string.pwgen_no_chars_error
        is PasswordLengthTooShortException -> R.string.pwgen_length_too_short_error
        else -> R.string.pwgen_some_error_occurred
      }
    }

/** The diceware generator's options; defaults are the old dialog's. */
@Immutable data class DicewareSpec(val words: Int = 5, val separator: Char = '-')
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.editor.EditorModelTest`
Expected: `BUILD SUCCESSFUL`, 11 tests pass. If `manualTotpUriIsReadBackByTheParser` fails on the
issuer, the parser (`com.eygraber.uri.Uri`) decodes differently from `URLEncoder`: compare with
`UriTotpFinderTest` and adjust `encode` only, never the test's expectations.

- [ ] **Step 3: Failing test for the picker's folder listing**

In `app/src/test/java/app/passwordstore/ui/list/ListModelTest.kt`, add inside the class, after
the last test:

```kotlin
  @Test
  fun pickerOffersFoldersByName() {
    folder("b")
    folder("A")
    folder(".hidden")
    folder(".git")
    file("c.gpg")
    assertEquals(listOf("A", "b"), subfolders(root, showHidden = false).map { it.name })
    assertEquals(listOf(".hidden", "A", "b"), subfolders(root, showHidden = true).map { it.name })
    assertEquals(emptyList<File>(), subfolders(File(root, "missing"), showHidden = false))
  }
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.list.ListModelTest`
Expected: compilation FAILS with `Unresolved reference 'subfolders'`.

- [ ] **Step 4: The folder listing**

In `app/src/main/java/app/passwordstore/ui/list/ListModel.kt`, add after `childCount`:

```kotlin
/**
 * Folders directly inside [dir] that the folder picker offers, by name: never `.git`, other hidden
 * folders only with "Show hidden contents" ([showHidden]), as the list filters them.
 */
fun subfolders(dir: File, showHidden: Boolean): List<File> =
  dir
    .listFiles { f -> f.isDirectory && !f.name.startsWith(".git") && (showHidden || !f.isHidden) }
    .orEmpty()
    .sortedBy { it.name.lowercase() }
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.list.ListModelTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Checks and commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
git add app/src/main/java/app/passwordstore/ui/editor/EditorModel.kt \
  app/src/test/java/app/passwordstore/ui/editor/EditorModelTest.kt \
  app/src/main/java/app/passwordstore/ui/list/ListModel.kt \
  app/src/test/java/app/passwordstore/ui/list/ListModelTest.kt
git commit -m "feat(app): add the editor model and the picker's folder listing

Pure functions for the Compose editor: the form and the pass file it
writes, validation, the target file, the overwrite check, an atomic
write, OTP URI building and the random generator's options. The folder
picker lists subfolders with the list's hidden filter.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Folder picker dialog; the list's move uses it

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/list/FolderPicker.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`

`SelectFolderActivity` stays compiled but unused until Task 7.

- [ ] **Step 1: The picker**

`app/src/main/java/app/passwordstore/ui/list/FolderPicker.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.BreadcrumbBar
import app.passwordstore.ui.compose.FolderRow
import app.passwordstore.ui.compose.crumbsFor
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks a folder of the store at [root], starting at [start] (relative to the root, "" is the
 * root): breadcrumbs jump up, rows open subfolders, "New folder" creates one inside the folder
 * shown (the list's validation) and opens it, Select reports the folder shown, relative to the
 * root. Used by the list's move action and by the editor. Shows no secrets; its window inherits
 * FLAG_SECURE from the screen below.
 */
@Composable
fun FolderPicker(
  root: File,
  start: String,
  onPick: (relativePath: String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var path by rememberSaveable { mutableStateOf(start) }
  var naming by rememberSaveable { mutableStateOf(false) }
  val showHidden = remember {
    context.sharedPrefs.getBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, false)
  }
  val rows by
    produceState<ImmutableList<ListRow>>(persistentListOf(), path, naming) {
      val dir = File(root, path)
      value = withContext(Dispatchers.IO) { listRows(subfolders(dir, showHidden), dir).folders }
    }
  if (naming) {
    // The list's own dialog and checks: blank, already a file or folder, outside the store
    // (component-wise isInsideRepository).
    val dir = File(root, path)
    FolderNameDialog(
      title = stringResource(R.string.title_create_folder),
      message = null,
      confirm = stringResource(R.string.button_create),
      validate = { folderNameError(it, File(dir, it)) },
      onConfirm = { name ->
        val folder = File(dir, name).canonicalFile
        folder.mkdirs()
        naming = false
        path = folder.relativeTo(root.canonicalFile).path
      },
      onDismiss = { naming = false },
    )
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(R.string.folder_picker_title)) },
    text = {
      Column {
        BreadcrumbBar(
          crumbsFor(path),
          stringResource(R.string.list_store_root),
          onCrumbClick = { path = it },
          modifier = Modifier.fillMaxWidth().padding(bottom = SpacingMedium),
        )
        // ponytail: a plain scrolling column, fine for the folders of one level; a LazyColumn
        // if a store ever has thousands of subfolders in one folder.
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
          rows.forEach { row ->
            key(row.path) {
              FolderRow(
                name = row.label,
                count = row.count,
                onClick = { path = File(row.path).relativeTo(root).path },
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onPick(path) }) { Text(stringResource(R.string.crypto_select)) }
    },
    dismissButton = {
      Row {
        TextButton(onClick = { naming = true }) {
          Text(stringResource(R.string.folder_picker_new))
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
      }
    },
  )
}
```

`mkdirs` runs on the main thread, as the list's own "Create folder" does: one directory, no
listing. An empty folder is not tracked by git, so nothing is committed until an entry lands in
it, exactly as with the list's "Create folder".

- [ ] **Step 2: Share the list's folder name dialog**

In `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`, replace

```kotlin
/** Asks for a folder name; [validate] returns an error string resource or null. */
@Composable
private fun FolderNameDialog(
```

with

```kotlin
/**
 * Asks for a folder name; [validate] returns an error string resource or null. Shared with the
 * folder picker's "New folder".
 */
@Composable
internal fun FolderNameDialog(
```

(`internal`, not public: Slack's `ComposeModifierMissing` checks public composables only, and the
dialog has no layout to modify. If lint still flags it, add `modifier: Modifier = Modifier` after
`onDismiss` and pass it to its `AlertDialog`.)

- [ ] **Step 3: Strings**

In `app/src/main/res/values/strings.xml`, before `</resources>`, add:

```xml

  <!-- Compose folder picker -->
  <string name="folder_picker_title">Choose a folder</string>
  <string name="folder_picker_new">New folder</string>
```

In `app/src/main/res/values-it/strings.xml`, before `</resources>`, add:

```xml

  <!-- Compose folder picker -->
  <string name="folder_picker_title">Scegli una cartella</string>
  <string name="folder_picker_new">Nuova cartella</string>
```

- [ ] **Step 4: The list's move uses the picker**

In `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`:

Delete these imports:

```kotlin
import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
```

and

```kotlin
import app.passwordstore.ui.folderselect.SelectFolderActivity
```

In `enum class ListDialog`, replace

```kotlin
  Delete,
```

with

```kotlin
  Delete,
  Move,
```

Delete the whole `moveLauncher` block:

```kotlin
  val moveLauncher =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      val data = result.data
      val target = data?.getStringExtra("SELECTED_FOLDER_PATH")?.let(::File)
      val files = data?.getStringArrayExtra("Files")?.map(::File)
      if (result.resultCode == Activity.RESULT_OK && target?.isDirectory == true && files != null) {
        selected = emptyList()
        activity.lifecycleScope.launch {
          activity.moveItems(files, target)
          refresh()
        }
      }
    }

```

Replace

```kotlin
      onMove = {
        moveLauncher.launch(
          Intent(activity, SelectFolderActivity::class.java)
            .putExtra("Files", selected.toTypedArray())
        )
      },
```

with

```kotlin
      onMove = { dialog = ListDialog.Move },
```

In the `when (dialog)` block, after the `ListDialog.Delete -> Prompt(...)` case (before
`ListDialog.Clone ->`), add:

```kotlin
      ListDialog.Move ->
        FolderPicker(
          root = root,
          start = path,
          onPick = { target ->
            // One move per pick: a double tap on Select must not move twice.
            if (dialog == ListDialog.Move) {
              val files = selected.map(::File)
              dialog = ListDialog.None
              selected = emptyList()
              activity.lifecycleScope.launch {
                activity.moveItems(files, File(root, target))
                refresh()
              }
            }
          },
          onDismiss = { dialog = ListDialog.None },
        )
```

In the KDoc of `ListDestination`, replace

```kotlin
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * legacy activities and the store operations. [onOpenFolder] takes a path relative to the root,
 * [onOpenEntry] an entry file.
```

with

```kotlin
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * folder picker, the legacy activities and the store operations. [onOpenFolder] takes a path
 * relative to the root, [onOpenEntry] an entry file.
```

- [ ] **Step 5: Checks and commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. (Full move and "New folder" check on the emulator in Task 8,
step 9.)

```bash
git add app/src/main/java/app/passwordstore/ui/list/FolderPicker.kt \
  app/src/main/java/app/passwordstore/ui/list/ListDestination.kt \
  app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat(app): add the Compose folder picker for moves

A dialog with breadcrumbs, subfolder rows and New folder replaces the
legacy folder picker activity for the list's move action; the editor will
share it. New folder reuses the list's name dialog and checks. A second
tap on Select no longer moves twice.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Editor ViewModel

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/editor/EditorViewModel.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/StoreActions.kt`
- Modify: `app/src/main/java/app/passwordstore/injection/pwgen/DicewareModule.kt`

- [ ] **Step 1: Shared recently-used move in StoreActions**

In `app/src/main/java/app/passwordstore/ui/list/StoreActions.kt`, replace `renameFolder`:

```kotlin
/** Renames the folder [from] to [to], keeps its recently-used time, then commits. */
suspend fun BaseGitActivity.renameFolder(from: File, to: File) {
  if (!moveFile(from, to)) return
  val history = getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
  val time = history.getString(from.absolutePath.base64())
  if (time != null)
    history.edit {
      remove(from.absolutePath.base64())
      putString(to.absolutePath.base64(), time)
    }
  commitChange(getString(R.string.git_commit_move_text, from.name, to.name))
}
```

with

```kotlin
/** Renames the folder [from] to [to], keeps its recently-used time, then commits. */
suspend fun BaseGitActivity.renameFolder(from: File, to: File) {
  if (!moveFile(from, to)) return
  moveHistory(from, to)
  commitChange(getString(R.string.git_commit_move_text, from.name, to.name))
}

/** Carries the "recently used" time of [from] over to [to]. */
fun Context.moveHistory(from: File, to: File) {
  val history = getSharedPreferences(HISTORY, Context.MODE_PRIVATE)
  val time = history.getString(from.absolutePath.base64()) ?: return
  history.edit {
    remove(from.absolutePath.base64())
    putString(to.absolutePath.base64(), time)
  }
}
```

- [ ] **Step 2: Diceware generator for ViewModels**

In `app/src/main/java/app/passwordstore/injection/pwgen/DicewareModule.kt`, add the import

```kotlin
import dagger.hilt.android.components.ViewModelComponent
```

and replace

```kotlin
@InstallIn(FragmentComponent::class)
```

with

```kotlin
@InstallIn(FragmentComponent::class, ViewModelComponent::class)
```

(`FragmentComponent` goes with the legacy dialog in Task 7.)

- [ ] **Step 3: The ViewModel**

`app/src/main/java/app/passwordstore/ui/editor/EditorViewModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.injection.prefs.PasswordGeneratorPreferences
import app.passwordstore.passgen.diceware.DicewarePassphraseGenerator
import app.passwordstore.passgen.random.PasswordOption
import app.passwordstore.ui.list.moveHistory
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.runCatching
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/** What a save did. */
sealed interface SaveResult {

  /**
   * Written to [file] from [form]. [staleOriginal] is the old file of a rename or move, left
   * behind because it could not be deleted.
   */
  data class Saved(val file: File, val form: EditorForm, val staleOriginal: File?) : SaveResult

  /** Nothing was written; [message] (a string resource, formatted with [arg]) says why. */
  data class Failed(@StringRes val message: Int, val arg: String = "") : SaveResult
}

/**
 * The editor's state and work: the [form] (plaintext, in memory only: never in saved state, reset
 * when this ViewModel is cleared), the generators and their stored options, QR decoding, and the
 * save (encrypt, write, replace the original). Holds no Context; calls take one and use the
 * application context.
 */
@HiltViewModel
class EditorViewModel
@Inject
constructor(
  private val repository: CryptoRepository,
  private val entryFactory: PasswordEntry.Factory,
  private val diceware: dagger.Lazy<DicewarePassphraseGenerator>,
  @PasswordGeneratorPreferences private val pwgenPrefs: SharedPreferences,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  /** The fields being edited. */
  var form by mutableStateOf(EditorForm())

  /** Set while a save runs, and after one succeeded (the editor is leaving): saves and closes wait. */
  var saving by mutableStateOf(false)
    private set

  /** Whether [start] filled the form. */
  var started = false
    private set

  /** Fills the form the first time only and returns true then; a recreated activity keeps edits. */
  fun start(initial: EditorForm): Boolean {
    if (started) return false
    started = true
    form = initial
    return true
  }

  /** True when [extra] already holds a TOTP secret (the OTP actions hide), as the old editor did. */
  fun hasOtp(extra: String): Boolean =
    entryFactory.create("PLACEHOLDER\n$extra".encodeToByteArray()).hasTotp()

  /** The random generator's options as the old dialog stored them. */
  fun randomSpec(context: Context): RandomSpec {
    val prefs = context.randomPrefs()
    return RandomSpec(
      numerals = !prefs.getBoolean(PasswordOption.NoDigits.key, false),
      symbols = prefs.getBoolean(PasswordOption.AtLeastOneSymbol.key, false),
      uppercase = !prefs.getBoolean(PasswordOption.NoUppercaseLetters.key, false),
      lowercase = !prefs.getBoolean(PasswordOption.NoLowercaseLetters.key, false),
      ambiguous = !prefs.getBoolean(PasswordOption.NoAmbiguousCharacters.key, false),
      pronounceable = !prefs.getBoolean(PasswordOption.FullyRandom.key, true),
      length = prefs.getInt(PreferenceKeys.LENGTH, 20),
    )
  }

  /** Stores [spec], as the old dialog did on every generation, then generates off the main thread. */
  suspend fun generateRandom(spec: RandomSpec, context: Context): Result<String, Int> {
    val options = spec.options()
    context.randomPrefs().edit {
      PasswordOption.entries.forEach { putBoolean(it.key, it in options) }
      putInt(PreferenceKeys.LENGTH, spec.length)
    }
    return withContext(dispatcherProvider.default()) { randomPassword(spec) }
  }

  /** The diceware generator's options as the old dialog stored them. */
  fun dicewareSpec(): DicewareSpec =
    DicewareSpec(
      words = pwgenPrefs.getInt(PreferenceKeys.DICEWARE_LENGTH, 5),
      separator = pwgenPrefs.getString(PreferenceKeys.DICEWARE_SEPARATOR)?.firstOrNull() ?: '-',
    )

  /** Stores [spec] and generates a passphrase; the word list is read on first use, off main. */
  suspend fun generateDiceware(spec: DicewareSpec): String {
    pwgenPrefs.edit {
      putInt(PreferenceKeys.DICEWARE_LENGTH, spec.words)
      putString(PreferenceKeys.DICEWARE_SEPARATOR, spec.separator.toString())
    }
    return withContext(dispatcherProvider.io()) {
      diceware.get().generatePassphrase(spec.words, spec.separator)
    }
  }

  /** The text of the QR code in the image at [uri], or null when it has none. Off main. */
  suspend fun readQr(uri: Uri, context: Context): String? {
    val resolver = context.applicationContext.contentResolver
    return withContext(dispatcherProvider.io()) {
      runCatching {
          val bitmap =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
              ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri))
                .copy(Bitmap.Config.ARGB_8888, false)
            } else {
              @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(resolver, uri)
            }
          val pixels = IntArray(bitmap.width * bitmap.height)
          bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
          val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
          QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        }
        .get()
    }
  }

  /**
   * Encrypts the form to [ids] and writes it to [target], replacing [original] when the entry was
   * renamed or moved. Nothing is written (not even the folder) when [target] would replace another
   * entry or when encryption fails; a recipient whose key is missing fails it (see
   * CryptoRepository.encrypt). The caller checked [formError] and that [target] is inside the
   * store. Returns null when a save already ran or is running.
   */
  suspend fun save(
    ids: List<PGPIdentifier>,
    target: File,
    original: File?,
    context: Context,
  ): SaveResult? {
    if (saving) return null
    saving = true
    var result: SaveResult? = null
    try {
      result = write(ids, target, original, context.applicationContext)
    } finally {
      // Stays set after a success (the editor is leaving); cleared on failure or cancellation.
      if (result !is SaveResult.Saved) saving = false
    }
    return result
  }

  override fun onCleared() {
    form = EditorForm()
  }

  private suspend fun write(
    ids: List<PGPIdentifier>,
    target: File,
    original: File?,
    app: Context,
  ): SaveResult {
    val form = form
    if (withContext(dispatcherProvider.io()) { overwrites(target, original) })
      return SaveResult.Failed(R.string.password_creation_duplicate_error)
    val out = ByteArrayOutputStream()
    val encrypted = repository.encrypt(ids, passContent(form).byteInputStream(), out)
    if (!encrypted.isOk) {
      logcat(ERROR) { encrypted.error.asLog() }
      return SaveResult.Failed(
        R.string.password_creation_encrypt_fail_message,
        encrypted.error.message.orEmpty(),
      )
    }
    return withContext(dispatcherProvider.io()) {
      try {
        writeEntry(target, out.toByteArray())
      } catch (e: IOException) {
        logcat(ERROR) { e.asLog() }
        return@withContext SaveResult.Failed(R.string.password_creation_file_write_fail_message)
      }
      var stale: File? = null
      if (original != null && original.canonicalPath != target.canonicalPath) {
        if (original.delete() || !original.exists()) {
          AutofillMatcher.updateMatches(app, moveFromTo = mapOf(original to target))
          app.moveHistory(original, target)
        } else {
          stale = original
        }
      }
      SaveResult.Saved(target, form, stale)
    }
  }

  private fun Context.randomPrefs(): SharedPreferences =
    getSharedPreferences(RANDOM_PREFS, Context.MODE_PRIVATE)

  private companion object {
    /** The old random generator dialog's preferences file. */
    const val RANDOM_PREFS = "PasswordGenerator"
  }
}
```

- [ ] **Step 4: Checks and commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL` (Hilt generates the ViewModel factory; nothing uses it yet). If Hilt
reports a missing binding for `PasswordEntry.Factory` in `ViewModelComponent`, check that
`TotpModule` still lists `ViewModelComponent` (added in step 3).

```bash
git add app/src/main/java/app/passwordstore/ui/editor/EditorViewModel.kt \
  app/src/main/java/app/passwordstore/ui/list/StoreActions.kt \
  app/src/main/java/app/passwordstore/injection/pwgen/DicewareModule.kt
git commit -m "feat(app): add the editor ViewModel

Holds the form in memory only, generates passwords with the stored
generator options, decodes QR images and saves off the main thread:
nothing is written when the target would replace another entry or when
encryption fails, the file is written through a temporary file, and a
renamed or moved entry takes its autofill matches and recently-used time
along.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Editor screen, generator sheet, OTP dialog and strings

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/editor/EditorScreen.kt`
- Create: `app/src/main/java/app/passwordstore/ui/editor/GeneratorSheet.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`

- [ ] **Step 1: Strings**

In `app/src/main/res/values/strings.xml`, before `</resources>`, add:

```xml

  <!-- Compose editor -->
  <string name="editor_new_title">New entry</string>
  <string name="editor_edit_title">Edit entry</string>
  <string name="editor_close">Close</string>
  <string name="editor_folder">Folder</string>
  <string name="editor_change_folder">Change</string>
  <string name="editor_otp_qr">OTP from QR</string>
  <string name="editor_otp_manual">Manual OTP</string>
  <string name="generator_random">Random</string>
  <string name="generator_diceware" translatable="false">Diceware</string>
  <string name="generator_use">Use</string>
```

In `app/src/main/res/values-it/strings.xml`, before `</resources>`, add:

```xml

  <!-- Compose editor -->
  <string name="editor_new_title">Nuova voce</string>
  <string name="editor_edit_title">Modifica voce</string>
  <string name="editor_close">Chiudi</string>
  <string name="editor_folder">Cartella</string>
  <string name="editor_change_folder">Cambia</string>
  <string name="editor_otp_qr">OTP da QR</string>
  <string name="editor_otp_manual">OTP manuale</string>
  <string name="generator_random">Casuale</string>
  <string name="generator_use">Usa</string>
```

(`generator_diceware` is not translatable, so it must not appear in `values-it`: lint's
`ExtraTranslation` would fail. Everything else reuses existing translated strings.)

- [ ] **Step 2: The screen and the OTP dialog**

`app/src/main/java/app/passwordstore/ui/editor/EditorScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.passwordstore.R
import app.passwordstore.ui.compose.BreadcrumbBar
import app.passwordstore.ui.compose.R as composeR
import app.passwordstore.ui.compose.crumbsFor
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/** Which field takes focus when the editor first opens. */
enum class EditorFocus {
  None,
  Name,
  Username,
}

/**
 * The editor: name, folder (breadcrumbs; they and "Change" open the picker), password with reveal
 * and generator, username, free-text extra content (the pass format as text) and the OTP actions,
 * shown while the extras hold no TOTP secret ([showOtpActions]). [onScanQr] is null on a device
 * without a camera. Only the reveal toggle and the menus keep state here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
  form: EditorForm,
  editing: Boolean,
  saving: Boolean,
  showOtpActions: Boolean,
  initiallyRevealed: Boolean,
  focus: EditorFocus,
  onFormChange: (EditorForm) -> Unit,
  onClose: () -> Unit,
  onSave: (copy: Boolean) -> Unit,
  onChangeFolder: () -> Unit,
  onGenerate: () -> Unit,
  onScanQr: (() -> Unit)?,
  onQrImage: () -> Unit,
  onManualOtp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var revealed by remember { mutableStateOf(initiallyRevealed) }
  var menu by remember { mutableStateOf(false) }
  var qrMenu by remember { mutableStateOf(false) }
  val nameFocus = remember { FocusRequester() }
  val usernameFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    when (focus) {
      EditorFocus.Name -> nameFocus.requestFocus()
      EditorFocus.Username -> usernameFocus.requestFocus()
      EditorFocus.None -> Unit
    }
  }
  val field = Modifier.fillMaxWidth().padding(horizontal = SpacingLarge)
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(if (editing) R.string.editor_edit_title else R.string.editor_new_title)
          )
        },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, stringResource(R.string.editor_close))
          }
        },
        actions = {
          TextButton(onClick = { onSave(false) }, enabled = !saving) {
            Text(stringResource(R.string.crypto_save))
          }
          Box {
            IconButton(onClick = { menu = true }) {
              Icon(Icons.Filled.MoreVert, stringResource(R.string.list_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.crypto_save_and_copy)) },
                onClick = {
                  menu = false
                  onSave(true)
                },
                enabled = !saving,
              )
            }
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(bottom = SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingMedium),
    ) {
      OutlinedTextField(
        value = form.name,
        onValueChange = { onFormChange(form.copy(name = it)) },
        modifier = field.focusRequester(nameFocus),
        label = { Text(stringResource(R.string.crypto_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
      )
      Text(
        stringResource(R.string.editor_folder),
        field,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        BreadcrumbBar(
          crumbsFor(form.dir),
          stringResource(R.string.list_store_root),
          onCrumbClick = { onChangeFolder() },
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChangeFolder, modifier = Modifier.padding(end = SpacingMedium)) {
          Text(stringResource(R.string.editor_change_folder))
        }
      }
      OutlinedTextField(
        value = form.password,
        onValueChange = { onFormChange(form.copy(password = it)) },
        modifier = field,
        label = { Text(stringResource(R.string.crypto_pass_label)) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        visualTransformation =
          if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
          ),
        trailingIcon = {
          Row {
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
            IconButton(onClick = onGenerate) {
              Icon(
                painterResource(R.drawable.ic_password_24px),
                contentDescription = stringResource(R.string.pwgen_title),
              )
            }
          }
        },
      )
      OutlinedTextField(
        value = form.username,
        onValueChange = { onFormChange(form.copy(username = it)) },
        modifier = field.focusRequester(usernameFocus),
        label = { Text(stringResource(R.string.crypto_username_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
      )
      OutlinedTextField(
        value = form.extra,
        onValueChange = { onFormChange(form.copy(extra = it)) },
        modifier = field,
        label = { Text(stringResource(R.string.crypto_extra_label)) },
        minLines = 3,
        // A password keyboard does not learn what is typed, as the old textVisiblePassword.
        keyboardOptions =
          KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
      )
      if (showOtpActions)
        Row(field, horizontalArrangement = Arrangement.spacedBy(SpacingMedium)) {
          Box {
            OutlinedButton(onClick = { qrMenu = true }) {
              Icon(painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null)
              Text(stringResource(R.string.editor_otp_qr), Modifier.padding(start = SpacingMedium))
            }
            DropdownMenu(expanded = qrMenu, onDismissRequest = { qrMenu = false }) {
              if (onScanQr != null)
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.otp_import_qr_code)) },
                  onClick = {
                    qrMenu = false
                    onScanQr()
                  },
                )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.otp_import_from_file)) },
                onClick = {
                  qrMenu = false
                  onQrImage()
                },
              )
            }
          }
          OutlinedButton(onClick = onManualOtp) {
            Text(stringResource(R.string.editor_otp_manual))
          }
        }
    }
  }
}

/**
 * Asks for a TOTP secret and an optional account (the URI's issuer), as the old manual OTP dialog
 * did. Both live in [remember] only, and the window is FLAG_SECURE.
 */
@Composable
fun OtpDialog(
  onAdd: (secret: String, account: String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var secret by remember { mutableStateOf("") }
  var account by remember { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    title = { Text(stringResource(R.string.add_otp)) },
    text = {
      Column {
        OutlinedTextField(
          value = secret,
          onValueChange = { secret = it },
          modifier = Modifier.focusRequester(focus),
          label = { Text(stringResource(R.string.otp_import_manual_hint_secret)) },
          singleLine = true,
          keyboardOptions =
            KeyboardOptions(
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
          value = account,
          onValueChange = { account = it },
          label = { Text(stringResource(R.string.otp_import_manual_hint_account)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = {
      TextButton(onClick = { onAdd(secret, account) }, enabled = secret.isNotBlank()) {
        Text(stringResource(R.string.dialog_ok))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}
```

- [ ] **Step 3: The generator sheet**

`app/src/main/java/app/passwordstore/ui/editor/GeneratorSheet.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.SecureFlagPolicy
import app.passwordstore.R
import app.passwordstore.passgen.random.PasswordGenerator
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.compose.theme.SpacingSmall
import com.github.michaelbull.result.Result

private const val RANDOM_TAB = 0
private const val DICEWARE_TAB = 1

/**
 * The password generator as a bottom sheet: a random tab and a diceware tab with the old dialogs'
 * options, starting from [random] and [diceware]. [classicFirst] opens the random tab (the
 * "Password generator" setting). Every change generates again; [onUse] takes the password shown.
 * The password lives in [remember] only, and the sheet's window is FLAG_SECURE.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorSheet(
  classicFirst: Boolean,
  random: RandomSpec,
  diceware: DicewareSpec,
  onRandom: suspend (RandomSpec) -> Result<String, Int>,
  onDiceware: suspend (DicewareSpec) -> String,
  onUse: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var tab by remember { mutableIntStateOf(if (classicFirst) RANDOM_TAB else DICEWARE_TAB) }
  var randomSpec by remember { mutableStateOf(random) }
  var dicewareSpec by remember { mutableStateOf(diceware) }
  var length by remember { mutableStateOf(random.length.toString()) }
  var words by remember { mutableStateOf(diceware.words.toString()) }
  var separator by remember { mutableStateOf(diceware.separator.toString()) }
  var rolls by remember { mutableIntStateOf(0) }
  var password by remember { mutableStateOf("") }
  var problem by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(tab, randomSpec, dicewareSpec, rolls) {
    if (tab == RANDOM_TAB) {
      val result = onRandom(randomSpec)
      password = if (result.isOk) result.value else ""
      problem = if (result.isOk) null else result.error
    } else {
      password = onDiceware(dicewareSpec)
      problem = null
    }
  }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    modifier = modifier,
    properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
  ) {
    PrimaryTabRow(selectedTabIndex = tab) {
      Tab(
        selected = tab == RANDOM_TAB,
        onClick = { tab = RANDOM_TAB },
        text = { Text(stringResource(R.string.generator_random)) },
      )
      Tab(
        selected = tab == DICEWARE_TAB,
        onClick = { tab = DICEWARE_TAB },
        text = { Text(stringResource(R.string.generator_diceware)) },
      )
    }
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingSmall),
    ) {
      val shown = problem
      Text(
        if (shown == null) password else stringResource(shown),
        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        color =
          if (shown == null) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.error,
      )
      if (tab == RANDOM_TAB) {
        CheckRow(R.string.pwgen_numerals, randomSpec.numerals) {
          randomSpec = randomSpec.copy(numerals = it)
        }
        CheckRow(R.string.pwgen_symbols, randomSpec.symbols) {
          randomSpec = randomSpec.copy(symbols = it)
        }
        CheckRow(R.string.pwgen_uppercase, randomSpec.uppercase) {
          randomSpec = randomSpec.copy(uppercase = it)
        }
        CheckRow(R.string.pwgen_lowercase, randomSpec.lowercase) {
          randomSpec = randomSpec.copy(lowercase = it)
        }
        CheckRow(R.string.pwgen_pronounceable, randomSpec.pronounceable) {
          randomSpec = randomSpec.copy(pronounceable = it)
        }
        CheckRow(R.string.pwgen_ambiguous, randomSpec.ambiguous) {
          randomSpec = randomSpec.copy(ambiguous = it)
        }
        OutlinedTextField(
          value = length,
          onValueChange = { text ->
            // ponytail: at most 999 characters; the generator has no upper bound of its own.
            val digits = text.filter(Char::isDigit).take(3)
            length = digits
            randomSpec =
              randomSpec.copy(length = digits.toIntOrNull() ?: PasswordGenerator.DEFAULT_LENGTH)
          },
          label = { Text(stringResource(R.string.pwgen_length)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      } else {
        OutlinedTextField(
          value = words,
          onValueChange = { text ->
            // ponytail: at most 99 words.
            val digits = text.filter(Char::isDigit).take(2)
            words = digits
            dicewareSpec =
              dicewareSpec.copy(words = digits.toIntOrNull()?.takeIf { it > 0 } ?: 5)
          },
          label = { Text(stringResource(R.string.pwgen_length)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
          value = separator,
          onValueChange = { text ->
            separator = text.take(1)
            dicewareSpec = dicewareSpec.copy(separator = separator.firstOrNull() ?: '-')
          },
          label = { Text(stringResource(R.string.pwgen_separator)) },
          singleLine = true,
        )
      }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingMedium, Alignment.End),
      ) {
        TextButton(onClick = { rolls++ }) { Text(stringResource(R.string.pwgen_generate)) }
        Button(
          onClick = { onUse(password) },
          enabled = password.isNotEmpty() && problem == null,
        ) {
          Text(stringResource(R.string.generator_use))
        }
      }
    }
  }
}

@Composable
private fun CheckRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = null)
    Text(stringResource(label), Modifier.padding(start = SpacingMedium))
  }
}
```

- [ ] **Step 4: Checks and commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. If `composeR` does not resolve, check the ui:compose namespace in
`ui/compose/build.gradle.kts` (`app.passwordstore.ui.compose`). If lint flags `PrimaryTabRow` or
`ModalBottomSheetProperties` as experimental beyond the existing `@OptIn`, add
`ExperimentalMaterial3Api` to that `@OptIn` only.

```bash
git add app/src/main/java/app/passwordstore/ui/editor/EditorScreen.kt \
  app/src/main/java/app/passwordstore/ui/editor/GeneratorSheet.kt \
  app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat(app): add the Compose editor screen and generator sheet

Stateless editor: name, folder breadcrumbs with Change, password with
reveal and generator, username, extra content and the OTP actions. The
generator sheet has random and diceware tabs with the old options; the
manual OTP dialog and the sheet are FLAG_SECURE.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Editor destination and navigation

After this task the list and the entry view open the Compose editor. `PasswordCreationActivity`
is still started by `AutofillSaveActivity` (Task 6).

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/editor/EditorDestination.kt`
- Modify (replace whole file): `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`

- [ ] **Step 1: The destination**

`app/src/main/java/app/passwordstore/ui/editor/EditorDestination.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.copyPasswordToClipboard
import app.passwordstore.ui.crypto.getPGPIdentifiers
import app.passwordstore.ui.list.FolderPicker
import app.passwordstore.util.extensions.commitChange
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.isInsideRepository
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.integration.android.IntentIntegrator
import java.io.File
import kotlinx.coroutines.launch

/**
 * The editor: wires [EditorScreen] to the [viewModel], the generator sheet, the folder picker, OTP
 * import and the save path (checks, recipients from `.gpg-id`, encryption, write, git commit).
 * [initial] fills the form the first time; null means the entry to edit is no longer in memory
 * (process death) and the editor closes. [original] is the file being edited, null for a new
 * entry. [generate] opens the generator at once (autofill's "generate password"). [onClose]
 * leaves without saving; [onSaved] runs after the commit.
 */
@Composable
fun EditorDestination(
  activity: FragmentActivity,
  initial: EditorForm?,
  original: File?,
  onClose: () -> Unit,
  onSaved: (SaveResult.Saved) -> Unit,
  modifier: Modifier = Modifier,
  generate: Boolean = false,
  viewModel: EditorViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()
  // ponytail: filled once per ViewModel, so a recreated activity keeps the edits; an edit restored
  // after process death has nothing to fill from (the entry view's plaintext is gone) and closes,
  // back to the entry view's passphrase prompt.
  val first = remember { initial != null && viewModel.start(initial) }
  val lost = !viewModel.started
  LaunchedEffect(lost) { if (lost) onClose() }
  if (lost) return

  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current
  val scope = rememberCoroutineScope()
  var sheet by rememberSaveable { mutableStateOf(first && generate) }
  var picker by rememberSaveable { mutableStateOf(false) }
  var otp by rememberSaveable { mutableStateOf(false) }
  var failure by remember { mutableStateOf<String?>(null) }
  val form = viewModel.form
  val hasOtp = remember(form.extra) { viewModel.hasOtp(form.extra) }
  val hasCamera = remember {
    activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
  }
  val showPassword = remember {
    activity.sharedPrefs.getBoolean(PreferenceKeys.SHOW_PASSWORD, true)
  }
  val classicFirst = remember {
    activity.sharedPrefs.getString(PreferenceKeys.PREF_KEY_PWGEN_TYPE) == "classic"
  }

  fun say(message: Int) {
    activity.snackbar(message = activity.getString(message))
  }

  fun addLine(line: String) {
    viewModel.form = viewModel.form.copy(extra = withLine(viewModel.form.extra, line))
  }

  // The one way out without saving: only while no save runs, keyboard and focus dropped first.
  fun close() {
    if (viewModel.saving) return
    keyboard?.hide()
    focusManager.clearFocus()
    onClose()
  }

  fun save(copy: Boolean) {
    if (viewModel.saving) return
    val current = viewModel.form
    val target = entryFile(root, current)
    val problem =
      formError(current)
        ?: R.string.message_error_destination_outside_repo.takeUnless {
          target.isInsideRepository()
        }
    if (problem != null) return say(problem)
    // Recipients from the nearest .gpg-id, as pass does; null: an invalid ID, already reported.
    val ids = activity.getPGPIdentifiers(current.dir) ?: return
    activity.lifecycleScope.launch {
      when (val result = viewModel.save(ids, target, original, activity)) {
        null -> Unit
        is SaveResult.Failed -> failure = activity.getString(result.message, result.arg)
        is SaveResult.Saved -> {
          if (copy) activity.copyPasswordToClipboard(result.form.password)
          result.staleOriginal?.let {
            activity.snackbar(
              message =
                activity.getString(
                  R.string.password_creation_file_delete_fail_message,
                  it.nameWithoutExtension,
                ),
              length = Snackbar.LENGTH_LONG,
            )
          }
          // ponytail: commit failures are not shown, as for the list's move and delete; a theme
          // or locale change in the middle of a save cancels before this commit, and the next
          // commit picks the file up.
          activity.commitChange(
            activity.getString(
              if (original != null) R.string.git_commit_edit_text
              else R.string.git_commit_add_text,
              target.relativeTo(root).path.removeSuffix(".gpg"),
            )
          )
          keyboard?.hide()
          focusManager.clearFocus()
          onSaved(result)
        }
      }
    }
  }

  BackHandler(onBack = ::close)

  val scan =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      val text = IntentIntegrator.parseActivityResult(result.resultCode, result.data)?.contents
      if (result.resultCode == Activity.RESULT_OK && text != null) {
        addLine("$text\n")
        say(R.string.otp_import_success)
      } else {
        say(R.string.otp_import_failure_generic)
      }
    }
  val image =
    rememberLauncherForActivityResult(GetContent()) { uri ->
      if (uri == null) {
        say(R.string.otp_import_failure_no_selection)
      } else {
        scope.launch {
          val text = viewModel.readQr(uri, activity)
          if (text != null) {
            addLine(text)
            say(R.string.otp_import_success)
          } else {
            say(R.string.otp_import_failure_generic)
          }
        }
      }
    }

  Box(modifier) {
    EditorScreen(
      form = form,
      editing = original != null,
      saving = viewModel.saving,
      showOtpActions = !hasOtp,
      initiallyRevealed = showPassword,
      focus =
        when {
          !first -> EditorFocus.None
          form.name.isEmpty() -> EditorFocus.Name
          original == null -> EditorFocus.Username
          else -> EditorFocus.None
        },
      onFormChange = { viewModel.form = it },
      onClose = ::close,
      onSave = ::save,
      onChangeFolder = { picker = true },
      onGenerate = { sheet = true },
      onScanQr =
        if (hasCamera) {
          {
            scan.launch(
              IntentIntegrator(activity)
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .createScanIntent()
            )
          }
        } else null,
      onQrImage = { image.launch("image/*") },
      onManualOtp = { otp = true },
    )
    if (sheet)
      GeneratorSheet(
        classicFirst = classicFirst,
        random = viewModel.randomSpec(activity),
        diceware = viewModel.dicewareSpec(),
        onRandom = { spec -> viewModel.generateRandom(spec, activity) },
        onDiceware = viewModel::generateDiceware,
        onUse = { password ->
          viewModel.form = viewModel.form.copy(password = password)
          sheet = false
        },
        onDismiss = { sheet = false },
      )
    if (picker)
      FolderPicker(
        root = root,
        start = form.dir,
        onPick = { dir ->
          viewModel.form = viewModel.form.copy(dir = dir)
          picker = false
        },
        onDismiss = { picker = false },
      )
    if (otp)
      OtpDialog(
        onAdd = { secret, account ->
          totpUri(secret, account)?.let(::addLine)
          otp = false
        },
        onDismiss = { otp = false },
      )
    failure?.let { message ->
      AlertDialog(
        onDismissRequest = { failure = null },
        title = { Text(stringResource(R.string.password_creation_file_fail_title)) },
        text = { Text(message) },
        confirmButton = {
          TextButton(onClick = { failure = null }) { Text(stringResource(R.string.dialog_ok)) }
        },
      )
    }
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
 * [name] (file name without `.gpg`) there. An edit takes its plaintext from the entry view below
 * it on the back stack, never from the route.
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
                  val root = PasswordRepository.getRepositoryDirectory().canonicalFile
                  val dir = file.canonicalFile.parentFile?.relativeTo(root)?.path.orEmpty()
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
                remember(entry) { runCatching { nav.getBackStackEntry<EntryRoute>() }.getOrNull() }
                  ?.let { hiltViewModel<EntryViewModel>(it).entry }
            SecureWindow(this@MainActivity)
            EditorDestination(
              activity = this@MainActivity,
              initial =
                if (name == null) EditorForm(dir = route.dir)
                else decrypted?.let { editorForm(it, route.dir, name) },
              original =
                name?.let { File(File(PasswordRepository.getRepositoryDirectory(), route.dir), "$it.gpg") },
              onClose = {
                if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack()
              },
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
```

(`createEntry` is gone: the list navigates to `EditRoute` itself.)

- [ ] **Step 3: EntryDestination opens the Compose editor**

In `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt`:

Delete these imports:

```kotlin
import android.view.WindowManager
```

```kotlin
import androidx.compose.runtime.DisposableEffect
```

```kotlin
import app.passwordstore.data.passfile.PasswordEntry
```

```kotlin
import app.passwordstore.ui.crypto.BasePGPActivity
```

```kotlin
import app.passwordstore.ui.crypto.PasswordCreationActivity
```

Replace the KDoc and signature start:

```kotlin
/**
 * One entry: wires [EntryScreen] to the [viewModel], the biometric and passphrase prompts, the
 * clipboard and the legacy editor. [onClose] leaves the destination; [onSaved] runs after the
 * editor saved the entry (the host returns to the list).
 */
@Composable
fun EntryDestination(
  activity: MainActivity,
  file: File,
  onClose: () -> Unit,
  onSaved: () -> Unit,
```

with

```kotlin
/**
 * One entry: wires [EntryScreen] to the [viewModel], the biometric and passphrase prompts and the
 * clipboard. [onClose] leaves the destination; [onEdit] opens the editor on the decrypted entry.
 * The host keeps FLAG_SECURE on while this is composed.
 */
@Composable
fun EntryDestination(
  activity: MainActivity,
  file: File,
  onClose: () -> Unit,
  onEdit: () -> Unit,
```

Delete:

```kotlin
  // Secrets stay out of screenshots and recents while this destination is composed.
  // ponytail: plain add/clear, fine while no other secret-showing destination can overlap this
  // one in a transition; count in MainActivity once the editor (step 4) becomes a destination.
  DisposableEffect(Unit) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
  }

```

Replace

```kotlin
  // ponytail: after process death during key import or the editor, the unlock flow can start
```

with

```kotlin
  // ponytail: after process death during key import, the unlock flow can start
```

Delete:

```kotlin
  val editor =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) onSaved()
    }
```

Replace

```kotlin
      onEdit = { entry?.let { editor.launch(editIntent(activity, file, root, it)) } },
```

with

```kotlin
      onEdit = { if (entry != null) onEdit() },
```

Delete the whole `editIntent` function with its KDoc:

```kotlin
/** The legacy editor, filled exactly as the old decrypt screen filled it. */
private fun editIntent(activity: Activity, file: File, root: File, entry: PasswordEntry): Intent =
  Intent(activity, PasswordCreationActivity::class.java)
    .setAction(Intent.ACTION_VIEW)
    .putExtra(BasePGPActivity.EXTRA_FILE_PATH, getParentPath(file.absolutePath, root.absolutePath))
    .putExtra(BasePGPActivity.EXTRA_REPO_PATH, root.absolutePath)
    .putExtra(PasswordCreationActivity.EXTRA_FILE_NAME, file.nameWithoutExtension)
    .putExtra(PasswordCreationActivity.EXTRA_USERNAME, entry.username)
    .putExtra(PasswordCreationActivity.EXTRA_PASSWORD, entry.password)
    .putExtra(PasswordCreationActivity.EXTRA_EXTRA_CONTENT, entry.extraContentWithoutUsername)
    .putExtra(PasswordCreationActivity.EXTRA_EDITING, true)

```

(`Activity`, `Intent`, the result launcher imports and `getParentPath` stay: the key import
launcher, the share intent and `unlock()` use them.)

- [ ] **Step 4: The list opens the editor**

In `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`, replace

```kotlin
 * The list at [path] (relative to the store root): wires [ListScreen] to the [viewModel], the
 * folder picker, the legacy activities and the store operations. [onOpenFolder] takes a path
 * relative to the root, [onOpenEntry] an entry file.
 */
@Composable
fun ListDestination(
  activity: MainActivity,
  path: String,
  onOpenFolder: (relativePath: String) -> Unit,
  onOpenEntry: (File) -> Unit,
```

with

```kotlin
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
```

and replace

```kotlin
            ifInitialized { activity.createEntry(dir) }
```

with

```kotlin
            ifInitialized(onNewEntry)
```

- [ ] **Step 5: Build and smoke check**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. If `popBackStack<ListRoute>(inclusive = false)` does not resolve,
use `nav.popBackStack(ListRoute::class, inclusive = false)`. If Slack lint's
`ComposeViewModelInjection` flags the `hiltViewModel<EntryViewModel>(it)` call in the `NavHost`
builder, move the lookup into a private top-level `@Composable fun editedEntry(nav:
NavHostController, entry: NavBackStackEntry): PasswordEntry?` in `MainActivity.kt` with a
`@Suppress("ComposeViewModelInjection")` and a one-line reason (it reads another destination's
ViewModel, it does not own one).

With the emulator from Task 8 running and the store cloned (`export ANDROID_SERIAL=emulator-5554`
first; a phone may be connected), `./gradlew :app:installDebug`, open the app, tap "New entry",
"Create new password": the Compose editor opens (`adb exec-out uiautomator dump /dev/tty | grep -o
'text="Save"'`); the close icon returns to the list. Full checks are in Task 8.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/editor/EditorDestination.kt \
  app/src/main/java/app/passwordstore/ui/main/MainActivity.kt \
  app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt \
  app/src/main/java/app/passwordstore/ui/list/ListDestination.kt
git commit -m "feat(app): open the Compose editor from the list and entry view

EditRoute replaces the legacy editor for new entries and edits. An edit
copies its plaintext from the entry view's ViewModel below it, never
through the route or saved state. Saving returns to the list, closing
returns to where the editor was opened. FLAG_SECURE is now counted in
MainActivity, so moving between the entry view and the editor never
clears it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Autofill save hosts the Compose editor

**Files:**
- Modify (replace whole file): `app/src/main/java/app/passwordstore/ui/autofill/AutofillSaveActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: The activity**

Replace `app/src/main/java/app/passwordstore/ui/autofill/AutofillSaveActivity.kt` with:

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
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.editor.EditorDestination
import app.passwordstore.ui.editor.EditorForm
import app.passwordstore.ui.editor.SaveResult
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.autofill.AutofillResponseBuilder
import app.passwordstore.util.extensions.unsafeLazy
import com.github.androidpasswordstore.autofillparser.AutofillAction
import com.github.androidpasswordstore.autofillparser.Credentials
import com.github.androidpasswordstore.autofillparser.FormOrigin
import dagger.hilt.android.AndroidEntryPoint
import logcat.LogPriority.ERROR
import logcat.logcat

/**
 * Saves credentials the autofill service caught (or generates a password for a form) in the
 * Compose editor, prefilled from the form's origin, then records the autofill match and, for a
 * generated password, fills it in.
 */
@AndroidEntryPoint
class AutofillSaveActivity : AppCompatActivity() {

  companion object {

    private const val EXTRA_FOLDER_NAME = "app.passwordstore.autofill.oreo.ui.EXTRA_FOLDER_NAME"
    private const val EXTRA_PASSWORD = "app.passwordstore.autofill.oreo.ui.EXTRA_PASSWORD"
    private const val EXTRA_NAME = "app.passwordstore.autofill.oreo.ui.EXTRA_NAME"
    private const val EXTRA_SHOULD_MATCH_APP =
      "app.passwordstore.autofill.oreo.ui.EXTRA_SHOULD_MATCH_APP"
    private const val EXTRA_SHOULD_MATCH_WEB =
      "app.passwordstore.autofill.oreo.ui.EXTRA_SHOULD_MATCH_WEB"
    private const val EXTRA_GENERATE_PASSWORD =
      "app.passwordstore.autofill.oreo.ui.EXTRA_GENERATE_PASSWORD"

    private var saveRequestCode = 1

    fun makeSaveIntentSender(
      context: Context,
      credentials: Credentials?,
      formOrigin: FormOrigin,
    ): IntentSender {
      val identifier = formOrigin.getPrettyIdentifier(context, untrusted = false)
      // Prevent directory traversals
      val sanitizedIdentifier =
        identifier.replace('\\', '_').replace('/', '_').trimStart('.').takeUnless { it.isBlank() }
          ?: formOrigin.identifier
      val directoryStructure = AutofillPreferences.directoryStructure(context)
      val folderName =
        directoryStructure.getSaveFolderName(
          sanitizedIdentifier = sanitizedIdentifier,
          username = credentials?.username,
        )
      val fileName =
        directoryStructure.getSaveFileName(
          username = credentials?.username,
          identifier = identifier,
        )
      val intent =
        Intent(context, AutofillSaveActivity::class.java).apply {
          putExtras(
            bundleOf(
              EXTRA_FOLDER_NAME to folderName,
              EXTRA_NAME to fileName,
              EXTRA_PASSWORD to credentials?.password,
              EXTRA_SHOULD_MATCH_APP to
                formOrigin.identifier.takeIf { formOrigin is FormOrigin.App },
              EXTRA_SHOULD_MATCH_WEB to
                formOrigin.identifier.takeIf { formOrigin is FormOrigin.Web },
              EXTRA_GENERATE_PASSWORD to (credentials == null),
            )
          )
        }
      return PendingIntent.getActivity(
          context,
          saveRequestCode++,
          intent,
          PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        .intentSender
    }
  }

  private val formOrigin by unsafeLazy {
    val shouldMatchApp: String? = intent.getStringExtra(EXTRA_SHOULD_MATCH_APP)
    val shouldMatchWeb: String? = intent.getStringExtra(EXTRA_SHOULD_MATCH_WEB)
    if (shouldMatchApp != null && shouldMatchWeb == null) {
      FormOrigin.App(shouldMatchApp)
    } else if (shouldMatchApp == null && shouldMatchWeb != null) {
      FormOrigin.Web(shouldMatchWeb)
    } else {
      null
    }
  }

  private val generate by unsafeLazy { intent.getBooleanExtra(EXTRA_GENERATE_PASSWORD, false) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The whole window is the editor: keep it out of screenshots and recents.
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val initial =
      EditorForm(
        name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
        dir = intent.getStringExtra(EXTRA_FOLDER_NAME).orEmpty().trim('/'),
        password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
      )
    setContent {
      APSTheme {
        EditorDestination(
          activity = this,
          initial = initial,
          original = null,
          onClose = {
            setResult(RESULT_CANCELED)
            finish()
          },
          onSaved = ::finishSaved,
          generate = generate,
        )
      }
    }
  }

  /** Records the autofill match and, for a generated password, returns it to fill the form. */
  private fun finishSaved(saved: SaveResult.Saved) {
    formOrigin?.let { AutofillMatcher.addMatchFor(this, it, saved.file) }
    val resultIntent =
      if (generate) {
        val clientState =
          intent?.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
            ?: run {
              logcat(ERROR) { "AutofillSaveActivity started without EXTRA_CLIENT_STATE" }
              finish()
              return
            }
        val username =
          saved.form.username.ifEmpty { null }
            ?: AutofillPreferences.directoryStructure(this).getUsernameFor(saved.file)
        val credentials = Credentials(username, saved.form.password, null)
        val fillInDataset =
          AutofillResponseBuilder.makeFillInDataset(
            this,
            credentials,
            clientState,
            AutofillAction.Generate,
          )
        Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, fillInDataset)
      } else {
        // The password came from the form: there is nothing to fill.
        Intent()
      }
    setResult(RESULT_OK, resultIntent)
    finish()
  }
}
```

- [ ] **Step 2: Manifest**

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <activity
      android:name=".ui.autofill.AutofillSaveActivity"
      android:exported="false"
      android:theme="@style/NoBackgroundThemeM3" />
```

with

```xml
    <!-- ponytail: rotation must not cancel a save running in lifecycleScope, as in MainActivity. -->
    <activity
      android:name=".ui.autofill.AutofillSaveActivity"
      android:configChanges="orientation|screenSize"
      android:exported="false"
      android:theme="@style/NoActionBarThemeM3"
      android:windowSoftInputMode="adjustResize" />
```

(The old transparent theme hid a trampoline that started the editor; the activity now is the
editor.)

- [ ] **Step 3: Checks and commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. `grep -rn PasswordCreationActivity app/src/main/java` now lists only
`PasswordCreationActivity.kt` and the three legacy dialogs.

```bash
git add app/src/main/java/app/passwordstore/ui/autofill/AutofillSaveActivity.kt \
  app/src/main/AndroidManifest.xml
git commit -m "feat(app): host the Compose editor in the autofill save flow

AutofillSaveActivity shows the editor itself, prefilled from the form
and FLAG_SECURE, instead of starting the legacy editor for a result. The
autofill match and the generated-password fill work as before.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Remove the legacy editor, generators and folder picker

**Files:**
- Delete: the files listed in "Deleted in this step" above
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/injection/pwgen/DicewareModule.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Modify: `app/lint-baseline.xml`
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`

- [ ] **Step 1: Delete the files**

```bash
git rm app/src/main/java/app/passwordstore/ui/crypto/PasswordCreationActivity.kt \
  app/src/main/java/app/passwordstore/ui/crypto/EditPasswordScreen.kt \
  app/src/main/java/app/passwordstore/ui/dialogs/PasswordGeneratorDialogFragment.kt \
  app/src/main/java/app/passwordstore/ui/dialogs/DicewarePasswordGeneratorDialogFragment.kt \
  app/src/main/java/app/passwordstore/ui/dialogs/OtpImportDialogFragment.kt \
  app/src/main/java/app/passwordstore/ui/folderselect/SelectFolderActivity.kt \
  app/src/main/java/app/passwordstore/ui/folderselect/SelectFolderFragment.kt \
  app/src/main/java/app/passwordstore/ui/adapters/PasswordItemRecyclerAdapter.kt \
  app/src/main/res/layout/password_creation_activity.xml \
  app/src/main/res/layout/fragment_pwgen.xml \
  app/src/main/res/layout/fragment_pwgen_diceware.xml \
  app/src/main/res/layout/fragment_manual_otp_entry.xml \
  app/src/main/res/layout/select_folder_layout.xml \
  app/src/main/res/layout/password_recycler_view.xml \
  app/src/main/res/layout/password_row_layout.xml \
  app/src/main/res/menu/pgp_handler_new_password.xml \
  app/src/main/res/menu/pgp_handler_select_folder.xml \
  app/src/main/res/drawable/ic_save_24dp.xml \
  app/src/main/res/drawable/ic_save_copy_24dp.xml \
  ui/compose/src/main/kotlin/app/passwordstore/ui/compose/PasswordField.kt
```

- [ ] **Step 2: Manifest**

In `app/src/main/AndroidManifest.xml`, delete

```xml
    <activity
      android:name=".ui.crypto.PasswordCreationActivity"
      android:exported="false"
      android:label="@string/new_password_title"
      android:windowSoftInputMode="adjustResize" />

```

and

```xml
    <activity
      android:name=".ui.folderselect.SelectFolderActivity"
      android:exported="false" />
```

- [ ] **Step 3: BasePGPActivity**

In `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt`, delete

```kotlin
  /** Full path to the repository */
  val repoPath by unsafeLazy { intent.getStringExtra("REPO_PATH")!! }

  /** Full path to the password file being worked on */
  val fullPath by unsafeLazy { intent.getStringExtra("FILE_PATH")!! }

```

the line

```kotlin
    const val EXTRA_REPO_PATH = "REPO_PATH"
```

and the import

```kotlin
import app.passwordstore.util.extensions.unsafeLazy
```

- [ ] **Step 4: Diceware only for ViewModels**

In `app/src/main/java/app/passwordstore/injection/pwgen/DicewareModule.kt`, replace

```kotlin
@InstallIn(FragmentComponent::class, ViewModelComponent::class)
```

with

```kotlin
@InstallIn(ViewModelComponent::class)
```

and delete the import `import dagger.hilt.android.components.FragmentComponent`.

- [ ] **Step 5: Unused dependencies**

In `app/build.gradle.kts`, delete the lines

```kotlin
  implementation(libs.androidx.swiperefreshlayout)
```

```kotlin
  implementation(libs.thirdparty.flowbinding.android)
```

In `gradle/libs.versions.toml`, delete the lines

```toml
flowbinding = "1.2.0"
```

```toml
androidx-swiperefreshlayout = "androidx.swiperefreshlayout:swiperefreshlayout:1.2.0"
```

```toml
thirdparty-flowbinding-android = { module = "io.github.reactivecircus.flowbinding:flowbinding-android", version.ref = "flowbinding" }
```

- [ ] **Step 6: Lint baseline**

`app/lint-baseline.xml` has two `SetTextI18n` issues located in the deleted generator dialogs.
Remove both `<issue>` blocks:

```bash
perl -0pi -e 's/\n    <issue\n        id="SetTextI18n"\n(?:(?!<\/issue>).)*?GeneratorDialogFragment\.kt(?:(?!<\/issue>).)*?<\/issue>\n//gs' app/lint-baseline.xml
grep -c GeneratorDialogFragment app/lint-baseline.xml
```
Expected: `0`, and `git diff app/lint-baseline.xml` shows exactly two removed `<issue>` blocks.

- [ ] **Step 7: Nothing refers to the removed code**

```bash
grep -rnwE 'PasswordCreationActivity|PasswordGeneratorDialogFragment|DicewarePasswordGeneratorDialogFragment|OtpImportDialogFragment|SelectFolderActivity|SelectFolderFragment|PasswordItemRecyclerAdapter|EditPasswordScreen|CopyButton|EXTRA_REPO_PATH|repoPath|fullPath|flowbinding|swiperefreshlayout' app/src ui/compose/src app/build.gradle.kts gradle/libs.versions.toml
grep -rnE 'PasswordCreationActivityBinding|FragmentPwgen|FragmentManualOtpEntryBinding|SelectFolderLayoutBinding|PasswordRecyclerViewBinding|R\.menu\.pgp_handler_(new_password|select_folder)|ic_save(_copy)?_24dp|ui\.compose\.PasswordField' app/src ui/compose/src
```
Expected: no output from either.

- [ ] **Step 8: The spec**

In `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`, section "Editor", replace

```
- "OTP from QR" and "OTP manual" actions.
```

with

```
- "OTP from QR" (camera or image) and "OTP manual" actions.
- "Save and copy" in the overflow. Saving returns to the list; closing returns to where the
  editor was opened. The folder picker is a dialog, shared with the list's move action. The
  autofill save flow (`AutofillSaveActivity`) hosts the same editor.
```

and in section "Security", replace

```
- `FLAG_SECURE` is set on the window while the entry view or editor destination is shown and
  cleared when leaving it, so secrets stay out of screenshots and recents.
```

with

```
- `FLAG_SECURE` is set on the window while the entry view or editor destination is shown and
  cleared when leaving it, so secrets stay out of screenshots and recents. It is counted, so
  moving between the two never clears it.
```

- [ ] **Step 9: Full local check**

Run:
```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`. If lint reports unused resources, they were used only by the
deleted files (likely candidates: `new_password_title`, `directory_hint`, `path_toast_text`,
`crypto_encrypt_username_label`, `pwd_generate_button`, `otp_import_manual_entry`,
`pwgen_include`, `select_folder`-style strings, `password_row_background` only if
`oreo_autofill_filter_row.xml` no longer uses it): confirm with `grep -rn` that nothing else uses
them, then delete them from every `values*/strings.xml` (a string left only in a translation is an
`ExtraTranslation` error) or from `res/drawable`, and re-run. If lint reports an issue in a file
from Tasks 1 to 6, fix it there and include it in this commit.

- [ ] **Step 10: Commit**

```bash
git add -A app/src/main app/build.gradle.kts gradle/libs.versions.toml app/lint-baseline.xml \
  ui/compose/src docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "refactor(app): remove the legacy editor, generators and folder picker

The Compose editor, generator sheet, OTP dialog and folder picker
replace PasswordCreationActivity, the generator and manual OTP dialog
fragments, SelectFolderActivity with its fragment and adapter, their
layouts and menus, and upstream's unused EditPasswordScreen with the
PasswordField it used. flowbinding and swiperefreshlayout had no other
users. BasePGPActivity keeps what autofill decryption still needs.

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
adb shell pm grant xyz.danix.hardenedpass.debug android.permission.CAMERA
scripts/e2e-env.sh push-keys
OUT=$(mktemp -d)
```

(`ANDROID_HOME` is the `sdk.dir` from `local.properties`. `push-keys` only after the unlock.
`installDebug` and `adb` both honor `ANDROID_SERIAL`. The uninstall also avoids a signature
mismatch between checkouts, see AGENTS.md. The camera grant keeps zxing's permission prompt out
of step 6; if `pm grant` says the permission is not requested, drop it and answer the prompt.)

Helpers for this shell. `center`, `tap` and `field` take an optional match index (1 = first);
in the editor the text fields are, in order, 1 name, 2 password, 3 username, 4 extra content.
`cmd clipboard` is not available on this image: clipboard checks paste into the list's search
field (keycode 279) instead.

```bash
ui() { adb exec-out uiautomator dump /dev/tty; }
center() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | sed -n "${2:-1}p" | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }; set -- $b; echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; }
tap() { local c; c=$(center "$1" "$2") && adb shell input tap $c; sleep 1; }
hold() { local c; c=$(center "$1") && set -- $c && adb shell input swipe $1 $2 $1 $2 900; sleep 1; }
field() { tap 'class="android.widget.EditText"' "$1"; adb shell input keycombination 113 29; adb shell input keyevent 67; adb shell input text "$2"; }
value() { ui | grep -o '<node [^>]*class="android.widget.EditText"[^>]*>' | sed -n "${1}p" | grep -o ' text="[^"]*"' | cut -d'"' -f2; }
seen() { ui | grep -q "$1" && echo "ok: $1" || echo "MISSING: $1"; }
top() { adb shell dumpsys activity activities | grep topResumedActivity; }
open() { adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity; sleep 3; }
secure() { adb shell dumpsys window windows | awk -v a="${1:-ui[.]main[.]MainActivity}" '/Window #/{w=($0 ~ a)} w && /fl=/{print (/SECURE/ ? "secure" : "not secure"); w=0}'; }
pass() { tap 'class="android.widget.EditText"'; adb shell input text "$1"; tap 'text="OK"'; sleep 2; }
store() { adb shell run-as xyz.danix.hardenedpass.debug ls -a "files/store/$1"; }
srv() { docker exec -u git aps-e2e-git git -C /srv/git/store.git "$@"; }
reveal() { srv show "master:$1.gpg" > "$OUT/x.gpg"; GNUPGHOME=.e2e/gnupg gpg -q --batch --pinentry-mode loopback --passphrase test -d "$OUT/x.gpg"; }
```

- [ ] **Step 2: Onboarding and clone**

`open`, then go through onboarding: "Let's Go", "Clone Remote Repo", Repository URL
`ssh://git@10.0.2.2:2222/srv/git/store.git`, authentication mode "SSH key" (the toggle may need a
second tap before it shows as selected), Save; import the SSH key `aps-e2e-ssh-key` from Download
when asked, accept the host key, then "Select key" and import `aps-e2e-pgp-key.asc` from Download.
Expected afterwards: `top` shows `MainActivity`; `seen 'text="example.org"'` prints `ok`;
`secure` prints `not secure`.

- [ ] **Step 3: New entry in the root, random generator, Save and copy**

```bash
tap 'text="New entry"'; tap 'text="Create new password"'
top; seen 'text="Save"'; seen 'text="New entry"'; secure
adb shell input text site.example.net                       # the name field has focus
tap 'content-desc="Generate Password"'; tap 'text="Random"'
seen 'text="Numerals"'; seen 'text="Pronounceable"'; seen 'text="Ambiguous"'
tap 'text="Pronounceable"'                                   # an option change regenerates
adb exec-out screencap -p > "$OUT/sheet.png"
tap 'text="Use"'
PW=$(value 2); echo "${#PW} $PW"
field 3 erin
tap 'content-desc="More options"'; tap 'text="Save and Copy"'
sleep 3; top; seen 'text="site.example.net"'; secure
tap 'text="Search"'; adb shell input keyevent 279; seen "text=\"$PW\""
adb shell input keyevent 4; adb shell input keyevent 4
```
Expected: the editor is a `MainActivity` destination (no `PasswordCreationActivity`), `secure`;
the sheet shows the random options; `$OUT/sheet.png` is black (open it); the password field holds
a 20-character password (`20 ...`); after saving, the list shows `site.example.net`, the window is
`not secure`, and the paste puts the same password in the search field (copied with the clear
timer).

- [ ] **Step 4: New entry in a subfolder, diceware generator**

```bash
tap 'text="work"'; tap 'text="New entry"'; tap 'text="Create new password"'
seen 'text="work"'                                            # folder crumb
adb shell input text vpn
tap 'content-desc="Generate Password"'; tap 'text="Diceware"'
seen 'text="Separator"'
field 2 _                                                     # in the sheet: 1 words, 2 separator
ui | grep -o 'text="[^"]*_[^"]*"'
tap 'text="Use"'; DW=$(value 2); echo "$DW"
tap 'text="Save"'; sleep 3; seen 'text="vpn"'; secure
```
Expected: the editor shows the `work` crumb; the sheet's preview turns into five words joined by
`_`; the password field holds it; `vpn` is listed in `work`; `not secure`.

- [ ] **Step 5: Edit: rename, move with the picker, new password; cancel back to the entry**

```bash
adb shell input keyevent 4                                    # root
tap 'text="example.org"'; pass test; seen 'text="hunter2"'; secure
tap 'content-desc="Edit password"'
seen 'text="Edit entry"'; seen 'text="example.org"'; seen 'text="hunter2"'; seen 'text="alice"'
seen 'url: https://example.org'; secure
tap 'content-desc="Close"'; seen 'text="hunter2"'; secure      # cancel: back on the entry view
tap 'content-desc="Edit password"'
field 1 example-login
tap 'text="Change"'; seen 'text="Choose a folder"'; top
tap 'text="work"'; tap 'text="Select"'; seen 'text="work"'
field 2 n3w-pass
tap 'text="Save"'; sleep 3
top; seen 'text="otp"'; seen 'text="example.org"'; secure
store; store work
tap 'text="work"'; tap 'text="example-login"'; pass test
seen 'text="n3w-pass"'; seen 'text="alice"'; seen 'text="https://example.org"'
adb shell input keyevent 4
```
Expected: the editor opens filled in and `secure`; Close returns to the decrypted entry view, still
`secure` (entry to editor to entry never cleared the flag); the picker is a dialog over
`MainActivity`; after Save the root list is back (`ok: text="otp"`), `MISSING:
text="example.org"`, `not secure`; `store` lists no `example.org.gpg` at the root and `store work`
lists `example-login.gpg`; the moved entry decrypts with the new password, its username and url.

- [ ] **Step 6: OTP from QR (menu only) and manual OTP**

```bash
tap 'text="vpn"'; pass test; tap 'content-desc="Edit password"'
tap 'text="OTP from QR"'; seen 'text="Scan QR code"'; seen 'text="Choose an image"'
tap 'text="Choose an image"'; sleep 2; top
adb shell input keyevent 4; sleep 1; seen 'text="No image file was selected"'
tap 'text="OTP from QR"'; tap 'text="Scan QR code"'; sleep 3; top
adb shell input keyevent 4; sleep 1; seen 'text="Failed to import TOTP configuration"'
tap 'text="Manual OTP"'; seen 'text="Add OTP"'
adb shell input text JBSWY3DPEHPK3PXP                          # the secret field has focus
tap 'class="android.widget.EditText"' 2; adb shell input text example.net
tap 'text="OK"'
seen 'otpauth://totp?secret=JBSWY3DPEHPK3PXP&amp;issuer=example.net'; seen 'text="Manual OTP"'
tap 'text="Save"'; sleep 3; seen 'text="vpn"'
```
Expected: the QR menu offers both sources; "Choose an image" opens the system picker
(`com.android.documentsui` or `com.google.android.documentsui` on top) and backing out shows the
"no image" snackbar; "Scan QR code" opens zxing's `CaptureActivity`, backing out shows the failure
snackbar. QR decoding itself cannot be exercised on the emulator (no QR in front of its virtual
camera, no QR image on the device); the maintainer checks it on the phone. The manual dialog adds
the URI line to the extra content and both OTP actions disappear (`MISSING: text="Manual OTP"`).
The OTP entry view never goes idle, so its code is checked on the server in step 11, not here.

- [ ] **Step 7: Missing recipient refusal (0.1.2)**

```bash
FPR=$(cat .e2e/seed/.gpg-id)
printf '%s\nMissing Key <missing@example.org>\n' "$FPR" | adb exec-in "run-as xyz.danix.hardenedpass.debug sh -c 'mkdir -p files/store/missing && cat > files/store/missing/.gpg-id'"
adb shell input keyevent 4; sleep 1                            # root, re-read on resume
tap 'text="missing"'; tap 'text="New entry"'; tap 'text="Create new password"'
adb shell input text nope; field 2 x
tap 'text="Save"'; sleep 2
seen 'text="Error"'; ui | grep -o 'nothing was saved[^"]*'
tap 'text="OK"'; seen 'text="nope"'
tap 'content-desc="Close"'; sleep 1; seen 'text="nope"'
store missing
adb shell run-as xyz.danix.hardenedpass.debug rm -r files/store/missing
adb shell input keyevent 4
```
Expected: saving shows the "Error" dialog with "Could not encrypt the password, nothing was saved:
No key found with id: ..." naming the missing recipient; OK leaves the editor open with its
content; Close returns to the folder without a `nope` entry; `store missing` lists only `.`, `..`
and `.gpg-id` (no file, not even an empty one). The folder is removed again before the sync.

- [ ] **Step 8: Cancel paths and rotation mid-edit**

```bash
tap 'text="New entry"'; tap 'text="Create new password"'
adb shell input text cancel-test; tap 'content-desc="Close"'; sleep 1
seen 'text="cancel-test"'; secure
tap 'text="New entry"'; tap 'text="Create new password"'
adb shell input text rot-test; field 2 abc123; field 4 line1
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 2
seen 'text="rot-test"'; seen 'text="abc123"'; seen 'text="line1"'; secure
adb shell settings put system user_rotation 0; sleep 2; seen 'text="rot-test"'
for i in 1 2; do ui | grep -q 'text="Save"' && adb shell input keyevent 4; sleep 1; done
seen 'text="rot-test"'; secure; store
```
Expected: Close discards (`MISSING: text="cancel-test"`, `not secure`); after rotation the editor
still shows all three values and is `secure`; system back (after the keyboard, if it was up)
discards too (`MISSING: text="rot-test"`, `not secure`), and `store` lists neither file.

- [ ] **Step 9: List move with the new picker**

```bash
hold 'text="site.example.net"'; tap 'content-desc="Move"'
seen 'text="Choose a folder"'; top
tap 'text="work"'; tap 'content-desc="Password store"'        # into a folder, back to the root
tap 'text="New folder"'; seen 'text="Create folder"'
tap 'text="Create"'; seen 'Category name can'                 # blank
adb shell input text work; tap 'text="Create"'; seen 'text="A folder by that name already exists"'
field 1 ../outside; tap 'text="Create"'; seen 'text="Destination must be within the repository"'
field 1 archive; tap 'text="Create"'; seen 'text="archive"'; seen 'text="Choose a folder"'
store; store archive; adb shell run-as xyz.danix.hardenedpass.debug ls files
tap 'content-desc="Password store"'
tap 'text="otp"'; tap 'text="Select"'; sleep 2
seen 'text="site.example.net"'; seen 'text="archive"'
tap 'text="otp"'; seen 'text="site.example.net"'
adb shell input keyevent 4
```
Expected: the picker is a dialog over `MainActivity` (no `SelectFolderActivity`), its breadcrumbs
jump back to the root. "New folder" opens the list's "Create folder" dialog over the picker and
refuses, with the list's messages, a blank name, an existing folder (`work` at the root) and a
name that resolves outside the store (`../outside`); `archive` is created and the picker shows it
(crumb `archive`, still the picker); `store` lists `archive`, `store archive` lists only `.` and
`..`, and `ls files` shows no `outside` next to `store`. Back at the root crumb, Select on `otp` moves
the entry: gone from the root (`MISSING: text="site.example.net"`), `archive` listed in the
root, the entry listed in `otp`. (`archive` stays empty, so git never sees it and step 11's
server tree is unchanged.)

- [ ] **Step 10: Autofill save flow (if `adb root` works on this image)**

```bash
if adb root | grep -q 'cannot run as root'; then echo "skip: no adb root"; else
  adb wait-for-device; sleep 2
  adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.autofill.AutofillSaveActivity \
    --es app.passwordstore.autofill.oreo.ui.EXTRA_FOLDER_NAME example.net \
    --es app.passwordstore.autofill.oreo.ui.EXTRA_NAME dave \
    --es app.passwordstore.autofill.oreo.ui.EXTRA_PASSWORD from-the-form \
    --es app.passwordstore.autofill.oreo.ui.EXTRA_SHOULD_MATCH_WEB example.net
  sleep 3; top; seen 'text="dave"'; seen 'text="from-the-form"'; seen 'text="example.net"'
  secure 'AutofillSaveActivity'
  tap 'text="Save"'; sleep 3; top
  adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.autofill.AutofillSaveActivity \
    --es app.passwordstore.autofill.oreo.ui.EXTRA_FOLDER_NAME example.net \
    --es app.passwordstore.autofill.oreo.ui.EXTRA_NAME frank \
    --ez app.passwordstore.autofill.oreo.ui.EXTRA_GENERATE_PASSWORD true
  sleep 3; seen 'text="Use"'; tap 'text="Use"'; tap 'text="Save"'; sleep 3; top
  adb unroot; adb wait-for-device; sleep 2
fi
open; tap 'text="example.net"'; seen 'text="dave"'; seen 'text="frank"'; adb shell input keyevent 4
```
Expected (with root): `AutofillSaveActivity` on top showing the editor prefilled with `dave`,
`from-the-form` and the `example.net` crumb, `secure`; Save finishes it. The second start opens
with the generator sheet already up; Use and Save finish it (no autofill client state from the
shell, so it only logs that and closes; the file is still saved and committed). The list then
shows `example.net` with `dave` and `frank`. Without root, the step prints `skip` and the list
check is skipped too. Either way the real flow is the maintainer's check, not part of this run
(maintainer decision 1): on the tablet with Firefox, submit a login form and save it from the
autofill prompt, then use "Generate password" on a sign-up form; both open this editor, save,
and the generated password is filled in. Record the result in the session handoff.

- [ ] **Step 11: Sync, and what the server got**

```bash
adb shell input swipe 540 700 540 1800 400; sleep 8
srv log --format=%s
srv ls-tree -r --name-only master
reveal otp/site.example.net; echo ---
reveal work/example-login; echo ---
reveal work/vpn; echo ---
python3 -c 'import hmac,hashlib,struct,time,base64;k=base64.b32decode("JBSWY3DPEHPK3PXP");h=hmac.new(k,struct.pack(">Q",int(time.time())//30),hashlib.sha1).digest();o=h[-1]&15;print("%06d"%((struct.unpack(">I",h[o:o+4])[0]&0x7fffffff)%1000000))'
```
Expected: no error dialog. The log shows, above `Initial test store`: `Add generated password
for site.example.net using HardenedPass.`, `Add generated password for work/vpn using
HardenedPass.`, `Edit password for work/example-login using HardenedPass.`, `Edit password for
work/vpn using HardenedPass.`, `Rename site.example.net to otp/site.example.net.` (plus the two
`example.net/...` additions when step 10 ran). The tree has no `example.org.gpg`, no
`missing/`, no `rot-test.gpg` or `cancel-test.gpg`, no `.tmp` file. Decrypted:
`otp/site.example.net` is `$PW`, then `username: erin`; `work/example-login` is `n3w-pass`,
`username: alice`, `url: https://example.org`; `work/vpn` is `$DW` then
`otpauth://totp?secret=JBSWY3DPEHPK3PXP&issuer=example.net`. (The Python line prints the current
code for that secret, for a by-eye comparison if wanted: open `work/vpn` and take an emulator
console screenshot, as in step 3's plan.)

- [ ] **Step 12: Tear down and push**

```bash
adb shell settings put system user_rotation 0; adb shell settings put system accelerometer_rotation 1
adb uninstall xyz.danix.hardenedpass.debug
adb shell rm -f /sdcard/Download/aps-e2e-ssh-key /sdcard/Download/aps-e2e-pgp-key.asc
adb emu kill
scripts/e2e-env.sh down
unset ANDROID_SERIAL
rm -rf "$OUT"
git push
```

Expected: push succeeds to every `origin` push URL.

---

## Self-review notes

- **Spec coverage for step 4:** `EditRoute` is a Compose destination in `MainActivity`, opened from
  the create sheet in the current folder and from the entry view's edit action (Task 5); after save
  the list, after cancel back where it came from (Task 5, checked in Task 8 steps 3, 5, 8). Top bar
  close, title, Save; name; folder chips with Change opening the picker; password with reveal and
  generator; generator bottom sheet with random and diceware tabs and the old options; username;
  free-text extras; OTP from QR and manual (Tasks 4, 5). Strings in `values/` and `values-it/`
  ("voce" for entry; Tasks 2, 4). Folder picker shared with the list's move (Tasks 2, 5; checked
  in steps 5 and 9, "New folder" with its refusals in step 9). `FLAG_SECURE` counted across entry and editor, explicit on the sheet and the
  OTP dialog, on for the autofill editor (Tasks 4 to 6; steps 3, 5, 8, 10). Plaintext only in
  ViewModel memory; edits copy it from the entry view's ViewModel (Tasks 3, 5). Save path keeps
  every check and closes three gaps (Task 3; steps 5, 7, 11). Autofill save keeps working, now on
  the Compose editor (Task 6; step 10 and the maintainer's device check). Deletions and what is
  left of `BasePGPActivity` (Task 7). TDD for the pure logic (Task 1). Full local CI (Task 7).
  Emulator check of every scenario asked for (Task 8).
- **Parity summary:** ported: secure window, titles, name focus, password reveal and monospace,
  generators with all options and stored preferences, OTP scan, image and manual import with
  snackbars, OTP actions hidden when a secret exists, validation messages, `.gpg-id` recipients,
  missing-key refusal, nothing written on failed encryption, duplicate check, inside-repository
  check, rename with recently-used time, commit messages, autofill result. Changed: folder chips
  and picker instead of a path field, username always shown, password visibility from the setting,
  Save and copy in the overflow and only after success, generator errors in the sheet, write
  errors keep the editor open, an undeletable old file is a snackbar, rotation keeps the form in
  memory instead of saved state. Fixed: overwrite on a move, copy left behind on a move, folder
  created before the inside check, failed write truncating an entry, autofill matches lost on
  rename. Dropped: "Encrypt username" switch, the editor's "No keys imported" prompt.
- **Deliberate shortcuts (`ponytail:`):** an edit restored after process death closes (Task 5);
  commit failures not shown and a mid-save theme change committing later (Task 5); the picker's
  plain scrolling column (Task 2); generator length caps (Task 4); `AutofillSaveActivity`'s
  `configChanges` (Task 6).
- **Type and name consistency:** `EditorForm`, `editorForm`, `passContent`, `formError`,
  `entryFile`, `overwrites`, `writeEntry`, `withLine`, `totpUri`, `RandomSpec.options`,
  `randomPassword`, `DicewareSpec` (Task 1) are used with the same signatures in Tasks 3 and 5;
  `SaveResult.Saved(file, form, staleOriginal)` in Tasks 3, 5, 6; `EditorViewModel.start`,
  `started`, `saving`, `hasOtp`, `randomSpec`, `generateRandom`, `dicewareSpec`,
  `generateDiceware`, `readQr`, `save` (Task 3) match their calls in Task 5; `FolderPicker(root,
  start, onPick, onDismiss)` in Tasks 2 and 5, `FolderNameDialog` and `folderNameError` shared by
  the list and the picker; `ListDestination(..., onNewEntry)` and
  `EntryDestination(..., onEdit)` match `MainActivity` (Task 5).

## Maintainer decisions (2026-11-03)

1. Autofill save moves to the Compose editor in this step (Task 6 as planned). The tablet and
   Firefox check of the real autofill save and generate flows is the maintainer's; Task 8, step 10
   says so.
2. The folder picker gets a "New folder" action: it creates a folder inside the folder the picker
   shows, with the list's validation (blank, inside the repository by path components, already
   exists), then shows it so Select picks it. The list's `FolderNameDialog` (now `internal`) and
   `folderNameError` are reused, not duplicated. New string `folder_picker_new` in `values/` and
   `values-it/`. Applied in Task 2, checked in Task 8, step 9.
3. "Encrypt username" switch: dropped, as planned.
4. "Save and copy": kept in the overflow menu, copies only after a successful save, as planned.
