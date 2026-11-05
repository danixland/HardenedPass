# Git dialogs to Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Material Components and the app's own AppCompat use, moving the last View dialogs and the snackbar to Compose, at minSdk 31.

**Architecture:** An activity-scoped `Prompts` ViewModel holds pending dialogs and the current snackbar as Compose state; `PromptHost()` draws them at the root of `MainActivity` and `AutofillSaveActivity`. Git code reaches it through `activity.prompts`. Spec: `docs/superpowers/specs/2026-11-05-git-dialogs-compose-design.md`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, AndroidX Lifecycle, Gradle. No new dependencies.

Work on `master`. Commits are GPG signed; never disable signing. Do not push until the user asks. Run commands from the repository root. Prefix Gradle with `CI=1` where shown: Kotlin warnings become errors, and lint has `warningsAsErrors`.

## File structure

- Create `app/src/main/java/app/passwordstore/ui/prompts/Prompts.kt`: state (`Prompts`, `Prompt`, `Choice`, `Snack`) and drawing (`PromptHost`).
- Create `app/src/test/java/app/passwordstore/ui/prompts/PromptsTest.kt`.
- Delete `app/src/main/java/app/passwordstore/util/autofill/Api26AutofillResponseBuilder.kt`, `app/src/main/res/values-v29/`, `app/src/main/res/layout/git_credential_layout.xml`.
- Everything else is a modification listed per task.

---

### Task 1: minSdk 31 and dead branches

**Files:**
- Modify: `build-logic/src/main/kotlin/app/passwordstore/gradle/AndroidCommon.kt:23`
- Modify: `app/src/main/java/app/passwordstore/injection/AutofillResponseBuilderModule.kt`
- Modify: `app/src/main/java/app/passwordstore/ui/crypto/CryptoExtensions.kt:54-71`
- Modify: `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/theme/Theme.kt:87`
- Modify: `app/src/test/java/app/passwordstore/util/viewmodel/StrictDomainRegexTest.kt:19`
- Modify: every `SDK_INT` site listed in Step 4, `app/src/main/res/values/arrays.xml`, `app/src/main/res/values/prefs.xml`, `strings.xml` in `values` and 10 locales
- Delete: `Api26AutofillResponseBuilder.kt`, `app/src/main/res/values-v29/`

- [ ] **Step 1: Raise minSdk**

In `AndroidCommon.kt`: `defaultConfig.minSdk = 31`.

- [ ] **Step 2: Drop the Api26 builder**

`AutofillResponseBuilderModule.kt` body becomes:

```kotlin
  @Provides
  @Reusable
  fun provideAutofillResponseBuilder(): AutofillResponseBuilder.Factory =
    Api30AutofillResponseBuilder.Factory
```

Remove the `Build` and `Api26AutofillResponseBuilder` imports, delete `Api26AutofillResponseBuilder.kt`, and remove `@RequiresApi(Build.VERSION_CODES.R)` from `Api30AutofillResponseBuilder.kt:34`.

- [ ] **Step 3: Simplify the clipboard helper**

In `CryptoExtensions.kt`:

```kotlin
/** Copies provided [text] to the clipboard, flagged sensitive. */
fun FragmentActivity.copyTextToClipboard(text: String?) {
  val clipboard = clipboard ?: return
  val clip = ClipData.newPlainText("pgp_handler_result_pm", text)
  clip.description.extras =
    PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
  clipboard.setPrimaryClip(clip)
}
```

Remove imports that become unused (`Build`, `StringRes`, `snackbar`) if nothing else in the file needs them.

- [ ] **Step 4: Remove obsolete version checks**

Rule: a check against `P`, `Q`, `R` or `S` is now always true for `>=` and always false for `<`. Keep the taken branch, delete the other, and delete the condition. Checks against `TIRAMISU` and `UPSIDE_DOWN_CAKE` stay.

Sites:

