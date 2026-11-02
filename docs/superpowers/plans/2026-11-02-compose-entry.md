# Compose UI overhaul, step 3: Entry view. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy `DecryptActivity` with a Compose entry view behind `MainActivity`'s `EntryRoute`: header (avatar, name, folder), top bar (back, edit, overflow), a first group with password, username and one-time code (countdown bar), a second group with the extra fields. The list navigates to it, `LaunchActivity` shortcuts start on it. Decryption, the passphrase cache, biometric unlock, clipboard handling and the auto-close timer behave as before.

**Architecture:** `EntryRoute(filePath)` (absolute path) gets its own `EntryViewModel` through `hiltViewModel()`. The ViewModel ports `DecryptActivity`'s unlock flow (cache, passphrase prompt, retries, decryption off the main thread, auto-close timer) and holds the decrypted `PasswordEntry` in Compose state only: never in saved state or a route, dropped by the timer and when the back stack entry is popped (its ViewModel is cleared). The activity-bound parts stay in the destination composable: the biometric prompt (needs the `FragmentActivity`), the PGP key import and editor launchers, the clipboard and share intents, and `FLAG_SECURE`, set on the window while the destination is composed. The screen itself is a stateless composable fed by a small pure model (`EntryFields`). `BasePGPActivity` keeps what `PasswordCreationActivity` and `AutofillDecryptActivity` need; its clipboard and `.gpg-id` helpers become top-level `FragmentActivity` extensions in the same file, so `MainActivity` can call them too and the subclasses compile unchanged. `DecryptActivity` and everything only it used are deleted.

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2024.09.03 (Material 3 1.3.0: `TopAppBar`, `LinearProgressIndicator(progress = { })`, `AlertDialog(properties = ...)`), navigation-compose 2.8.9 (type-safe `popUpTo<T>`), `hilt-navigation-compose` 1.2.0, androidx.biometric (unchanged), kotlinx-collections-immutable. No new dependencies.

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`. Earlier plans: `docs/superpowers/plans/2026-11-02-compose-foundation.md`, `docs/superpowers/plans/2026-11-02-compose-list.md`.

## Decisions taken in this plan

- **Entry state lives in a ViewModel scoped to the route's back stack entry.** Reasons: Hilt injects the crypto stack there (the destination composable cannot `@Inject`); `viewModelScope` is cancelled exactly when the entry is popped, which is also when the decrypted entry must go; a theme or locale change (which still recreates `MainActivity`) keeps the decrypted view instead of prompting again. Rotation does not recreate at all (`configChanges="orientation|screenSize"`, kept). The ViewModel takes a `Context` per call (`applicationContext`) and holds no `Context` field (lint `StaticFieldLeak`).
- **FLAG_SECURE:** the entry destination adds the flag in a `DisposableEffect` and clears it on dispose. In this step no two secret-showing destinations are ever composed together (entry to entry transitions do not exist), so a plain add/clear pair is enough; it is `ponytail:` marked so step 4 counts instead if the editor destination can overlap the entry during a transition. The list is not `FLAG_SECURE` today (only `BasePGPActivity` and `PasswordDialog` set it) and stays so. The passphrase dialog sets `SecureFlagPolicy.SecureOn` explicitly.
- **Path check:** routes from the list come from the app's own listing, routes from `LaunchActivity` passed `isFileInRepo`. The destination checks `isFileInRepo` once more before decrypting (the spec asks the entry route to be validated) and closes otherwise.
- **Back and close:** system back is the `NavHost` default (pop to the list, or leave the app when the entry is the start destination). The top bar back, the auto-close timer, a cancelled prompt and too many wrong passphrases all call one `onClose`: pop when there is a previous entry, else `finish()`. It acts only when its entry is on top, so the timer cannot race a back press into a double pop. The list's tap uses the same "only from the top entry" guard as folders, so a double tap does not push the entry twice.
- **Edit:** the top bar edit button starts the legacy `PasswordCreationActivity` for a result, with exactly the extras `DecryptActivity` passed. On `RESULT_OK` (saved) the entry route is popped and the list shows, refreshed by its resume effect; when the entry was the start destination (shortcut), the list root replaces it instead of finishing. No reopen, no second passphrase prompt. Cancelling the editor returns to the unchanged view. (Maintainer decision 3.)
- **Password visibility:** the password row starts revealed or masked per the existing "Show the password" setting (`SHOW_PASSWORD`, default on, as the old screen did); the eye toggles it. (Maintainer decision 2.)
- **Clipboard:** the password and one-time code copy buttons use `copyPasswordToClipboard` (sensitive flag plus the `ClipboardService` clear timer, the mechanism the old "Copy password" menu item used). Username, extra fields and the name use `copyTextToClipboard` (sensitive flag, no timer), as before. (Maintainer decision 1.) "Automatically copy password" (`COPY_ON_DECRYPT`) is kept.
- **OTP countdown:** `Totp` gains `period`, so the bar shows `remainingTime / period`. The label keeps the existing `otp_label_format` ("OTP (expires in %ds)"), which also gives screen readers the countdown the bar cannot.
- **Overflow menu:** "Copy name" (the old screen copied the name on a long press of the title, which a top bar has no room for) and "Share as plaintext" (chooser, as before). The old "Copy password" item is dropped: the password row has the same button.
- **Passphrase dialog:** a Compose `AlertDialog` replaces `PasswordDialog` here (same title, field, "Clear cached password on screen-off" box, "Wrong password" error, Enter submits). The typed passphrase is held in `remember`, never `rememberSaveable`. `PasswordDialog` stays for `AutofillDecryptActivity` until step 7.
- **`BasePGPActivity`:** `copyTextToClipboard`, `copyPasswordToClipboard` and `getPGPIdentifiers` move out of the class into top-level `FragmentActivity` extensions in `BasePGPActivity.kt` (same package, so `PasswordCreationActivity` needs no change; `AutofillDecryptActivity` gains one import). `copyPasswordToClipboard` reads `sharedPrefs` instead of the injected `settings`; both are the same preferences file. The `name` property, used only by `DecryptActivity`, is removed. Everything else stays for `PasswordCreationActivity` (step 4) and `AutofillDecryptActivity` (step 7).
- **Small fixes, recorded:** a cached passphrase that fails is not tried again (the old flow looped on it forever); after the third failed attempt the screen closes instead of calling `finish()` and still showing the dialog; dismissing the "No keys imported" dialog closes the entry instead of leaving an empty screen; reading the file and `isPasswordProtected` run on the IO dispatcher.

## Feature parity (old `DecryptActivity` / `BasePGPActivity` behavior)

| Behavior today | Decision |
|---|---|
| `FLAG_SECURE` on the whole decrypt activity | Port: on while the entry destination is composed, off on the list |
| Title = entry name; header with folder path and name | Port: header with letter avatar, name, folder ("Password store" at the root) |
| Long press on the name copies it | Port as overflow "Copy name" |
| Biometric prompt "Unlock passphrase cache" when the cache feature is on and the device can authenticate | Port unchanged (`BiometricAuthenticator`, same title) |
| Biometric cancelled, failed or unavailable: passphrase prompt | Port |
| `requireKeysExist`: "No keys imported" dialog, Import opens `PGPKeyImportActivity`, success continues, failure finishes | Port as Compose dialog; dismissing it now closes the entry |
| `.gpg-id` lookup up to the store root, snackbars for short or invalid IDs | Port (`getPGPIdentifiers` extension, same messages) |
| Cache cleared on first use after app start or screen off when "Automatically clear" is on | Port |
| Cached passphrase used after a successful biometric prompt | Port; a cached passphrase that fails is not retried (old code looped) |
| Key without passphrase: decrypt with an empty one | Port |
| `PasswordDialog`: field, cache box (only with the cache on), "Wrong password" error, Enter submits, `FLAG_SECURE`, cancel finishes | Port as Compose dialog (`SecureOn`, passphrase only in `remember`); cancel closes the entry. `PasswordDialog` kept for autofill |
| At most 3 passphrase prompts | Port (now really closes after the third) |
| Passphrase cached after a successful prompt following a biometric success; "clear on screen off" choice saved | Port |
| Decryption on the IO dispatcher | Port (`CryptoRepository.decrypt` unchanged; file read and key check also moved off main) |
| Fields: password (eye toggle, `SHOW_PASSWORD` initial state, JetBrains Mono, tap copies), OTP (label with seconds, copy), username (copy), free-form extras (copy) | Port in the spec's order: password, username, OTP in the first group; extras in the second. Monospace kept for the password. Password and one-time code copies start the clear timer |
| OTP refreshed every second | Port (`PasswordEntry.totp` flow) plus a countdown bar |
| "Automatically copy password" on decrypt | Port |
| Auto-close after "Password copy timeout" (`GENERAL_SHOW_TIME`, default 60 s, 0 = never) | Port: the ViewModel drops the entry and the destination closes (leaves the app when it was the start destination) |
| Menu: Edit (legacy editor, then finish) | Port as top bar icon; saving returns to the list (the list root when opened from a shortcut), cancelling returns to the view |
| Menu: Share as plaintext (always a chooser) | Port (overflow) |
| Menu: Copy password | Drop: duplicate of the password row's copy button |
| Up arrow | Port (top bar back) |
| Rotation recreates the screen and asks again | Changed: rotation keeps the view (`configChanges`); theme or locale change keeps it too (ViewModel) |
| Dynamic shortcut added when opened from the list | Port (`MainActivity.addShortcut`) |
| `DecryptActivity`, `decrypt_layout.xml`, `item_field.xml`, `menu/pgp_handler.xml`, `drawable/divider.xml`, `FieldItemAdapter`, `FieldItem`, upstream's unused `ViewPasswordScreen.kt` | Delete (Task 6) |
| `BasePGPActivity`: `repoPath`, `fullPath`, key import, injected `settings`, `repository`, `dispatcherProvider`, `FLAG_SECURE`, path helpers in the companion | Keep for `PasswordCreationActivity` and `AutofillDecryptActivity` |
| `BasePGPActivity.name` | Delete (only `DecryptActivity` used it) |
| Text selection of field values (old `textIsSelectable`) | Dropped: the copy buttons set the sensitive clipboard flag, selection toolbar copies would not |
| `TotpModule` | Also installed in `ViewModelComponent` (needed for `PasswordEntry.Factory` in the ViewModel) |

Left for later steps because other legacy screens still use them: `PasswordDialog` and `dialog_password_entry.xml` (`AutofillDecryptActivity`, step 7), `EditPasswordScreen.kt`, `PasswordField.kt`/`CopyButton`, `APSAppBar.kt` and the ui:compose `PasswordItem.kt` (unused upstream WIP, step 4 or 8), `BasePGPActivity` (steps 4 and 7).

## Lessons from step 2, designed in

- `android:configChanges="orientation|screenSize"` on `MainActivity` stays; the plan relies on it only for rotation. Work that must survive a theme change lives in `viewModelScope`, not `activity.lifecycleScope`.
- Slack compose-lint: no composable members on the activity (`EntryDestination` and `EntryScreen` are top-level and take the activity as a parameter, like `ListDestination`); every public composable has `modifier: Modifier = Modifier` as its first optional parameter; the only collection parameter is inside `EntryFields` (`ImmutableList`, class marked `@Immutable`); the ViewModel is not passed down.
- Accessibility: every `IconButton` has a content description (back, edit, more options, show or hide password, copy); there is no FAB on this screen. `LetterAvatar` clears its own semantics, so the name is read once.
- Focus: the entry screen has no text field; the passphrase dialog requests focus inside its own window, so nothing is left focused when the list comes back (the list's own return handling from step 2 then applies).
- Decryption and entry file IO never run on the main thread.

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

### Task 1: OTP period and the entry model (TDD)

**Files:**
- Modify: `format/common/src/main/kotlin/app/passwordstore/data/passfile/Totp.kt`
- Modify: `format/common/src/main/kotlin/app/passwordstore/data/passfile/PasswordEntry.kt`
- Modify: `format/common/src/test/kotlin/app/passwordstore/data/passfile/PasswordEntryTest.kt`
- Create: `app/src/main/java/app/passwordstore/ui/entry/EntryModel.kt`
- Test: `app/src/test/java/app/passwordstore/ui/entry/EntryModelTest.kt`

- [ ] **Step 1: Failing test for the period**

In `PasswordEntryTest.kt`, inside `generatesOtpFromTotpUri`, after
`assertEquals(30.seconds, otp.remainingTime)` add:

```kotlin
      assertEquals(30.seconds, otp.period)
