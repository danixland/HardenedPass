# Git dialogs to Compose, Material and AppCompat out

Last `TODO.md` item. Moves the remaining View dialogs and the snackbar helper to Compose, removes
Material Components, raises minSdk to 31 and takes the app's own code off AppCompat.

## Goals

- `com.google.android.material:material` is gone from the dependency graph.
- No `androidx.appcompat` import and no direct `appcompat` dependency in the app.
- minSdk is 31 (Android 12) in every module.

## Non-goals

- Removing AppCompat from the APK. `zxing-android-embedded` still pulls it in: `CaptureManager`
  uses its `AlertDialog` and `zxing_CaptureTheme` has parent `Theme.AppCompat.NoActionBar`.
  Replacing the QR scanner is a new `TODO.md` item.
- Renaming or restructuring the git operations beyond what the dialogs need.

## Why minSdk 31

The project targets GrapheneOS, which runs current Android only. `androidx.biometric`'s
`FingerprintDialogFragment` builds an AppCompat `AlertDialog`, used on old Android only, so moving
themes off AppCompat at minSdk 26 risks a crash on Android 8. At 31 that path is unused,
`UiModeManager.setApplicationNightMode` exists, and dynamic colors are always available. Devices
below Android 12 stay on 0.2.0; the next release notes must say so.

## Design

### Prompts

`Prompts` is a ViewModel scoped to the activity, reached with the extension `activity.prompts`
(`ViewModelProvider(activity)`), so no interface is needed. It offers:

- `suspend fun ask(title, message, confirm, dismiss = null, neutral = null): Choice`, which
  suspends until a button is pressed or the dialog is dismissed. Dismissing counts as the
  cancelling answer.
- `askCredential(...)` for the credential dialog, delivering the entered text and the remember
  choice, or null on cancel.
- `snackbar(message, length)`, with `length` short, long or indefinite, returning a handle to
  dismiss it.

`PromptHost()` is a composable placed inside `APSTheme` at the root of `MainActivity` and
`AutofillSaveActivity`, the only two activities that show these. It draws the current dialog and a
`SnackbarHost` at the bottom of the window, above the navigation bar and keyboard.

The state lives in the ViewModel, so a theme or locale recreation redraws a pending dialog instead
of leaving the operation waiting on a dialog that no longer exists.

### Dialogs

These all move to `ask`:

- the git error dialog in `BaseGitActivity.promptOnErrorHandler`, keeping `onPromptDone`
- the missing SSH key dialog in `GitOperation` (import, generate, cancel)
- `BreakOutOfDetached`'s "not needed" notice
- `moveFile`'s error and `confirm` in `StoreActions`

`GitOperation.preExecute` becomes suspend so the notice can wait for its answer.

The credential dialog in `CredentialFinder` becomes a Compose `AlertDialog`: password field with
the mode's hint, remember checkbox, retry error that clears on typing, keyboard open on show, and
`SecureFlagPolicy.SecureOn` (the View dialog had no `FLAG_SECURE`). Storing a remembered credential
stays in `CredentialFinder`. `InteractivePasswordFinder`'s blocking wait on the main dispatcher
does not change. `git_credential_layout.xml` is deleted.

### Snackbar

`FragmentActivity.snackbar` keeps its name and now goes through `Prompts`, so call sites change
only their `length` argument. A new snackbar replaces the current one, as the View snackbar did,
rather than queueing behind it as `SnackbarHostState` does by default. `GitCommandExecutor` keeps
"operation running" up until the operation ends, then dismisses it.

### minSdk

`AndroidCommon.kt` sets 31. Branches that become dead are deleted, including the below-Android-11
snackbar in `copyTextToClipboard` and its parameters.

### AppCompat out

- `BaseGitActivity`, `LaunchActivity` and the autofill activities extend `FragmentActivity`, which
  `BiometricPrompt` needs. `GitOperation` subclasses and `SshAuthMethod` take `FragmentActivity`.
- `supportActionBar?.hide()` goes: the themes have no action bar.
- Window themes derive from `android:Theme.Material.Light.NoActionBar`, and
  `android:Theme.Material.NoActionBar` in `values-night`. AppCompat-only theme items are removed
  or switched to their `android:` form. `zxing_CaptureTheme` is untouched.
- `fragment-ktx` stays as the provider of `FragmentActivity`; its comment is updated.

### Night mode

`Application.setNightMode` calls `UiModeManager.setApplicationNightMode`: `light` is
`MODE_NIGHT_NO`, `dark` is `MODE_NIGHT_YES`, everything else is `MODE_NIGHT_AUTO`.

"Set by Battery Saver" leaves the settings list, and `app_theme_def` becomes `follow_system`.
Android already turns dark in battery saver, so follow-system covers it. A stored `battery_saver`
value falls into the "everything else" case; no migration is needed, but the settings row must
show "System default" for it rather than nothing.

### System bars

`DynamicColors` tints the bars today through the window theme. Without it, `APSTheme` sets the
status and navigation bar colors from `colorScheme.background` in its existing `SideEffect`,
skipping windows whose theme has transparent bars (the autofill decrypt window). The deprecation
on these setters at compileSdk 35 is suppressed at that line, since `CI=1` makes warnings errors.

### Dependencies and resources

`androidx-material` and `androidx-appcompat` leave `app/build.gradle.kts` and the catalog.
`DynamicColors.applyToActivitiesIfAvailable` goes from `Application`. Color resources that lint
reports as unused after this (`md_theme_*`) are deleted.

## Testing

- JVM test for `Prompts`: an answer resumes `ask`, dismissing gives the cancelling answer, a new
  snackbar replaces the current one.
- Full command from `AGENTS.md`: `CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug
  :app:assembleRelease lint metalavaCheckCompatibilityRelease`.
- Emulator, on the e2e store: credential dialog (wrong passphrase retry, remember, cancel), missing
  SSH key dialog, git error dialog, conflict recovery, move error, delete and move confirmations,
  every theme setting including a stored `battery_saver`, bar colors in light and dark, QR scanner
  opens, fingerprint unlock, a theme change while a dialog is open.
- Real device with Firefox: autofill save, since its window now hosts `PromptHost`, and the
  autofill windows' transparent bars.

## Docs

- `AGENTS.md`: the Material Components gotcha, the window theme parent, the autofill activities'
  base class.
- `TODO.md`: remove this item, add replacing the QR scanner so AppCompat can leave the APK.
- The website docs, if they state a minimum Android version. That lives in the site repo and
  deploys through production.

## Commit order

1. minSdk 31 and dead branches.
2. `Prompts`, `PromptHost` and all call sites.
3. Material out: themes, `DynamicColors`, system bars, dependency.
4. AppCompat out: activities, night mode, settings list, dependency, docs.

After step 3 the window themes must still satisfy AppCompat, so that commit moves them to
`Theme.AppCompat.DayNight.NoActionBar`, and step 4 moves them to `android:Theme.Material.*`.
