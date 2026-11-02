# Compose UI overhaul: design

Date: 2026-11-02. Status: approved, not started.

## Goal

Replace every XML/View/Fragment screen in HardenedPass with Jetpack Compose, inside a single
activity, and give the app a new Material 3 look. Visuals, flows and the underlying UI stack all
change; the data layer does not. Nothing is released until the whole move is complete and stable;
the maintainer's phone stays on 0.1.4 until then.

## Visual direction

- Material 3 "Material You": dynamic color on Android 12+, a fixed purple scheme on Android 8 to
  11 (minSdk 26). Light and dark follow the system.
- Pill-shaped search bar at the top of the list, grouped rounded surfaces, letter avatars on
  entries, extended FAB ("New entry").
- Folder location shown as breadcrumb chips (root, then each folder); the last chip is
  highlighted. Tapping a chip jumps to that level.

### Password list

- Breadcrumb chips under the search bar.
- Folders in one grouped surface, entries in another below it. Folder rows show their entry
  count; entry rows show the username, plus "OTP" when the entry has one.
- Pull to sync. Long-press starts multi-select (move, delete). FAB opens the create sheet
  (entry or folder).

### Entry view

- Header: avatar, entry name, folder path. Top bar: back, edit, overflow.
- First group: password (masked, reveal toggle, copy), username (copy), one-time code with a
  countdown bar (copy).
- Second group: remaining extra fields, copy where it makes sense.

### Editor

- Top bar: close, title, Save.
- Name field, folder as breadcrumb chips with "change" (opens the folder picker).
- Password with reveal and a generator button; the generator is a bottom sheet with random and
  diceware tabs.
- Username field, free-text extra content (pass format kept as text, nothing hidden).
- "OTP from QR" and "OTP manual" actions.

## Architecture

### Activities

- `MainActivity` (new, not exported, extends `FragmentActivity` for `BiometricPrompt`, uses no
  fragments) hosts a Compose `NavHost`. It replaces `PasswordStore`, `DecryptActivity`,
  `PasswordCreationActivity`, `SelectFolderActivity`, `OnboardingActivity`, `SettingsActivity`,
  `GitServerConfigActivity`, `GitConfigActivity`, `GitLogActivity`, `ProxySelectorActivity`,
  `SshKeyGenActivity`, `SshKeyImportActivity`, `PGPKeyImportActivity` and `PGPKeyListActivity`.
- `LaunchActivity` stays the only exported activity. It keeps the biometric gate and the
  `isFileInRepo` check, then starts `MainActivity` with either the list route or an entry route
  (dynamic shortcuts).
- Kept as separate activities, because the autofill service launches them directly:
  `AutofillDecryptActivity`, `AutofillSaveActivity`, `AutofillPublisherChangedActivity`,
  `AutofillFilterView`. Their UI moves to Compose with the shared theme.
- The zxing `CaptureActivity` stays as is (third-party, launched for a result).

### Navigation and state

- `navigation-compose` with type-safe routes (kotlinx-serialization plugin) and
  `hilt-navigation-compose` for `hiltViewModel()`. These are the only new dependencies.
- Screens are stateless composables (state in, callbacks out), each driven by a ViewModel.
  Existing ViewModels (`SearchableRepositoryViewModel`, `PGPKeyListViewModel`) are reused where
  they fit.
- Dialogs (folder creation, password generators, OTP import) become Compose dialogs or bottom
  sheets within the graph.
- Settings is a few hand-written Compose preference rows (switch, list, click) over the existing
  `SharedPreferences` keys. No settings library.

### Unchanged

Crypto, git, autofill parsing, preference keys, the Keystore-backed secret storage, clipboard
handling and the passphrase cache. Existing stores, keys and settings carry over without
migration.

### Removed at the end

`fragment-ktx`, `recyclerview`, `recyclerview-selection`, `swiperefreshlayout`,
`modernandroidpreferences`, the view-binding helpers (`FragmentViewBindingDelegate`,
`FragmentExtensions`), all layout XML files, and Material Components (Views) if nothing still
needs it.

## Security

- `FLAG_SECURE` is set on the window while the entry view or editor destination is shown and
  cleared when leaving it, so secrets stay out of screenshots and recents.
- `MainActivity` is not exported. Routes come only from `LaunchActivity` after its checks; an
  entry route is still validated with `isFileInRepo`.

## Error handling

Same behavior as today: git, crypto and IO failures surface as a snackbar or dialog on the
screen that triggered them. Nothing new is swallowed.

## Branching

Work happens on a temporary `compose` branch, an explicit exception to the master-only rule in
AGENTS.md (recorded there when the branch is created, removed at merge). `master` stays
releasable for the monthly public suffix list update and hotfixes to 0.1.4. The branch is pushed
to `origin` as a backup. CI is not extended to the branch: the local pre-commit check from
AGENTS.md runs before every commit, and CI runs at the merge.

## Migration order

Each step leaves the branch building and runnable. Screens not yet ported are opened from the
graph through their old intents.

1. Foundation: dependencies, `MainActivity` and `NavHost`, theme, shared components (breadcrumb
   bar, entry row, field row, grouped surface), `LaunchActivity` routing.
2. Password list: browsing, search, pull-to-sync, multi-select, create sheet.
3. Entry view: decrypt, passphrase and biometric prompts, OTP, copy, `FLAG_SECURE`.
4. Editor: generator sheet, folder picker, OTP from QR and manual.
5. Setup: onboarding, git server config and clone, SSH keygen and import, PGP import and key
   list.
6. Settings, git log, proxy.
7. Autofill activities' UI.
8. Cleanup: delete old activities, layouts and dependencies; update AGENTS.md; merge to
   `master`.

## Testing

- Existing unit tests keep passing.
- Logic moved into new ViewModels (breadcrumb path, selection, field grouping for the view) gets
  unit tests.
- Every step is checked on the `aps-test` emulator against the e2e git server
  (`scripts/e2e-env.sh`).
- Before merge: the full device checklist from AGENTS.md (clone over SSH, PGP import, decrypt,
  TOTP, create and push, conflict recovery, hard reset, fingerprint unlock, Firefox autofill),
  then the maintainer tests on the phone.