```

Run: `./gradlew :format:common:test --tests app.passwordstore.data.passfile.PasswordEntryTest`
Expected: compilation FAILS with `Unresolved reference 'period'`.

- [ ] **Step 2: Add the period**

Replace the body of `Totp.kt` after the package line with:

```kotlin
import kotlin.time.Duration

/** A TOTP code, how long it stays valid, and the full period it was generated for. */
public data class Totp(
  public val value: String,
  public val remainingTime: Duration,
  public val period: Duration,
)
```

In `PasswordEntry.kt`, `calculateTotp`, replace

```kotlin
      .mapBoth({ code -> Ok(Totp(code, remainingTime)) }, ::Err)
```

with

```kotlin
      .mapBoth({ code -> Ok(Totp(code, remainingTime, totpPeriod.seconds)) }, ::Err)
```

(`totpPeriod` is a `Long`; `kotlin.time.Duration.Companion.seconds` is already imported.)

Run: `./gradlew :format:common:test`
Expected: `BUILD SUCCESSFUL`, all format tests pass. (`format:common` has no metalava API file;
only `autofill-parser` does, so the public constructor change needs no API update.)

- [ ] **Step 3: Failing test for the entry model**

`app/src/test/java/app/passwordstore/ui/entry/EntryModelTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.passfile.Totp
import app.passwordstore.util.time.UserClock
import app.passwordstore.util.totp.UriTotpFinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class EntryModelTest {

  private fun entry(content: String) =
    PasswordEntry(UserClock(), UriTotpFinder(), content.encodeToByteArray())

  @Test
  fun firstGroupAndExtrasInFileOrder() {
    val fields =
      entryFields(entry("hunter2\nusername: alice\nurl: https://example.org\npin: 1234\n"))
    assertEquals("hunter2", fields.password)
    assertEquals("alice", fields.username)
    assertEquals(listOf("url" to "https://example.org", "pin" to "1234"), fields.extras)
  }

  @Test
  fun blankPasswordAndMissingUsernameAreLeftOut() {
    val fields = entryFields(entry("\nnote: none\n"))
    assertNull(fields.password)
    assertNull(fields.username)
    assertEquals(listOf("note" to "none"), fields.extras)
  }

  @Test
  fun otpUriIsNotAnExtraField() {
    val fields =
      entryFields(
        entry(
          "s3cret\notpauth://totp/example.com:carol?secret=JBSWY3DPEHPK3PXP&issuer=example.com\n"
        )
      )
    assertEquals("s3cret", fields.password)
    assertTrue(fields.extras.isEmpty())
  }

  @Test
  fun fractionLeftOfTheOtpPeriod() {
    assertEquals(1f, Totp("123456", 30.seconds, 30.seconds).fractionLeft())
    assertEquals(0.5f, Totp("123456", 15.seconds, 30.seconds).fractionLeft())
  }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.entry.EntryModelTest`
Expected: compilation FAILS with `Unresolved reference 'entryFields'` and `'fractionLeft'`.

- [ ] **Step 4: The model**

`app/src/main/java/app/passwordstore/ui/entry/EntryModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import androidx.compose.runtime.Immutable
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.passfile.Totp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * What the entry view shows. [password] and [username] open the first group (the one-time code
 * joins them from the entry's TOTP flow); [extras] are the remaining `key: value` lines in file
 * order, plus lines that are not `key: value` under the parser's "Extra Content" key. Blank values
 * are null, so their rows are not shown.
 */
@Immutable
data class EntryFields(
  val password: String?,
  val username: String?,
  val extras: ImmutableList<Pair<String, String>>,
)

fun entryFields(entry: PasswordEntry): EntryFields =
  EntryFields(
    password = entry.password?.takeIf { it.isNotBlank() },
    username = entry.username?.takeIf { it.isNotBlank() },
    extras = entry.extraContent.toList().toImmutableList(),
  )

/** Share of the code's lifetime left: 1 right after it was generated, toward 0 at expiry. */
fun Totp.fractionLeft(): Float = (remainingTime / period).toFloat()
```

(`PasswordEntry.extraContent` already leaves out the password line, the first username line and
TOTP lines, and is a `LinkedHashMap`, so file order holds.)

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.entry.EntryModelTest`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Build and commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :format:common:test :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

```bash
git add format/common/src app/src/main/java/app/passwordstore/ui/entry/EntryModel.kt \
  app/src/test/java/app/passwordstore/ui/entry/EntryModelTest.kt
git commit -m "feat(app): add the entry view model and the OTP period

Totp now carries its period, so the entry view can draw how much of the
code's lifetime is left. EntryFields groups a decrypted entry the way the
spec lays it out.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: PGP clipboard and identifier helpers become activity extensions

`MainActivity` is not a `BasePGPActivity`, but the entry view needs the same clipboard and
`.gpg-id` behavior. Moving the three helpers out of the class, unchanged, into top-level
`FragmentActivity` extensions in the same file keeps one implementation. Calls inside
`BasePGPActivity` subclasses resolve to the extensions with the activity as implicit receiver,
so `PasswordCreationActivity` and `DecryptActivity` (same package) compile unchanged.

**Files:**
- Modify (replace whole file): `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/autofill/AutofillDecryptActivity.kt`

- [ ] **Step 1: BasePGPActivity**

Replace `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt` with:

```kotlin
/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.crypto

import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.CallSuper
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.passwordstore.R
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.ui.pgp.PGPKeyImportActivity
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.clipboard
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.snackbar
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.services.ClipboardService
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Suppress("Registered")
@AndroidEntryPoint
open class BasePGPActivity : AppCompatActivity() {

  /** Full path to the repository */
  val repoPath by unsafeLazy { intent.getStringExtra("REPO_PATH")!! }

  /** Full path to the password file being worked on */
  val fullPath by unsafeLazy { intent.getStringExtra("FILE_PATH")!! }

  /**
   * Name of the password file
   *
   * Converts personal/auth.foo.org/john_doe@example.org.gpg to john_doe.example.org
   */
  val name: String by unsafeLazy { File(fullPath).nameWithoutExtension }

  /** Action to invoke if [keyImportAction] succeeds. */
  private var onKeyImport: (() -> Unit)? = null
  private val keyImportAction =
    registerForActivityResult(StartActivityForResult()) {
      if (it.resultCode == RESULT_OK) {
        onKeyImport?.invoke()
        onKeyImport = null
      } else {
        finish()
      }
    }

  /** [SharedPreferences] instance used by subclasses to persist settings */
  @SettingsPreferences @Inject lateinit var settings: SharedPreferences
  @Inject lateinit var repository: CryptoRepository
  @Inject lateinit var dispatcherProvider: DispatcherProvider

  /**
   * [onCreate] sets the window up with the right flags to prevent auth leaks through screenshots or
   * recent apps screen.
   */
  @CallSuper
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
  }

  /**
   * Function to execute [onKeysExist] only if there are PGP keys imported in the app's key manager.
   */
  fun requireKeysExist(onKeysExist: () -> Unit) {
    lifecycleScope.launch {
      val hasKeys = repository.hasKeys()
      if (!hasKeys) {
        withContext(dispatcherProvider.main()) {
          MaterialAlertDialogBuilder(this@BasePGPActivity)
            .setTitle(resources.getString(R.string.no_keys_imported_dialog_title))
            .setMessage(resources.getString(R.string.no_keys_imported_dialog_message))
            .setPositiveButton(resources.getString(R.string.button_label_import)) { _, _ ->
              onKeyImport = onKeysExist
              keyImportAction.launch(Intent(this@BasePGPActivity, PGPKeyImportActivity::class.java))
            }
            .show()
        }
      } else {
        onKeysExist()
      }
    }
  }

  companion object {

    const val EXTRA_FILE_PATH = "FILE_PATH"
    const val EXTRA_REPO_PATH = "REPO_PATH"

    /** Gets the relative path to the repository */
    fun getRelativePath(fullPath: String, repositoryPath: String): String =
      fullPath.replace(repositoryPath, "").replace("/+".toRegex(), "/")

    /** Gets the Parent path, relative to the repository */
    fun getParentPath(fullPath: String, repositoryPath: String): String {
      val relativePath = getRelativePath(fullPath, repositoryPath)
      val index = relativePath.lastIndexOf("/")
      return "/${relativePath.substring(startIndex = 0, endIndex = index + 1)}/"
        .replace("/+".toRegex(), "/")
    }

    /** /path/to/store/social/facebook.gpg -> social/facebook */
    @JvmStatic
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
  }
}

/**
 * Copies provided [text] to the clipboard, flagged sensitive. Below Android 11 it also shows a
 * snackbar, which can be disabled by passing [showSnackbar] as false.
 */
fun FragmentActivity.copyTextToClipboard(
  text: String?,
  showSnackbar: Boolean = true,
  @StringRes snackbarTextRes: Int = R.string.clipboard_copied_text,
) {
  val clipboard = clipboard ?: return
  val clip = ClipData.newPlainText("pgp_handler_result_pm", text)
  clip.description.extras =
    PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
  clipboard.setPrimaryClip(clip)
  if (showSnackbar && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    snackbar(message = resources.getString(snackbarTextRes))
  }
}

/**
 * Copies a provided [password] string to the clipboard. This wraps [copyTextToClipboard] and
 * starts off an instance of [ClipboardService] to provide a way of clearing the clipboard.
 */
fun FragmentActivity.copyPasswordToClipboard(password: String?) {
  copyTextToClipboard(password)
  val clearAfter =
    sharedPrefs.getString(PreferenceKeys.GENERAL_SHOW_TIME)?.toIntOrNull()
      ?: Constants.DEFAULT_DECRYPTION_TIMEOUT

  if (clearAfter != 0) {
    val service =
      Intent(this, ClipboardService::class.java).apply {
        action = ClipboardService.ACTION_START
        putExtra(ClipboardService.EXTRA_NOTIFICATION_TIME, clearAfter)
      }
    startForegroundService(service)
  }
}

/**
 * Get a list of available [PGPIdentifier]s for the current password repository. This method
 * throws when no identifiers were able to be parsed. If this method returns null, it means that an
 * invalid identifier was encountered and further execution must stop to let the user correct the
 * problem.
 */
fun FragmentActivity.getPGPIdentifiers(subDir: String): List<PGPIdentifier>? {
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
            if (line.removePrefix("0x").matches("[a-fA-F0-9]{8}".toRegex())) {
              snackbar(message = resources.getString(R.string.short_gpg_id))
            } else {
              snackbar(message = resources.getString(R.string.invalid_gpg_id))
            }
            return null
          }
      }
      .filterIsInstance<PGPIdentifier>()
  if (gpgIdentifiers.isEmpty()) {
    error("Failed to parse identifiers from .gpg-id: ${gpgIdentifierFile.readText()}")
  }
  return gpgIdentifiers
}