- `app/src/main/java/app/passwordstore/`: `util/services/ClipboardService.kt:124`, `Application.kt:105,109,113,117`, `util/crypto/KeystorePreferences.kt:70,80,94`, `util/autofill/AutofillResponseBuilder.kt:44` (if line numbers moved, grep for `SDK_INT`), `util/services/OreoAutofillService.kt:77`, `util/git/sshj/SshKey.kt:160,201,321`, `util/autofill/AutofillViewUtils.kt:50,59`, `util/auth/BiometricAuthenticator.kt:32`, `ui/autofill/AutofillPublisherChangedActivity.kt:57`, `ui/autofill/AutofillFilterView.kt:77`, `ui/autofill/AutofillDecryptActivity.kt:371`, `ui/settings/ProxyViewModel.kt:23`, `ui/editor/EditorViewModel.kt:171`.
- `autofill-parser/src/main/java/com/github/androidpasswordstore/autofillparser/`: `PublicSuffixListCache.kt:56`, `AutofillHelper.kt:59,89,123`, `FeatureAndTrustDetection.kt:241`.
- `ui/compose/.../Theme.kt:87`: the case becomes `dynamicColor -> {`.

Note `FeatureAndTrustDetection.kt:241`: `SDK_INT < P` is false, so the whole `||`/`&&` expression must be reduced by hand, not just deleted. At `AutofillHelper.kt:123` the `P` case of the `when` becomes `else`, and any older `else` goes. `KeystorePreferences.kt:80` still returns early on `!strongBox`.

Then remove the obsolete annotations:

```bash
grep -rl 'RequiresApi(Build.VERSION_CODES.O)' autofill-parser/src/main | xargs sed -i '/@RequiresApi(Build.VERSION_CODES.O)/d'
```

- [ ] **Step 5: Fold `values-v29` into `values`**

In `app/src/main/res/values/arrays.xml`, the `app_theme_options` third item becomes `@string/pref_app_theme_value_follow_system` and the `app_theme_values` third item becomes `follow_system`. In `values/prefs.xml`, `app_theme_def` becomes `follow_system`. Then:

```bash
command rm -r app/src/main/res/values-v29
grep -rl pref_app_theme_value_battery_saver app/src/main/res | xargs sed -i '/pref_app_theme_value_battery_saver/d'
```

- [ ] **Step 6: Fix the test below minSdk**

In `StrictDomainRegexTest.kt` delete `@Config(sdk = [23])` and its import.

- [ ] **Step 7: Verify**

Run: `./gradlew spotlessApply && CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug lint metalavaCheckCompatibilityRelease`

Expected: BUILD SUCCESSFUL. Lint failures name leftovers: `ObsoleteSdkInt` means a missed check, `UnusedResources` means a string or resource only the deleted code used; delete it, including its translations. If metalava reports an API change in `autofill-parser`, run `./gradlew metalavaGenerateRelease` and include the signature file.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "build: raise minSdk to 31

The project targets GrapheneOS, which only runs current Android. Android 12
gives the platform night mode API and removes the fingerprint dialog path that
needs AppCompat. Devices below it stay on 0.2.0.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Prompts state

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/prompts/Prompts.kt`
- Test: `app/src/test/java/app/passwordstore/ui/prompts/PromptsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.prompts