@Suppress("ReturnCount")
private fun File.findTillRoot(fileName: String, rootPath: File): File? {
  val gpgFile = File(this, fileName)
  if (gpgFile.exists()) return gpgFile

  if (this.absolutePath == rootPath.absolutePath) {
    return null
  }
  val parent = parentFile
  return if (parent != null && parent.exists()) {
    parent.findTillRoot(fileName, rootPath)
  } else {
    null
  }
}
```

(`name` stays until Task 6: `DecryptActivity` still uses it.)

- [ ] **Step 2: Autofill imports the moved helper**

In `AutofillDecryptActivity.kt`, after `import app.passwordstore.ui.crypto.PasswordDialog` add:

```kotlin
import app.passwordstore.ui.crypto.getPGPIdentifiers
```

- [ ] **Step 3: Build, lint, commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. If the compiler reports an unresolved `getPGPIdentifiers`,
`copyTextToClipboard` or `copyPasswordToClipboard` in a file outside `ui/crypto/`, add the
matching `import app.passwordstore.ui.crypto.<name>` there (the grep below should show none).

```bash
grep -rnE 'copyTextToClipboard|copyPasswordToClipboard|getPGPIdentifiers' app/src/main --include=*.kt | grep -v '/ui/crypto/'
```
Expected: only the `AutofillDecryptActivity.kt` import and call.

```bash
git add app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt \
  app/src/main/java/app/passwordstore/ui/autofill/AutofillDecryptActivity.kt
git commit -m "refactor(app): make the PGP clipboard and .gpg-id helpers extensions

The Compose entry view lives in MainActivity, which is not a
BasePGPActivity. The helpers move unchanged to FragmentActivity
extensions in the same file, so the existing subclasses keep calling
them as before.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Entry ViewModel

Ports `DecryptActivity`'s unlock flow, minus the parts that need the activity (biometric prompt,
`.gpg-id` snackbars, launchers, clipboard), which the destination does in Task 5.

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/entry/EntryViewModel.kt`

- [ ] **Step 1: The ViewModel**

`app/src/main/java/app/passwordstore/ui/entry/EntryViewModel.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.passwordstore.Application.Companion.screenWasOff
import app.passwordstore.crypto.PGPIdentifier
import app.passwordstore.data.crypto.CryptoRepository
import app.passwordstore.data.crypto.PGPPassphraseCache
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.injection.prefs.SettingsPreferences
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.features.Feature.EnablePGPPassphraseCache
import app.passwordstore.util.features.Features
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority.ERROR
import logcat.asLog
import logcat.logcat

/** The dialog the entry destination shows, if any. */
enum class EntryPrompt {
  None,
  NoKeys,
  Passphrase,
  WrongPassphrase,
}

/**
 * Decrypts one entry as the old decrypt screen did: passphrase cache, passphrase prompt with
 * retries, then the auto-close timer. The decrypted [entry] lives only in memory: never in saved
 * state or a route argument. The timer drops it, and so does popping the destination (its back
 * stack entry clears this ViewModel). Holds no Context; calls take one and use the application
 * context.
 */
@HiltViewModel
class EntryViewModel
@Inject
constructor(
  private val repository: CryptoRepository,
  private val entryFactory: PasswordEntry.Factory,
  private val passphraseCache: PGPPassphraseCache,
  private val features: Features,
  @SettingsPreferences private val settings: SharedPreferences,
  private val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  var entry by mutableStateOf<PasswordEntry?>(null)
    private set

  var prompt by mutableStateOf(EntryPrompt.None)
    private set

  /** Set when the destination must close: timer, cancelled prompt, too many wrong passphrases. */
  var closed by mutableStateOf(false)
    private set

  /** Initial state of the passphrase dialog's "clear on screen off" box. */
  var clearCache by mutableStateOf(true)
    private set

  val cacheEnabled: Boolean
    get() = features.isEnabled(EnablePGPPassphraseCache)

  private var started = false
  private var copyOnDecrypt = false
  private var retries = 0
  private var auth: BiometricResult = BiometricResult.CanceledByUser
  private var ids: List<PGPIdentifier> = emptyList()
  private var file: File? = null

  /** True the first time only: a recreated activity must not run the unlock flow again. */
  fun start(): Boolean {
    if (started) return false
    started = true
    return true
  }

  /** False, with the "no keys" prompt up, while no PGP key is imported. */
  suspend fun checkKeys(): Boolean {
    val hasKeys = repository.hasKeys()
    if (!hasKeys) prompt = EntryPrompt.NoKeys
    return hasKeys
  }

  /** Continues after the biometric prompt (or without one) with its [auth] result. */
  fun unlock(file: File, ids: List<PGPIdentifier>, auth: BiometricResult, context: Context) {
    this.file = file
    this.ids = ids
    this.auth = auth
    val app = context.applicationContext
    viewModelScope.launch { next(isError = false, app) }
  }

  fun submit(passphrase: String, clearCache: Boolean, context: Context) {
    prompt = EntryPrompt.None
    this.clearCache = clearCache
    val app = context.applicationContext
    viewModelScope.launch {
      if (decrypt(passphrase, app) && auth is BiometricResult.Success) {
        runCatching {
            passphraseCache.cachePassphrase(app, ids.first(), passphrase)
            settings.edit { putBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, clearCache) }
          }
          .onFailure { e -> logcat { e.asLog() } }
      }
    }
  }

  fun dismissPrompt() {
    prompt = EntryPrompt.None
  }

  fun cancel() {
    prompt = EntryPrompt.None
    closed = true
  }

  /** True once after a decryption when "Automatically copy password" is on. */
  fun takeCopyOnDecrypt(): Boolean = copyOnDecrypt.also { copyOnDecrypt = false }

  override fun onCleared() {
    entry = null
  }

  private suspend fun next(isError: Boolean, context: Context) {
    when (auth) {
      // Handled inside the biometric prompt.
      BiometricResult.Retry -> Unit
      is BiometricResult.Success -> {
        // Clear the cache on first use after app start or after the screen was off; this also
        // purges a stale cache after caching was disabled in the PGP settings.
        clearCache = settings.getBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, true)
        if (screenWasOff && clearCache) {
          passphraseCache.clearAllCachedPassphrases(context)
          screenWasOff = false
        }
        // A cached passphrase that just failed is not tried again (the old screen looped on it).
        val cached =
          if (isError) null else passphraseCache.retrieveCachedPassphrase(context, ids.first())
        if (cached != null) decrypt(cached, context) else ask(isError, context)
      }
      else -> ask(isError, context)
    }
  }

  private suspend fun ask(isError: Boolean, context: Context) {
    if (retries >= MAX_RETRIES) {
      closed = true
      return
    }
    retries++
    if (withContext(dispatcherProvider.io()) { repository.isPasswordProtected(ids) }) {
      prompt = if (isError) EntryPrompt.WrongPassphrase else EntryPrompt.Passphrase
    } else {
      decrypt(passphrase = "", context)
    }
  }

  /** Decrypts off the main thread; a failure goes back to [next] with an error. */
  private suspend fun decrypt(passphrase: String, context: Context): Boolean {
    val source = file ?: return false
    val bytes = withContext(dispatcherProvider.io()) { source.readBytes() }
    val out = ByteArrayOutputStream()
    val result = repository.decrypt(passphrase, ids, bytes.inputStream(), out)
    if (!result.isOk) {
      logcat(ERROR) { result.error.stackTraceToString() }
      next(isError = true, context)
      return false
    }
    copyOnDecrypt = settings.getBoolean(PreferenceKeys.COPY_ON_DECRYPT, false)
    entry = withContext(dispatcherProvider.io()) { entryFactory.create(out.toByteArray()) }
    startTimer()
    return true
  }

  /** Drops the entry and closes after the "Password copy timeout" setting (0 = never). */
  private fun startTimer() {
    val timeout =
      settings.getString(PreferenceKeys.GENERAL_SHOW_TIME)?.toIntOrNull()
        ?: Constants.DEFAULT_DECRYPTION_TIMEOUT
    if (timeout == 0) return
    viewModelScope.launch {
      delay(timeout.seconds)
      entry = null
      closed = true
    }
  }

  private companion object {
    const val MAX_RETRIES = 3
  }
}
```

- [ ] **Step 2: Build, lint, commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:lintDebug`
Expected: `BUILD SUCCESSFUL` (the ViewModel is not used yet; Hilt still generates its factory).
If `when (auth)` reports `Retry` as needing `is`, write `is BiometricResult.Retry -> Unit`.

```bash
git add app/src/main/java/app/passwordstore/ui/entry/EntryViewModel.kt
git commit -m "feat(app): add the entry ViewModel

Ports the decrypt screen's unlock flow: passphrase cache after a
biometric success, passphrase prompt with three tries, decryption off
the main thread and the auto-close timer. The decrypted entry is held in
memory only and dropped by the timer or when the destination is popped.
A cached passphrase that fails is no longer retried in a loop.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Entry screen (stateless UI), field row options and strings

**Files:**
- Modify (replace whole file): `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/FieldRow.kt`
- Create: `app/src/main/java/app/passwordstore/ui/entry/EntryScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-it/strings.xml`

- [ ] **Step 1: FieldRow: initial reveal state and a monospace password**

`FieldRow` has no callers yet. Replace
`ui/compose/src/main/kotlin/app/passwordstore/ui/compose/FieldRow.kt` with:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.passwordstore.ui.compose.preview.ThemePreviews
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/** Content descriptions for [FieldRow]'s buttons, supplied by the app's translated strings. */
public data class FieldRowLabels(val copy: String, val show: String, val hide: String)

/** Masked values (passwords) use a monospace face, so 0 and O, l and 1 stay apart. */
private val Monospace = FontFamily(Font(R.font.jetbrainsmono_nl_regular))

/**
 * A labelled value with an optional copy button. When [masked] is true the value is hidden behind
 * dots until the reveal toggle is pressed, unless [initiallyRevealed] shows it from the start.
 */
@Composable
public fun FieldRow(
  label: String,
  value: String,
  labels: FieldRowLabels,
  modifier: Modifier = Modifier,
  masked: Boolean = false,
  initiallyRevealed: Boolean = false,
  onCopy: (() -> Unit)? = null,
) {
  var revealed by rememberSaveable { mutableStateOf(initiallyRevealed) }
  Row(
    modifier
      .fillMaxWidth()
      .padding(
        start = SpacingLarge,
        end = SpacingMedium,
        top = SpacingMedium,
        bottom = SpacingMedium,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      val style = MaterialTheme.typography.bodyLarge
      Text(
        if (masked && !revealed) "•".repeat(10) else value,
        style = if (masked) style.copy(fontFamily = Monospace) else style,
      )
    }
    if (masked)
      IconButton(onClick = { revealed = !revealed }) {
        Icon(
          painterResource(
            if (revealed) R.drawable.baseline_visibility_off_24
            else R.drawable.baseline_visibility_24
          ),
          contentDescription = if (revealed) labels.hide else labels.show,
        )
      }
    if (onCopy != null)
      IconButton(onClick = onCopy) {
        Icon(painterResource(R.drawable.ic_content_copy), contentDescription = labels.copy)
      }
  }
}

@ThemePreviews
@Composable
private fun FieldRowPreview() {
  val labels = FieldRowLabels("Copy", "Show", "Hide")
  APSTheme {
    GroupedSurface {
      FieldRow("Password", "hunter2", labels, masked = true, onCopy = {})
      FieldRow("Username", "you", labels, onCopy = {})
    }
  }
}
```

(The JetBrains Mono font was used only by the old `FieldItemAdapter` and an autofill layout;
`FieldRow` keeps it used once the adapter is deleted in Task 6.)

- [ ] **Step 2: Strings**

In `app/src/main/res/values/strings.xml`, before `</resources>`, add:

```xml

  <!-- Compose entry view -->
  <string name="entry_back">Back</string>
  <string name="entry_show_password">Show password</string>
  <string name="entry_hide_password">Hide password</string>
  <string name="entry_copy_name">Copy name</string>
```

In `app/src/main/res/values-it/strings.xml`, before `</resources>`, add:

```xml

  <!-- Compose entry view -->
  <string name="entry_back">Indietro</string>
  <string name="entry_show_password">Mostra password</string>
  <string name="entry_hide_password">Nascondi password</string>
  <string name="entry_copy_name">Copia nome</string>
```

Everything else the screen shows already exists in both locales: `password`, `username`,
`otp_label_format`, `copy_label`, `copy_password`, `edit_password`, `share_as_plaintext`,
`list_more_options`, `list_store_root`.

- [ ] **Step 3: The screen**