import androidx.compose.material3.SnackbarDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class PromptsTest {

  private fun Prompts.question() = current as Question

  @Test
  fun answerResumesAsk() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    prompts.answer(prompts.question(), Choice.Other)
    assertEquals(Choice.Other, asked.await())
    assertNull(prompts.current)
  }

  @Test
  fun dismissingAnswersCancel() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    prompts.cancel(prompts.question())
    assertEquals(Choice.Cancel, asked.await())
  }

  @Test
  fun secondAnswerIsIgnored() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    val question = prompts.question()
    prompts.answer(question, Choice.Confirm)
    prompts.answer(question, Choice.Cancel)
    assertEquals(Choice.Confirm, asked.await())
  }

  @Test
  fun cancelledAskRemovesItsDialog() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    asked.cancel()
    assertNull(prompts.current)
  }

  @Test
  fun promptsShowOneAtATimeInOrder() = runBlocking {
    val prompts = Prompts()
    val first = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("1", "m", "ok") }
    val second = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("2", "m", "ok") }
    assertEquals("1", prompts.question().title)
    prompts.answer(prompts.question(), Choice.Confirm)
    assertEquals("2", prompts.question().title)
    prompts.answer(prompts.question(), Choice.Confirm)
    first.await()
    second.await()
  }

  @Test
  fun cancelAllAnswersAPendingCredentialWithNull() {
    val prompts = Prompts()
    val answers = mutableListOf<CredentialAnswer?>(CredentialAnswer("unset", false))
    prompts.askCredential("m", "h", "r", null) { answers[0] = it }
    prompts.cancelAll()
    assertNull(answers[0])
    assertNull(prompts.current)
  }

  @Test
  fun newSnackbarReplacesTheCurrentOne() {
    val prompts = Prompts()
    val first = prompts.snackbar("one", SnackbarDuration.Indefinite)
    val second = prompts.snackbar("two", SnackbarDuration.Short)
    assertSame(second, prompts.snack)
    // The finished operation dismisses its own snackbar only.
    prompts.dismiss(first)
    assertSame(second, prompts.snack)
    prompts.dismiss(second)
    assertNull(prompts.snack)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest -PslimTests --tests 'app.passwordstore.ui.prompts.PromptsTest'`
Expected: FAIL, compilation error, unresolved reference `Prompts`.

- [ ] **Step 3: Write the state**

`app/src/main/java/app/passwordstore/ui/prompts/Prompts.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.prompts

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The button that answered a [Question]. Dismissing the dialog answers [Cancel]. */
enum class Choice {
  Confirm,
  Other,
  Cancel,
}

/** What the user entered in a [CredentialRequest]. */
class CredentialAnswer(val text: String, val remember: Boolean)

/** A dialog waiting for its answer; [cancelled] is the answer to dismissing it. */
sealed class Prompt<T>(private val cancelled: T, private val deliver: (T) -> Unit) {
  internal fun answer(value: T) = deliver(value)

  internal fun cancel() = deliver(cancelled)
}

class Question(
  val title: String,
  val message: String,
  val confirm: String,
  val cancel: String?,
  val other: String?,
  deliver: (Choice) -> Unit,
) : Prompt<Choice>(Choice.Cancel, deliver)

class CredentialRequest(
  val message: String,
  val hint: String,
  val remember: String,
  val error: String?,
  deliver: (CredentialAnswer?) -> Unit,
) : Prompt<CredentialAnswer?>(null, deliver)

/** A snackbar; each one is a distinct object, so showing the same text again shows it again. */
class Snack(val message: String, val length: SnackbarDuration)

/**
 * The dialogs and the snackbar of one activity, for code with no composable of its own (git
 * operations and store actions), drawn by [PromptHost]. Reach it through [prompts].
 */
class Prompts : ViewModel() {

  private val queue = mutableStateListOf<Prompt<*>>()

  /** The dialog on screen. The others wait for it to be answered. */
  val current: Prompt<*>?
    get() = queue.firstOrNull()

  var snack by mutableStateOf<Snack?>(null)
    private set

  /** Shows a dialog and suspends until it is answered. Cancelling the caller closes it. */
  suspend fun ask(
    title: String,
    message: String,
    confirm: String,
    cancel: String? = null,
    other: String? = null,
  ): Choice = suspendCancellableCoroutine { cont ->
    val question = Question(title, message, confirm, cancel, other) { cont.resume(it) }
    queue += question
    cont.invokeOnCancellation { queue.remove(question) }
  }

  /**
   * Asks for a credential. Not suspending: the SSH thread waits on [deliver], and no cancellation
   * reaches it, so this dialog outlives a recreated activity and is drawn again by the new one.
   */
  fun askCredential(
    message: String,
    hint: String,
    remember: String,
    error: String?,
    deliver: (CredentialAnswer?) -> Unit,
  ) {
    queue += CredentialRequest(message, hint, remember, error, deliver)
  }

  /** Answers [prompt], once: a double tap must not resume its caller twice. */
  fun <T> answer(prompt: Prompt<T>, value: T) {
    if (queue.remove(prompt)) prompt.answer(value)
  }

  fun cancel(prompt: Prompt<*>) {
    if (queue.remove(prompt)) prompt.cancel()
  }

  fun cancelAll() {
    queue.toList().forEach(::cancel)
  }

  /** Shows [message] in place of any current snackbar, as the View snackbar did. */
  fun snackbar(message: String, length: SnackbarDuration = SnackbarDuration.Short): Snack =
    Snack(message, length).also { snack = it }

  /** Hides [shown], unless another snackbar replaced it. */
  fun dismiss(shown: Snack) {
    if (snack === shown) snack = null
  }

  override fun onCleared() = cancelAll()
}

/** The [Prompts] of this activity; it survives recreation. */
val FragmentActivity.prompts: Prompts
  get() = ViewModelProvider(this)[Prompts::class.java]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest -PslimTests --tests 'app.passwordstore.ui.prompts.PromptsTest'`
Expected: PASS, 7 tests. No commit yet: nothing uses the file, Task 3 commits it with its callers.

---

### Task 3: PromptHost and all call sites

**Files:**
- Modify: `app/src/main/java/app/passwordstore/ui/prompts/Prompts.kt`
- Modify: `ui/main/MainActivity.kt`, `ui/autofill/AutofillSaveActivity.kt`
- Modify: `util/extensions/AndroidExtensions.kt`, `util/git/GitCommandExecutor.kt`
- Modify: `util/git/operation/CredentialFinder.kt`, `util/git/operation/BreakOutOfDetached.kt`, `util/git/operation/GitOperation.kt`
- Modify: `ui/git/base/BaseGitActivity.kt`, `ui/list/StoreActions.kt`
- Modify: `ui/setup/PgpKeysScreen.kt`, `ui/setup/GitServerScreen.kt`, `ui/settings/GitConfigScreen.kt`, `ui/editor/EditorDestination.kt`
- Delete: `app/src/main/res/layout/git_credential_layout.xml`

Paths without a root are under `app/src/main/java/app/passwordstore/`.

- [ ] **Step 1: Add `PromptHost` to `Prompts.kt`**

```kotlin
/** Draws the [Prompts] of [activity]. Place it after the activity's content, inside `APSTheme`. */
@Composable
fun PromptHost(activity: FragmentActivity) {
  val prompts = remember(activity) { activity.prompts }
  val snackbars = remember { SnackbarHostState() }
  val snack = prompts.snack
  // A new snack cancels this effect, which takes the old one off the screen.
  LaunchedEffect(snack) {
    if (snack != null) {
      snackbars.showSnackbar(snack.message, duration = snack.length)
      prompts.dismiss(snack)
    }
  }
  Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding(), Alignment.BottomCenter) {
    SnackbarHost(snackbars)
  }
  val prompt = prompts.current
  // Keyed: a retry is a new request and starts with an empty field.
  key(prompt) {
    when (prompt) {
      is Question -> QuestionDialog(prompt, prompts)
      is CredentialRequest -> CredentialDialog(prompt, prompts)
      null -> {}
    }
  }
}