`app/src/main/java/app/passwordstore/ui/entry/EntryScreen.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.passwordstore.R
import app.passwordstore.ui.compose.FieldRow
import app.passwordstore.ui.compose.FieldRowLabels
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.LetterAvatar
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/**
 * One entry: header (avatar, name, [folder] relative to the store root, "" at the root), then the
 * password, username and one-time code, then the extra fields. [fields] is null until decrypted;
 * [otp] is null when the entry has no TOTP. [onCopySecret] (password, one-time code) is separate
 * from [onCopy] because only those copies start the clipboard clear timer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
  name: String,
  folder: String,
  fields: EntryFields?,
  otp: String?,
  otpSecondsLeft: Long,
  otpFractionLeft: Float,
  showPassword: Boolean,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onShare: () -> Unit,
  onCopyName: () -> Unit,
  onCopySecret: (String) -> Unit,
  onCopy: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var menu by remember { mutableStateOf(false) }
  val labels =
    FieldRowLabels(
      copy = stringResource(R.string.copy_label),
      show = stringResource(R.string.entry_show_password),
      hide = stringResource(R.string.entry_hide_password),
    )
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = {},
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
        actions = {
          if (fields != null)
            IconButton(onClick = onEdit) {
              Icon(
                painterResource(R.drawable.ic_edit_24dp),
                contentDescription = stringResource(R.string.edit_password),
              )
            }
          Box {
            IconButton(onClick = { menu = true }) {
              Icon(Icons.Filled.MoreVert, stringResource(R.string.list_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.entry_copy_name)) },
                onClick = {
                  menu = false
                  onCopyName()
                },
              )
              if (fields?.password != null)
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.share_as_plaintext)) },
                  onClick = {
                    menu = false
                    onShare()
                  },
                  leadingIcon = {
                    Icon(painterResource(R.drawable.ic_share_24dp), contentDescription = null)
                  },
                )
            }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = SpacingLarge, vertical = SpacingMedium),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LetterAvatar(name)
        Column(Modifier.weight(1f).padding(start = SpacingLarge)) {
          Text(name, style = MaterialTheme.typography.titleLarge)
          Text(
            if (folder.isEmpty()) stringResource(R.string.list_store_root) else folder,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (fields != null) {
        if (fields.password != null || fields.username != null || otp != null)
          GroupedSurface(Modifier.fillMaxWidth()) {
            if (fields.password != null)
              FieldRow(
                label = stringResource(R.string.password),
                value = fields.password,
                labels = labels.copy(copy = stringResource(R.string.copy_password)),
                masked = true,
                initiallyRevealed = showPassword,
                onCopy = { onCopySecret(fields.password) },
              )
            if (fields.username != null)
              FieldRow(
                label = stringResource(R.string.username),
                value = fields.username,
                labels = labels,
                onCopy = { onCopy(fields.username) },
              )
            if (otp != null) {
              FieldRow(
                label = stringResource(R.string.otp_label_format, otpSecondsLeft),
                value = otp,
                labels = labels,
                onCopy = { onCopySecret(otp) },
              )
              LinearProgressIndicator(
                progress = { otpFractionLeft },
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(start = SpacingLarge, end = SpacingLarge, bottom = SpacingMedium),
              )
            }
          }
        if (fields.extras.isNotEmpty())
          GroupedSurface(Modifier.fillMaxWidth()) {
            fields.extras.forEach { (label, value) ->
              key(label) { FieldRow(label, value, labels, onCopy = { onCopy(value) }) }
            }
          }
      }
    }
  }
}
```

- [ ] **Step 4: Build, lint, commit**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:lintDebug :ui:compose:lintDebug`
Expected: `BUILD SUCCESSFUL`. If `Icons.AutoMirrored.Filled.ArrowBack` does not resolve, use
`painterResource(R.drawable.ic_arrow_back_black_24dp)` with the same content description. If a
compose-lint rule fires on `EntryScreen`'s parameters, follow its message (the usual fix is
parameter order: required, then `modifier`, then optional).

```bash
git add ui/compose/src/main/kotlin/app/passwordstore/ui/compose/FieldRow.kt \
  app/src/main/java/app/passwordstore/ui/entry/EntryScreen.kt \
  app/src/main/res/values/strings.xml app/src/main/res/values-it/strings.xml
git commit -m "feat(app): add the Compose entry screen

Header with avatar, name and folder; password, username and one-time
code with a countdown bar in the first group; extra fields in the second.
FieldRow can start revealed (the \"Show the password\" setting) and shows
masked values in JetBrains Mono, as the old screen did.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Entry destination and navigation

After this task the list and the shortcuts open entries in Compose. `DecryptActivity` still
compiles but nothing starts it (deleted in Task 6).

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt`
- Modify (replace whole file): `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/list/ListDestination.kt`

- [ ] **Step 1: The destination**

`app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.hilt.navigation.compose.hiltViewModel
import app.passwordstore.R
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.passfile.Totp
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.BasePGPActivity.Companion.getParentPath
import app.passwordstore.ui.crypto.PasswordCreationActivity
import app.passwordstore.ui.crypto.copyPasswordToClipboard
import app.passwordstore.ui.crypto.copyTextToClipboard
import app.passwordstore.ui.crypto.getPGPIdentifiers
import app.passwordstore.ui.main.LaunchActivity
import app.passwordstore.ui.main.MainActivity
import app.passwordstore.ui.pgp.PGPKeyImportActivity
import app.passwordstore.util.auth.BiometricAuthenticator
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.settings.PreferenceKeys
import java.io.File

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
  modifier: Modifier = Modifier,
  viewModel: EntryViewModel = hiltViewModel(),
) {
  val root = PasswordRepository.getRepositoryDirectory()

  // Secrets stay out of screenshots and recents while this destination is composed.
  // ponytail: plain add/clear, fine while no other secret-showing destination can overlap this
  // one in a transition; count in MainActivity once the editor (step 4) becomes a destination.
  DisposableEffect(Unit) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
  }

  // Routes come from the app's own listing or from LaunchActivity's check; check once more.
  val inRepo = remember(file) { LaunchActivity.isFileInRepo(file.path, root) }
  LaunchedEffect(inRepo) { if (!inRepo) onClose() }
  if (!inRepo) return

  fun unlock() {
    val ids =
      activity.getPGPIdentifiers(getParentPath(file.absolutePath, root.absolutePath)) ?: return
    if (viewModel.cacheEnabled && BiometricAuthenticator.canAuthenticate(activity)) {
      BiometricAuthenticator.authenticate(
        activity,
        R.string.biometric_prompt_title_gpg_passphrase_cache,
      ) { result ->
        viewModel.unlock(file, ids, result, activity)
      }
    } else {
      viewModel.unlock(file, ids, BiometricResult.CanceledByUser, activity)
    }
  }

  val keyImport =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) unlock() else onClose()
    }
  val editor =
    rememberLauncherForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) onSaved()
    }

  // ponytail: a theme or locale change while the biometric prompt is up loses that prompt's
  // callback (the flow already counts as started); back out and reopen the entry.
  LaunchedEffect(Unit) { if (viewModel.start() && viewModel.checkKeys()) unlock() }
  LaunchedEffect(viewModel.closed) { if (viewModel.closed) onClose() }

  val entry = viewModel.entry
  LaunchedEffect(entry) {
    val password = entry?.password
    if (viewModel.takeCopyOnDecrypt() && !password.isNullOrBlank()) {
      activity.copyPasswordToClipboard(password)
    }
  }
  val fields = remember(entry) { entry?.let(::entryFields) }
  val totp by
    produceState<Totp?>(null, entry) {
      if (entry != null && entry.hasTotp()) entry.totp.collect { value = it }
    }
  val folder =
    remember(file) {
      file.canonicalFile.parentFile?.relativeTo(root.canonicalFile)?.path.orEmpty()
    }
  val showPassword = remember {
    activity.sharedPrefs.getBoolean(PreferenceKeys.SHOW_PASSWORD, true)
  }

  Box(modifier) {
    EntryScreen(
      name = file.nameWithoutExtension,
      folder = folder,
      fields = fields,
      otp = totp?.value,
      otpSecondsLeft = totp?.remainingTime?.inWholeSeconds ?: 0L,
      otpFractionLeft = totp?.fractionLeft() ?: 0f,
      showPassword = showPassword,
      onBack = onClose,
      onEdit = { entry?.let { editor.launch(editIntent(activity, file, root, it)) } },
      onShare = { fields?.password?.let { activity.startActivity(shareIntent(activity, it)) } },
      onCopyName = { activity.copyTextToClipboard(file.nameWithoutExtension) },
      onCopySecret = { activity.copyPasswordToClipboard(it) },
      onCopy = { activity.copyTextToClipboard(it) },
    )

    when (viewModel.prompt) {
      EntryPrompt.None -> Unit
      EntryPrompt.NoKeys ->
        AlertDialog(
          onDismissRequest = viewModel::cancel,
          title = { Text(stringResource(R.string.no_keys_imported_dialog_title)) },
          text = { Text(stringResource(R.string.no_keys_imported_dialog_message)) },
          confirmButton = {
            TextButton(
              onClick = {
                viewModel.dismissPrompt()
                keyImport.launch(Intent(activity, PGPKeyImportActivity::class.java))
              }
            ) {
              Text(stringResource(R.string.button_label_import))
            }
          },
        )
      EntryPrompt.Passphrase,
      EntryPrompt.WrongPassphrase ->
        PassphraseDialog(
          wrong = viewModel.prompt == EntryPrompt.WrongPassphrase,
          cacheEnabled = viewModel.cacheEnabled,
          clearCache = viewModel.clearCache,
          onSubmit = { passphrase, clear -> viewModel.submit(passphrase, clear, activity) },
          onCancel = viewModel::cancel,
        )
    }
  }
}

/** Asks for the PGP passphrase, as the old `PasswordDialog` did. */
@Composable
private fun PassphraseDialog(
  wrong: Boolean,
  cacheEnabled: Boolean,
  clearCache: Boolean,
  onSubmit: (passphrase: String, clearCache: Boolean) -> Unit,
  onCancel: () -> Unit,
) {
  // remember, never rememberSaveable: the passphrase must not reach saved state.
  var passphrase by remember { mutableStateOf("") }
  var clear by remember { mutableStateOf(clearCache) }
  var error by remember { mutableStateOf(wrong) }
  val focus = remember { FocusRequester() }
  AlertDialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    title = { Text(stringResource(R.string.password)) },
    text = {
      Column {
        OutlinedTextField(
          value = passphrase,
          onValueChange = {
            passphrase = it
            error = false
          },
          modifier = Modifier.focusRequester(focus),
          label = { Text(stringResource(R.string.password)) },
          supportingText =
            if (error) {
              { Text(stringResource(R.string.git_operation_wrong_password)) }
            } else null,
          isError = error,
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { onSubmit(passphrase, clear) }),
        )
        if (cacheEnabled)
          Row(
            Modifier.toggleable(value = clear, role = Role.Checkbox, onValueChange = { clear = it }),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(checked = clear, onCheckedChange = null)
            Text(stringResource(R.string.clear_cached_password_on_screen_off))
          }
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = {
      TextButton(onClick = { onSubmit(passphrase, clear) }) {
        Text(stringResource(R.string.dialog_ok))
      }
    },
  )
}

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

/** Always a chooser, so the user can still cancel. */
private fun shareIntent(activity: Activity, password: String): Intent =
  Intent.createChooser(
    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, password).setType("text/plain"),
    activity.getText(R.string.send_plaintext_password_to),
  )
```

(`root.absolutePath` and `file.absolutePath` as `REPO_PATH`/`FILE_PATH`, not canonical paths:
the editor hashes `"$repoPath/..."` for the recently-used history, and those keys were written
with absolute paths. The header folder uses canonical paths only for display.)

- [ ] **Step 2: The list hands entries to the host**

In `ListDestination.kt`:

Replace the KDoc sentence
` * legacy activities and the store operations. [onOpenFolder] takes a path relative to the root.`
with
` * legacy activities and the store operations. [onOpenFolder] takes a path relative to the root,`
` * [onOpenEntry] an entry file.`

In the parameter list, replace

```kotlin
  onOpenFolder: (relativePath: String) -> Unit,
  modifier: Modifier = Modifier,
```

with

```kotlin
  onOpenFolder: (relativePath: String) -> Unit,
  onOpenEntry: (File) -> Unit,
  modifier: Modifier = Modifier,
```

and in the `ListScreen(...)` call replace

```kotlin
      onOpenEntry = { entry ->
        activity.recordUse(File(entry))
        activity.openEntry(File(entry))
      },
```

with

```kotlin
      onOpenEntry = { entry ->
        activity.recordUse(File(entry))
        onOpenEntry(File(entry))
      },
```

- [ ] **Step 3: MainActivity**

Replace `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt` with:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.passwordstore.data.password.PasswordItem
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.PasswordCreationActivity
import app.passwordstore.ui.entry.EntryDestination
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
              onOpenEntry = { file ->
                if (nav.currentBackStackEntry?.id == entry.id) {
                  addShortcut(file)
                  nav.navigate(EntryRoute(file.absolutePath))
                }
              },
            )
          }
          composable<EntryRoute> { entry ->
            EntryDestination(
              activity = this@MainActivity,
              file = File(entry.toRoute<EntryRoute>().filePath),
              onClose = {
                // Only the entry on top closes: the auto-close timer can race a back press.
                if (nav.currentBackStackEntry?.id == entry.id) {
                  if (nav.previousBackStackEntry == null) finish() else nav.popBackStack()
                }
              },
              onSaved = {
                // Back to the list, which re-reads the store on resume. Opened from a shortcut,
                // the entry has no list below it: the list root replaces it.
                if (nav.currentBackStackEntry?.id == entry.id) {
                  if (nav.previousBackStackEntry != null) nav.popBackStack()
                  else nav.navigate(ListRoute()) { popUpTo<EntryRoute> { inclusive = true } }
                }
              },
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

  /** Adds [file] to the launcher's dynamic shortcuts, as opening it from the list always did. */
  fun addShortcut(file: File) {
    val item = entryItem(file)
    shortcutHandler.addDynamicShortcut(item, item.createAuthEnabledIntent(this))
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
```

(`LegacyScreen` is gone: `EntryRoute` was its last caller, and its comment said the last port
deletes it. Later steps start not-yet-ported screens with plain intents from their callers, as
the list already does.)

- [ ] **Step 4: Build and smoke check**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. If `popUpTo<EntryRoute>` does not resolve, use
`popUpTo(entry.destination.id) { inclusive = true }` (same effect: the current entry is the
top one). If lint flags the early `return` in `EntryDestination`, wrap everything after it in
`if (inRepo) { ... }` instead.

With the emulator from Task 7 running and the store cloned (`export ANDROID_SERIAL=emulator-5554`
first; a phone may be connected), `./gradlew :app:installDebug`, open the app, tap `example.org`,
enter `test`: the entry view shows `hunter2` (`adb exec-out uiautomator dump /dev/tty | grep -o
'text="hunter2"'`). Back returns to the list. Full checks are in Task 7.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/entry/EntryDestination.kt \
  app/src/main/java/app/passwordstore/ui/main/MainActivity.kt \
  app/src/main/java/app/passwordstore/ui/list/ListDestination.kt
git commit -m "feat(app): show the Compose entry view in MainActivity

The list navigates to EntryRoute and shortcuts start on it, replacing the
legacy decrypt screen. FLAG_SECURE is on while an entry is composed and
off on the list; the route is checked with isFileInRepo again before
decrypting. Saving in the editor returns to the list. Password and one-time
code copies start the clipboard clear timer.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Remove DecryptActivity and what only it used

**Files:**
- Delete: `app/src/main/java/app/passwordstore/ui/crypto/DecryptActivity.kt`
- Delete: `app/src/main/java/app/passwordstore/ui/crypto/ViewPasswordScreen.kt`
- Delete: `app/src/main/java/app/passwordstore/ui/adapters/FieldItemAdapter.kt`
- Delete: `app/src/main/java/app/passwordstore/data/password/FieldItem.kt`
- Delete: `app/src/main/res/layout/decrypt_layout.xml`, `item_field.xml`
- Delete: `app/src/main/res/menu/pgp_handler.xml`
- Delete: `app/src/main/res/drawable/divider.xml`
- Modify: `app/src/main/java/app/passwordstore/ui/crypto/BasePGPActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/lint-baseline.xml`
- Modify: `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`

Kept on purpose: `PasswordDialog` and `dialog_password_entry.xml` (autofill), `EditPasswordScreen.kt`,
`PasswordField.kt`, `APSAppBar.kt`, ui:compose `PasswordItem.kt` (later steps).

- [ ] **Step 1: Drop `name` from BasePGPActivity**

In `BasePGPActivity.kt`, delete:

```kotlin
  /**
   * Name of the password file
   *
   * Converts personal/auth.foo.org/john_doe@example.org.gpg to john_doe.example.org
   */
  val name: String by unsafeLazy { File(fullPath).nameWithoutExtension }

```

(`File` stays imported: `getPGPIdentifiers` uses it.)

- [ ] **Step 2: Manifest**

In `app/src/main/AndroidManifest.xml`, delete:

```xml
    <activity
      android:name=".ui.crypto.DecryptActivity"
      android:exported="false" />

```

- [ ] **Step 3: Delete the files**

```bash
git rm app/src/main/java/app/passwordstore/ui/crypto/DecryptActivity.kt \
  app/src/main/java/app/passwordstore/ui/crypto/ViewPasswordScreen.kt \
  app/src/main/java/app/passwordstore/ui/adapters/FieldItemAdapter.kt \
  app/src/main/java/app/passwordstore/data/password/FieldItem.kt \
  app/src/main/res/layout/decrypt_layout.xml \
  app/src/main/res/layout/item_field.xml \
  app/src/main/res/menu/pgp_handler.xml \
  app/src/main/res/drawable/divider.xml
```