@Composable
private fun QuestionDialog(question: Question, prompts: Prompts) {
  AlertDialog(
    onDismissRequest = { prompts.cancel(question) },
    title = { Text(question.title) },
    text = { Text(question.message) },
    confirmButton = {
      TextButton(onClick = { prompts.answer(question, Choice.Confirm) }) {
        Text(question.confirm)
      }
    },
    dismissButton = {
      // No Row: the dialog's button area wraps its children when they do not fit.
      question.cancel?.let {
        TextButton(onClick = { prompts.answer(question, Choice.Cancel) }) { Text(it) }
      }
      question.other?.let {
        TextButton(onClick = { prompts.answer(question, Choice.Other) }) { Text(it) }
      }
    },
  )
}

/** The credential lives only in `remember` and the dialog's window is FLAG_SECURE. */
@Composable
private fun CredentialDialog(request: CredentialRequest, prompts: Prompts) {
  // remember, never rememberSaveable: the credential must not reach saved state.
  var text by remember { mutableStateOf("") }
  var keep by remember { mutableStateOf(true) }
  var revealed by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf(request.error) }
  val focus = remember { FocusRequester() }
  fun submit() = prompts.answer(request, CredentialAnswer(text, keep))
  AlertDialog(
    onDismissRequest = { prompts.cancel(request) },
    properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    title = { Text(stringResource(R.string.passphrase_dialog_title)) },
    text = {
      Column {
        Text(request.message)
        OutlinedTextField(
          value = text,
          onValueChange = {
            text = it
            error = null
          },
          modifier = Modifier.focusRequester(focus),
          label = { Text(request.hint) },
          supportingText = error?.let { { Text(it) } },
          isError = error != null,
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
          keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Row(
          Modifier.toggleable(value = keep, role = Role.Checkbox, onValueChange = { keep = it }),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(checked = keep, onCheckedChange = null)
          Text(request.remember)
        }
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = { TextButton(onClick = ::submit) { Text(stringResource(R.string.dialog_ok)) } },
    dismissButton = {
      TextButton(onClick = { prompts.cancel(request) }) {
        Text(stringResource(R.string.dialog_cancel))
      }
    },
  )
}
```

Imports follow `ProxyScreen.kt` (including its `composeR` alias for the `ui:compose` R class) and `EntryDestination.kt`'s `PassphraseDialog`.

- [ ] **Step 2: Host it**

`MainActivity.kt`: directly after the `NavHost(...) { ... }` call, still inside `APSTheme { }`, add `PromptHost(this@MainActivity)`.

`AutofillSaveActivity.kt`: after the `EditorDestination(...)` call inside `APSTheme { }`, add `PromptHost(this@AutofillSaveActivity)`, and change `activity = this` above it only if the compiler needs the label.

- [ ] **Step 3: Replace the snackbar helper**

In `AndroidExtensions.kt`:

```kotlin
/** Shows a snackbar through this activity's [prompts]; it must host a `PromptHost`. */
fun FragmentActivity.snackbar(
  message: String,
  length: SnackbarDuration = SnackbarDuration.Short,
): Snack = prompts.snackbar(message, length)
```

Remove the `Snackbar` and `View` imports if unused. At every caller replace `Snackbar.LENGTH_LONG` with `SnackbarDuration.Long` and the `com.google.android.material.snackbar.Snackbar` import with `androidx.compose.material3.SnackbarDuration`: `ui/setup/PgpKeysScreen.kt`, `ui/setup/GitServerScreen.kt`, `ui/settings/GitConfigScreen.kt`, `ui/editor/EditorDestination.kt`.

In `GitCommandExecutor.kt`:

```kotlin
    val running =
      activity.snackbar(
        message = activity.resources.getString(R.string.git_operation_running),
        length = SnackbarDuration.Indefinite,
      )
```

and the end of `execute()` becomes `.also { activity.prompts.dismiss(running) }`.

- [ ] **Step 4: Credential dialog**

In `CredentialFinder.kt`, replace the whole `if (storedCredential == null) { ... }` branch body, keeping the `else`:

```kotlin
    if (storedCredential == null) {
      val resources = callingActivity.resources
      callingActivity.prompts.askCredential(
        message = resources.getString(messageRes),
        hint = resources.getString(hintRes),
        remember = resources.getString(rememberRes),
        error = if (isRetry) resources.getString(errorRes) else null,
      ) { answer ->
        if (answer?.remember == true) {
          gitOperationPrefs.edit { putString(credentialPref, answer.text) }
        }
        cont.resume(answer?.text)
      }
    } else {
```

Remove the imports of `SuppressLint`, `LayoutInflater`, `WindowManager`, `doOnTextChanged` and the four Material classes. Delete `app/src/main/res/layout/git_credential_layout.xml`.

- [ ] **Step 5: Operations**

`GitOperation.kt`: `open suspend fun preExecute() = true`, and:

```kotlin
  private suspend fun onMissingSshKeyFile() {
    val resources = callingActivity.resources
    val choice =
      callingActivity.prompts.ask(
        title = resources.getString(R.string.ssh_preferences_dialog_title),
        message = resources.getString(R.string.ssh_preferences_dialog_text),
        confirm = resources.getString(R.string.button_label_import),
        cancel = resources.getString(R.string.dialog_cancel),
        other = resources.getString(R.string.ssh_preferences_dialog_generate),
      )
    when (choice) {
      Choice.Confirm -> getSshKey(false)
      Choice.Other -> getSshKey(true)
      // Only closes the dialog: the calling activity is the app's own screen now.
      Choice.Cancel -> {}
    }
  }
```

`BreakOutOfDetached.kt`:

```kotlin
  override suspend fun preExecute() =
    if (!git.repository.repositoryState.isRebasing && !merging) {
      val resources = callingActivity.resources
      callingActivity.prompts.ask(
        title = resources.getString(R.string.git_abort_and_push_title),
        message = resources.getString(R.string.git_break_out_of_detached_unneeded),
        confirm = resources.getString(R.string.dialog_ok),
      )
      false
    } else {
      true
    }
```

`AndroidExtensions.kt:73`: `override suspend fun preExecute(): Boolean {`.

- [ ] **Step 6: Error dialog**

`BaseGitActivity.kt`:

```kotlin
  suspend fun promptOnErrorHandler(err: Throwable) {
    val error = rootCauseException(err)
    if (!isExplicitlyUserInitiatedError(error)) {
      gitPrefs.edit { remove(PreferenceKeys.HTTPS_PASSWORD) }
      sharedPrefs.edit { remove(PreferenceKeys.SSH_OPENKEYSTORE_KEYID) }
      logcat { error.asLog() }
      prompts.ask(
        title = getString(R.string.jgit_error_dialog_title),
        message = ErrorMessages[error],
        confirm = getString(R.string.dialog_ok),
      )
    }
  }
```

No caller passes `onPromptDone`. Drop the `withContext` import if unused.

- [ ] **Step 7: Store actions**

`StoreActions.kt`, in `moveFile`, replacing the dialog and its `ponytail` comment:

```kotlin
    prompts.ask(
      title = getString(R.string.password_move_error_title),
      message = getString(R.string.password_move_error_message, source, destination),
      confirm = getString(R.string.dialog_ok),
    )
```

and `confirm`:

```kotlin
/** Shows [message] under [title] and suspends until answered; true when confirmed. */
private suspend fun BaseGitActivity.confirm(title: String, message: String): Boolean =
  prompts.ask(
    title = title,
    message = message,
    confirm = getString(R.string.dialog_ok),
    cancel = getString(R.string.dialog_cancel),
  ) == Choice.Confirm
```

Remove `MaterialAlertDialogBuilder`, `resume` and `suspendCancellableCoroutine` imports. `Context` stays for `recordUse` and `moveHistory`.

- [ ] **Step 8: Verify no Material dialog or snackbar is left**

Run: `grep -rnE 'MaterialAlertDialog|material\.snackbar|material\.checkbox|material\.textfield' app/src/main`
Expected: no output.

Run: `./gradlew spotlessApply && CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug lint`
Expected: BUILD SUCCESSFUL. Delete any string lint reports unused, with its translations.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(app): move the git dialogs and the snackbar to Compose

An activity-scoped Prompts ViewModel holds them, since git operations have no
composable to draw in; PromptHost draws them. The credential dialog's window is
now FLAG_SECURE, and a recreated activity no longer loses it and strands the
SSH thread.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: AppCompat out of the code

**Files:**
- Modify: `ui/git/base/BaseGitActivity.kt`, `ui/main/LaunchActivity.kt`, `ui/autofill/AutofillDecryptActivity.kt`, `ui/autofill/AutofillFilterView.kt`, `ui/autofill/AutofillPublisherChangedActivity.kt`, `ui/autofill/AutofillSaveActivity.kt`
- Modify: `util/git/operation/{Push,Pull,Sync,Clone,Gc,ResetToRemote,BreakOutOfDetached,GitOperation}*.kt`, `util/git/sshj/SshjSessionFactory.kt`
- Modify: `Application.kt`
- Modify: `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/theme/Theme.kt`
- Modify: `app/src/main/res/values/themes_material3.xml`, `app/src/main/res/values-night/themes_material3.xml`
- Test: `app/src/test/java/app/passwordstore/ui/main/LaunchActivityTest.kt` must keep passing

- [ ] **Step 1: Activities and operations**

```bash
grep -rl 'androidx.appcompat.app.AppCompatActivity' app/src | xargs sed -i \
  -e 's/androidx\.appcompat\.app\.AppCompatActivity/androidx.fragment.app.FragmentActivity/' \
  -e 's/\bAppCompatActivity\b/FragmentActivity/g'
sed -i '/supportActionBar?.hide()/d' \
  app/src/main/java/app/passwordstore/ui/autofill/AutofillFilterView.kt \
  app/src/main/java/app/passwordstore/ui/autofill/AutofillPublisherChangedActivity.kt
```

Update the `MainActivity` class comment: "Extends [BaseGitActivity] (a FragmentActivity, which BiometricPrompt needs) because the git operations require one; it hosts no fragments."

- [ ] **Step 2: Night mode**

`Application.kt`, replacing the `AppCompatDelegate.*` import with `android.app.UiModeManager`, and removing the `DynamicColors` import and call:

```kotlin
  private fun setNightMode() {
    getSystemService(UiModeManager::class.java)
      .setApplicationNightMode(
        when (prefs.getString(PreferenceKeys.APP_THEME) ?: getString(R.string.app_theme_def)) {
          "light" -> UiModeManager.MODE_NIGHT_NO
          "dark" -> UiModeManager.MODE_NIGHT_YES
          else -> UiModeManager.MODE_NIGHT_AUTO
        }
      )
  }
```

- [ ] **Step 3: Window themes**

`values/themes_material3.xml`, whole `resources` body. Style names stay: renaming them is churn.

```xml
  <style name="AppThemeM3" parent="APSThemeM3">
    <item name="android:windowLightStatusBar">@bool/light_status_bar</item>
  </style>

  <!-- Activities whose whole UI is Compose -->
  <style name="NoActionBarThemeM3" parent="AppThemeM3" />

  <style name="NoBackgroundThemeM3" parent="@style/AppThemeM3">
    <item name="android:background">@android:color/transparent</item>
    <item name="android:backgroundDimEnabled">true</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowContentOverlay">@null</item>
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowEnterAnimation">@android:anim/fade_in</item>
    <item name="android:windowExitAnimation">@android:anim/fade_out</item>
  </style>

  <style name="DialogLikeThemeM3" parent="AppThemeM3">
    <item name="android:windowIsFloating">true</item>
    <item name="android:backgroundDimEnabled">true</item>
  </style>

  <style name="APSThemeM3" parent="android:Theme.Material.Light.NoActionBar" />
```

`values-night/themes_material3.xml`, whole `resources` body:

```xml
  <style name="APSThemeM3" parent="android:Theme.Material.NoActionBar" />
```

The `android:windowLightStatusBar` item needs `@bool/light_status_bar`, which `values-night/bools.xml` already flips.

- [ ] **Step 4: System bars**

`Theme.kt`, the existing `SideEffect`:

```kotlin
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      val bars = WindowCompat.getInsetsController(window, view)
      bars.isAppearanceLightStatusBars = !darkTheme
      bars.isAppearanceLightNavigationBars = !darkTheme
      paintBars(window, colorScheme.background.toArgb())
    }
  }
```

```kotlin
/** Colors the system bars, except in windows that keep them see-through (autofill decryption). */
@Suppress("DEPRECATION") // Deprecated with edge-to-edge, which targetSdk 34 does not force.
private fun paintBars(window: Window, color: Int) {
  if (window.statusBarColor == AndroidColor.TRANSPARENT) return
  window.statusBarColor = color
  window.navigationBarColor = color
}
```

Imports: `android.graphics.Color as AndroidColor`, `android.view.Window`, `androidx.compose.ui.graphics.toArgb`.

- [ ] **Step 5: Verify**

Run: `grep -rn 'androidx.appcompat' app/src/main ui autofill-parser/src/main/java/com/github/androidpasswordstore/autofillparser/AutofillHelper.kt`
Expected: no output. (`FormField.kt` naming `androidx.appcompat.widget.AppCompatEditText` is a class name matched in other apps' forms and stays.)

Run: `./gradlew spotlessApply && CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug lint`
Expected: BUILD SUCCESSFUL. `metalava` is unaffected: the theme module's public API does not change.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(app): take the app's own code off AppCompat

Activities extend FragmentActivity, window themes derive from the platform's
Material theme and night mode uses UiModeManager. APSTheme colors the system
bars, which DynamicColors did through the window theme. AppCompat stays in the
APK through zxing.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Dependencies, resources, docs

**Files:**
- Modify: `app/build.gradle.kts:60,65,71`, `gradle/libs.versions.toml:17,30`
- Modify: `app/src/main/res/values/colors_material3.xml`, `AGENTS.md`, `TODO.md`
- Possibly modify: `app/lint-baseline.xml`

- [ ] **Step 1: Remove the dependencies**

From `app/build.gradle.kts` remove `implementation(libs.androidx.appcompat)` and `implementation(libs.androidx.material)`, and change the fragment comment to `// FragmentActivity, which BiometricPrompt needs.` From `gradle/libs.versions.toml` remove `androidx-appcompat` and `androidx-material`.

- [ ] **Step 2: Remove unused colors**

`oreo_autofill_dataset.xml` uses `@color/md_theme_light_primary`, which stays because it is a `RemoteViews` layout. Delete every other color in `colors_material3.xml`.

- [ ] **Step 3: Confirm the graph**

Run: `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -E 'google.android.material|appcompat:appcompat' | sort -u`
Expected: no `com.google.android.material` line; `appcompat` only below `zxing-android-embedded` and `biometric`.

- [ ] **Step 4: Docs**

`AGENTS.md`:
- Replace the "Material Components (Views) stays after the Compose overhaul" bullet with: "Window themes derive from the platform `android:Theme.Material.*.NoActionBar`, activities from `FragmentActivity` (`BiometricPrompt` needs it). Git operations and store actions show dialogs and snackbars through the activity-scoped `Prompts` ViewModel (`activity.prompts`), drawn by `PromptHost`, which only `MainActivity` and `AutofillSaveActivity` host. AppCompat stays in the APK through zxing (`CaptureManager`, `zxing_CaptureTheme`) and `androidx.biometric`; `oreo_autofill_dataset.xml` stays a layout: fill suggestions are `RemoteViews` drawn by the system."
- In "What this is", add: "minSdk is 31: GrapheneOS runs current Android only, and Android 12 has `UiModeManager.setApplicationNightMode`."

`TODO.md`: replace the git dialogs item with:

```markdown
- QR scanner: `zxing-android-embedded` is the last thing keeping AppCompat in the APK
  (`androidx.biometric` references it only on the fingerprint dialog path, unused at minSdk 31).
  Replacing it would let AppCompat go.
```

- [ ] **Step 5: Full check**

Run: `CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease lint metalavaCheckCompatibilityRelease`
Expected: BUILD SUCCESSFUL. A lint error about a baselined jar path means the entry in `app/lint-baseline.xml` needs its path updated (see `AGENTS.md`).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "build: remove Material Components and the AppCompat dependency

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Emulator run

No code unless a check fails. Use the setup in `AGENTS.md` ("End-to-end testing on an emulator"): `scripts/e2e-env.sh up`, AVD `aps-test`, `export ANDROID_SERIAL=emulator-5554`, animations off, `settings put secure autofill_service null`. `FLAG_SECURE` windows show black in `screencap`: read them from `uiautomator dump`.

- [ ] **Step 1: Install and clone**

Run `./gradlew :app:installDebug`, clone from the e2e server, import the PGP key, decrypt an entry.

- [ ] **Step 2: Check each dialog**

Each must behave as stated:

| Check | Expected |
|---|---|
| SSH key with passphrase, wrong passphrase | Dialog returns with the error, cleared on typing, field empty |
| Same, remember left ticked (the default), right passphrase | Next sync asks nothing |
| Same, Cancel | Operation stops, no error dialog |
| Tap outside the credential dialog | Same as Cancel |
| Credential dialog opens | Keyboard comes up on the field |
| Missing SSH key | Three buttons; outside tap only closes; Import and Generate open setup |
| Unreachable server | Git error dialog |
| Conflict, then "abort and push" | Recovery works; notice shows on a clean store |
| Move over an existing entry | Confirm dialog, both answers |
| "Operation running" snackbar | Shows, goes away at the end; "done" replaces it |

- [ ] **Step 3: Check themes**

Light, dark and system default in Settings: the app switches, status and navigation bars match the screen, icons readable. With "System default", `adb shell cmd uimode night yes` follows.

- [ ] **Step 4: Check the rest**

- Theme change with a credential dialog open: dialog comes back, answering works.
- QR scanner opens from the editor.
- Fingerprint unlock, per `AGENTS.md`.
- Autofill windows via `am start -n` after `adb root` (picker, publisher warning, save editor): no action bar, bars look right.

- [ ] **Step 5: Fix what failed**

Each failure gets its own `fix(app):` commit, with the full check from Task 5 Step 5 first.

- [ ] **Step 6: Ask the user for the device run**

Autofill save and fill on a real device with Firefox cannot run on the emulator. The website docs live in another repository: check them for a stated minimum Android version and tell the user. Release notes for the next version must state Android 12 as the minimum.

- [ ] **Step 7: Record the run**

Update "Last full emulator run" in `AGENTS.md` with the date and what passed; commit as `docs: record the emulator run`.