- [ ] **Step 4: Lint baseline**

`app/lint-baseline.xml` has two `DenyListedApi` issues located in `FieldItemAdapter.kt`. Remove
both `<issue>` blocks:

```bash
perl -0pi -e 's/\n    <issue\n        id="DenyListedApi"\n(?:(?!<\/issue>).)*?FieldItemAdapter\.kt(?:(?!<\/issue>).)*?<\/issue>\n//gs' app/lint-baseline.xml
grep -c FieldItemAdapter app/lint-baseline.xml
```
Expected: `0`, and `git diff app/lint-baseline.xml` shows exactly two removed `<issue>` blocks.

- [ ] **Step 5: Nothing refers to the removed code**

```bash
grep -rnwE 'DecryptActivity|DecryptLayoutBinding|ItemFieldBinding|FieldItemAdapter|FieldItem|ViewPasswordScreen|openEntry' app/src
grep -rnE 'R\.menu\.pgp_handler\b|@drawable/divider|R\.drawable\.divider' app/src
```
Expected: no output from either (`AutofillDecryptActivity` does not match `-w DecryptActivity`).

- [ ] **Step 6: AGENTS.md and the spec**

In `AGENTS.md`, "Verified facts and gotchas", replace

```
- `LaunchActivity` is exported and forwards `FILE_PATH` to `DecryptActivity` (not exported)
  only after `isFileInRepo` checks it canonically resolves inside the store.
```

with

```
- `LaunchActivity` is exported and forwards `FILE_PATH` to `MainActivity` (not exported) only
  after `isFileInRepo` checks it canonically resolves inside the store; `MainActivity` opens it as
  `EntryRoute`, whose destination checks it again before decrypting.
```

In the spec, section "Entry view", replace

```
- Second group: remaining extra fields, copy where it makes sense.
```

with

```
- Second group: remaining extra fields, copy where it makes sense.
- Overflow: copy name, share as plaintext. The password starts masked or shown per the existing
  "Show the password" setting. Saving in the editor returns to the list. Password and one-time
  code copies are cleared from the clipboard after the timeout.
```

- [ ] **Step 7: Full local check**

Run:
```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`. If lint reports further unused resources, they were used only by
the deleted files: delete them (strings from every `values*/strings.xml`, since a string left
only in a translation is an `ExtraTranslation` error) and re-run. If it reports an issue in a
file from Tasks 1 to 5, fix it there and include it in this commit.

- [ ] **Step 8: Commit**

```bash
git add -A app/src/main app/lint-baseline.xml AGENTS.md \
  docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "refactor(app): remove the legacy decrypt screen

The Compose entry view replaces DecryptActivity, its layouts, menu and
field adapter, and upstream's unused ViewPasswordScreen. BasePGPActivity
keeps what the editor and autofill still need.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Emulator check and push

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
`installDebug` and `adb` both honor `ANDROID_SERIAL`. The uninstall also avoids a signature
mismatch between checkouts, see AGENTS.md.)

Helpers for this shell:

```bash
ui() { adb exec-out uiautomator dump /dev/tty; }
center() { local b; b=$(ui | grep -o "<node [^>]*$1[^>]*>" | head -1 | grep -o 'bounds="[^"]*"' | grep -oE '[0-9]+' | tr '\n' ' '); [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }; set -- $b; echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; }
tap() { local c; c=$(center "$1") && adb shell input tap $c; sleep 1; }
seen() { ui | grep -q "$1" && echo "ok: $1" || echo "MISSING: $1"; }
top() { adb shell dumpsys activity activities | grep topResumedActivity; }
open() { adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity; sleep 3; }
secure() { adb shell dumpsys window windows | awk '/Window #/{w=($0 ~ /ui\.main\.MainActivity/)} w && /fl=/{print (/SECURE/ ? "secure" : "not secure"); w=0}'; }
pass() { tap 'class="android.widget.EditText"'; adb shell input text "$1"; tap 'text="OK"'; sleep 2; }
STORE=/data/user/0/xyz.danix.hardenedpass.debug/files/store
```

- [ ] **Step 2: Onboarding and clone**

`open`, then go through onboarding as in step 2's plan: "Let's Go", "Clone Remote Repo",
Repository URL `ssh://git@10.0.2.2:2222/srv/git/store.git`, authentication mode "SSH key", Save;
import the SSH key `aps-e2e-ssh-key` from Download when asked, accept the host key, then
"Select key" and import `aps-e2e-pgp-key.asc` from Download.
Expected afterwards: `top` shows `MainActivity`; `seen 'text="example.org"'` prints `ok`;
`secure` prints `not secure` (the list is not `FLAG_SECURE`).

- [ ] **Step 3: Open from the list, passphrase prompt, fields, FLAG_SECURE**

```bash
tap 'text="example.org"'; top                       # MainActivity, no DecryptActivity
seen 'class="android.widget.EditText"'              # passphrase dialog
pass wrong; seen 'text="Wrong password"'
pass test
seen 'text="hunter2"'; seen 'text="alice"'; seen 'text="url"'; seen 'text="https://example.org"'
seen 'text="Password store"'; seen 'content-desc="Edit password"'
secure
adb exec-out screencap -p > "$OUT/entry.png"
```
Expected: every `seen` prints `ok`; `top` shows `MainActivity`; `secure` prints `secure`;
`$OUT/entry.png` is black (open it to check).

- [ ] **Step 4: Reveal toggle and copies**

```bash
tap 'content-desc="Hide password"'; seen 'text="••••••••••"'
tap 'content-desc="Show password"'; seen 'text="hunter2"'
tap 'content-desc="Copy password"'
adb shell dumpsys activity services xyz.danix.hardenedpass.debug | grep -c ClipboardService
tap 'content-desc="Copy"'                           # first plain copy: the username row
```
Expected: `ok` for both `seen`; the `grep -c` prints at least `1` (the clear timer started).
The username copy starts no timer (maintainer decision 1).

- [ ] **Step 5: Back clears the view; pasted username; reopen asks again**

```bash
adb shell input keyevent 4; sleep 1; secure; seen 'text="otp"'
adb exec-out screencap -p > "$OUT/list.png"
tap 'text="Search"'; adb shell input keyevent 279; seen 'text="alice"'
adb shell input keyevent 4; adb shell input keyevent 4
tap 'text="example.org"'; seen 'class="android.widget.EditText"'
pass test; seen 'text="hunter2"'
```
Expected: `not secure` after back, `$OUT/list.png` shows the list (not black); the paste puts
`alice` in the search field; reopening shows the passphrase dialog again (the decrypted entry
went with the popped destination).

- [ ] **Step 6: Rotation keeps the view**

```bash
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1
sleep 2; seen 'text="hunter2"'; seen 'class="android.widget.EditText"'; secure
adb shell settings put system user_rotation 0; sleep 2
```
Expected: `ok: text="hunter2"`, `MISSING: class="android.widget.EditText"` (no new prompt),
`secure`.

- [ ] **Step 7: Edit through the legacy editor, back to the list**

```bash
tap 'content-desc="Edit password"'; top             # PasswordCreationActivity
tap 'resource-id="xyz.danix.hardenedpass.debug:id/extra_content"'
adb shell input keyevent KEYCODE_MOVE_END; adb shell input keyevent 66
adb shell input text 'note:%sedited'
tap 'content-desc="Save"' || tap 'text="Save"'
sleep 3; top; seen 'text="otp"'; seen 'class="android.widget.EditText"'; secure
tap 'text="example.org"'; pass test; seen 'text="note"'; seen 'text="edited"'
adb shell input keyevent 4; sleep 1; seen 'text="otp"'
```
Expected: the editor opens with the entry filled in; after Save, `MainActivity` is on top showing
the list (`ok: text="otp"`), with no passphrase dialog (`MISSING: ...EditText`) and `not secure`.
Reopening the entry shows the new `note` field; back returns to the list.

- [ ] **Step 8: OTP entry (by eye) and its clipboard clear**

```bash
tap 'text="otp"'; tap 'text="example.com"'; pass test
adb emu screenrecord screenshot "$OUT"
python3 -c 'import hmac,hashlib,struct,time,base64;k=base64.b32decode("JBSWY3DPEHPK3PXP");h=hmac.new(k,struct.pack(">Q",int(time.time())//30),hashlib.sha1).digest();o=h[-1]&15;print("%06d"%((struct.unpack(">I",h[o:o+4])[0]&0x7fffffff)%1000000))'
secure
```
The OTP screen never goes idle, so `uiautomator dump` fails on it (AGENTS.md) and `screencap`
is black (`FLAG_SECURE`). The emulator console screenshot (`adb emu screenrecord screenshot`)
grabs the host-side display; open the PNG it wrote in `$OUT`. Expected: password `s3cret`, a row
"OTP (expires in Ns)" whose code matches the Python output (same 30 s window), a progress bar
under it shrinking with N; `secure` prints `secure`. If the console screenshot is black too or
unsupported, start the emulator with a window (needs the host's `libbsd`, AGENTS.md) and check
by eye, or leave this item to the maintainer.

Copy the code and check that it is cleared after the timeout (unset, so 60 s). Read the OTP
row's copy icon position from the screenshot (its pixels are the device's) and tap it:

```bash
adb shell input tap X Y                     # X Y: center of the OTP row's copy icon
COPIED=$(date +%s)
adb shell dumpsys activity services xyz.danix.hardenedpass.debug | grep -c ClipboardService
adb shell input keyevent 4; adb shell input keyevent 4; sleep 1
tap 'text="Search"'; adb shell input keyevent 279; ui | grep -oE 'text="[0-9]{6}"'
adb shell input keyevent 4; adb shell input keyevent 4
sleep $(( 65 - ($(date +%s) - COPIED) ))
adb shell dumpsys activity services xyz.danix.hardenedpass.debug | grep -c ClipboardService
tap 'text="Search"'; adb shell input keyevent 279; ui | grep -oE 'text="[0-9]{6}"'
adb shell input keyevent 4; adb shell input keyevent 4
```
Expected: the first `grep -c` prints at least `1` (the clear timer started for the code, as for
the password); the first paste puts the six-digit code in the search field; after the timeout
the second `grep -c` prints `0` and the second paste prints nothing (the clipboard was cleared).
The list is back at the root.

- [ ] **Step 9: Shortcut start, back leaves the app; path outside the store**

```bash
adb shell am force-stop xyz.danix.hardenedpass.debug
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity \
  -a DECRYPT_PASS --es FILE_PATH "$STORE/work/mail.example.org.gpg"; sleep 3
pass test; seen 'text="correct horse battery staple"'; seen 'text="bob"'; seen 'text="work"'; secure
adb shell input keyevent 4; sleep 1; adb shell dumpsys window | grep mCurrentFocus
adb shell am force-stop xyz.danix.hardenedpass.debug
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity \
  -a DECRYPT_PASS --es FILE_PATH "$STORE/../shared_prefs/xyz.danix.hardenedpass.debug_preferences.xml"
sleep 3; seen 'text="otp"'; secure
adb shell am force-stop xyz.danix.hardenedpass.debug
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity \
  -a DECRYPT_PASS --es FILE_PATH "$STORE/work/mail.example.org.gpg"; sleep 3
pass test; tap 'content-desc="Edit password"'
tap 'resource-id="xyz.danix.hardenedpass.debug:id/extra_content"'
adb shell input keyevent KEYCODE_MOVE_END; adb shell input keyevent 66
adb shell input text 'note:%sshortcut'
tap 'content-desc="Save"' || tap 'text="Save"'
sleep 3; top; seen 'text="otp"'; seen 'content-desc="Password store"'; secure
adb shell input keyevent 4; sleep 1; adb shell dumpsys window | grep mCurrentFocus
open
```
Expected: the shortcut opens the entry directly (`ok` for all, `secure`); back leaves the app
(`mCurrentFocus` outside `xyz.danix.hardenedpass.debug`); the outside path opens the list
(`ok: text="otp"`, `not secure`). Saving an entry opened from a shortcut lands on the list root
(`MainActivity`, `ok` for both, `not secure`) instead of closing the app; back from there leaves
the app.

- [ ] **Step 10: Biometric unlock and the passphrase cache**

Fingerprint (once per AVD): `adb shell dumpsys fingerprint | grep -o '"count":[0-9]*'`. If it
prints nothing or `"count":0`, enroll: `adb shell am start -a android.settings.FINGERPRINT_ENROLL`,
enter the emulator PIN `1234`, follow the screens (dump them to find the buttons) and run
`adb emu finger touch 1` until enrollment completes; then `open`.

```bash
tap 'content-desc="More options"'; tap 'text="Settings"'; tap 'text="PGP settings"'
tap 'text="Enable passphrase caching"'; sleep 1; adb emu finger touch 1; sleep 1
adb shell input keyevent 4; adb shell input keyevent 4; sleep 1
tap 'text="example.org"'; seen 'text="Unlock passphrase cache"'
adb emu finger touch 1; sleep 1
seen 'text="Clear cached password on screen-off"'
tap 'text="Clear cached password on screen-off"'      # uncheck: keep the cache across screen off
pass test; seen 'text="hunter2"'
adb shell input keyevent 4; sleep 1
tap 'text="example.org"'; adb emu finger touch 1; sleep 2
seen 'text="hunter2"'; seen 'class="android.widget.EditText"'
adb shell input keyevent 4; sleep 1
tap 'text="example.org"'; adb shell input keyevent 4; sleep 1
seen 'class="android.widget.EditText"'; adb shell input keyevent 4; sleep 1; seen 'text="otp"'
```
Expected, in order: the cache switch turns on after the fingerprint; opening the entry shows the
"Unlock passphrase cache" prompt; after the touch, the passphrase dialog shows the cache box; the
entry decrypts. Reopening with a touch decrypts without a dialog (`ok: hunter2`, `MISSING:
EditText`). Cancelling the biometric prompt with back falls back to the passphrase dialog;
cancelling that closes the entry (`ok: text="otp"`).

- [ ] **Step 11: Auto-close timer**

```bash
tap 'content-desc="More options"'; tap 'text="Settings"'; tap 'text="Passwords"'
tap 'text="Password copy timeout"'; adb shell input keyevent KEYCODE_MOVE_END
for i in 1 2 3 4; do adb shell input keyevent 67; done; adb shell input text 10; tap 'text="OK"'
adb shell input keyevent 4; adb shell input keyevent 4; sleep 1
tap 'text="example.org"'; adb emu finger touch 1; sleep 2; seen 'text="hunter2"'
sleep 12; seen 'text="otp"'; secure
```
Expected: the entry opens, then about 10 s later the list is back (`ok: text="otp"`) and the
window is `not secure`.

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

- **Spec coverage for step 3:** `EntryRoute` is a real Compose destination in `MainActivity`,
  opened from the list by navigation and from `LaunchActivity` as the start destination (Task 5);
  back pops to the list or leaves the app (Task 5, checked in Task 7 steps 5 and 9). Header,
  top bar with back, edit and overflow, first group password (masked or shown, reveal, copy),
  username (copy), OTP with countdown bar (copy), second group extras with copy (Tasks 1, 4).
  Strings in `values/` and `values-it/` (Task 4; Italian uses existing translations for
  everything but the four new strings). `FLAG_SECURE` only while an entry is composed, explicit on
  the passphrase dialog; the list stays unflagged (Task 5,
  checked in Task 7 steps 3, 5, 7, 8, 9, 11). Decrypted content only in ViewModel memory, dropped on
  pop and by the timer; not in saved state, not in routes; passphrase only in `remember`
  (Tasks 3, 5). Passphrase cache, biometric flow and clipboard handling reuse the existing code
  paths unchanged (Tasks 2, 3). `isFileInRepo` re-checked in the destination (Task 5).
  `DecryptActivity` deleted; `BasePGPActivity` reduced to what the editor and autofill need
  (Tasks 2, 6). TDD for the pure logic: field grouping and OTP fraction (Task 1). Full local CI
  (Task 6). Emulator check covering every listed scenario (Task 7).
- **Parity summary:** ported: `FLAG_SECURE`, header, biometric cache unlock, no-keys import,
  `.gpg-id` checks, cache clearing on screen off, cached passphrase, empty passphrase for
  unprotected keys, passphrase dialog with cache box and error, three tries, caching after
  success, decryption off main, all fields with copy, monospace password, `SHOW_PASSWORD`,
  OTP refresh, `COPY_ON_DECRYPT`, auto-close timer, edit, share, up, dynamic shortcut. Changed:
  name copy moves from long press to the overflow; saving in the editor returns to the list
  (the list root when started from a shortcut); rotation and
  theme changes keep the view; password and one-time code copies always start the clear timer.
  Dropped: the "Copy
  password" menu item (same as the row's button). Fixed: cached-passphrase loop, dialog after the
  last retry, dismissed no-keys dialog leaving an empty screen.
- **Deliberate shortcuts (`ponytail:`):** a theme or locale change during the biometric prompt
  loses that prompt's callback; `FLAG_SECURE` is a plain add/clear until step 4 can overlap two
  secret-showing destinations (both Task 5).

## Maintainer decisions (2026-11-02)

1. Clipboard: the password and the one-time code copies are cleared after the timeout, with the
   timer and mechanism the password copy already uses (`copyPasswordToClipboard`,
   `ClipboardService`). Username and extra fields are not cleared. Applied in Tasks 4 and 5,
   checked in Task 7 steps 4 and 8.
2. Password visibility: follow the "Show the password" setting, as planned.
3. After saving in the editor: back to the list (pop the entry route; when the entry was the
   start destination, navigate to the list root instead of finishing). No reopen, no new
   passphrase prompt. The reopen logic (`RETURN_EXTRA_CREATED_FILE` handling, the entry to entry
   replacement and the counted `FLAG_SECURE` it needed) is removed. Applied in Task 5, checked in
   Task 7 steps 7 and 9.
